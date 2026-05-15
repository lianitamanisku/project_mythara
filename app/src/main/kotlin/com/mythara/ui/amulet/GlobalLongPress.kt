package com.mythara.ui.amulet

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Modifier extension that fires [onLongPress] when the user holds a
 * single finger on the wrapped surface for [thresholdMs] milliseconds
 * without releasing or moving outside [moveTolerancePx].
 *
 * Designed to coexist with child gesture detectors (TextField cursor
 * placement, button presses, scrollback drag, etc.) — uses the
 * Final pointer-event pass so it only sees down events that no child
 * has consumed. A long-press on a TextField won't fire the amulet
 * (the TextField wins the gesture for cursor placement); a long-press
 * on bare chat scrollback / wallpaper / scaffold WILL.
 *
 * Movement check: any movement greater than [moveTolerancePx] from
 * the initial down position cancels the long-press detection so a
 * scroll / drag never accidentally summons the amulet.
 */
fun Modifier.detectGlobalLongPress(
    thresholdMs: Long = 600L,
    moveTolerancePx: Float = 24f,
    onLongPress: (Offset) -> Unit,
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        // Final pass — only fire if no descendant consumed the down.
        // requireUnconsumed=true is the default behaviour for Final.
        val down = awaitFirstDown(
            requireUnconsumed = true,
            pass = PointerEventPass.Final,
        )
        val downPos = down.position
        var moved = false

        // Loop awaiting events until either:
        //   - the user lifts the finger (release before threshold = no fire)
        //   - the user moves past tolerance (drag = no fire)
        //   - thresholdMs passes with finger still down within tolerance (fire)
        val fired = withTimeoutOrNull(thresholdMs) {
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // Released before threshold — not a long press.
                    return@withTimeoutOrNull false
                }
                val total = change.position - downPos
                if (kotlin.math.abs(total.x) > moveTolerancePx ||
                    kotlin.math.abs(total.y) > moveTolerancePx) {
                    moved = true
                    return@withTimeoutOrNull false
                }
            }
            false
        }

        // withTimeoutOrNull returns null on timeout — that's the
        // "still pressed, still inside tolerance" case = LONG PRESS.
        if (fired == null && !moved) {
            onLongPress(downPos)
        }
    }
}
