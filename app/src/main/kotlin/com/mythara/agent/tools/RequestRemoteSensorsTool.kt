package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.MemorySyncScheduler
import com.mythara.memory.devices.DeviceMessageEntity
import com.mythara.memory.devices.DeviceMessageKind
import com.mythara.memory.devices.DeviceMessageRepository
import com.mythara.memory.devices.DeviceMessageStatus
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `request_remote_sensors` — ask another Mythara install for a full
 * sensor snapshot. Each Mythara device is an "agent in a body"; this
 * tool lets the cluster benefit from sensors not present on the
 * current device (e.g., one phone has a pressure sensor / pedometer,
 * another doesn't — the agent on the latter can ask the former).
 *
 * Mirrors [RequestRemoteLocationTool] — same DeviceMessage round-trip
 * (SENSOR_REQUEST → recipient handles → SENSOR_RESPONSE), bounded
 * polling against the local DB for the response. Sync cadence drives
 * the round-trip latency (~5 min via HeartbeatSyncer, ~seconds if
 * both devices are alive + responding fast).
 */
@Singleton
class RequestRemoteSensorsTool @Inject constructor(
    private val deviceIdStore: DeviceIdStore,
    private val deviceMessages: DeviceMessageRepository,
    private val scheduler: MemorySyncScheduler,
) : Tool {

    override val name: String = "request_remote_sensors"
    override val description: String =
        "Ask another of the user's Mythara installs to read every sensor on its end and ship the snapshot back. Returns battery, motion (accelerometer/gyroscope/steps), environment (light/pressure/temperature/humidity/magnetic), orientation, proximity, connectivity, wifi, bluetooth, storage, and device info. Use when the user asks 'what does my <other phone> see / hear / measure', 'is my tablet charging', 'how many steps on my watch-phone today', 'what's the air pressure on my bedroom phone'. Requires memory sync configured + the target device id (use list_mythara_devices to discover)."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "target_device_id",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Stable device id of the install to query. Find with list_mythara_devices.",
                        )
                    },
                )
                put(
                    "max_wait_seconds",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Seconds to poll for the response before returning 'still waiting'. Default 30, max 120. Use 0 to fire-and-return immediately.",
                        )
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("target_device_id")) })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val target = (args["target_device_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (target.isEmpty()) return ToolResult(false, """{"error":"missing_target_device_id"}""")
        val maxWaitSec = ((args["max_wait_seconds"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 30L)
            .coerceIn(0L, 120L)

        val myId = deviceIdStore.id()
        if (target == myId) {
            return ToolResult(
                false,
                """{"error":"target_is_self","detail":"Use read_sensors directly for this device."}""",
            )
        }

        val requestId = UUID.randomUUID().toString()
        val request = DeviceMessageEntity(
            id = requestId,
            tsMillis = System.currentTimeMillis(),
            fromDevice = myId,
            toDevice = target,
            kind = DeviceMessageKind.SENSOR_REQUEST,
            requestId = requestId,
            payloadJson = "{}",
            status = DeviceMessageStatus.PENDING,
        )
        deviceMessages.dao.insertIfAbsent(request)
        Log.d(TAG, "enqueued sensor_request $requestId → $target")

        // Kick a sync immediately so the request lands in the repo
        // without waiting up to 5 min for the next heartbeat.
        runCatching { scheduler.fireNow(force = true) }

        val deadline = System.currentTimeMillis() + maxWaitSec * 1000L
        var lastSyncKick = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val responses = deviceMessages.dao.byRequestId(requestId)
                .filter { it.kind == DeviceMessageKind.SENSOR_RESPONSE && it.fromDevice == target }
            if (responses.isNotEmpty()) {
                val newest = responses.maxByOrNull { it.tsMillis } ?: continue
                return ToolResult(
                    true,
                    """{"ok":true,"request_id":${JsonPrimitive(requestId)},"target":${JsonPrimitive(target)},"snapshot":${newest.payloadJson},"response_ts":${newest.tsMillis}}""",
                )
            }
            delay(POLL_INTERVAL_MS)
            if (System.currentTimeMillis() - lastSyncKick > RESYNC_INTERVAL_MS) {
                runCatching { scheduler.fireNow(force = true) }
                lastSyncKick = System.currentTimeMillis()
            }
        }
        return ToolResult(
            true,
            """{"ok":true,"status":"still_waiting","request_id":${JsonPrimitive(requestId)},"target":${JsonPrimitive(target)},"detail":"Request sent. Target hasn't synced yet — ask again later to fetch the snapshot."}""",
        )
    }

    companion object {
        private const val TAG = "Mythara/RemoteSensors"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val RESYNC_INTERVAL_MS = 8_000L
    }
}
