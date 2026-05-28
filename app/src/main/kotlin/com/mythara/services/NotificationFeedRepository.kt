package com.mythara.services

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes [NotificationListener]'s otherwise-ephemeral rolling buffer
 * observable so the in-app Notification Hub ([com.mythara.ui.notifications.NotificationHubScreen])
 * can render a LIVE list of the phone's notifications and update as
 * they post / dismiss.
 *
 * The listener calls [publish] with a fresh `snapshot()` on every
 * capture / removal / (dis)connect; the hub collects [feed]. No Room
 * persistence — the buffer is ephemeral by design (privacy: nothing
 * notification-derived is written to disk here), so the hub shows
 * whatever the listener currently holds and goes empty when the
 * listener disconnects (e.g. permission revoked / reboot).
 *
 * Singleton so the one listener instance and the one VM share it.
 */
@Singleton
class NotificationFeedRepository @Inject constructor() {

    private val _feed = MutableStateFlow<List<NotificationListener.Recent>>(emptyList())

    /** Live snapshot of the listener's buffer, most-recent first. */
    val feed: StateFlow<List<NotificationListener.Recent>> = _feed.asStateFlow()

    /** Called by [NotificationListener] whenever its buffer changes. */
    fun publish(items: List<NotificationListener.Recent>) {
        _feed.value = items
    }
}

/**
 * Open the source app of a captured notification: try the captured
 * tap [PendingIntent][android.app.PendingIntent] first (this is how
 * the system shade opens an app on notification tap — it lands on
 * the exact screen the notification points to), and fall back to
 * `PackageManager.getLaunchIntentForPackage(packageName)` if no
 * content intent was attached. Returns true if something launched.
 */
fun openNotificationSource(ctx: Context, recent: NotificationListener.Recent): Boolean {
    // 1. Prefer the captured contentIntent — lands on the exact
    //    activity the notification points to (a specific WhatsApp
    //    chat, a calendar event, etc).
    recent.contentIntent?.let { pi ->
        val ok = runCatching { pi.send() }.isSuccess
        if (ok) return true
        Log.w("Mythara/NotifFeed", "contentIntent.send failed for ${recent.packageName}")
    }
    // 2. Fallback: launch the app's main activity.
    val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(recent.packageName) }
        .getOrNull() ?: return false
    return runCatching {
        ctx.startActivity(launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        true
    }.getOrDefault(false)
}
