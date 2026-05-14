package com.mythara.memory.devices

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mythara.memory.DeviceIdStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes inbound device-to-device messages and produces the
 * outbound response (when one is owed).
 *
 * Called from [DeviceMessageSync] after a pull lands new rows. For
 * every received row whose status is "received", we dispatch by
 * [DeviceMessageEntity.kind] and write the result (status update,
 * optional response message in the outbox) back to the repository.
 *
 * Handlers must be SHORT and self-contained — they run on the sync
 * coroutine and shouldn't block on network or user input. The
 * location handler fetches the most recent cached GPS fix via the
 * system [LocationManager]; no live-listener subscriptions.
 */
@Singleton
class DeviceMessageHandler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: DeviceMessageRepository,
    private val deviceIdStore: DeviceIdStore,
    private val history: com.mythara.data.HistoryRepository,
    private val sensorsTool: com.mythara.agent.tools.ReadSensorsTool,
) {
    @Serializable
    data class LocationResponsePayload(
        val ok: Boolean,
        val ts: Long? = null,
        val lat: Double? = null,
        val lng: Double? = null,
        val accuracyMeters: Float? = null,
        val provider: String? = null,
        val ageMs: Long? = null,
        val error: String? = null,
    )

    private val json = Json { encodeDefaults = false }

    suspend fun handleReceived(msg: DeviceMessageEntity) {
        if (msg.status != DeviceMessageStatus.RECEIVED) return
        when (msg.kind) {
            DeviceMessageKind.LOCATION_REQUEST -> handleLocationRequest(msg)
            DeviceMessageKind.LOCATION_RESPONSE -> handleLocationResponse(msg)
            DeviceMessageKind.PING -> repo.dao.setStatus(msg.id, DeviceMessageStatus.HANDLED)
            DeviceMessageKind.CHAT_NOTE -> handleChatNote(msg)
            DeviceMessageKind.SENSOR_REQUEST -> handleSensorRequest(msg)
            DeviceMessageKind.SENSOR_RESPONSE -> {
                // Requester-side polling tool reads back via byRequestId —
                // nothing to do here beyond marking handled.
                repo.dao.setStatus(msg.id, DeviceMessageStatus.HANDLED)
            }
            else -> {
                Log.w(TAG, "unknown message kind '${msg.kind}' — marking handled")
                repo.dao.setStatus(msg.id, DeviceMessageStatus.HANDLED, "unknown kind")
            }
        }
    }

    /**
     * Pop an incoming chat note straight into the message history with
     * the SENDER's deviceId set, so ChatViewModel.rebuildItems renders
     * it as a FromOtherDevice card in the recipient's scrollback —
     * same path already used for cross-device chat-history restore.
     */
    private suspend fun handleChatNote(msg: DeviceMessageEntity) {
        val payload = runCatching {
            json.decodeFromString(ChatNotePayload.serializer(), msg.payloadJson)
        }.getOrNull() ?: ChatNotePayload(title = "(empty note)", body = "")
        val display = buildString {
            append("📝 ").append(payload.title)
            if (payload.body.isNotBlank()) append("\n\n").append(payload.body)
        }
        runCatching {
            history.dao.insert(
                com.mythara.data.MessageRow(
                    tsMillis = msg.tsMillis,
                    role = "user",
                    content = display,
                    deviceId = msg.fromDevice,
                ),
            )
        }
        repo.dao.setStatus(msg.id, DeviceMessageStatus.HANDLED)
        Log.d(TAG, "chat_note from ${msg.fromDevice}: '${payload.title.take(40)}'")
    }

    @Serializable
    data class ChatNotePayload(
        val title: String,
        val body: String = "",
    )

    /**
     * Recipient-side: read every sensor on THIS device and ship the
     * snapshot back to the requester as a SENSOR_RESPONSE message.
     * The payload is the raw JsonObject from [ReadSensorsTool.collectSnapshot]
     * encoded as a string — the requesting tool parses it back.
     */
    private suspend fun handleSensorRequest(msg: DeviceMessageEntity) {
        val payload = runCatching {
            withContext(Dispatchers.IO) { sensorsTool.collectSnapshot().toString() }
        }.getOrElse { e ->
            """{"ok":false,"error":${kotlinx.serialization.json.JsonPrimitive(e.message ?: e.javaClass.simpleName)}}"""
        }
        val response = DeviceMessageEntity(
            id = UUID.randomUUID().toString(),
            tsMillis = System.currentTimeMillis(),
            fromDevice = deviceIdStore.id(),
            toDevice = msg.fromDevice,
            kind = DeviceMessageKind.SENSOR_RESPONSE,
            requestId = msg.requestId ?: msg.id,
            payloadJson = payload,
            status = DeviceMessageStatus.PENDING,
        )
        repo.dao.insertIfAbsent(response)
        repo.dao.setStatus(msg.id, DeviceMessageStatus.HANDLED)
        Log.d(TAG, "answered sensor_request ${msg.id} from ${msg.fromDevice} (${payload.length}B)")
    }

    private suspend fun handleLocationRequest(msg: DeviceMessageEntity) {
        val payload: LocationResponsePayload = runCatching {
            withContext(Dispatchers.IO) { resolveLocation() }
        }.getOrElse { e ->
            LocationResponsePayload(ok = false, error = e.message ?: e.javaClass.simpleName)
        }
        val response = DeviceMessageEntity(
            id = UUID.randomUUID().toString(),
            tsMillis = System.currentTimeMillis(),
            fromDevice = deviceIdStore.id(),
            toDevice = msg.fromDevice,
            kind = DeviceMessageKind.LOCATION_RESPONSE,
            requestId = msg.requestId ?: msg.id,
            payloadJson = json.encodeToString(LocationResponsePayload.serializer(), payload),
            status = DeviceMessageStatus.PENDING,
        )
        repo.dao.insertIfAbsent(response)
        repo.dao.setStatus(msg.id, DeviceMessageStatus.HANDLED)
        Log.d(TAG, "answered location_request ${msg.id} from ${msg.fromDevice} → ok=${payload.ok}")
    }

    private suspend fun handleLocationResponse(msg: DeviceMessageEntity) {
        // The agent tool that fires location_request polls
        // byRequestId looking for matching response rows — nothing
        // else to do here except mark as handled.
        repo.dao.setStatus(msg.id, DeviceMessageStatus.HANDLED)
    }

    /** Pull the most recent cached fix from any granted provider. */
    private fun resolveLocation(): LocationResponsePayload {
        if (!hasLocationPermission()) {
            return LocationResponsePayload(
                ok = false,
                error = "Location permission not granted on this device.",
            )
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return LocationResponsePayload(ok = false, error = "No LocationManager")
        val best = bestLastKnown(lm)
            ?: return LocationResponsePayload(ok = false, error = "No cached location available")
        val now = System.currentTimeMillis()
        return LocationResponsePayload(
            ok = true,
            ts = best.time,
            lat = best.latitude,
            lng = best.longitude,
            accuracyMeters = if (best.hasAccuracy()) best.accuracy else null,
            provider = best.provider,
            ageMs = (now - best.time).coerceAtLeast(0L),
        )
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @androidx.annotation.RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ])
    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (p in providers) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    companion object {
        private const val TAG = "Mythara/DeviceMsg"
    }
}
