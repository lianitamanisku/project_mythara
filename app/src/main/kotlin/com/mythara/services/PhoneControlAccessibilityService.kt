package com.mythara.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Mythara's window-reading service. Granted via system Settings →
 * Accessibility (user-driven, can't be auto-granted by the app).
 *
 * Today it exposes:
 *   - [currentRootNode] — the AccessibilityNodeInfo of whatever's
 *     in the foreground. Read-only snapshot for the `read_screen`
 *     agent tool.
 *   - [isEnabled] — process-wide observable so the Settings panel
 *     can show a status pill without polling.
 *
 * M6 will grow this with `dispatchGesture(...)` plumbing for the
 * `tap` / `swipe` / `type_text` tools. The xml/accessibility_service_config
 * already declares `canPerformGestures=true` so the user only grants
 * the permission once and gets both read + gesture surface together.
 *
 * Lifecycle: Android wires this up automatically once the user
 * enables it in Accessibility settings. `onServiceConnected()` fires
 * when the system attaches us; `onDestroy()` when the user toggles
 * us off or the system kills the service.
 */
class PhoneControlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isEnabled.value = true
        Log.d(TAG, "service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
            _isEnabled.value = false
        }
        Log.d(TAG, "service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Snapshot model — we don't react to events live. The
        // `read_screen` tool pulls [rootInActiveWindow] on demand.
        // This callback exists because the service contract requires it.
    }

    override fun onInterrupt() {
        // System asked us to stop processing temporarily. No-op for a
        // snapshot-on-demand service; the next read_screen call will
        // succeed once interruption clears.
    }

    /** Snapshot of the foreground window's root node. Null if the
     *  service hasn't been granted by the user or no window is active. */
    fun currentRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Dispatch a single tap at the given screen coordinates. Returns
     * true if the gesture was accepted by the system, false if the
     * service hasn't been granted gesture capability or coordinates
     * are off-screen. Suspends until the gesture completes or fails.
     */
    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        return dispatchGestureSuspending(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Drag from (x1, y1) to (x2, y2) over [durationMs]. Swipe gestures
     * use a single stroke with a path traversing the two points.
     */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = SWIPE_DURATION_MS): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureSuspending(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Find a node whose text contains [text] (case-insensitive) and
     * tap its centre. Walks the foreground window tree;
     * findAccessibilityNodeInfosByText handles deeply-nested matches.
     * Returns true on success, false if no node matched OR the
     * gesture was rejected. Used by the skill runner so multi-step
     * automations don't depend on pixel coordinates.
     */
    suspend fun tapNodeWithText(text: String): Boolean {
        val target = findFirstNodeWithText(text) ?: return false
        return tapNode(target)
    }

    /**
     * Tap the first node whose `contentDescription` matches [desc]
     * (case-insensitive substring). For icon buttons that have no
     * visible text — send arrows, kebab menus, etc.
     */
    suspend fun tapNodeWithDesc(desc: String): Boolean {
        val target = findFirstNodeWithDesc(desc) ?: return false
        return tapNode(target)
    }

    /**
     * Tap the first node whose Android view-id resource name ends
     * with the given suffix. Tolerant of package prefixing — model
     * can pass "send_button" and we match both
     * "com.whatsapp:id/send_button" and bare "send_button".
     */
    suspend fun tapNodeWithId(id: String): Boolean {
        val target = findFirstNodeWithId(id) ?: return false
        return tapNode(target)
    }

    /**
     * Is there any node on screen whose text contains [text]?
     * Used by skill-runner verify steps.
     */
    fun isTextVisible(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val hits = runCatching { root.findAccessibilityNodeInfosByText(text) }.getOrNull()
        val found = !hits.isNullOrEmpty()
        hits?.forEach { runCatching { it.recycle() } }
        runCatching { root.recycle() }
        return found
    }

    private fun findFirstNodeWithText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val hits = runCatching { root.findAccessibilityNodeInfosByText(text) }
            .getOrNull()
            .orEmpty()
        val match = hits.firstOrNull { isLikelyTappable(it) } ?: hits.firstOrNull()
        // Don't recycle the match (caller owns); recycle root + siblings.
        hits.forEach { if (it !== match) runCatching { it.recycle() } }
        runCatching { root.recycle() }
        return match
    }

    private fun findFirstNodeWithDesc(desc: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val needle = desc.lowercase()
        val match = walkAndFind(root) { node ->
            node.contentDescription?.toString()?.lowercase()?.contains(needle) == true
        }
        if (match !== root) runCatching { root.recycle() }
        return match
    }

    private fun findFirstNodeWithId(id: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val match = walkAndFind(root) { node ->
            val rid = node.viewIdResourceName ?: return@walkAndFind false
            rid.endsWith("/$id") || rid == id
        }
        if (match !== root) runCatching { root.recycle() }
        return match
    }

    private fun walkAndFind(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = walkAndFind(child, predicate)
            if (match != null) {
                // recycle siblings we didn't pick
                if (child !== match) runCatching { child.recycle() }
                return match
            }
            runCatching { child.recycle() }
        }
        return null
    }

    private fun isLikelyTappable(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.isLongClickable || node.parent?.isClickable == true
    }

    /**
     * Dispatch a tap on the centre of [node]'s bounds-on-screen rect.
     * Some nodes report tap-handling at their PARENT — fall back to
     * walking up if [node] itself isn't clickable.
     */
    private suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val effective = if (node.isClickable) node else walkUpForClickable(node) ?: node
        val rect = android.graphics.Rect()
        effective.getBoundsInScreen(rect)
        runCatching { effective.recycle() }
        if (rect.isEmpty) return false
        return tap(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun walkUpForClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cursor: AccessibilityNodeInfo? = node.parent
        var hops = 0
        while (cursor != null && hops < 6) {
            if (cursor.isClickable) return cursor
            val next = cursor.parent
            runCatching { cursor.recycle() }
            cursor = next
            hops++
        }
        return null
    }

    /**
     * Find the currently-focused editable node and set its text via
     * AccessibilityNodeInfo.ACTION_SET_TEXT. Returns true if a target
     * node was found AND the action succeeded. The model can use
     * read_screen first to confirm which field is focused if needed.
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)
            ?: return false.also { runCatching { root.recycle() } }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        runCatching { focused.recycle() }
        runCatching { root.recycle() }
        return ok
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findFirstEditableNode(child)
            if (match != null) return match
            runCatching { child.recycle() }
        }
        return null
    }

    /**
     * Bridge AccessibilityService.dispatchGesture (callback-based) to
     * a single suspend Boolean. The system calls onCompleted on
     * success or onCancelled on failure; either way we resume with
     * the corresponding flag.
     */
    private suspend fun dispatchGestureSuspending(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val ok = runCatching { dispatchGesture(gesture, callback, null) }.getOrDefault(false)
            if (!ok && cont.isActive) cont.resume(false)
        }

    companion object {
        private const val TAG = "Mythara/A11y"

        /** Live process-wide handle. Null when the service isn't
         *  currently bound (user hasn't enabled it, or system killed it). */
        @Volatile var instance: PhoneControlAccessibilityService? = null
            private set

        private val _isEnabled = MutableStateFlow(false)

        /** Observable enabled-state for UI status pills. */
        val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

        /** Single-tap duration. ~80ms reads as a real tap to most apps. */
        private const val TAP_DURATION_MS = 80L
        /** Swipe duration. 300ms is the system-default fling feel. */
        private const val SWIPE_DURATION_MS = 300L
    }
}
