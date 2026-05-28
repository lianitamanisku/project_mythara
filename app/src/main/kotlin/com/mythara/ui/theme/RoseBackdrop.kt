package com.mythara.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import com.mythara.branding.LiveWallpaperPulseSink
import com.mythara.ui.amulet.RoseGeometry
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Living Rose backdrop — a single large geometric rose, drawn
 * centred behind the NavHost, rotating once every 90 s (matching the
 * live wallpaper + amulet cadence) with its cyan hex nucleus breathing
 * in time with the user's live heart rate.
 *
 * It's a *watermark*, not a loud illustration: the petals render at low
 * alpha over the rose-tinted base so screen content stays legible while
 * the surface clearly reads as "alive". A soft rose radial glow sits
 * under the rose to lift it off the flat base, and the nucleus pulse is
 * the only thing that moves quickly — everything else drifts.
 *
 * HR comes from [LiveWallpaperPulseSink]; in v6 the primary feeder is
 * [com.mythara.health.HealthConnectHrPoller] (Google Health), with the
 * watch path as fallback. When no fresh HR is available the nucleus
 * falls back to a 0.2 Hz calm-breath cadence so the rose still lives.
 */
@Composable
fun RoseBackdrop(
    palette: MythPalette,
    modifier: Modifier = Modifier,
) {
    // Slow rotation — one revolution per 90 s.
    val rot = rememberInfiniteTransition(label = "rose-backdrop")
    val rotationDeg by rot.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ROT_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rose-backdrop-rot",
    )

    // Live HR → breath rate, refreshed once a second.
    val pulseHz by produceState(initialValue = DEFAULT_PULSE_HZ, key1 = Unit) {
        while (true) {
            value = effectivePulseHz()
            delay(1_000L)
        }
    }

    // Per-frame breath phase (0..1) from system time so it's smooth
    // across recompositions and continuous when the rate changes.
    val pulse by produceState(initialValue = 0f, key1 = pulseHz) {
        val startMs = System.currentTimeMillis()
        while (true) {
            val tSec = (System.currentTimeMillis() - startMs) / 1000f
            val phase = tSec * pulseHz * 2f * PI.toFloat()
            value = (sin(phase) + 1f) * 0.5f
            delay(40L)
        }
    }

    val petalPath = remember { Path() }
    val hexPath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Base fill (palette already rose-tinted).
        drawRect(palette.Bg)

        val cx = w / 2f
        val cy = h * 0.42f               // sit slightly above centre

        // Rose spans ~84% of the smaller dimension's half-extent so it
        // fills the screen as an ambient watermark.
        val rose = minOf(w, h) * 0.84f
        val scale = (rose * 0.5f) / RoseGeometry.OuterRadiusSourceUnits

        // Soft rose glow behind the rose — lifts it off the flat base
        // and gives the breath a visible "bloom" as the nucleus pulses.
        val glowR = rose * 0.8f
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to palette.Charple.copy(alpha = 0.18f + pulse * 0.08f),
                    0.55f to palette.Charple.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = glowR,
            ),
            radius = glowR,
            center = Offset(cx, cy),
        )

        rotate(degrees = rotationDeg, pivot = Offset(cx, cy)) {
            // Big petals — rose accent, low alpha watermark.
            for (deg in RoseGeometry.BigPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.BigPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx, cy = cy, scale = scale, out = petalPath,
                )
                drawPath(petalPath, color = palette.Charple.copy(alpha = BIG_ALPHA))
            }
            // Small petals — slightly brighter rose, even softer.
            for (deg in RoseGeometry.SmallPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.SmallPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx, cy = cy, scale = scale, out = petalPath,
                )
                drawPath(petalPath, color = palette.Charple.copy(alpha = SMALL_ALPHA))
            }
            // Cyan nucleus — the one element that breathes with HR.
            val hexAlpha = HEX_ALPHA_MIN + pulse * (HEX_ALPHA_MAX - HEX_ALPHA_MIN)
            RoseGeometry.hexPath(cx, cy, scale, hexPath)
            drawPath(hexPath, color = palette.Bok.copy(alpha = hexAlpha))
        }
    }
}

private const val ROT_PERIOD_MS = 90_000
private const val DEFAULT_PULSE_HZ = 0.2f
private const val MAX_PULSE_HZ = 0.8f
private const val BIG_ALPHA = 0.30f
private const val SMALL_ALPHA = 0.34f
private const val HEX_ALPHA_MIN = 0.35f
private const val HEX_ALPHA_MAX = 0.95f

/** bpm/300 clamped [0.2, 0.8], or 0.2 calm-breath when no fresh HR. */
private fun effectivePulseHz(): Float {
    val bpm = LiveWallpaperPulseSink.bpm() ?: return DEFAULT_PULSE_HZ
    return (bpm / 300f).coerceIn(DEFAULT_PULSE_HZ, MAX_PULSE_HZ)
}
