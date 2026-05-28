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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Holographic HUD backdrop — a cockpit-glass instrument readout
 * rendered entirely in thin cyan line-art over the deep navy base:
 *
 *  - three concentric rings centred on the screen
 *  - a ring of radial tick marks around the outer circle
 *  - a slow radar "sweep" wedge that rotates once every 8 s, leaving
 *    a fading trailing gradient like a radar display
 *  - corner reticle brackets framing the viewport
 *  - a fine drifting horizontal scan line
 *
 * Everything is stroked (no fills) at low alpha so screen content
 * stays legible on top. The whole thing is cheap — a handful of
 * stroked circles + lines per frame on one Canvas.
 */
@Composable
fun HudBackdrop(
    palette: MythPalette,
    modifier: Modifier = Modifier,
) {
    val t = rememberInfiniteTransition(label = "hud")
    // Radar sweep — one revolution per 8 s.
    val sweepDeg by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hud-sweep",
    )
    // Scan line drift — top→bottom over 6 s.
    val scan by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hud-scan",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(palette.Bg)

        val cx = w / 2f
        val cy = h * 0.46f
        val accent = palette.Charple
        val maxR = maxOf(w, h) * 0.46f

        // Concentric rings.
        val ringStroke = Stroke(width = 1.2f)
        listOf(0.42f, 0.68f, 1.0f).forEach { f ->
            drawCircle(
                color = accent.copy(alpha = 0.12f),
                radius = maxR * f,
                center = Offset(cx, cy),
                style = ringStroke,
            )
        }

        // Radial tick marks around the outer ring (every 15°; longer
        // ticks every 90°).
        val outer = maxR
        for (deg in 0 until 360 step 15) {
            val r = deg * (Math.PI / 180.0).toFloat()
            val major = deg % 90 == 0
            val tickLen = if (major) maxR * 0.06f else maxR * 0.03f
            val r0 = outer - tickLen
            val c = cos(r); val s = sin(r)
            drawLine(
                color = accent.copy(alpha = if (major) 0.30f else 0.14f),
                start = Offset(cx + c * r0, cy + s * r0),
                end = Offset(cx + c * outer, cy + s * outer),
                strokeWidth = if (major) 2f else 1f,
            )
        }

        // Crosshair through the centre.
        drawLine(
            color = accent.copy(alpha = 0.10f),
            start = Offset(cx - maxR, cy), end = Offset(cx + maxR, cy),
            strokeWidth = 1f,
        )
        drawLine(
            color = accent.copy(alpha = 0.10f),
            start = Offset(cx, cy - maxR), end = Offset(cx, cy + maxR),
            strokeWidth = 1f,
        )

        // Radar sweep wedge — a thin bright leading edge with a fading
        // trailing gradient sector.
        rotate(degrees = sweepDeg, pivot = Offset(cx, cy)) {
            drawLine(
                color = accent.copy(alpha = 0.5f),
                start = Offset(cx, cy),
                end = Offset(cx + maxR, cy),
                strokeWidth = 2f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to accent.copy(alpha = 0.08f),
                        1f to Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = maxR,
                ),
                radius = maxR,
                center = Offset(cx, cy),
            )
        }

        // Corner reticle brackets.
        val m = 28f          // margin from edges
        val len = 56f        // bracket arm length
        val bracket = accent.copy(alpha = 0.28f)
        fun corner(ox: Float, oy: Float, dx: Float, dy: Float) {
            drawLine(bracket, Offset(ox, oy), Offset(ox + dx * len, oy), strokeWidth = 2f)
            drawLine(bracket, Offset(ox, oy), Offset(ox, oy + dy * len), strokeWidth = 2f)
        }
        corner(m, m, 1f, 1f)
        corner(w - m, m, -1f, 1f)
        corner(m, h - m, 1f, -1f)
        corner(w - m, h - m, -1f, -1f)

        // Drifting horizontal scan line.
        val scanY = scan * h
        drawLine(
            color = palette.Bok.copy(alpha = 0.18f),
            start = Offset(0f, scanY), end = Offset(w, scanY),
            strokeWidth = 1.5f,
        )
    }
}
