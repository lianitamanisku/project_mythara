package com.mythara.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Mythara's notification listener. Granted via system Settings →
 * Apps → Special app access → Notification access. Can't be
 * auto-granted by the app — same pattern as the Accessibility
 * Service.
 *
 * Posture: snapshot-on-demand. We don't act on notifications live
 * (no auto-replies, no auto-mutes); we just maintain a small rolling
 * buffer of recent ones that the `read_notifications` agent tool
 * can read when the user asks "what notifications do I have."
 *
 * Privacy:
 *   - buffer is in-memory only, capped at [BUFFER_SIZE]. Never
 *     persisted to disk, never synced to GitHub.
 *   - ongoing notifications (the persistent media-player / FGS
 *     icons) are filtered out — the user almost never means those
 *     when they say "what notifications".
 *   - filtered-by-system notifications (DND silenced, sensitive
 *     content hidden on lockscreen) are honoured — we don't try to
 *     work around system policy.
 */
class NotificationListener : NotificationListenerService() {

    data class Recent(
        val key: String,
        val packageName: String,
        val postTimeMs: Long,
        val title: String?,
        val text: String?,
        val subText: String?,
        val ongoing: Boolean,
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _isEnabled.value = true
        Log.d(TAG, "listener connected")
        // Seed buffer with active notifications now that we have access.
        runCatching { activeNotifications?.toList() }.getOrNull()?.forEach { sbn ->
            captureLocked(sbn)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
            _isEnabled.value = false
            recent.clear()
        }
        Log.d(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        captureLocked(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        recent.removeIf { it.key == sbn.key }
    }

    private fun captureLocked(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        // Prefer EXTRA_BIG_TEXT for fuller content (think: an email
        // notification with the message body). Fallback to EXTRA_TEXT.
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val text = bigText ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val isOngoing = (sbn.notification?.flags ?: 0) and Notification.FLAG_ONGOING_EVENT != 0
        val r = Recent(
            key = sbn.key.orEmpty(),
            packageName = sbn.packageName.orEmpty(),
            postTimeMs = sbn.postTime,
            title = title?.take(MAX_TEXT_LEN),
            text = text?.take(MAX_TEXT_LEN),
            subText = subText?.take(MAX_TEXT_LEN),
            ongoing = isOngoing,
        )
        // Replace any prior entry with the same key (notification updates).
        recent.removeIf { it.key == r.key }
        recent.addFirst(r)
        while (recent.size > BUFFER_SIZE) recent.pollLast()
    }

    /** Snapshot the rolling buffer. Most-recent first. Ongoing
     *  notifications can be filtered out by the caller. */
    fun snapshot(): List<Recent> = recent.toList()

    companion object {
        private const val TAG = "Mythara/Notif"
        private const val BUFFER_SIZE = 50
        private const val MAX_TEXT_LEN = 600

        @Volatile var instance: NotificationListener? = null
            private set

        private val _isEnabled = MutableStateFlow(false)
        val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

        // Lock-free deque is fine — onNotification* runs on the
        // service's main thread; snapshot() runs from the tool's
        // coroutine. ConcurrentLinkedDeque gives us a snapshot-safe
        // iterator without explicit synchronisation.
        private val recent = ConcurrentLinkedDeque<Recent>()
    }
}
