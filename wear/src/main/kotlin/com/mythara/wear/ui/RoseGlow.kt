package com.mythara.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

/**
 * The Mythara rose surrounded by a soft purple particle glow.
 *
 * Layout: rose in the centre at [roseSize], particles drifting around
 * it on Lissajous orbits inside the enclosing box. Particles are tiny
 * lavender/charple dots with a wider, dimmer halo behind each — together
 * they read as a quiet purple aura rather than discrete bullets.
 *
 * All animation is driven by a single [rememberInfiniteTransition] —
 * Compose pauses the driver automatically when this composable leaves
 * recomposition (overlay covers it, screen sleeps), so the per-frame
 * cost drops to zero when nobody's looking at it.
 */
@Composable
fun RoseWithGlow(
    modifier: Modifier = Modifier,
    roseSize: Dp = 120.dp,
    listening: Boolean = false,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        ParticleGlow(
            modifier = Modifier.fillMaxSize(),
            listening = listening,
        )
        MytharaRose(
            modifier = Modifier.size(roseSize),
            listening = listening,
            showRing = listening,
        )
    }
}

/** Drifting lavender dots — see file-level docstring. Drawn UNDER the
 *  rose so the particles read as "behind / around" the brand mark. */
@Composable
private fun ParticleGlow(modifier: Modifier, listening: Boolean) {
    val particles = remember { generateParticles() }
    val transition = rememberInfiniteTransition(label = "rose-glow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // 40 s for one full loop — slow enough that motion reads
            // as ambient atmosphere, not a screensaver.
            animation = tween(40_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rose-glow-phase",
    )
    // Brightness pulse — same period as the rose's hex breath (5 s
    // idle, 1.25 s listening) so the glow inhales/exhales in unison
    // with the brand mark.
    val pulsePeriodMs = if (listening) 1_250 else 5_000
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulsePeriodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rose-glow-pulse",
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val side = min(size.width, size.height)
        val tSec = phase * 40f
        // Scale particle distances + sizes off `side` so the glow looks
        // proportional on any watch canvas (Pixel Watch ~390 px, Galaxy
        // Watch Ultra ~480 px, etc).
        val sizeScale = side / 240f
        // Pulse multiplier for alpha/radius — keeps everything ≥ baseline
        // so the glow never fully vanishes between beats.
        val pulseMul = 0.7f + 0.3f * pulse

        for (p in particles) {
            val dx = p.driftAmp * sizeScale *
                cos(tSec * (2f * PI.toFloat() / p.periodX) + p.phaseX)
            val dy = p.driftAmp * sizeScale *
                sin(tSec * (2f * PI.toFloat() / p.periodY) + p.phaseY)
            // Place anchor radially around centre at the particle's base
            // radius; then add the lissajous drift.
            val ax = cx + p.baseRadius * sizeScale * cos(p.baseAngleRad)
            val ay = cy + p.baseRadius * sizeScale * sin(p.baseAngleRad)
            val x = ax + dx
            val y = ay + dy

            val r = p.coreRadius * sizeScale * pulseMul
            val haloR = r * 3.5f
            // Halo first (drawn under the core).
            drawCircle(
                color = LAVENDER.copy(alpha = HALO_ALPHA * pulseMul),
                radius = haloR,
                center = Offset(x, y),
            )
            drawCircle(
                color = CHARPLE.copy(alpha = CORE_ALPHA * pulseMul),
                radius = r,
                center = Offset(x, y),
            )
        }
    }
}

private data class Particle(
    val baseRadius: Float,   // distance from centre in 240-px units
    val baseAngleRad: Float, // angular anchor around centre
    val driftAmp: Float,     // lissajous drift amplitude
    val periodX: Float,      // seconds per x loop
    val periodY: Float,      // seconds per y loop
    val phaseX: Float,
    val phaseY: Float,
    val coreRadius: Float,   // core dot radius in 240-px units
)

private fun generateParticles(): List<Particle> {
    // Deterministic seed so the layout is identical across recompositions
    // and the user gets to mentally locate "the bright one near 2 o'clock"
    // every time they look. Seed bytes spell "Rose" in ASCII.
    val rng = Random(0x526F7365L)
    return List(PARTICLE_COUNT) {
        Particle(
            // Particles ring the rose at radii 70..115 (rose is 60 radius
            // at 120 dp, so they sit just outside the rose silhouette).
            baseRadius = 70f + rng.nextFloat() * 45f,
            baseAngleRad = rng.nextFloat() * 2f * PI.toFloat(),
            driftAmp = 8f + rng.nextFloat() * 18f,
            periodX = 6f + rng.nextFloat() * 12f,
            periodY = 6f + rng.nextFloat() * 12f,
            phaseX = rng.nextFloat() * 2f * PI.toFloat(),
            phaseY = rng.nextFloat() * 2f * PI.toFloat(),
            coreRadius = 1.4f + rng.nextFloat() * 1.8f,
        )
    }
}

private const val PARTICLE_COUNT = 18
private const val HALO_ALPHA = 0.18f
private const val CORE_ALPHA = 0.65f

private val LAVENDER = Color(0xFF9B86FF)
private val CHARPLE = Color(0xFF6B50FF)
