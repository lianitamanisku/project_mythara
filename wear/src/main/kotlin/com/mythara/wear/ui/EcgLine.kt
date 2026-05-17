package com.mythara.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A scrolling ECG waveform tied to a live heart-rate reading.
 *
 *  • When [bpm] is non-null: draws a QRS-pattern (small Q dip → tall R
 *    spike → small S dip → flat → small T bump) scrolling right-to-left
 *    at one beat per `60/bpm` seconds. Looks like a hospital monitor.
 *  • When [bpm] is null (no fresh reading from HeartRateService): drops
 *    to a static flatline so the strip is still visibly there but
 *    obviously inactive.
 *
 * The whole render is one Canvas + one Path per frame — same per-frame
 * cost as the existing `♥ 67` Text label it replaces, plus a single
 * `infiniteRepeatable` driver Compose pauses automatically when the
 * composable leaves recomposition (overlay covers it, screen sleep).
 */
@Composable
fun EcgLine(
    bpm: Int?,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFEB4268),
) {
    val effectiveBpm = bpm?.coerceIn(MIN_BPM, MAX_BPM)
    if (effectiveBpm == null) {
        // Inactive: draw a static flatline so the layout doesn't jump
        // when readings come and go, but it's visibly not pulsing.
        Canvas(modifier = modifier) {
            val midY = size.height / 2f
            drawLine(
                color = color.copy(alpha = 0.45f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        return
    }

    // One beat = 60_000 / bpm milliseconds. The animation cycles a
    // 0..1 phase over exactly two beat-periods so we always have at
    // least one full QRS in-frame as the strip scrolls left.
    val beatPeriodMs = (60_000f / effectiveBpm).toInt()
    val cyclePeriodMs = (beatPeriodMs * BEATS_PER_CYCLE).coerceAtLeast(200)
    val transition = rememberInfiniteTransition(label = "ecg")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = BEATS_PER_CYCLE.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(cyclePeriodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ecg-phase",
    )

    Canvas(modifier = modifier) {
        drawEcgStrip(phase, color)
    }
}

private fun DrawScope.drawEcgStrip(phaseBeats: Float, color: Color) {
    val w = size.width
    val h = size.height
    val midY = h / 2f
    val beatWidth = w / VISIBLE_BEATS  // canvas shows N beats wide

    val path = Path()
    path.moveTo(0f, midY)

    // For each x along the canvas, work out which beat we're inside
    // and how far through it. `phaseBeats` is monotonically advancing
    // — modulo it into beat-space so the wave scrolls without ever
    // teleporting.
    val steps = (w / 1.5f).toInt().coerceAtLeast(40)  // density: 1 segment per 1.5 px
    for (i in 0..steps) {
        val x = (i.toFloat() / steps) * w
        // Convert x to "time" — leftmost pixel is in the past, rightmost
        // is "now". The phase shifts the whole strip leftward over time.
        val beatPos = (x / beatWidth) + phaseBeats
        val phase01 = ((beatPos % 1f) + 1f) % 1f  // 0..1 within current beat
        val y = midY - qrsAmplitude(phase01) * (h * 0.45f)
        path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

/**
 * The QRS-T waveform as a function of phase 0..1 within one beat.
 * Returns an amplitude in [-1, 1] where +1 is "max R-spike" and -1
 * is "max Q/S dip". Outside the QRS-T window it's 0 (flatline).
 *
 * Wave layout:
 *   0.00 - 0.50 → flat baseline (diastolic plateau)
 *   0.50 - 0.55 → tiny Q dip (-0.18)
 *   0.55 - 0.62 → tall R spike (+1.0)
 *   0.62 - 0.68 → S dip (-0.35)
 *   0.68 - 0.78 → back to baseline
 *   0.78 - 0.92 → broad T-wave bump (+0.30)
 *   0.92 - 1.00 → flat baseline tail
 */
private fun qrsAmplitude(phase01: Float): Float = when {
    phase01 < 0.50f -> 0f
    phase01 < 0.55f -> lerp(0f, -0.18f, (phase01 - 0.50f) / 0.05f)
    phase01 < 0.58f -> lerp(-0.18f, 1.0f, (phase01 - 0.55f) / 0.03f)
    phase01 < 0.62f -> lerp(1.0f, -0.35f, (phase01 - 0.58f) / 0.04f)
    phase01 < 0.68f -> lerp(-0.35f, 0f, (phase01 - 0.62f) / 0.06f)
    phase01 < 0.78f -> 0f
    phase01 < 0.85f -> {
        // Rising half of T-wave bump.
        val t = (phase01 - 0.78f) / 0.07f
        lerp(0f, 0.30f, smoothstep(t))
    }
    phase01 < 0.92f -> {
        // Falling half of T-wave bump.
        val t = (phase01 - 0.85f) / 0.07f
        lerp(0.30f, 0f, smoothstep(t))
    }
    else -> 0f
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private const val MIN_BPM = 30
private const val MAX_BPM = 220
private const val BEATS_PER_CYCLE = 4    // wave loops every 4 beats so the strip never visibly resets
private const val VISIBLE_BEATS = 2f      // ~2 QRS complexes on-screen at once
