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
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Aurora Glass backdrop — three large, soft, low-alpha colour
 * "blobs" (charple / bok / lavender) drifting on slow Lissajous paths
 * over the base colour. No RenderEffect blur needed: each blob is a
 * radial gradient that fades to transparent at its edge, so the
 * overlaps read as a soft, living aurora. Cheap (one Canvas, three
 * radial-gradient fills per frame) and runs smoothly even on mid-tier
 * hardware.
 *
 * Translucent [MythCard]s drawn on top let this aurora show through,
 * which is what sells the "glass over northern lights" feel.
 */
@Composable
fun AuroraBackdrop(
    palette: MythPalette,
    modifier: Modifier = Modifier,
) {
    val t = rememberInfiniteTransition(label = "aurora")
    // One slow master phase (60 s loop); each blob samples it at a
    // different multiplier + offset so they never line up.
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "auroraPhase",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Base fill.
        drawRect(palette.Bg)

        // Blob radius scales with the larger screen dimension so the
        // aurora reads the same on phone + fold.
        val r = maxOf(w, h) * 0.55f

        fun blob(color: Color, mulX: Float, mulY: Float, offX: Float, offY: Float, cx0: Float, cy0: Float) {
            // Drift centre on a Lissajous path within the screen.
            val cx = cx0 * w + sin(phase * mulX + offX) * w * 0.18f
            val cy = cy0 * h + cos(phase * mulY + offY) * h * 0.16f
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to color.copy(alpha = 0.62f),
                        0.5f to color.copy(alpha = 0.26f),
                        1f to Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }

        blob(palette.Charple, 1.0f, 0.8f, 0f, 0f, 0.30f, 0.28f)
        blob(palette.Bok, 0.7f, 1.1f, 2.1f, 1.3f, 0.72f, 0.40f)
        blob(palette.Charple.copy(red = 0.61f, green = 0.52f, blue = 1f), 1.3f, 0.6f, 4.0f, 3.2f, 0.50f, 0.74f)
    }
}
