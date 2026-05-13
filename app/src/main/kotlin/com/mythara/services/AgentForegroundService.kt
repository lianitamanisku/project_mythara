package com.mythara.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mythara.MainActivity
import com.mythara.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Keepalive + background-mic foreground service for the agent.
 *
 * Spun up by [com.mythara.agent.AgentRunner] when there's active
 * agent work (a turn in flight, a voice trigger, continuous-mic mode).
 * Posts the required persistent notification so the OS:
 *   - keeps the Mythara process alive through screen-off / app switch
 *   - allows the mic to record from background (Android 12+ enforces
 *     FGS-type `microphone` for any bg mic access)
 *   - shows the user "Mythara is working" so they know something's
 *     running and can stop it
 *
 * This service intentionally does NOT host the agent execution
 * itself — AgentRunner's process-wide CoroutineScope does that. The
 * service is a passive anchor; agent state lives in AgentRunner.
 * Decoupling means the agent can write to Room, speak via TTS, and
 * react to flow events even during the brief window after this
 * service has stopped but before the OS reclaims memory.
 *
 * FGS-type: `microphone`. We don't need `specialUse` because the
 * agent's lifetime is task-bounded (a turn takes seconds-to-minutes,
 * not days). Observe gets `specialUse` because it's genuinely
 * always-on.
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Start as foreground IMMEDIATELY. Android (S+) requires this
        // to happen within 5 seconds of the service starting via
        // startForegroundService — we play it safe and do it in
        // onCreate so any onStartCommand path is already foreground.
        startInForeground()
        Log.d(TAG, "AgentForegroundService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky-not-redeliver: if the OS kills us under memory
        // pressure, we don't want it to silently respawn us — the
        // user would see a stale "Mythara is working" notification.
        // AgentRunner re-starts the service the moment there's new
        // work anyway.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AgentForegroundService stopped")
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        // Android 14+ (target SDK 34+) tightened the FGS-type
        // contract: starting with type=`microphone` throws
        // SecurityException unless RECORD_AUDIO is currently granted.
        // The user can hit a notification-driven turn (NotificationListener
        // → AgentRunner → start(this)) BEFORE they've granted mic in the
        // onboarding flow — Android 16 turns that throw into a process
        // crash. So we pick the type at runtime: `microphone` when mic
        // is granted (preserves background mic capture for STT /
        // continuous loops), `specialUse` otherwise (still a valid
        // foreground service for keepalive + the persistent notification,
        // just without the mic-capture privilege the user hasn't
        // granted anyway).
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val type = if (hasMic) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        runCatching {
            startForeground(NOTIFICATION_ID, notification, type)
        }.onFailure { e ->
            // Belt-and-braces: if a future Android tightens specialUse
            // too, fall back to plain startForeground so the keepalive
            // still lands rather than crashing the whole app.
            Log.w(TAG, "startForeground type=$type rejected (${e.message}); retrying typeless")
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mythara assistant",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description =
                "Shown while Lumi is actively processing a request (listening, " +
                    "thinking, or driving another app). Auto-dismisses when idle."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mythara")
            .setContentText("Lumi is working in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "Mythara/AgentFGS"
        private const val CHANNEL_ID = "mythara_agent_runner"
        private const val NOTIFICATION_ID = 0xA9E47 // arbitrary but stable

        /**
         * Idempotent start. AgentRunner calls this on every fresh
         * turn; the service no-ops if already running.
         */
        fun start(ctx: Context) {
            val intent = Intent(ctx, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AgentForegroundService::class.java))
        }
    }
}
