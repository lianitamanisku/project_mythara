package com.mythara.sensors

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mythara.agent.tools.ReadSensorsTool
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic sensor snapshot → LearningVault.
 *
 * Every couple of hours (charging + battery-not-low) the worker grabs
 * a sensor snapshot via [ReadSensorsTool] and folds a compact subset
 * into the learning vault with `topic:sensor` + `kind:sensor-snapshot`
 * facets. The vault's existing semantic-tier sync ships these rows
 * to the memory repo so peers see "this device measured X at time Y"
 * and the agent's downstream pipelines (recall, persona, analytics)
 * can mine them.
 *
 * What gets persisted (intentionally bounded — sensors fire often
 * enough that the raw torrent would drown the vault):
 *  - battery: percent + temperature + plug
 *  - environment: light, pressure, ambient temperature, humidity
 *  - motion: cumulative step count (snapshot delta is the daily steps)
 *  - connectivity: type
 *  - storage: free pct
 *
 * Raw motion / accelerometer streams stay OUT of the vault — they'd
 * blow it up at any meaningful sample rate. The learning pipeline
 * extracts patterns ("ambient light averages 800 lux around 14:00 →
 * usually outdoors") from these periodic snapshots over time.
 */
@HiltWorker
class SensorLearningWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val sensorsTool: ReadSensorsTool,
    private val vault: LearningVault,
    private val deviceIdStore: DeviceIdStore,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val snap = sensorsTool.collectSnapshot()
            val condensed = condense(snap, deviceIdStore.id())
            // One-line JSON so SemanticRecall / future analytics can
            // grep through them. The dedup window of the vault (live
            // sha-dedup) means consecutive identical-environment
            // snapshots reinforce rather than duplicate.
            val content = condensed.toString()
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "sensor:periodic-snapshot",
                facets = listOf(
                    "kind:sensor-snapshot",
                    "topic:sensor",
                    "device:${deviceIdStore.id()}",
                ),
                conf = 0.9,
            )
            Log.d(TAG, "snapshot persisted (${content.length}B)")
            Result.success()
        }.getOrElse { e ->
            Log.w(TAG, "snapshot failed: ${e.message}")
            Result.retry()
        }
    }

    /** Pull only the analytics-worthy fields out of a full sensor JSON. */
    private fun condense(snap: JsonObject, deviceId: String): JsonObject = buildJsonObject {
        put("ts_ms", snap["ts_ms"] ?: JsonPrimitive(System.currentTimeMillis()))
        put("device", deviceId)
        (snap["battery"] as? JsonObject)?.let { b ->
            put(
                "battery",
                buildJsonObject {
                    b["percent"]?.let { put("percent", it) }
                    b["state"]?.let { put("state", it) }
                    b["temperature_c"]?.let { put("temperature_c", it) }
                    b["plug"]?.let { put("plug", it) }
                },
            )
        }
        (snap["environment"] as? JsonObject)?.let { e ->
            put(
                "environment",
                buildJsonObject {
                    listOf(
                        "light_lux", "pressure_hpa", "ambient_temperature_c",
                        "humidity_pct", "magnetic_field_ut",
                    ).forEach { key ->
                        (e[key] as? JsonObject)?.get("values")?.let { put(key, it) }
                    }
                },
            )
        }
        (snap["motion"] as? JsonObject)?.let { m ->
            (m["step_counter_total"] as? JsonObject)?.get("values")?.let {
                put("step_counter_total", it)
            }
        }
        (snap["connectivity"] as? JsonObject)?.let { put("connectivity_type", it["type"] ?: JsonPrimitive("unknown")) }
        (snap["storage"] as? JsonObject)?.let { put("storage_free_pct", it["free_pct"] ?: JsonPrimitive(-1)) }
    }

    companion object {
        private const val TAG = "Mythara/SensorLearn"
        const val UNIQUE_NAME = "mythara_sensor_learning"
    }
}

@Singleton
class SensorLearningScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        val req = PeriodicWorkRequestBuilder<SensorLearningWorker>(Duration.ofHours(2))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .setInitialDelay(Duration.ofMinutes(10))
            .build()
        wm.enqueueUniquePeriodicWork(
            SensorLearningWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }
}
