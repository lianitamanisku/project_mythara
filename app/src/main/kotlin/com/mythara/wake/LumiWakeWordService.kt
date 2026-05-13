package com.mythara.wake

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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that hosts the Lumi wake-word listener while the
 * app is in the background. Android forces a visible notification for
 * any FGS, which we keep deliberately minimal — "Mythara is listening
 * for 'Lumi'" plus a tap-to-open intent.
 *
 * Lifecycle:
 *   - Started by [LumiWakeWordToggle] when the user flips the Settings
 *     toggle ON, or by app start if the toggle is persisted ON.
 *   - Stopped on toggle OFF, or when the user explicitly stops via the
 *     notification action.
 *
 * Audio is captured by [LumiWakeWordController] inside the library; the
 * service just hosts it across process priority changes. AudioRecord
 * hardware can't multiplex with Observe's recorder — see the controller
 * docstring for the mutual-exclusion contract.
 */
@AndroidEntryPoint
class LumiWakeWordService : Service() {

    @Inject lateinit var controller: LumiWakeWordController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeRelay: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(), serviceTypeForFgs())
        controller.start()
        wakeRelay = scope.launch {
            controller.wakes.collect { event ->
                Log.d(TAG, "relay wake → app: trigger='${event.triggerPhrase}' agent='${event.agentName}' score=${event.score}")
                Wakes.broadcast.tryEmit(event)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.stop()
        runCatching { wakeRelay?.cancel() }
        scope.cancel()
    }

    private fun serviceTypeForFgs(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Lumi wake-word listener",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mythara is listening for the 'Lumi' wake word."
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LumiWakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Lumi (Mythara)")
            .setContentText("Listening for 'Hey Jarvis'")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    /**
     * App-wide bridge so any component (typically MainActivity) can
     * react to a wake event regardless of which process subsystem
     * detected it. Replay buffer = 0; subscribe-or-miss semantics.
     */
    object Wakes {
        val broadcast: MutableSharedFlow<LumiWakeWordController.WakeEvent> =
            MutableSharedFlow(replay = 0, extraBufferCapacity = 4)
        val flow: SharedFlow<LumiWakeWordController.WakeEvent> = broadcast.asSharedFlow()
    }

    companion object {
        private const val TAG = "Mythara/Wake/Svc"
        private const val CHANNEL_ID = "mythara.wake.lumi"
        private const val NOTIF_ID = 0x77ABBA
        const val ACTION_STOP = "com.mythara.wake.STOP"
    }
}
