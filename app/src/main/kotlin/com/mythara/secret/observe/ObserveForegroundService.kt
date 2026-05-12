package com.mythara.secret.observe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mythara.MainActivity
import com.mythara.R
import com.mythara.growth.LearningJournal
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the Observe pipeline. M8.1a is a
 * heartbeat: it occupies the FGS slot with the correct
 * `microphone | specialUse` type, displays the mandatory persistent
 * notification, and journals a heartbeat entry every 30s so the user
 * can confirm it survives app dismissal. M8.1b plugs in AudioRecord +
 * Vosk and turns the heartbeat into real transcription.
 *
 * FGS-type rules (Android 14+):
 *   - `microphone`    grants the service mic access while in foreground
 *   - `specialUse`    long-running app-specific use; we declare a
 *                     subType in the manifest for compliance
 *   Both are declared so we don't have to migrate when ASR lands.
 *
 * The notification text is deliberately neutral ("Mythara is running")
 * — Android forces it to be present; we don't hide it but we don't
 * advertise Observe specifically. Tapping the notification opens
 * MainActivity, where the device-auth gate stops anyone who isn't
 * the user.
 */
@AndroidEntryPoint
class ObserveForegroundService : Service() {

    @Inject lateinit var store: ObserveStore
    @Inject lateinit var purger: RawDataPurger
    @Inject lateinit var journal: LearningJournal

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startInForeground()
            ACTION_PAUSE -> {
                heartbeatJob?.cancel(); heartbeatJob = null
                store.publish(ObserveState.Paused(System.currentTimeMillis()))
                Log.d(TAG, "paused")
            }
            ACTION_RESUME -> {
                store.publish(ObserveState.Running(System.currentTimeMillis()))
                startHeartbeat()
                Log.d(TAG, "resumed")
            }
            ACTION_STOP -> {
                shutdownAndStop()
                return START_NOT_STICKY
            }
            else -> startInForeground()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            store.publish(ObserveState.Running(System.currentTimeMillis()))
            startHeartbeat()
            Log.d(TAG, "foreground service started")
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: ${t.message}", t)
            store.publish(ObserveState.Error(t.message ?: "startForeground failed"))
            stopSelf()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // Sweep purger on launch so any stale files from a previous
            // boot get cleaned even before the heartbeat loop runs.
            purger.sweep()
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isActive) break
                purger.sweep()
                journal.append(LearningJournal.Entry(
                    tsMillis = System.currentTimeMillis(),
                    kind = "observe",
                    note = "heartbeat (M8.1a — audio capture lands in M8.1b)",
                ))
            }
        }
    }

    private fun shutdownAndStop() {
        heartbeatJob?.cancel(); heartbeatJob = null
        store.publish(ObserveState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentTitle("Mythara is running")
            .setContentText("Background service active")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel(); heartbeatJob = null
        if (store.state.value !is ObserveState.Idle) {
            store.publish(ObserveState.Idle)
        }
    }

    companion object {
        const val ACTION_START = "com.mythara.observe.START"
        const val ACTION_PAUSE = "com.mythara.observe.PAUSE"
        const val ACTION_RESUME = "com.mythara.observe.RESUME"
        const val ACTION_STOP = "com.mythara.observe.STOP"

        private const val TAG = "Mythara/Observe"
        private const val NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "mythara.observe.service"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mythara service",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Persistent indicator that Mythara background service is running."
                setShowBadge(false)
                enableLights(false); enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            mgr.createNotificationChannel(channel)
        }
    }
}
