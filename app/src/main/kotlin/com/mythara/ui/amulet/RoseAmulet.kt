package com.mythara.ui.amulet

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mythara.branding.LiveWallpaperPulseSink
import com.mythara.branding.MoodSink
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The rose amulet — Mythara's persistent brand mark + universal nav
 * hub, drawn as a Compose [Canvas].
 *
 * In Phase 1 it is purely visual: same 10-petal rose as the splash /
 * wallpaper / watch face, slowly rotating, hex nucleus pulsing in
 * sync with the user's heart rate when fresh, falling back to a
 * 0.2 Hz calm-breath cadence otherwise. The petal palette tints
 * subtly with the user's most recent mood.
 *
 * Subsequent phases will layer gestures (tap → home, swipe-up →
 * constellation, long-press → quick-action wheel, triple-tap →
 * secret unlock) on top of this same composable. None of those are
 * wired yet — for now the amulet is just a heartbeat-synced badge
 * the user can see and recognise as their own.
 *
 * Reuses the shared [RoseGeometry] constants so it renders pixel-
 * for-pixel identically to the rose drawn by the live wallpaper
 * underneath. Reads [LiveWallpaperPulseSink] + [MoodSink] — the
 * same process-wide sinks the wallpaper engine reads from — so an
 * adb HR or mood injection updates both surfaces simultaneously.
 */
@Composable
fun RoseAmulet(
    modifier: Modifier = Modifier,
    sizeDp: Dp = AMULET_SIZE_DP,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onTripleTap: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    /**
     * PTT (push-to-talk) gesture — press-and-hold the rose.
     *
     * Fires `(true, 0)` once the user has been pressing the rose
     * for at least [PTT_THRESHOLD_MS] (250 ms by default). The host
     * starts mic capture at that point.
     *
     * Fires `(false, durationMs)` on release OR when the press
     * lasts long enough to elevate to [onLongPress] (≥800 ms).
     * `durationMs` is the total press duration from down to release;
     * the host can use it to decide whether the captured PCM is
     * worth submitting (e.g. drop sub-250 ms blips that elevated to
     * PTT then released immediately).
     *
     * Also fires `(false, 0)` when a swipe gesture interrupts the
     * press, so the host can cancel mic capture cleanly.
     *
     * This is additive — the existing `onTap` / `onLongPress` /
     * `onSwipeUp` callbacks all continue to fire normally. Hosts
     * that don't want PTT can pass the default no-op.
     */
    onPttHoldChange: (isHolding: Boolean, durationMs: Long) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val swipeUpThresholdPx = with(density) { SWIPE_UP_DP.dp.toPx() }
    val swipeHorizThresholdPx = with(density) { SWIPE_HORIZ_DP.dp.toPx() }

    // Triple-tap detector — collects taps within TRIPLE_TAP_WINDOW_MS
    // and fires onTripleTap when three land in the window. Resets on
    // any longer gap. Single tap still fires onTap immediately
    // because we want the universal "tap = home" gesture to feel
    // responsive; triple-tap is an additive escalation.
    var tapTimes by remember { mutableStateOf(longArrayOf()) }
    // Slow infinite rotation — one full revolution per ROT_PERIOD_MS.
    // Same cadence as the live wallpaper for visual continuity.
    val infiniteTransition = rememberInfiniteTransition(label = "amulet-rotation")
    val rotationDeg by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ROT_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "amulet-rotation-angle",
    )

    // Live HR pulse rate, refreshed once a second so the renderer
    // doesn't synchronously read the volatile field on every frame.
    // Stale-after fallback handled inside effectivePulseHz().
    val pulseHz by produceState(initialValue = DEFAULT_PULSE_HZ, key1 = Unit) {
        while (true) {
            value = effectivePulseHz()
            delay(POLL_INTERVAL_MS)
        }
    }

    // Mood-tinted petal accents. Re-evaluated on the same cadence as
    // pulse so a chat turn that just landed propagates within ~1 s.
    val moodAccent by produceState(initialValue = RoseGeometry.Lavender, key1 = Unit) {
        while (true) {
            value = moodAccentFor(MoodSink.current())
            delay(POLL_INTERVAL_MS)
        }
    }

    // Per-frame breath phase computed inline from system time so the
    // pulse stays continuous across recompositions. Using a separate
    // infiniteTransition would reset on rate change.
    val pulse by produceState(initialValue = 0f, key1 = pulseHz) {
        val startMs = System.currentTimeMillis()
        while (true) {
            val tSec = (System.currentTimeMillis() - startMs) / 1000f
            val phase = tSec * pulseHz * 2f * PI.toFloat()
            value = (sin(phase) + 1f) * 0.5f       // 0..1
            delay(40L)                             // ~25 fps; cheap
        }
    }

    val petalPath = remember()
    val hexPath = remember()

    Canvas(
        modifier = modifier
            // Tap detector — single tap → onTap, long-press →
            // onLongPress. Triple-tap is handled in the swipe-pointer
            // input below (we record press times there to avoid two
            // gesture detectors fighting over the down event).
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                )
            }
            // Swipe + triple-tap + PTT-hold detector. Lives in the
            // same pointerInput so we can correlate the same down
            // event across all three gestures.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val downTime = System.currentTimeMillis()
                    var totalDx = 0f
                    var totalDy = 0f
                    var swipeFired = false
                    var pttHolding = false
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val held = System.currentTimeMillis() - downTime
                        // PTT cross-threshold: still pressed, no swipe
                        // fired, held >= PTT_HOLD_MS (3 s). Fire once —
                        // a deliberate long press arms walkie-talkie
                        // push-to-talk; release sends.
                        if (!pttHolding && !swipeFired && change.pressed && held >= PTT_HOLD_MS) {
                            pttHolding = true
                            onPttHoldChange(true, 0L)
                        }
                        if (!change.pressed) {
                            val totalHeld = System.currentTimeMillis() - downTime
                            // If we were in PTT mode, end it.
                            if (pttHolding) {
                                onPttHoldChange(false, totalHeld)
                            }
                            // Up — if no swipe fired AND no PTT was
                            // captured (i.e. quick tap before threshold),
                            // it's a tap; record the time and decide
                            // single vs triple. If PTT was captured,
                            // the host already handled it and we
                            // suppress the tap to avoid double-fire.
                            if (!swipeFired && !pttHolding) {
                                val now = System.currentTimeMillis()
                                val recent = tapTimes.filter { now - it <= TRIPLE_TAP_WINDOW_MS }
                                val updated = (recent + now).takeLast(3).toLongArray()
                                tapTimes = updated
                                if (updated.size == 3) {
                                    tapTimes = longArrayOf()
                                    onTripleTap()
                                } else {
                                    onTap()
                                }
                            }
                            break
                        }
                        val drag = change.positionChange()
                        totalDx += drag.x
                        totalDy += drag.y
                        if (!swipeFired) {
                            // Threshold check. Vertical takes
                            // precedence over horizontal because the
                            // amulet's most important gesture
                            // (constellation reveal) is swipe-up.
                            if (totalDy < -swipeUpThresholdPx) {
                                swipeFired = true
                                // Swipe interrupts PTT — cancel.
                                if (pttHolding) {
                                    pttHolding = false
                                    onPttHoldChange(false, 0L)
                                }
                                onSwipeUp()
                            } else if (abs(totalDx) > swipeHorizThresholdPx && abs(totalDx) > abs(totalDy)) {
                                swipeFired = true
                                if (pttHolding) {
                                    pttHolding = false
                                    onPttHoldChange(false, 0L)
                                }
                                if (totalDx > 0) onSwipeRight() else onSwipeLeft()
                            }
                        }
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        // Rose source viewport is 108×108 with petals tipping at
        // |30| source units from the centre. Fit into the smaller of
        // the two canvas dims minus a tiny margin.
        val scale = (minOf(w, h) * 0.5f) / RoseGeometry.OuterRadiusSourceUnits

        rotate(degrees = rotationDeg, pivot = Offset(cx, cy)) {
            // Big petals — purple, mood-tinted toward moodAccent.
            val bigColor = lerp(RoseGeometry.Purple, moodAccent, MOOD_TINT_AMOUNT)
            for (deg in RoseGeometry.BigPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.BigPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx,
                    cy = cy,
                    scale = scale,
                    out = petalPath,
                )
                drawPath(petalPath, color = bigColor)
            }
            // Small petals — lavender, also mood-tinted.
            val smallColor = lerp(RoseGeometry.Lavender, moodAccent, MOOD_TINT_AMOUNT)
            for (deg in RoseGeometry.SmallPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.SmallPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx,
                    cy = cy,
                    scale = scale,
                    out = petalPath,
                )
                drawPath(petalPath, color = smallColor)
            }
            // Cyan hex nucleus, opacity pulses with HR. Floor stays
            // at HEX_ALPHA_MIN so the nucleus never fully disappears.
            val hexAlpha = HEX_ALPHA_MIN + pulse * (1f - HEX_ALPHA_MIN)
            RoseGeometry.hexPath(cx, cy, scale, hexPath)
            drawPath(hexPath, color = RoseGeometry.Cyan.copy(alpha = hexAlpha))
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────

/** Default amulet diameter in dp. 84 dp matches the watch face's PTT
 *  button size + reads as a clear tap target without dominating the
 *  composer area. */
val AMULET_SIZE_DP: Dp = 84.dp

/** Default amulet bottom margin from the canvas bottom edge. */
val AMULET_BOTTOM_MARGIN_DP: Dp = 16.dp

private const val ROT_PERIOD_MS = 90_000      // matches WallpaperRenderer
private const val POLL_INTERVAL_MS = 1_000L
private const val DEFAULT_PULSE_HZ = 0.2f     // calm breath fallback
private const val MAX_PULSE_HZ = 0.8f
private const val HEX_ALPHA_MIN = 0.55f
private const val MOOD_TINT_AMOUNT = 0.18f    // 0=no tint, 1=full mood colour

/** Pixel-distance the user must drag UP from the amulet before we
 *  treat the gesture as a "summon constellation" swipe. 40 dp ≈ a
 *  thumb-flick that's clearly intentional rather than a stray drag. */
private const val SWIPE_UP_DP = 40

/** Horizontal swipe threshold for stepping between adjacent primary
 *  screens (chat ↔ insights ↔ face ↔ people, etc). Higher than the
 *  vertical threshold so casual horizontal jitter on a tap doesn't
 *  navigate. */
private const val SWIPE_HORIZ_DP = 80

/** Window during which three quick taps register as a "secret
 *  unlock" triple-tap. 800 ms is comfortable for a deliberate
 *  triple-tap without being slow enough to merge unrelated taps. */
private const val TRIPLE_TAP_WINDOW_MS = 800L

/** How long the user must keep their finger on the rose before we
 *  treat the press as PTT (push-to-talk). 250 ms is short enough
 *  that "press and start speaking" feels immediate, yet long enough
 *  that a quick tap (which should fire `onTap`) won't accidentally
 *  trigger PTT.
 *
 *  Long-press (`onLongPress`) fires from a separate detector at the
 *  Android default (~500 ms). Hosts that want PTT-only and don't
 *  want a long-press wheel should leave `onLongPress` as the
 *  no-op default. */
private const val PTT_THRESHOLD_MS = 250L

/** v7 — push-to-talk arms only after a deliberate 3 s hold of the
 *  rose ("activate on long press more than 3 seconds"), then sends on
 *  release. Long enough that a quick tap (=Chat) or a 600 ms global
 *  long-press (=PopupAmulet, fired off-rose) never collides with it. */
private const val PTT_HOLD_MS = 3000L

/** Mirrors WallpaperRenderer.effectivePulseHz: bpm/300 clamped
 *  [DEFAULT_PULSE_HZ, MAX_PULSE_HZ], or DEFAULT when no fresh HR. */
private fun effectivePulseHz(): Float {
    val bpm = LiveWallpaperPulseSink.bpm() ?: return DEFAULT_PULSE_HZ
    return (bpm / 300f).coerceIn(DEFAULT_PULSE_HZ, MAX_PULSE_HZ)
}

/** Mood → petal accent colour. Subtle nudges toward the same hue
 *  family the wallpaper's gradient uses for that mood, so the amulet
 *  + the gradient underneath read as a single coherent colour story. */
private fun moodAccentFor(mood: String?): Color = when (mood) {
    "anxious" -> Color(0xFFFF6B7E)        // warm red flag
    "sad" -> Color(0xFF6B86FF)            // cool blue
    "frustrated" -> Color(0xFFFF8B50)     // orange-red
    "excited" -> Color(0xFFE368FF)        // magenta
    "happy" -> Color(0xFFB0FFE0)          // bright cyan-green
    else -> RoseGeometry.Lavender         // neutral
}

/** Linear interpolate two colours channel-by-channel. */
private fun lerp(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt,
    )
}

/** Convenience: zero-arg `remember { Path() }` for paths reused
 *  across recompositions but not across calls. */
@Composable
private fun remember(): Path = androidx.compose.runtime.remember { Path() }
