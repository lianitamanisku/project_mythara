package com.mythara.services

import android.app.Notification
import android.graphics.Bitmap
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
@AndroidEntryPoint
class NotificationListener : NotificationListenerService() {

    @Inject lateinit var actionStore: NotificationActionStore

    /** v6 — publishes every buffer change so the in-app Notification
     *  Hub renders a live, observable feed. */
    @Inject lateinit var feedRepo: NotificationFeedRepository

    // Scope for fire-and-forget DataStore writes when notifications
    // are cancelled / clicked. Tied to the service instance so it
    // doesn't outlive the bind; cancelled in onListenerDisconnected.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Recent(
        val key: String,
        val packageName: String,
        val postTimeMs: Long,
        val title: String?,
        val text: String?,
        val subText: String?,
        val ongoing: Boolean,
        /**
         * Absolute paths to images extracted from this notification's
         * Notification.EXTRA_PICTURE / large icon big extras. Saved to
         * app-private storage on capture and consumed by
         * [com.mythara.agent.NotificationImageIngestor] which deletes
         * them after processing. Empty when no image was attached.
         */
        val imagePaths: List<String> = emptyList(),
        /**
         * Heuristic: the body looks like a video placeholder
         * ("🎥 Video" or similar short marker). When true, the
         * dispatcher / ingestor skips this notification entirely —
         * we don't process video notifications.
         */
        val looksLikeVideo: Boolean = false,
        /**
         * True when this notification is a phone / VoIP call. Set
         * from Notification.category == CATEGORY_CALL (the canonical
         * Android signal) plus a fallback check on common dialer
         * packages + title patterns. Consumers (AutoReplyDispatcher,
         * ChatViewModel.notif auto-process) gate on the
         * ProcessCallNotificationsStore toggle: by default the agent
         * never reacts to calls; the user can opt in via Settings.
         */
        val looksLikeCall: Boolean = false,
        /**
         * v7 — the notification's tap PendingIntent (sbn.notification.
         * contentIntent). Used by the in-app Notification Hub + Home
         * notifications strip to "open the source app" on tap. Null
         * for notifications without a tap intent; callers fall back
         * to `PackageManager.getLaunchIntentForPackage(packageName)`.
         */
        val contentIntent: android.app.PendingIntent? = null,
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
        publishFeed()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) {
            instance = null
            _isEnabled.value = false
            recent.clear()
            publishFeed()
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
        publishFeed()
    }

    /**
     * Reason-aware variant Android invokes when available. Lets us
     * distinguish "user dismissed" (REASON_CANCEL) from "user
     * clicked" (REASON_CLICK) from "app cancelled" (REASON_APP_CANCEL).
     * The action store builds its auto-dismiss decision from these
     * counts; bumping the wrong counter trains it wrong.
     */
    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: android.service.notification.NotificationListenerService.RankingMap?,
        reason: Int,
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return
        recent.removeIf { it.key == sbn.key }
        publishFeed()
        val pkg = sbn.packageName ?: return
        // Only count user-driven actions; app-side cancels, system
        // overlay updates, etc. don't tell us anything about user
        // preference for that pkg.
        // Dwell time: how long was the notification visible before
        // the user reacted? Pulled from the in-memory `recent` buffer
        // (post times we captured in captureLocked). Short dwells on
        // dismiss are the strongest "this is noise" signal we have.
        val dwellMs: Long = recent.firstOrNull { it.key == sbn.key }
            ?.let { (System.currentTimeMillis() - it.postTimeMs).coerceAtLeast(0L) }
            ?: -1L
        ioScope.launch {
            runCatching {
                when (reason) {
                    REASON_CANCEL -> {
                        if (dwellMs >= 0) actionStore.bumpUserDismissedWithDwell(pkg, dwellMs)
                        else actionStore.bumpUserDismissed(pkg)
                    }
                    REASON_CLICK -> actionStore.bumpUserOpened(pkg)
                    // REASON_LISTENER_CANCEL is OUR cancel (we
                    // initiated it from auto-dismiss path); already
                    // tracked via bumpAutoDismissed at the call site.
                    else -> { /* APP_CANCEL, USER_STOPPED, etc. — no signal */ }
                }
            }.onFailure { Log.w(TAG, "actionStore bump failed: ${it.message}") }
        }
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

        // Image + video extraction. Notifications can carry an inline
        // bitmap via EXTRA_PICTURE (big-picture style) or
        // EXTRA_LARGE_ICON_BIG (some apps use this for thumbnail). We
        // save whatever's there to disk so the ingestor can decide
        // whether to process it — the bitmap itself shouldn't ride in
        // the Recent struct (Bitmaps are heavy, the Recent goes through
        // a SharedFlow + buffer).
        val savedImages = extractAndSaveImages(extras, sbn.key.orEmpty())
        val looksLikeVideo = looksLikeVideoNotification(text)
        val looksLikeCall = looksLikeCallNotification(
            category = sbn.notification?.category,
            pkg = sbn.packageName.orEmpty(),
            title = title,
            text = text,
        )

        val r = Recent(
            key = sbn.key.orEmpty(),
            packageName = sbn.packageName.orEmpty(),
            postTimeMs = sbn.postTime,
            title = title?.take(MAX_TEXT_LEN),
            text = text?.take(MAX_TEXT_LEN),
            subText = subText?.take(MAX_TEXT_LEN),
            ongoing = isOngoing,
            imagePaths = savedImages,
            looksLikeVideo = looksLikeVideo,
            looksLikeCall = looksLikeCall,
            // v7 — capture the tap PendingIntent so the in-app hub +
            // Home strip can open the source app on tap.
            contentIntent = sbn.notification?.contentIntent,
        )
        // Detect whether this is a *new* notification vs an update to an
        // existing one (sticky media controls re-post on every track
        // change, for instance). Update-in-place doesn't fire the
        // auto-process flow; only genuinely new keys do.
        val isUpdate = recent.any { it.key == r.key }
        recent.removeIf { it.key == r.key }
        recent.addFirst(r)
        while (recent.size > BUFFER_SIZE) recent.pollLast()
        publishFeed()
        // Fan-out for auto-process. We emit even ongoing + self-package
        // here and let the downstream collector filter — keeps the
        // service code simple and lets downstream policy evolve without
        // a service redeploy.
        if (!isUpdate && r.packageName.isNotEmpty()) {
            // tryEmit on a BufferOverflow.DROP_OLDEST flow never blocks
            // and never fails — at worst we drop the oldest pending
            // event when bursts overrun the 16-slot buffer.
            _newNotifications.tryEmit(r)
        }
        // Auto-dismiss noise AFTER we've captured the metadata for
        // downstream pipelines. Two paths:
        //
        //   1. Static PromoNotificationClassifier (package allowlist
        //      / category match / phrase patterns).
        //
        //   2. Learned per-pkg pattern from NotificationActionStore —
        //      a sliding window of dismiss latencies. When the user
        //      has consistently swiped a pkg's notifications away
        //      within a few seconds AND we have enough samples to
        //      be confident, we auto-dismiss the next one even if
        //      no static rule fires. The "learning slowly" + "auto-
        //      dismiss when confident" semantic.
        //
        //   The store check is async (DataStore); we hop to ioScope
        //   and cancel from there. Captures the event for the audit
        //   log regardless of which path fired.
        if (PromoNotificationClassifier.shouldAutoDismiss(
                packageName = r.packageName,
                category = sbn.notification?.category,
                title = title,
                text = text,
            )
        ) {
            runCatching { cancelNotification(sbn.key) }
                .onFailure { Log.w(TAG, "promo dismiss cancel failed: ${it.message}") }
            ioScope.launch {
                runCatching { actionStore.bumpAutoDismissed(r.packageName, title, text) }
            }
            Log.d(TAG, "auto-dismissed promo notif from ${r.packageName} (static rule)")
        } else {
            ioScope.launch {
                val learned = runCatching { actionStore.shouldAutoDismiss(r.packageName) }
                    .getOrDefault(false)
                if (learned) {
                    runCatching { cancelNotification(sbn.key) }
                        .onFailure { Log.w(TAG, "learned dismiss failed: ${it.message}") }
                    runCatching { actionStore.bumpAutoDismissed(r.packageName, title, text) }
                    Log.d(TAG, "auto-dismissed notif from ${r.packageName} (learned pattern)")
                }
            }
        }
    }

    /** Snapshot the rolling buffer. Most-recent first. Ongoing
     *  notifications can be filtered out by the caller. */
    fun snapshot(): List<Recent> = recent.toList()

    /** Push the current buffer to the observable feed repo so the
     *  in-app hub updates live. Guarded — if injection hasn't landed
     *  yet (very early connect) it just no-ops. */
    private fun publishFeed() {
        runCatching { feedRepo.publish(snapshot()) }
    }

    /**
     * Pull any inline image bitmap out of a notification's extras and
     * persist it under app-private filesDir so downstream ingestors
     * can pass paths around without keeping heavy Bitmap references on
     * the SharedFlow.
     *
     * Sources we cover:
     *   - Notification.EXTRA_PICTURE   (big-picture style, the canonical
     *                                   path WhatsApp uses for image
     *                                   message thumbnails)
     *   - Notification.EXTRA_LARGE_ICON_BIG (some apps surface the
     *                                   image as the larger icon)
     *
     * We do NOT extract MessagingStyle dataUri images for v1 —
     * resolving those URIs requires URI permissions that aren't
     * guaranteed for notification listeners, and the bitmap path
     * covers WhatsApp + most SMS clients which is the bulk of the
     * use case.
     *
     * Returns absolute paths to JPEGs (75% quality, capped at
     * MAX_NOTIF_IMAGE_SIDE on the long edge — the vision model
     * doesn't need full-res, and shrinking saves disk + transport).
     */
    @Suppress("DEPRECATION")
    private fun extractAndSaveImages(extras: android.os.Bundle, key: String): List<String> {
        val out = mutableListOf<String>()
        val candidates = mutableListOf<Bitmap>()

        // EXTRA_PICTURE — big-picture style.
        val pic: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        }
        pic?.let(candidates::add)

        // EXTRA_LARGE_ICON_BIG — alternate carrier.
        val icon: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG)
        }
        icon?.let { i ->
            // Skip tiny icons — those are app glyphs, not actual photos.
            if (i.width >= MIN_NOTIF_IMAGE_SIDE && i.height >= MIN_NOTIF_IMAGE_SIDE) {
                candidates.add(i)
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // Save to app-private staging dir. Unique filename keyed by
        // (notif key, nanoTime) so multiple notifications + multiple
        // images don't collide.
        val dir = imageStagingDir()
        dir.mkdirs()
        for ((idx, bmp) in candidates.withIndex()) {
            val resized = downscaleIfNeeded(bmp)
            val safeKey = key.replace(Regex("[^A-Za-z0-9]"), "_").take(40)
            val file = File(dir, "notif_${safeKey}_${System.nanoTime()}_$idx.jpg")
            val ok = runCatching {
                FileOutputStream(file).use { fos ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 75, fos)
                }
                true
            }.getOrElse {
                Log.w(TAG, "couldn't persist notification image: ${it.message}")
                false
            }
            if (ok) out.add(file.absolutePath)
        }
        return out
    }

    private fun downscaleIfNeeded(src: Bitmap): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= MAX_NOTIF_IMAGE_SIDE) return src
        val scale = MAX_NOTIF_IMAGE_SIDE.toFloat() / longEdge
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(src, newW, newH, true) }.getOrDefault(src)
    }

    /**
     * Cheap text heuristic for "this is a video, not an image"
     * placeholders. WhatsApp surfaces video messages as "🎥 Video"
     * or "📹 Video" in EXTRA_TEXT before the user opens the chat.
     * Conservative: if the body is short AND contains a video glyph
     * or the literal word "video", treat as video.
     */
    private fun looksLikeVideoNotification(body: String?): Boolean {
        val b = body?.trim().orEmpty()
        if (b.isEmpty()) return false
        if (b.length > 30) return false
        if (b.contains('\uD83C') /* 🎥 / 📹 / 🎬 high-surrogate */) {
            if (b.contains("🎥") || b.contains("📹") || b.contains("🎬")) return true
        }
        return b.equals("video", ignoreCase = true) || b.startsWith("video", ignoreCase = true) &&
            b.length < 15
    }

    private fun imageStagingDir(): File = File(filesDir, "notif_images")

    /**
     * Detect "this is a phone/VoIP call" notification across the
     * three reliable signals:
     *   1. Notification.category == CATEGORY_CALL (Android's
     *      canonical hint — all well-behaved dialer / voip apps set
     *      this; both incoming + missed-call use it)
     *   2. Package is a known dialer (handles legacy apps that
     *      don't set category correctly)
     *   3. Title / text matches obvious call patterns ("Incoming
     *      call", "Missed call", "Voice call from", etc.) — catches
     *      apps that route call notifs through a generic category
     *
     * Returns true if ANY of the three signals fire.
     */
    private fun looksLikeCallNotification(
        category: String?,
        pkg: String,
        title: String?,
        text: String?,
    ): Boolean {
        if (category == Notification.CATEGORY_CALL) return true
        if (pkg in DIALER_PACKAGES) return true
        val combined = "${title.orEmpty()} ${text.orEmpty()}".lowercase()
        if (combined.isBlank()) return false
        return CALL_BODY_PATTERNS.any { combined.contains(it) }
    }

    companion object {
        private const val TAG = "Mythara/Notif"
        private const val BUFFER_SIZE = 50
        private const val MAX_TEXT_LEN = 600

        /** Below this, the "image" is almost certainly an app glyph. */
        private const val MIN_NOTIF_IMAGE_SIDE = 96

        /** Long-edge cap. Vision models work fine at this resolution. */
        private const val MAX_NOTIF_IMAGE_SIDE = 1024

        /**
         * Common dialer / phone apps. Used as a fallback when an app
         * routes call notifications through a generic category. Add
         * to this set if a specific call app is leaking past
         * detection.
         */
        private val DIALER_PACKAGES: Set<String> = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.huawei.contacts",
            "com.miui.voicecall",
            "com.oneplus.dialer",
            "com.oppo.contacts",
        )

        /**
         * Substrings checked against `(title + " " + text).lowercase()`
         * — covers en-US wording across most messengers + the
         * stock Phone app. Conservative; we'd rather miss a borderline
         * call notif than misclassify a chat as a call.
         */
        private val CALL_BODY_PATTERNS: List<String> = listOf(
            "incoming call",
            "missed call",
            "calling…",
            "calling...",
            "ongoing call",
            "voice call",
            "video call",
            "voip call",
            "answer",
            "is calling",
            "called you",
        )

        @Volatile var instance: NotificationListener? = null
            private set

        private val _isEnabled = MutableStateFlow(false)
        val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

        // Lock-free deque is fine — onNotification* runs on the
        // service's main thread; snapshot() runs from the tool's
        // coroutine. ConcurrentLinkedDeque gives us a snapshot-safe
        // iterator without explicit synchronisation.
        private val recent = ConcurrentLinkedDeque<Recent>()

        /**
         * Fires once per *new* notification (keyed by Android's notif
         * key — sticky updates of the same key don't refire). Subscribers
         * apply their own filtering (ongoing, self-package, "auto-process
         * enabled?" toggle) before acting.
         *
         * Buffer size 16 with DROP_OLDEST: a burst of notifications during
         * a backgrounded period won't crash; the auto-processor catches
         * up on the most recent few.
         */
        private val _newNotifications = MutableSharedFlow<Recent>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val newNotifications: SharedFlow<Recent> = _newNotifications.asSharedFlow()
    }
}
