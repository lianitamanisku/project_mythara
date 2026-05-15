package com.mythara.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that samples the watch's heart-rate sensor and
 * pushes the latest reading to the phone every ~3 minutes over the
 * Wearable Data Layer ([WearPaths.HEART_RATE]).
 *
 * The phone files each reading into the health memory pipeline so the
 * "About Me" analytics can use it alongside the rest of the Health
 * Connect data. Runs foreground (minimal ongoing notification) so
 * sampling continues while the watch app isn't on screen — the point
 * is regular, spaced-out capture, not just app-session capture.
 */
class HeartRateService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sensorManager: SensorManager? = null

    /** Most recent valid bpm reading, or -1 until the sensor warms up. */
    @Volatile private var latestBpm: Int = -1

    /** Current sensor sample rate. Slow by default; the Resonance
     *  fast-stream mode bumps it up while a session is active. */
    @Volatile private var currentRate: Int = SensorManager.SENSOR_DELAY_NORMAL

    /** Fast push job — set while streaming, cancelled when streaming
     *  stops. The slow [PUSH_INTERVAL_MS] loop in onCreate keeps
     *  running independently. */
    private var streamJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        sensorManager = (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.also { sm ->
            val hr = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            if (hr != null) {
                sm.registerListener(this, hr, currentRate)
            } else {
                Log.w(TAG, "no heart-rate sensor on this device")
            }
        }
        // Push the latest reading on a slow timer — spaced capture, not
        // a firehose of every sensor sample.
        scope.launch {
            while (true) {
                delay(PUSH_INTERVAL_MS)
                pushLatest()
            }
        }
    }

    // NOT sticky: a background auto-restart would hit the Android 12+
    // background-FGS-start ban and crash-loop. The activity re-starts
    // it from onResume() instead, which is a guaranteed-foreground point.
    //
    // The streaming state is a tri-state on the wire: extra absent →
    // "just keep me alive, don't touch the mode" (used by the
    // plain `start()` from MainActivity.onResume); extra present + true
    // → start streaming; extra present + false → stop streaming.
    // Without this, every onResume would kill an active Resonance HR
    // stream because `start()` was overloaded with a default-false flag.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_STREAMING) == true) {
            val wantStreaming = intent.getBooleanExtra(EXTRA_STREAMING, false)
            if (wantStreaming) startStreamingInternal() else stopStreamingInternal()
        }
        return START_NOT_STICKY
    }

    /**
     * Bump sensor sample rate to [SensorManager.SENSOR_DELAY_UI] (~16ms)
     * and start a 1Hz push of the latest reading over
     * [WearPaths.RESONANCE_HR]. Idempotent. The slow 3-minute push on
     * [WearPaths.HEART_RATE] keeps running underneath.
     */
    private fun startStreamingInternal() {
        if (streamJob?.isActive == true) {
            Log.d(TAG, "streaming already active; skipping")
            return
        }
        streamPushCount = 0
        lastNoReadingLogMs = 0L
        lastNoNodesLogMs = 0L
        applySensorRate(SensorManager.SENSOR_DELAY_UI)
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        Log.d(TAG, "resonance HR stream starting (sensor=${sensor?.name ?: "NONE"}, latestBpm=$latestBpm)")
        streamJob = scope.launch {
            while (isActive) {
                delay(STREAM_INTERVAL_MS)
                pushStreamSample()
            }
        }
    }

    /** Drop sample rate back to NORMAL and stop the fast push loop. */
    private fun stopStreamingInternal() {
        if (streamJob?.isActive != true) return
        Log.d(TAG, "resonance HR stream stopping")
        streamJob?.cancel()
        streamJob = null
        applySensorRate(SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun applySensorRate(target: Int) {
        if (target == currentRate) return
        val sm = sensorManager ?: return
        val hr = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return
        runCatching { sm.unregisterListener(this) }
        sm.registerListener(this, hr, target)
        currentRate = target
    }

    private fun pushStreamSample() {
        val bpm = latestBpm
        if (bpm !in VALID_BPM) {
            // Surface this every ~5s so a "no readings yet" condition
            // is debuggable instead of silent.
            val now = System.currentTimeMillis()
            if (now - lastNoReadingLogMs > 5_000L) {
                Log.d(TAG, "stream tick — no valid HR yet (latest=$bpm)")
                lastNoReadingLogMs = now
            }
            return
        }
        streamPushCount++
        if (streamPushCount % 10 == 1) {
            Log.d(TAG, "streaming HR $bpm bpm (#$streamPushCount)")
        }
        val payload = "$bpm|${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
        val nodeClient = Wearable.getNodeClient(this)
        val msgClient = Wearable.getMessageClient(this)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - lastNoNodesLogMs > 10_000L) {
                        Log.w(TAG, "stream tick — NO connected phone nodes")
                        lastNoNodesLogMs = now
                    }
                    return@addOnSuccessListener
                }
                for (node in nodes) msgClient.sendMessage(node.id, WearPaths.RESONANCE_HR, payload)
            }
            .addOnFailureListener { e -> Log.w(TAG, "stream HR push failed: ${e.message}") }
    }

    @Volatile private var streamPushCount = 0
    @Volatile private var lastNoReadingLogMs = 0L
    @Volatile private var lastNoNodesLogMs = 0L

    override fun onSensorChanged(event: SensorEvent?) {
        val bpm = event?.values?.firstOrNull()?.toInt() ?: return
        if (bpm in VALID_BPM) latestBpm = bpm
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun pushLatest() {
        val bpm = latestBpm
        if (bpm !in VALID_BPM) {
            Log.d(TAG, "no valid HR reading yet; skipping push")
            return
        }
        val bytes = bpm.toString().toByteArray(Charsets.UTF_8)
        val nodeClient = Wearable.getNodeClient(this)
        val msgClient = Wearable.getMessageClient(this)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                for (node in nodes) msgClient.sendMessage(node.id, WearPaths.HEART_RATE, bytes)
                if (nodes.isNotEmpty()) Log.d(TAG, "pushed HR $bpm bpm to ${nodes.size} node(s)")
            }
            .addOnFailureListener { e -> Log.w(TAG, "HR push failed: ${e.message}") }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Heart-rate monitor",
                        NotificationManager.IMPORTANCE_MIN,
                    ),
                )
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mythara")
            .setContentText("monitoring heart rate")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { sensorManager?.unregisterListener(this) }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "Mythara/HeartRate"
        private const val CHANNEL_ID = "mythara_heart_rate"
        private const val NOTIF_ID = 0x4842
        private const val PUSH_INTERVAL_MS = 3L * 60 * 1000
        /** Resonance Mode fast-stream cadence — ~1Hz. */
        private const val STREAM_INTERVAL_MS = 1_000L
        private val VALID_BPM = 30..240

        /** Intent extra: when true, the service runs in fast-stream
         *  mode for a Resonance session (1Hz push on RESONANCE_HR). */
        private const val EXTRA_STREAMING = "stream"

        /** Start (or re-attach to) the slow 3-min HR push baseline.
         *  Does NOT touch the streaming mode — calling this from
         *  `MainActivity.onResume` must not knock an active Resonance
         *  HR stream back to the slow baseline. */
        fun start(ctx: Context) = launchSelf(ctx, streaming = null)

        /** Bump the running service into fast-stream mode for the
         *  duration of a Resonance session. Idempotent. */
        fun startStreaming(ctx: Context) = launchSelf(ctx, streaming = true)

        /** Drop fast-stream mode back to the slow baseline. */
        fun stopStreaming(ctx: Context) = launchSelf(ctx, streaming = false)

        private fun launchSelf(ctx: Context, streaming: Boolean?) {
            val intent = Intent(ctx, HeartRateService::class.java)
            if (streaming != null) intent.putExtra(EXTRA_STREAMING, streaming)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
