package com.mythara.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mythara.MainActivity
import com.mythara.R
import com.mythara.ui.system.DynamicIsland
import com.mythara.ui.system.rememberCutoutRect
import com.mythara.ui.theme.MytharaTheme

/**
 * Foreground service that draws the Mythara Dynamic Island as a
 * SYSTEM-OVERLAY WINDOW — so the same pill the user sees inside
 * Mythara is also visible:
 *   - over every other app
 *   - on the home screen
 *   - on the lock screen (best-effort; see Android Z-order notes
 *     below)
 *
 * Why a service:
 *   Compose UI inside an Activity dies when the Activity stops.
 *   To keep a window painted across the OS surface we need either
 *   a service or a launcher, both of which can hold a
 *   WindowManager view independent of any Activity lifecycle.
 *
 * Permission posture:
 *   Requires SYSTEM_ALERT_WINDOW (the user flips it in
 *   Settings → Special app access → "Display over other apps").
 *   The manifest permission is the install-time grant; the
 *   per-app toggle is runtime via [Settings.canDrawOverlays].
 *   Caller (typically [com.mythara.ui.permissions.PermissionsScreen])
 *   should gate the start() call on
 *   [LockscreenIslandService.canRender].
 *
 * Lock-screen Z-order:
 *   On Android 12+ the secure keyguard renders at TYPE_KEYGUARD,
 *   which is ABOVE TYPE_APPLICATION_OVERLAY. So when the user
 *   has a PIN/pattern/biometric lock active, the overlay won't
 *   draw on the secure surface — it appears once the user has
 *   unlocked but BEFORE entering an app. For "swipe up to unlock"
 *   styles where the lock screen is non-secure, the overlay
 *   shows immediately. We accept this trade-off rather than
 *   fight Android's lock-screen security; users who want
 *   always-visible behavior can use the in-app status-bar pill,
 *   which the overlay mirrors.
 *
 * Tap behaviour:
 *   The pill is touch-passthrough by default (NOT_TOUCH_MODAL +
 *   NOT_FOCUSABLE) so it doesn't steal scrolls / taps from
 *   underlying apps. The interactive zone is the pill itself —
 *   tapping the rose center triggers the same animation +
 *   sink-clear the in-app version does, and a long-press routes
 *   the user into Mythara's Chat.
 */
class LockscreenIslandService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    /** Observes the PROCESS lifecycle so the overlay only shows when
     *  Mythara itself is NOT in the foreground. The island's whole
     *  purpose is to surface insights over the lock screen + OTHER
     *  apps; over Mythara it's redundant AND harmful — its top-centre
     *  hit rect eats taps on the app's own top-of-screen elements
     *  (the user's "can't tap things near the island" bug). So:
     *    app foreground (≥ STARTED) → removeOverlay()
     *    app background             → ensureOverlay() */
    private val foregroundObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                Log.d(TAG, "app foregrounded → hiding overlay")
                removeOverlay()
            }
            Lifecycle.Event.ON_STOP -> {
                Log.d(TAG, "app backgrounded → showing overlay")
                ensureOverlay()
            }
            else -> {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // ComposeOwner's setup happens lazily in
        // [ComposeOwner.ensureReady] — that path correctly
        // sequences performAttach + performRestore BEFORE moving
        // the lifecycle to RESUMED, which is what
        // SavedStateRegistry's contract requires (the old in-
        // class-init path called performAttach AFTER the apply
        // block had already set the lifecycle to RESUMED →
        // "Restarter must be created only during owner's
        // initialization stage" crash).
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        // Register the process-lifecycle observer ONCE (idempotent —
        // observers dedupe). It immediately drives the correct initial
        // state: if the app is already foreground, the overlay stays
        // hidden; if backgrounded, it shows. addObserver delivers the
        // current state synchronously so we don't need a manual
        // ensureOverlay() here.
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        if (!isAppForegrounded()) ensureOverlay()
        return START_STICKY
    }

    private fun isAppForegrounded(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun onDestroy() {
        super.onDestroy()
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver) }
        removeOverlay()
        // ComposeOwner is process-wide (singleton object) and may
        // be reused if the service restarts. We deliberately don't
        // tear its lifecycle down here — moving it to DESTROYED
        // would break a subsequent restart of this service since
        // SavedStateRegistry can't be re-attached.
    }

    /* ------------------------------------------------- foreground */

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mythara overlay",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps the Mythara Dynamic Island visible everywhere"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Mythara")
            .setContentText("Dynamic Island is live")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    /* ------------------------------------------------- overlay window */

    private fun ensureOverlay() {
        if (overlayView != null) return
        if (!canRender(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay disabled")
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        // Layout flags:
        //   - FLAG_NOT_FOCUSABLE: don't take keyboard focus
        //   - FLAG_NOT_TOUCH_MODAL: touches OUTSIDE the overlay
        //     window's bounds pass through to the underlying app
        //   - FLAG_LAYOUT_NO_LIMITS / FLAG_LAYOUT_IN_SCREEN: let
        //     us position above the system status-bar inset in
        //     screen-space coordinates
        //   - FLAG_SHOW_WHEN_LOCKED: best-effort render on lock
        //     screens (see service doc for Z-order caveats)
        //
        // We DO want touches INSIDE the window to land on the
        // pill — that's how the user controls the app from any
        // foreground context. So the window is sized via
        // WRAP_CONTENT in both axes: the WindowManager carves out
        // a region that's only as big as the pill / teardrop
        // actually paints, and FLAG_NOT_TOUCH_MODAL passes
        // everything outside that region through to the
        // underlying app.
        //
        // Earlier this used MATCH_PARENT width on the assumption
        // that "Compose passes the touch to the pill, not the
        // empty regions" — that's false. Compose's pointer
        // dispatching only routes events through the COMPOSE
        // node tree, but the WindowManager hit-test happens
        // BEFORE Compose ever runs. Any tap inside the window's
        // rect is owned by the window. So full-width meant the
        // top 130dp of every other app was a touch dead-zone,
        // breaking search bars + back gestures across the whole
        // device.
        //
        // WRAP_CONTENT does require a layout pass per expand /
        // collapse, but the pill only resizes on user action so
        // the cost is negligible.
        // Window-flag posture for an overlay that:
        //  • LETS underlying-app touches fall through anywhere the
        //    overlay doesn't paint (FLAG_NOT_TOUCH_MODAL)
        //  • NEVER steals key/IME focus from the activity below
        //    (FLAG_NOT_FOCUSABLE). The soft keyboard requires the
        //    activity's window to have IME focus; if the overlay
        //    is focusable, the IME tries to attach to IT instead
        //    of MainActivity and the keyboard never pops up when
        //    the user taps a TextField anywhere in Mythara.
        //  • Touch events DO still arrive at the ComposeView even
        //    when the host window is not focusable — `.clickable`
        //    and `pointerInput` operate on touch dispatch, not
        //    focus, so the pill and the teardrop launchers stay
        //    tappable.
        //  • LAYOUT_NO_LIMITS / IN_SCREEN so the pill can sit in
        //    the cutout zone at the very top of the display.
        //  • SHOW_WHEN_LOCKED so the pill remains visible on the
        //    lock screen.
        //
        // We previously tried FLAG_ALT_FOCUSABLE_IM as a way to
        // keep focus while excluding the IME; on this device
        // family it doesn't reliably free up IME focus when the
        // overlay window is on top, so we go back to the simpler
        // FLAG_NOT_FOCUSABLE posture.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        // Overlay window height starts SMALL — just enough for
        // the collapsed circle (+ its safeTopDp top inset).
        // 130dp covers safeTopDp (60dp) + pill height (45dp) +
        // a few dp of breathing room. When the user taps the
        // circle and the teardrop expands, onExpandedChange
        // fires resizeOverlayForExpand(true) which grows the
        // window to OVERLAY_EXPANDED_HEIGHT_DP so the launchers
        // are inside the touch zone. Collapse shrinks it back.
        //
        // Without this dynamic resize, the overlay window
        // captured touches in a 400dp tall zone CONSTANTLY,
        // blocking chat scrolls, back gestures, and every tap
        // underneath — exactly what the user reported.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }

        val view = ComposeView(this).apply {
            // Critical for overlay-hosted ComposeView: the
            // default ViewCompositionStrategy expects the view to
            // be attached to a normal view tree which has a
            // window-scoped Recomposer. WindowManager.addView
            // doesn't create that scope, so Compose's pointer
            // input dispatching falls back to a stub that NEVER
            // delivers MotionEvents to clickable {} / pointerInput
            // — which is why earlier overlay taps appeared dead.
            //
            // DisposeOnDetachedFromWindowOrReleasedFromPool builds
            // a local Recomposer tied to the View's own lifecycle,
            // which IS active for WindowManager-managed views.
            // With this strategy + the ComposeOwner lifecycle
            // attached below, click handlers inside the overlay
            // finally fire on tap.
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                MytharaTheme {
                    // The overlay hosts the SAME consolidated
                    // Dynamic Island the in-app surface uses, in
                    // its EXPANDED state by default — so users
                    // see the full status pill (clock + signal
                    // + M● + I● + Me + 🎙 + battery + MYTHARA)
                    // floating over whichever app they're in.
                    // alwaysExpanded = true hard-locks the
                    // pill open so the overlay never collapses
                    // back to the small rose+MYTHARA form (the
                    // user's explicit ask: "that status pill
                    // must be visible over other apps instead
                    // of the current one. The status must be
                    // the new overlay").
                    com.mythara.ui.system.MytharaStatusBar(
                        // Every teardrop-menu launcher deep-links
                        // MainActivity with EXTRA_OPEN_ROUTE so
                        // Mythara comes to foreground on the
                        // requested screen, per user spec.
                        onRoseTap = { launchMytharaRoute("chat") },
                        // Dynamically resize the overlay window
                        // when the pill expands / collapses.
                        // Without this the overlay covers a tall
                        // touch-capture zone EVEN when the pill
                        // is just a small circle — blocking
                        // chat scrolls, back gestures, and every
                        // tap underneath. Now the window shrinks
                        // back to a small footprint around the
                        // circle when collapsed.
                        onExpandedChange = { isExpanded ->
                            resizeOverlayForExpand(isExpanded)
                        },
                        onOpenAboutMe = { launchMytharaRoute("about-me") },
                        onOpenPeople = { launchMytharaRoute("people") },
                        onOpenMemory = { launchMytharaRoute("memory") },
                        onOpenTasks = { launchMytharaRoute("tasks") },
                        onOpenUsage = { launchMytharaRoute("usage") },
                        onOpenSettings = { launchMytharaRoute("settings") },
                        onOpenTriage = { launchMytharaRoute("triage") },
                    )
                }
            }
        }

        // Compose-in-overlay-window plumbing: the ComposeView needs
        // a LifecycleOwner + SavedStateRegistryOwner attached via
        // ViewTree owners, otherwise it crashes on first frame.
        // ComposeOwner.ensureReady() runs lazily on first access
        // and sequences the SavedStateRegistry attach+restore
        // before transitioning the lifecycle to RESUMED.
        ComposeOwner.ensureReady()
        view.setViewTreeLifecycleOwner(ComposeOwner)
        view.setViewTreeSavedStateRegistryOwner(ComposeOwner)

        runCatching { wm.addView(view, params) }
            .onSuccess {
                overlayView = view
                Log.i(TAG, "overlay attached")
            }
            .onFailure { Log.w(TAG, "overlay attach failed: ${it.message}") }
    }

    private fun removeOverlay() {
        overlayView?.let { v ->
            runCatching { windowManager?.removeViewImmediate(v) }
        }
        overlayView = null
        windowManager = null
    }

    /* ------------------------------------------------- overlay sizing */

    /** Collapsed overlay window height in PIXELS. Computed from
     *  the device's density so it adapts across screen
     *  classes. Covers the safeTopDp + circle. */
    private fun collapsedHeightPx(): Int =
        (OVERLAY_COLLAPSED_HEIGHT_DP * resources.displayMetrics.density).toInt()

    /** Expanded overlay window height in PIXELS — tall enough
     *  to fit the teardrop drop-down menu fully inside the
     *  touch-capture zone. */
    private fun expandedHeightPx(): Int =
        (OVERLAY_EXPANDED_HEIGHT_DP * resources.displayMetrics.density).toInt()

    /**
     * Re-trigger a WindowManager layout pass when the pill
     * toggles between collapsed (small circle) and expanded
     * (full teardrop). With WRAP_CONTENT params, the WindowManager
     * picks up the new measured size automatically — we just need
     * to poke it via updateViewLayout so the host window's
     * touch-capture rect resyncs with the Compose content's
     * actual footprint. Without this nudge, the window keeps the
     * old (smaller) size and the launchers fall outside the
     * touchable area.
     *
     * Wrapped in runCatching because WindowManager.updateViewLayout
     * can throw if the view's been detached between the click
     * registering and the size change applying.
     */
    private fun resizeOverlayForExpand(@Suppress("UNUSED_PARAMETER") expanded: Boolean) {
        val v = overlayView ?: return
        val wm = windowManager ?: return
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return
        // Force the WindowManager to re-measure. WRAP_CONTENT
        // sometimes caches the prior measured size; touching
        // params + updateViewLayout invalidates the cache.
        runCatching { wm.updateViewLayout(v, params) }
            .onFailure { Log.w(TAG, "updateViewLayout failed: ${it.message}") }
    }

    /**
     * Bring Mythara to foreground on a specific named route.
     * Used by every teardrop-menu launcher tap so the user can
     * jump from any third-party app into the exact Mythara
     * screen they wanted. Service runs without a back stack so
     * we use FLAG_ACTIVITY_NEW_TASK + EXTRA_OPEN_ROUTE; the
     * activity's MytharaRoot picks up the extra and navigates
     * its NavController there on mount.
     */
    private fun launchMytharaRoute(route: String) {
        runCatching {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_OPEN_ROUTE, route)
            startActivity(intent)
        }
    }

    /* --- Compose hosting glue: single LifecycleOwner + SavedStateOwner ---
     *
     * SavedStateRegistry's contract: performAttach() and
     * performRestore() MUST be called while the lifecycle is in
     * the INITIALIZED state. The previous version's `apply` block
     * was setting `currentState = RESUMED` BEFORE the
     * SavedStateRegistryController had been attached, which
     * threw "Restarter must be created only during owner's
     * initialization stage" the first time anything touched the
     * object.
     *
     * Fix: lazy init under a one-shot flag. ensureReady() runs
     * the attach+restore while the lifecycle is still INITIALIZED,
     * then promotes to RESUMED in a single pass. Idempotent —
     * subsequent calls no-op.
     */
    private object ComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val reg = LifecycleRegistry(this)
        private val ssrc = SavedStateRegistryController.create(this)
        @Volatile private var initialized = false

        fun ensureReady() {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                // 1) lifecycle is INITIALIZED by default; attach
                //    + restore the SavedStateRegistry now.
                ssrc.performAttach()
                ssrc.performRestore(null)
                // 2) Now promote through CREATED → STARTED →
                //    RESUMED so Compose's ViewTreeLifecycleOwner
                //    sees a fully-resumed lifecycle on first paint.
                reg.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                initialized = true
            }
        }

        override val lifecycle: androidx.lifecycle.Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry
    }

    companion object {
        private const val TAG = "Mythara/IslandOverlay"
        private const val CHANNEL_ID = "mythara_island_overlay"
        private const val NOTIFICATION_ID = 7711

        /** Window height when the pill is COLLAPSED (just the
         *  circle). Covers the safeTopDp position + circle +
         *  small bottom padding. Anything below is reachable
         *  for the underlying app. */
        private const val OVERLAY_COLLAPSED_HEIGHT_DP = 130

        /** Window height when the pill is EXPANDED (teardrop
         *  with status row + 2×4 launcher grid). Sized to
         *  comfortably contain the whole teardrop so every
         *  launcher icon is in the touch-capture zone. */
        private const val OVERLAY_EXPANDED_HEIGHT_DP = 400

        /** Intent extra MainActivity reads to land directly on a
         *  named route after the overlay launches it. Currently
         *  used by the Me-avatar tap (extra = "about-me"). */
        const val EXTRA_OPEN_ROUTE = "mythara.overlay.open_route"

        /** True when the user has flipped the per-app overlay
         *  toggle (Settings → Special app access → Display over
         *  other apps). Caller checks this before calling start. */
        fun canRender(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(ctx)

        /** Idempotent start. Caller should gate on [canRender].
         *
         *  Wrapped in runCatching because Android 12+ throws
         *  [android.app.ForegroundServiceStartNotAllowedException]
         *  when the caller is in BG context (e.g. Application.
         *  onCreate, or any non-foreground startup). The right
         *  fix is to call from an Activity, but a stray BG call
         *  shouldn't crash the whole app — silently skipping the
         *  overlay is preferable to taking the process down. */
        fun start(ctx: Context) {
            runCatching {
                val intent = Intent(ctx, LockscreenIslandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure { Log.w(TAG, "FGS start failed (likely BG-start restriction): ${it.message}") }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LockscreenIslandService::class.java))
        }

        /** Open the system Settings page where the user grants
         *  the SYSTEM_ALERT_WINDOW permission for Mythara. */
        fun requestPermission(ctx: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
        }
    }
}
