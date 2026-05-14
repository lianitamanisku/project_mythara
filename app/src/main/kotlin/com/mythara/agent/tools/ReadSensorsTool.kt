package com.mythara.agent.tools

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * `read_sensors` — comprehensive snapshot of every accessible sensor
 * and platform signal on this device. One tool, big JSON, so the
 * agent gets the whole picture in a single round-trip rather than
 * paying per-sensor tool-call overhead.
 *
 * Sections returned (each absent when unavailable / permission-denied):
 *
 *   battery        : percent, state (charging/discharging/...), tempC,
 *                    voltage, plug type, health
 *   motion         : accelerometer, gyroscope, linear_accel, gravity,
 *                    rotation vector, step counter (cumulative since
 *                    last reboot)
 *   environment    : light (lux), pressure (hPa → altitude estimate),
 *                    ambient temperature, humidity, magnetic field
 *   orientation    : pitch / roll / azimuth from rotation vector
 *   proximity      : centimetres (boolean on devices with binary-only
 *                    proximity)
 *   connectivity   : network type (wifi/cellular/none), validated,
 *                    metered, downstream/upstream kbps if known
 *   wifi           : SSID, BSSID, RSSI, frequency, link speed
 *                    (NEARBY_WIFI_DEVICES gated on API 33+)
 *   bluetooth      : adapter state (on/off), connected device count
 *   storage        : free / total internal storage in MB
 *   device         : model, manufacturer, brand, sdk, hardware
 *
 * No new permissions added — every signal here uses something
 * Mythara already declares (battery sticky broadcast / ACCESS_FINE_
 * LOCATION for SSID / etc.). When a section is permission-gated and
 * the user hasn't granted, the section just isn't included.
 */
@Singleton
class ReadSensorsTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "read_sensors"
    override val description: String =
        "Snapshot every accessible phone sensor + platform signal: battery (level, temp, voltage), motion (accelerometer, gyroscope, steps), environment (light, pressure, temp, humidity, magnetic field), orientation (pitch/roll/azimuth), proximity, network (type, wifi/cellular, throughput), wifi (SSID, RSSI), bluetooth state, storage, device-info. Returns one JSON object with sections present only when the sensor/data is available on this device."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val snapshot = collectSnapshot()
        return ToolResult.ok(snapshot.toString())
    }

    suspend fun collectSnapshot(): JsonObject = withContext(Dispatchers.Default) {
        buildJsonObject {
            put("ts_ms", System.currentTimeMillis())
            put("device", readDeviceInfo())
            put("battery", readBattery())
            put("motion", readMotionSensors())
            put("environment", readEnvironmentSensors())
            put("orientation", readOrientation())
            readProximity()?.let { put("proximity", it) }
            put("connectivity", readConnectivity())
            readWifi()?.let { put("wifi", it) }
            put("bluetooth", readBluetooth())
            put("storage", readStorage())
        }
    }

    // ------------------------------------------------------- device info

    private fun readDeviceInfo(): JsonObject = buildJsonObject {
        put("model", Build.MODEL ?: "unknown")
        put("manufacturer", Build.MANUFACTURER ?: "unknown")
        put("brand", Build.BRAND ?: "unknown")
        put("sdk", Build.VERSION.SDK_INT)
        put("hardware", Build.HARDWARE ?: "unknown")
        put("device", Build.DEVICE ?: "unknown")
    }

    // ----------------------------------------------------------- battery

    private fun readBattery(): JsonObject {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent: Intent? = ctx.registerReceiver(null, filter)
        return buildJsonObject {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            put("percent", pct)
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            put(
                "state",
                when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                    else -> "unknown"
                },
            )
            // temperature is tenths of a degree Celsius.
            intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
                ?.let { put("temperature_c", it / 10.0) }
            intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
                ?.let { put("voltage_mv", it) }
            put(
                "plug",
                when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    0 -> "unplugged"
                    else -> "unknown"
                },
            )
            put(
                "health",
                when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                    else -> "unknown"
                },
            )
            // BatteryManager properties — newer API, gives instantaneous values.
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { put("current_now_ua", it) }
        }
    }

    // ----------------------------------------------------- motion sensors

    private suspend fun readMotionSensors(): JsonObject = buildJsonObject {
        sensorReading(Sensor.TYPE_ACCELEROMETER)?.let { put("accelerometer", it) }
        sensorReading(Sensor.TYPE_GYROSCOPE)?.let { put("gyroscope", it) }
        sensorReading(Sensor.TYPE_LINEAR_ACCELERATION)?.let { put("linear_acceleration", it) }
        sensorReading(Sensor.TYPE_GRAVITY)?.let { put("gravity", it) }
        sensorReading(Sensor.TYPE_ROTATION_VECTOR)?.let { put("rotation_vector", it) }
        sensorReading(Sensor.TYPE_STEP_COUNTER)?.let { put("step_counter_total", it) }
        sensorReading(Sensor.TYPE_STEP_DETECTOR)?.let { put("step_detected", it) }
    }

    // ------------------------------------------------ environment sensors

    private suspend fun readEnvironmentSensors(): JsonObject = buildJsonObject {
        sensorReading(Sensor.TYPE_LIGHT)?.let { put("light_lux", it) }
        sensorReading(Sensor.TYPE_PRESSURE)?.let { put("pressure_hpa", it) }
        sensorReading(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let { put("ambient_temperature_c", it) }
        sensorReading(Sensor.TYPE_RELATIVE_HUMIDITY)?.let { put("humidity_pct", it) }
        sensorReading(Sensor.TYPE_MAGNETIC_FIELD)?.let { put("magnetic_field_ut", it) }
    }

    private suspend fun readOrientation(): JsonObject = buildJsonObject {
        // Derive pitch / roll / azimuth from the rotation vector — far
        // more stable than raw accelerometer + magnetometer math.
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return@buildJsonObject
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return@buildJsonObject
        val event = oneShotEvent(sm, rot) ?: return@buildJsonObject
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)
        put("azimuth_deg", Math.toDegrees(orientation[0].toDouble()))
        put("pitch_deg", Math.toDegrees(orientation[1].toDouble()))
        put("roll_deg", Math.toDegrees(orientation[2].toDouble()))
    }

    private suspend fun readProximity(): JsonObject? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return null
        val event = oneShotEvent(sm, prox) ?: return null
        return buildJsonObject {
            put("distance_cm", event.values.firstOrNull()?.toDouble() ?: 0.0)
            put("max_range_cm", prox.maximumRange.toDouble())
        }
    }

    // --------------------------------------------------- connectivity

    private fun readConnectivity(): JsonObject = buildJsonObject {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        if (active == null) {
            put("type", "none")
            return@buildJsonObject
        }
        val caps = cm.getNetworkCapabilities(active)
        if (caps == null) {
            put("type", "unknown")
            return@buildJsonObject
        }
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        put("type", type)
        put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { put("downstream_kbps", it) }
        caps.linkUpstreamBandwidthKbps.takeIf { it > 0 }?.let { put("upstream_kbps", it) }
    }

    private fun readWifi(): JsonObject? {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val info = runCatching { wm.connectionInfo }.getOrNull() ?: return null
        // SSID requires ACCESS_FINE_LOCATION on most modern Android versions;
        // when not granted the OS returns "<unknown ssid>" instead of erroring.
        return buildJsonObject {
            put("ssid", info.ssid?.trim('"') ?: "")
            put("bssid", info.bssid ?: "")
            put("rssi_dbm", info.rssi)
            put("link_speed_mbps", info.linkSpeed)
            put("frequency_mhz", info.frequency)
        }
    }

    // ------------------------------------------------------ bluetooth

    private fun readBluetooth(): JsonObject = buildJsonObject {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bm?.adapter
        if (adapter == null) {
            put("state", "no_adapter")
            return@buildJsonObject
        }
        put(
            "state",
            when (adapter.state) {
                BluetoothAdapter.STATE_ON -> "on"
                BluetoothAdapter.STATE_OFF -> "off"
                BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                else -> "unknown"
            },
        )
        // Counts of bonded + connected devices when accessible (needs
        // BLUETOOTH_CONNECT on API 31+). Wrapped in runCatching so a
        // permission-denied doesn't make the whole tool fail.
        runCatching {
            put("bonded_count", adapter.bondedDevices?.size ?: 0)
        }
    }

    // ------------------------------------------------------- storage

    private fun readStorage(): JsonObject = buildJsonObject {
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val total = stat.blockCountLong * stat.blockSizeLong
            put("free_mb", free / (1024 * 1024))
            put("total_mb", total / (1024 * 1024))
            put("free_pct", if (total > 0) (free * 100 / total).toInt() else -1)
        }
    }

    // ------------------------------------------------------- helpers

    /**
     * Register a one-shot SensorEventListener, await the first event
     * (or timeout 1s), unregister. Cleanest way to read a current
     * sensor value without keeping a long-lived subscription.
     */
    private suspend fun sensorReading(type: Int): JsonObject? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(type) ?: return null
        val event = oneShotEvent(sm, sensor) ?: return null
        return buildJsonObject {
            put(
                "values",
                buildJsonArray { event.values.forEach { add(JsonPrimitive(it.toDouble())) } },
            )
            put("accuracy", event.accuracy)
            put("name", sensor.name)
            put("vendor", sensor.vendor ?: "")
            put("ts_ns", event.timestamp)
        }
    }

    private suspend fun oneShotEvent(sm: SensorManager, sensor: Sensor): SensorEvent? {
        return withTimeoutOrNull(SENSOR_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event)
                    }
                    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { /* no-op */ }
                }
                val registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
                if (!registered) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    companion object {
        private const val TAG = "Mythara/Sensors"
        private const val SENSOR_TIMEOUT_MS = 1_000L
    }
}
