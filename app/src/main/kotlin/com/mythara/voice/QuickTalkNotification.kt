package com.mythara.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mythara.MainActivity
import com.mythara.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide "tap to talk to Lumi" persistent notification.
 *
 * Unlike the FGS keepalive notification posted by
 * [com.mythara.services.AgentForegroundService] (which only shows
 * while there's active agent work), this one is meant to live
 * indefinitely — the user's always-one-tap entry point to a voice
 * conversation with Lumi, regardless of what app they're in or
 * whether they have Pixel Buds connected.
 *
 * Implementation is a plain NotificationCompat ongoing notification,
 * NOT a foreground service. Reasons:
 *  - No work is happening when the user hasn't tapped. A bound FGS
 *    just to display a notification wastes battery + keeps the
 *    process pinned for no reason.
 *  - Tap fires a PendingIntent that launches MainActivity with
 *    ACTION_ASSIST; the existing MainActivity.handleVoiceIntent
 *    path catches it and runs the one-shot STT → agent → TTS
 *    pipeline that already works for Pixel Buds gestures.
 *
 * Gated by [QuickTalkSettings] — off by default so the user opts
 * in; flipping it on posts the notification immediately and on
 * every cold start.
 */
@Singleton
class QuickTalkNotification @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /**
     * Post (or refresh) the always-visible notification. Idempotent —
     * calling on every cold start is safe; the OS replaces the
     * existing notification in place.
     */
    fun show() {
        createChannel()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return

        // PendingIntent: open MainActivity with ACTION_ASSIST. The
        // assistant intent path in MainActivity fires the same
        // VoiceActionStore signal that Pixel-Buds touch-and-hold
        // does, which kicks off the one-shot STT + agent loop.
        val tapIntent = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapPi = PendingIntent.getActivity(
            ctx,
            REQUEST_TAP,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Talk to Lumi")
            .setContentText("Tap anywhere on this notification to speak.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPi)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // An explicit Talk action button in addition to the
            // tap-area, so the affordance is obvious on devices
            // that hide the body text in collapsed view.
            .addAction(
                NotificationCompat.Action.Builder(
                    R.mipmap.ic_launcher_round,
                    "Talk",
                    tapPi,
                ).build(),
            )
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Talk to Lumi",
            // LOW so the notification posts silently — it's a
            // persistent affordance, not an alert.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Always-visible 'tap to talk' affordance. Opens Mythara and starts listening."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mythara_quick_talk"
        private const val NOTIFICATION_ID = 0xA9E48 // distinct from AgentFGS's
        private const val REQUEST_TAP = 4071
    }
}
