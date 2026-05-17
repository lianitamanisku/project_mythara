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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

/**
 * The Mythara rose, watch-side. Mirrors the same 10-petal +
 * cyan-hex-nucleus geometry the live wallpaper renders, scaled to
 * any container size. Animates with two parameters:
 *
 *  • Slow rotation — 90 s per revolution by default (matches the
 *    wallpaper's cadence so a glance from wrist → phone reads as
 *    one design).
 *  • Hex nucleus opacity pulse — breath-paced at 0.2 Hz when idle,
 *    speeds + brightens to 0.8 Hz when [listening] is true so the
 *    PTT-active state reads at a glance without colour change.
 *
 * Battery-aware: the Compose `rememberInfiniteTransition` driver
 * pauses automatically when the composable leaves recomposition (e.g.
 * AOD entry, overlay replaces it). Caller is responsible for omitting
 * the rose from ambient mode if it wants zero pixels — see
 * MainActivity's ambient branch.
 */
@Composable
fun MytharaRose(
    modifier: Modifier = Modifier,
    listening: Boolean = false,
    showRing: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "rose")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(90_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rose-rotation",
    )
    val pulsePeriodMs = if (listening) 1_250 else 5_000  // 0.8 Hz / 0.2 Hz
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulsePeriodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rose-pulse",
    )
    Canvas(modifier = modifier) {
        drawRose(rotation, pulse, listening, showRing)
    }
}

private fun DrawScope.drawRose(
    rotationDeg: Float,
    pulse01: Float,
    listening: Boolean,
    showRing: Boolean,
) {
    val side = min(size.width, size.height)
    val cx = size.width / 2f
    val cy = size.height / 2f
    // The rose's source geometry puts the big-petal tip at y=-30 source
    // units, so the rose's visual radius is 30 source units. Scale to
    // fit comfortably inside the canvas with breathing room for the
    // optional listening ring (which sits at side*0.46 from centre).
    // Previously this was `6.5 * (side/240)` — sized for the wallpaper's
    // tall canvas, which made the rose overflow ~95% past the watch's
    // 120dp Canvas bounds and visually drift around / out of view.
    val rosePadFrac = if (showRing) 0.62f else 0.80f
    val targetRadius = (side / 2f) * rosePadFrac
    val scale = targetRadius / 30f

    // Optional growing ring around the rose while listening — visual
    // affordance for the "I'm capturing right now" state. Same charple
    // colour as the petals so it reads as the rose extending its halo.
    if (showRing && listening) {
        val ringR = side * (0.46f + 0.04f * pulse01)
        drawCircle(
            color = CHARPLE.copy(alpha = 0.45f + 0.30f * pulse01),
            radius = ringR,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
            style = Stroke(width = 3f * (side / 240f)),
        )
    }

    translate(cx, cy) {
        rotate(rotationDeg) {
            // Big purple petals at 0/72/144/216/288°
            for (deg in intArrayOf(0, 72, 144, 216, 288)) {
                drawPetal(deg.toFloat(), scale, BIG_PETAL, PURPLE)
            }
            // Small lavender petals at 36/108/180/252/324°
            for (deg in intArrayOf(36, 108, 180, 252, 324)) {
                drawPetal(deg.toFloat(), scale, SMALL_PETAL, LAVENDER)
            }
            // Cyan hex nucleus, breathing in opacity. Min ~140/255 so
            // it's always visible; ramps to opaque on the inhale phase.
            val hexAlpha = 0.55f + 0.45f * pulse01
            val hexColor = CYAN.copy(alpha = hexAlpha)
            val path = Path()
            for ((i, p) in HEX_POINTS.withIndex()) {
                val x = p.first * scale
                val y = p.second * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, hexColor)
        }
    }
}

private fun DrawScope.drawPetal(deg: Float, scale: Float, src: FloatArray, color: Color) {
    val r = deg * PI.toFloat() / 180f
    val c = cos(r)
    val s = sin(r)
    val path = Path()
    var i = 0
    while (i < 8) {
        val sx = src[i]
        val sy = src[i + 1]
        val x = (sx * c - sy * s) * scale
        val y = (sx * s + sy * c) * scale
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        i += 2
    }
    path.close()
    drawPath(path, color)
}

// Geometry — sized for a 108×108 source viewport, identical to the
// phone-side WallpaperRenderer and the Python render_wallpaper.py so
// every Mythara surface (wallpaper, splash icon, in-app amulet, watch
// rose) renders the SAME shape, just at different scales.
private val BIG_PETAL = floatArrayOf(0f, 0f, -3f, -16f, 0f, -30f, 3f, -16f)
private val SMALL_PETAL = floatArrayOf(0f, 0f, -2f, -10f, 0f, -18f, 2f, -10f)
private val HEX_POINTS = listOf(
    0f to -5f,
    4.33f to -2.5f,
    4.33f to 2.5f,
    0f to 5f,
    -4.33f to 2.5f,
    -4.33f to -2.5f,
)

private val PURPLE = Color(0xFF6B50FF)
private val LAVENDER = Color(0xFF9B86FF)
private val CYAN = Color(0xFF68FFD6)
private val CHARPLE = Color(0xFF8A6FFF)
