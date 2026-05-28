package com.mythara.ui.face

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.camera.FaceTracker
import com.mythara.mic.Tts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Exposes what the face avatar needs: the live TTS speaking state, and
 * the front-camera [FaceTracker.Pose] so the avatar can track the
 * user's actual head. Its own tiny ViewModel so the Face screen is a
 * standalone destination, independent of the chat surface.
 */
@HiltViewModel
class FaceViewModel @Inject constructor(
    tts: Tts,
    private val tracker: FaceTracker,
) : ViewModel() {
    val speaking: StateFlow<Boolean> =
        tts.speaking.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Smoothed front-camera head pose. `present = false` when no face. */
    val pose: StateFlow<FaceTracker.Pose> = tracker.pose

    /** Start / stop the front-camera stream — driven by the screen's
     *  composition lifetime so the camera only runs while it's open. */
    fun bindCamera() = tracker.bind()
    fun unbindCamera() = tracker.unbind()

    override fun onCleared() {
        tracker.unbind()
    }
}

// ----------------------------------------------------------- model

private enum class FGroup { HEAD, BROW, EYE_L, EYE_R, EYE_CORE, NOSE, MOUTH, MOUTH_LOWER, EAR, NECK }

/** A single point in the face cloud, in normalised face space
 *  (x right, y down, origin between the eyes). [z] is a fake depth in
 *  [0,1] — higher = closer to camera — used for parallax + shading. */
private class FPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val group: FGroup,
    val bright: Float,
)

/** Prebuilt cloud: points + the nearest-neighbour mesh edges between
 *  them (index pairs). Computed once and remembered. */
private class FModel(val points: List<FPoint>, val edges: List<IntArray>)

/** Eye centre Y in normalised face space — blink scales eye-ring
 *  points toward this line. */
private const val EYE_CY = -0.20f
private const val EYE_DX = 0.30f

private val CLOUD = Color(0xFF4FE2FF)      // electric cyan — the dots + mesh
private val EYE_GLOW = Color(0xFFE4FBFF)   // near-white cyan — the eye cores

/**
 * The Mythara face — a full-screen, alternate interface to the agent.
 *
 * A glowing point-cloud humanoid head on a pure-black field. With the
 * front-camera permission granted it **tracks the user's real face**:
 * ML Kit head euler angles drive the cloud's yaw / pitch / roll, and
 * the eye-open probabilities drive its blink — so the avatar mirrors
 * you in real time. Without the camera (or with no face in frame) it
 * falls back to a gentle idle sway + self-driven blink. The lower lip
 * still drops open while Mythara is speaking. Pure Compose Canvas.
 */
@Composable
fun FaceScreen(onBack: () -> Unit, vm: FaceViewModel = hiltViewModel()) {
    val speaking by vm.speaking.collectAsState()
    val pose by vm.pose.collectAsState()
    val ctx = LocalContext.current

    // Front-camera permission — needed to track the user's face.
    var hasCam by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val camLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCam = granted }
    LaunchedEffect(Unit) { if (!hasCam) camLauncher.launch(Manifest.permission.CAMERA) }

    // The camera runs ONLY while this screen is composed AND the
    // permission is held — bound on enter, unbound on leave.
    DisposableEffect(hasCam) {
        if (hasCam) vm.bindCamera()
        onDispose { vm.unbindCamera() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        FaceMesh(speaking = speaking, pose = pose, modifier = Modifier.fillMaxSize())

        // Phase C — back affordance moved to the MytharaScaffold
        // header sliver at the top; the in-screen "‹ chat" chip is
        // removed so the cinema view stays full-bleed.

        val status = when {
            speaking -> "● speaking"
            pose.present -> "● tracking you"
            hasCam -> "○ looking for you…"
            else -> "○ tap to enable face tracking"
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (speaking || pose.present) EYE_GLOW else CLOUD.copy(alpha = 0.55f),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp)
                .clickable(enabled = !hasCam) {
                    camLauncher.launch(Manifest.permission.CAMERA)
                },
        )
    }
}

/**
 * Particle face (v7). A field of glowing particles drifts freely
 * across the whole canvas. The moment the front camera detects a face
 * ([pose].present) the particles SNAP toward the centre and assemble
 * into two eyes + a mouth; lose the face and they scatter back out.
 * The eyes track the detected face (pupils aim with the head's
 * yaw/pitch) and blink with the real eye-open signal. The mouth is a
 * horizontal band of particles that, while Mythara is [speaking],
 * ripples like an audio waveform with a colour gradient sweeping along
 * it.
 *
 * Transparent canvas — the caller owns the background.
 */
@Composable
fun FaceMesh(
    speaking: Boolean,
    pose: FaceTracker.Pose,
    modifier: Modifier = Modifier,
) {
    val particles = remember { buildParticles() }

    // Continuous frame time (seconds) — drives drift + the soundwave.
    var timeSec by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableFloatStateOf(0f)
    }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                timeSec = (now - start) / 1_000_000_000f
            }
        }
    }

    // Assembly 0 (scattered) → 1 (formed). Springs in on face-detect
    // so the particles "suddenly" rush to centre with a little life.
    val assembly by animateFloatAsState(
        targetValue = if (pose.present) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.62f,
            stiffness = 190f,
        ),
        label = "assembly",
    )

    // Idle self-blink when no camera face; real eye-open when tracking.
    val idleBlink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2600L, 6000L))
            idleBlink.animateTo(0.08f, tween(80))
            idleBlink.animateTo(1f, tween(160))
        }
    }
    val tracking = pose.present
    val eyeOpenL = if (tracking) pose.leftEyeOpen else idleBlink.value
    val eyeOpenR = if (tracking) pose.rightEyeOpen else idleBlink.value
    // v7.3 — flowAmount drives the sunburst. 0 (idle) = quiet ring;
    // 1 (speaking) = particles continuously radiate outward.
    val flowAmount by animateFloatAsState(
        targetValue = if (speaking) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "flowAmount",
    )

    // Theme-adaptive gradient — pull stops from the active skin's
    // palette so the ring + sunburst always look "right" against the
    // current backdrop (rose on Living Rose, cyan on HUD, etc).
    val palette = com.mythara.ui.theme.LocalMythPalette.current
    val ringStops = remember(palette) {
        listOf(
            palette.Charple, palette.Malibu, palette.Bok,
            palette.Mustard, palette.Sriracha, palette.Charple,
        )
    }

    // Gaze — drives the inner sphere's positional offset so it
    // visibly tracks the user's head movement.
    val gazeX = pose.yaw.coerceIn(-1f, 1f)
    val gazeY = pose.pitch.coerceIn(-1f, 1f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val ease = assembly.coerceIn(0f, 1f)
        for (p in particles) {
            // Scatter position — slow Lissajous drift across the screen.
            val sxN = p.homeX + p.driftAmpX * sin(timeSec * p.driftFreqX + p.driftPhaseX)
            val syN = p.homeY + p.driftAmpY * cos(timeSec * p.driftFreqY + p.driftPhaseY)

            var txN = sxN
            var tyN = syN
            // Default to a theme-driven soft ambient hue (palette
            // Charple at the muted alpha used below) instead of the
            // old hardcoded lavender — so the scattered field carries
            // the active skin's brand colour rather than a fixed one.
            var color = palette.Charple
            var coreA: Float
            // For CIRCLE particles, alpha is further modulated by where
            // they are in their fly-in cycle (fades in as they approach
            // the ring); HALO ignores this.
            var cycleAlpha = 1f
            when (p.role) {
                PRole.HALO -> {
                    color = palette.Charple
                    coreA = 0.30f * (1f - 0.4f * ease) * p.glow
                }
                PRole.SPHERE -> {
                    val aspect = w / h
                    // Rotate the sphere around its vertical axis so
                    // it has visible volume even at rest.
                    val ang = timeSec * SPHERE_ROT_HZ
                    val cA = cos(ang); val sA = sin(ang)
                    val rx = p.sxv * cA + p.szv * sA
                    val rz = -p.sxv * sA + p.szv * cA
                    val ry = p.syv
                    // Pseudo-3D depth: front (rz>0) larger + brighter,
                    // back (rz<0) smaller + dimmer.
                    val depth01 = ((rz / SPHERE_RADIUS) + 1f) * 0.5f
                    txN = CIRCLE_CX + rx + gazeX * SPHERE_GAZE_MULT
                    tyN = CIRCLE_CY + ry * aspect + gazeY * SPHERE_GAZE_MULT * aspect
                    color = palette.Charple
                    // Sphere stays solid — alpha modulated only by
                    // depth + glow, not by the speaking sunburst.
                    coreA = (0.30f + 0.65f * depth01) * p.glow
                }
                PRole.CIRCLE -> {
                    val aspect = w / h
                    // Static position on the ring (slow global rotation).
                    val idleAngle = p.targetAngle + timeSec * CIRCLE_IDLE_ROT
                    val ringX = cos(idleAngle) * CIRCLE_RADIUS
                    val ringY = sin(idleAngle) * CIRCLE_RADIUS * aspect
                    // Sunburst cycle (speaking): cycle 0 = at ring,
                    // cycle 1 = at outer reach + faded. Quadratic
                    // easing makes particles linger near the ring then
                    // accelerate outward (like sparks). Per-particle
                    // phase staggers so emission is continuous.
                    val cycle = (((timeSec * p.cycleSpeed) + p.cyclePhase) % 1f + 1f) % 1f
                    val outProg = cycle * cycle
                    val burstR = CIRCLE_RADIUS + outProg * SUNBURST_DIST
                    val burstAngle = idleAngle +
                        sin(cycle * PI.toFloat() * 2f + p.cyclePhase * 6.2832f) * SUNBURST_WOBBLE
                    val burstX = cos(burstAngle) * burstR
                    val burstY = sin(burstAngle) * burstR * aspect
                    // Blend static ring with sunburst by flowAmount.
                    val assembledDX = ringX + (burstX - ringX) * flowAmount
                    val assembledDY = ringY + (burstY - ringY) * flowAmount
                    txN = CIRCLE_CX + assembledDX
                    tyN = CIRCLE_CY + assembledDY
                    color = particleColor(ringStops, p.hueU, timeSec, speaking)
                    coreA = 0.62f * p.glow
                    // Alpha: 1 at the ring, fades to 0 as the spark
                    // races outward (only while speaking).
                    val burstAlpha = 1f - cycle
                    cycleAlpha = 1f + (burstAlpha - 1f) * flowAmount
                }
            }

            // Both roles blend scatter → assembled by `ease`. For HALO,
            // txN == sxN (no assembled target) so this is a no-op. For
            // CIRCLE, ease=0 (no face) → particles scatter and drift;
            // ease=1 (face detected) → particles form the ring (or the
            // sunburst, when also speaking).
            val nx = sxN + (txN - sxN) * ease
            val ny = syN + (tyN - syN) * ease
            val cx = nx * w
            val cy = ny * h

            val baseR = p.size * minOf(w, h)
            val a = cycleAlpha
            drawCircle(color.copy(alpha = ((0.08f + 0.10f * coreA) * a).coerceIn(0f, 0.5f)), baseR * 2.6f, Offset(cx, cy))
            drawCircle(color.copy(alpha = ((0.40f * coreA + 0.08f) * a).coerceIn(0f, 0.9f)), baseR * 1.4f, Offset(cx, cy))
            drawCircle(color.copy(alpha = ((0.85f * coreA + 0.10f) * a).coerceIn(0f, 1f)), baseR, Offset(cx, cy))
        }
    }
}

// --------------------------------------------------- particle model

/** Role decides how a particle behaves. v7.4: ambient [HALO] drift +
 *  [CIRCLE] ring (sunburst while speaking) + [SPHERE] inner cluster
 *  that rotates and tracks the detected face. */
private enum class PRole { HALO, CIRCLE, SPHERE }

private class FParticle(
    val homeX: Float, val homeY: Float,        // scatter anchor (0..1 screen)
    val driftAmpX: Float, val driftAmpY: Float,
    val driftFreqX: Float, val driftFreqY: Float,
    val driftPhaseX: Float, val driftPhaseY: Float,
    val role: PRole,
    val size: Float,                            // radius as fraction of min(w,h)
    val glow: Float = 1f,                       // per-particle brightness scalar
    // CIRCLE-specific:
    val targetAngle: Float = 0f,                // position on inner ring [0, 2π]
    val outerAngle: Float = 0f,                 // angle of the outer spawn point
    val cyclePhase: Float = 0f,                 // [0,1] per-particle stagger
    val cycleSpeed: Float = 0f,                 // fly-in cycles per second
    val hueU: Float = 0f,                       // [0,1] color sweep position
    // SPHERE-specific: 3D coordinates on/in the cluster, normalised
    // so they stay inside the sphere's radius. Rotated around Y at
    // draw time → projected to 2D.
    val sxv: Float = 0f, val syv: Float = 0f, val szv: Float = 0f,
)

// v7.4 — a LARGE particle CIRCLE in the centre that ONLY forms when
// the front camera detects a face. Inside the ring sits a particle
// SPHERE that rotates slowly and tracks the user's head (its centre
// offsets by pose.yaw/pitch so it visibly moves with the face).
// While Mythara is speaking, the ring becomes a SUNBURST: particles
// continuously radiate outward in rays, then respawn at the ring.
private const val CIRCLE_CX = 0.50f
private const val CIRCLE_CY = 0.42f
/** Ring radius (normalised X — Y multiplied by w/h so the ring stays
 *  circular regardless of phone aspect). v7.4 bumped from 0.17 → 0.26
 *  so the circle is a substantial centrepiece, not a tight halo. */
private const val CIRCLE_RADIUS = 0.26f
/** Slow rotation of the static ring (rad/s) — calm idle drift. */
private const val CIRCLE_IDLE_ROT = 0.07f
/** How far past the ring a sunburst particle travels before
 *  respawning (normalised X units). Scaled with the bigger ring. */
private const val SUNBURST_DIST = 0.42f
/** Slight per-cycle angular wobble so rays aren't perfectly straight
 *  spokes — keeps the burst organic. */
private const val SUNBURST_WOBBLE = 0.10f

/** Inner particle sphere — sits inside the ring and tracks the
 *  detected face. Radius in normalised X (aspect-corrected at draw). */
private const val SPHERE_RADIUS = 0.10f
/** Sphere auto-rotation rate around the vertical axis (rad/s) — slow
 *  spin gives the cluster volume even when the face is still. */
private const val SPHERE_ROT_HZ = 0.30f
/** How strongly the sphere's centre offsets with head pose (gaze).
 *  Higher = more dramatic tracking. */
private const val SPHERE_GAZE_MULT = 0.085f

private val HALO_COLOR = Color(0xFF9B86FF)        // lavender ambient drift

// Vestigial constant — referenced only by the legacy point-cloud
// `drawFaceCloud`/`buildFaceModel` helpers which are now dead code
// (kept in the file for a release until polish removes them). Value
// is irrelevant; just needs to compile.
@Suppress("unused")
private const val FACE_EYE_CY = 0.40f

/** Mouth waveform y-offset (normalised height) at position [u] in
 *  [-1,1]. Always animated — even idle the ribbon undulates gently;
 *  while speaking the amplitude + speed jump and the multi-harmonic
 *  shape gives a true audio-visualiser look. */
private fun mouthWave(u: Float, t: Float, speaking: Boolean): Float {
    val pi = PI.toFloat()
    val taper = 1f - u * u * 0.45f
    return if (speaking) {
        val w = sin(u * pi * 3f - t * 9f) * 0.55f +
            sin(u * pi * 7f + t * 5.4f) * 0.34f +
            sin(u * pi * 13f - t * 13f) * 0.20f +
            sin(u * pi * 19f + t * 17f) * 0.12f
        w * 0.060f * taper
    } else {
        val w = sin(u * pi * 2f + t * 1.4f) * 0.5f +
            sin(u * pi * 4f - t * 0.9f) * 0.25f
        w * 0.024f * taper
    }
}

/** Mouth colour at [u] — a chromatic gradient (blue → purple →
 *  magenta → pink → orange → yellow) that ALWAYS sweeps along the
 *  ribbon, faster while speaking. The full-spectrum sweep is the
 *  signature look from the reference image. */
private fun mouthColor(u: Float, t: Float, speaking: Boolean): Color {
    val stops = MOUTH_STOPS
    val sweepSpeed = if (speaking) 0.22f else 0.07f
    val phase = (u + 1f) * 0.5f + t * sweepSpeed
    val f = ((phase % 1f) + 1f) % 1f
    val scaled = f * (stops.size - 1)
    val i = scaled.toInt().coerceIn(0, stops.size - 2)
    val frac = scaled - i
    return androidx.compose.ui.graphics.lerp(stops[i], stops[i + 1], frac)
}

/** Full-spectrum chromatic stops — legacy hardcoded gradient (dead;
 *  v7.3 reads stops from the active skin's palette at draw time). */
@Suppress("unused")
private val MOUTH_STOPS = listOf(
    Color(0xFF4A6BFF), // electric blue
    Color(0xFF8240FF), // purple
    Color(0xFFE040A0), // magenta
    Color(0xFFFF3060), // hot pink / red
    Color(0xFFFF8030), // orange
    Color(0xFFFFD040), // yellow
    Color(0xFF4A6BFF), // loop back to blue for continuous sweep
)

/** Sample a colour along the theme-adaptive gradient [stops] at
 *  position [u] (0..1), with a time-driven phase shift so the
 *  gradient sweeps around the ring. Sweep is slow when idle, faster
 *  while [speaking]. */
private fun particleColor(stops: List<Color>, u: Float, t: Float, speaking: Boolean): Color {
    if (stops.size < 2) return stops.firstOrNull() ?: Color.White
    val sweepSpeed = if (speaking) 0.22f else 0.07f
    val phase = u + t * sweepSpeed
    val f = ((phase % 1f) + 1f) % 1f
    val scaled = f * (stops.size - 1)
    val i = scaled.toInt().coerceIn(0, stops.size - 2)
    val frac = scaled - i
    return androidx.compose.ui.graphics.lerp(stops[i], stops[i + 1], frac)
}

/** Build the particle field once. v7 abstract redesign:
 *  - ~600 very fine particles for a flowing ribbon look (reference)
 *  - Eyes = SOFT Gaussian clusters (no rigid rings) → glowing orbs
 *  - Mouth = a THICK ribbon (vertical jitter) carrying the full
 *    chromatic gradient + multi-harmonic wave
 *  - Halo = denser ambient drift so the un-assembled field reads as
 *    a living particle cloud, not sparse dots
 *  All particles share the same tiny radius range so individual dots
 *  vanish into the band and the whole reads as fluid. */
private fun buildParticles(): List<FParticle> {
    val rnd = Random(11)
    val out = ArrayList<FParticle>(640)

    fun drift(): FloatArray = floatArrayOf(
        rnd.nextFloat(),                       // homeX
        rnd.nextFloat(),                       // homeY
        0.04f + rnd.nextFloat() * 0.11f,       // driftAmpX
        0.05f + rnd.nextFloat() * 0.13f,       // driftAmpY
        0.15f + rnd.nextFloat() * 0.55f,       // driftFreqX
        0.15f + rnd.nextFloat() * 0.55f,       // driftFreqY
        rnd.nextFloat() * 6.2832f,             // driftPhaseX
        rnd.nextFloat() * 6.2832f,             // driftPhaseY
    )

    /** Box–Muller standard-normal sample (mean 0, sigma 1). */
    fun gauss(): Float {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9).toFloat()
        val u2 = rnd.nextFloat()
        return sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)
    }

    // ─── Ambient halo (drifts everywhere, never converges) ──────────
    repeat(140) {
        val d = drift()
        out += FParticle(
            homeX = d[0], homeY = d[1],
            driftAmpX = d[2], driftAmpY = d[3],
            driftFreqX = d[4], driftFreqY = d[5],
            driftPhaseX = d[6], driftPhaseY = d[7],
            role = PRole.HALO,
            size = 0.0018f + rnd.nextFloat() * 0.0022f,
            glow = 0.5f + rnd.nextFloat() * 0.6f,
        )
    }

    // ─── Inner SPHERE — rotating cluster that tracks the face ──────
    // Surface points sampled by normalising a 3D Gaussian (Marsaglia
    // method) → uniform points on a sphere. A few interior particles
    // add density to the centre so the sphere reads as filled, not
    // just a thin shell.
    repeat(110) { idx ->
        val gx = gauss()
        val gy = gauss()
        val gz = gauss()
        val len = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6f)
        // First 80 = surface; remaining 30 = inner cluster (random
        // radial offset 0.2..1.0 of SPHERE_RADIUS).
        val rNorm = if (idx < 80) 1f else (0.2f + rnd.nextFloat() * 0.6f)
        val sx = gx / len * SPHERE_RADIUS * rNorm
        val sy = gy / len * SPHERE_RADIUS * rNorm
        val sz = gz / len * SPHERE_RADIUS * rNorm
        val d = drift()
        out += FParticle(
            homeX = d[0], homeY = d[1],
            driftAmpX = d[2], driftAmpY = d[3],
            driftFreqX = d[4], driftFreqY = d[5],
            driftPhaseX = d[6], driftPhaseY = d[7],
            role = PRole.SPHERE,
            size = 0.0020f + rnd.nextFloat() * 0.0022f,
            glow = 0.75f + rnd.nextFloat() * 0.40f,
            sxv = sx, syv = sy, szv = sz,
        )
    }

    // ─── Centre ring — CIRCLE particles + speaking fly-in cycle ─────
    // Each particle has a fixed target angle on the ring (evenly
    // distributed for clean coverage) and a RANDOM outer spawn angle
    // (uncorrelated, so the streaming comes from every direction).
    // Per-particle cyclePhase + cycleSpeed staggers the fly-ins so the
    // stream is continuous, not pulsed.
    val ringN = 320
    repeat(ringN) { k ->
        val tA = (k / ringN.toFloat()) * 6.2832f + rnd.nextFloat() * 0.05f
        val oA = rnd.nextFloat() * 6.2832f                  // random outer direction
        val phase = rnd.nextFloat()                         // staggered cycle start
        val speed = 0.40f + rnd.nextFloat() * 0.95f         // cycles/sec
        val hue = ((tA / 6.2832f) + rnd.nextFloat() * 0.04f) % 1f
        val d = drift()
        out += FParticle(
            homeX = d[0], homeY = d[1],
            driftAmpX = d[2], driftAmpY = d[3],
            driftFreqX = d[4], driftFreqY = d[5],
            driftPhaseX = d[6], driftPhaseY = d[7],
            role = PRole.CIRCLE,
            size = 0.0018f + rnd.nextFloat() * 0.0022f,
            glow = 0.7f + rnd.nextFloat() * 0.5f,
            targetAngle = tA,
            outerAngle = oA,
            cyclePhase = phase,
            cycleSpeed = speed,
            hueU = hue,
        )
    }
    return out
}

// ----------------------------------------------------------- drawing

private fun DrawScope.drawFaceCloud(
    model: FModel,
    yaw: Float,
    pitch: Float,
    roll: Float,
    bob: Float,
    shimmer: Float,
    eyeOpenL: Float,
    eyeOpenR: Float,
    mouthOpen: Float,
    speaking: Boolean,
) {
    val scale = minOf(size.width, size.height) * 0.40f
    val cx = size.width / 2f
    val cy = size.height * 0.46f + bob * 6f

    // Head-pose transform, applied once per point: yaw + pitch parallax
    // off each node's depth, then a 2D roll about the face origin.
    val yawS = sin(yaw * 0.5f)
    val pitchS = sin(pitch * 0.42f)
    val rollA = roll * 0.5f
    val rollC = cos(rollA)
    val rollSn = sin(rollA)

    val sx = FloatArray(model.points.size)
    val sy = FloatArray(model.points.size)
    val sb = FloatArray(model.points.size) // per-point brightness this frame
    model.points.forEachIndexed { i, p ->
        // per-group animation in face space first
        var baseY = p.y
        when (p.group) {
            FGroup.EYE_L, FGroup.EYE_R, FGroup.EYE_CORE -> {
                val open = if (p.x < 0f) eyeOpenL else eyeOpenR
                baseY = FACE_EYE_CY + (baseY - EYE_CY) * open
            }
            FGroup.MOUTH_LOWER -> baseY += mouthOpen * 0.12f
            else -> {}
        }
        // depth-driven parallax (yaw left/right, pitch up/down)
        val zc = p.z - 0.45f
        val nx = p.x + zc * yawS
        val ny = baseY - zc * pitchS
        // roll — rotate the whole face about its origin
        val rx = nx * rollC - ny * rollSn
        val ry = nx * rollSn + ny * rollC
        sx[i] = cx + rx * scale
        sy[i] = cy + ry * scale
        // depth shading + a gentle travelling shimmer
        val depth = 0.5f + 0.5f * p.z
        val shim = 0.85f + 0.15f * sin(shimmer + p.x * 3f + p.y * 2f)
        sb[i] = (p.bright * depth * shim).coerceIn(0f, 1.3f)
    }

    // ---- mesh edges — cyan filaments between neighbours ----
    for (e in model.edges) {
        val a = e[0]
        val b = e[1]
        val avg = (sb[a] + sb[b]) * 0.5f
        drawLine(
            color = CLOUD.copy(alpha = (0.10f + 0.22f * avg).coerceAtMost(0.5f)),
            start = Offset(sx[a], sy[a]),
            end = Offset(sx[b], sy[b]),
            strokeWidth = 1.1f,
        )
    }

    // ---- nodes — outer bloom + halo + a bright core per point ----
    model.points.forEachIndexed { i, p ->
        if (p.group == FGroup.EYE_CORE) return@forEachIndexed // drawn separately
        val c = Offset(sx[i], sy[i])
        val b = sb[i]
        val haloR = (3.2f + 5.0f * p.z) * (0.7f + 0.5f * b)
        // wide soft bloom
        drawCircle(CLOUD.copy(alpha = (0.05f + 0.07f * b).coerceAtMost(0.4f)), haloR * 2.1f, c)
        // tight halo
        drawCircle(CLOUD.copy(alpha = (0.13f + 0.20f * b).coerceAtMost(0.6f)), haloR, c)
        // bright core
        val coreR = 1.9f + 2.8f * p.z * b
        val talking = speaking && (p.group == FGroup.MOUTH || p.group == FGroup.MOUTH_LOWER)
        val core = if (talking) EYE_GLOW else CLOUD
        drawCircle(core.copy(alpha = (0.72f + 0.28f * b).coerceAtMost(1f)), coreR, c)
    }

    // ---- eyes — bright glowing cores with heavy bloom ----
    val pulse = if (speaking) 1f + 0.12f * sin(shimmer * 2f) else 1f
    model.points.forEachIndexed { i, p ->
        if (p.group != FGroup.EYE_CORE) return@forEachIndexed
        val c = Offset(sx[i], sy[i])
        val open = (if (p.x < 0f) eyeOpenL else eyeOpenR).coerceIn(0.06f, 1f)
        val bloom = scale * 0.092f * pulse * (0.35f + 0.65f * open)
        drawCircle(CLOUD.copy(alpha = 0.20f * open), bloom, c)
        drawCircle(EYE_GLOW.copy(alpha = 0.38f * open), bloom * 0.45f, c)
        drawCircle(EYE_GLOW.copy(alpha = 0.98f * open), bloom * 0.16f + 1.4f, c)
    }
}

// ----------------------------------------------------------- model build

/**
 * Build the face point cloud + its nearest-neighbour mesh, once.
 *
 * Geometry is hand-authored in normalised face space: the head
 * silhouette + brows / eyes / nose / lips / ears / neck are traced as
 * contours, then the head interior is filled with scattered nodes so
 * the whole thing reads as a volumetric mesh rather than an outline.
 */
private fun buildFaceModel(): FModel {
    val pts = ArrayList<FPoint>(420)
    val rnd = Random(7)

    // depth of a point — a hemisphere bulge, highest at the face centre
    fun depth(x: Float, y: Float): Float {
        val d = (x / 0.82f) * (x / 0.82f) + (y / 1.18f) * (y / 1.18f)
        return sqrt((1f - d).coerceAtLeast(0f))
    }
    fun add(x: Float, y: Float, group: FGroup, bright: Float) {
        pts.add(FPoint(x, y, depth(x, y), group, bright))
    }
    // points along an elliptical arc (angles in radians, y-down)
    fun arc(cxv: Float, cyv: Float, rx: Float, ry: Float, a0: Float, a1: Float, n: Int, g: FGroup, br: Float) {
        for (k in 0 until n) {
            val t = if (n == 1) 0.5f else k / (n - 1f)
            val a = a0 + (a1 - a0) * t
            add(cxv + rx * cos(a), cyv + ry * sin(a), g, br)
        }
    }
    // points along a straight segment
    fun seg(x0: Float, y0: Float, x1: Float, y1: Float, n: Int, g: FGroup, br: Float) {
        for (k in 0 until n) {
            val t = if (n == 1) 0.5f else k / (n - 1f)
            add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, g, br)
        }
    }

    // ---- head silhouette — a closed loop of control points, clockwise
    //      from the crown, sampled densely between each pair ----
    val outline = listOf(
        0.00f to -1.12f, 0.34f to -1.04f, 0.58f to -0.82f, 0.71f to -0.46f,
        0.76f to -0.04f, 0.69f to 0.33f, 0.52f to 0.64f, 0.30f to 0.90f,
        0.00f to 1.06f, -0.30f to 0.90f, -0.52f to 0.64f, -0.69f to 0.33f,
        -0.76f to -0.04f, -0.71f to -0.46f, -0.58f to -0.82f, -0.34f to -1.04f,
    )
    for (i in outline.indices) {
        val (x0, y0) = outline[i]
        val (x1, y1) = outline[(i + 1) % outline.size]
        seg(x0, y0, x1, y1, 6, FGroup.HEAD, 0.92f)
    }

    // ---- volumetric fill — scattered nodes inside the head ellipse ----
    var filled = 0
    var guard = 0
    while (filled < 150 && guard < 4000) {
        guard++
        val x = (rnd.nextFloat() * 2f - 1f) * 0.74f
        val y = (rnd.nextFloat() * 2.1f - 1.05f)
        val inside = (x / 0.74f) * (x / 0.74f) + ((y + 0.02f) / 1.06f) * ((y + 0.02f) / 1.06f)
        if (inside > 0.93f) continue
        add(x, y, FGroup.HEAD, 0.42f + rnd.nextFloat() * 0.20f)
        filled++
    }

    // ---- brows ----
    arc(-EYE_DX, EYE_CY - 0.20f, 0.27f, 0.13f, (PI * 1.08).toFloat(), (PI * 1.92).toFloat(), 11, FGroup.BROW, 0.86f)
    arc(EYE_DX, EYE_CY - 0.20f, 0.27f, 0.13f, (-PI * 0.92).toFloat(), (-PI * 0.08).toFloat(), 11, FGroup.BROW, 0.86f)

    // ---- eyes — almond rings + a bright core each ----
    for ((cxv, g) in listOf(-EYE_DX to FGroup.EYE_L, EYE_DX to FGroup.EYE_R)) {
        arc(cxv, EYE_CY, 0.185f, 0.105f, 0f, (2.0 * PI).toFloat(), 18, g, 0.98f)
        // inner iris ring
        arc(cxv, EYE_CY, 0.072f, 0.062f, 0f, (2.0 * PI).toFloat(), 8, g, 1.0f)
        add(cxv, EYE_CY, FGroup.EYE_CORE, 1.25f)
    }

    // ---- nose — ridge + nostril base + wings ----
    seg(0f, FACE_EYE_CY + 0.05f, 0f, 0.20f, 7, FGroup.NOSE, 0.82f)
    arc(0f, 0.205f, 0.135f, 0.075f, (PI * 0.12).toFloat(), (PI * 0.88).toFloat(), 11, FGroup.NOSE, 0.84f)
    add(-0.135f, 0.165f, FGroup.NOSE, 0.74f)
    add(0.135f, 0.165f, FGroup.NOSE, 0.74f)
    add(-0.052f, 0.205f, FGroup.NOSE, 0.84f)
    add(0.052f, 0.205f, FGroup.NOSE, 0.84f)

    // ---- lips — upper lip arc + lower lip arc (lower one drops open) ----
    arc(0f, 0.435f, 0.215f, 0.075f, (PI * 1.05).toFloat(), (PI * 1.95).toFloat(), 13, FGroup.MOUTH, 0.90f)
    seg(-0.215f, 0.45f, 0.215f, 0.45f, 9, FGroup.MOUTH, 0.74f)
    arc(0f, 0.47f, 0.19f, 0.12f, (PI * 0.08).toFloat(), (PI * 0.92).toFloat(), 13, FGroup.MOUTH_LOWER, 0.90f)

    // ---- ears — outer arcs hugging the temples ----
    arc(-0.80f, -0.04f, 0.13f, 0.25f, (PI * 0.55).toFloat(), (PI * 1.45).toFloat(), 9, FGroup.EAR, 0.66f)
    arc(0.80f, -0.04f, 0.13f, 0.25f, (-PI * 0.45).toFloat(), (PI * 0.45).toFloat(), 9, FGroup.EAR, 0.66f)

    // ---- neck + shoulder hint ----
    seg(-0.27f, 0.92f, -0.32f, 1.46f, 8, FGroup.NECK, 0.58f)
    seg(0.27f, 0.92f, 0.32f, 1.46f, 8, FGroup.NECK, 0.58f)
    seg(-0.32f, 1.46f, -0.96f, 1.66f, 9, FGroup.NECK, 0.48f)
    seg(0.32f, 1.46f, 0.96f, 1.66f, 9, FGroup.NECK, 0.48f)
    seg(-0.55f, 1.50f, 0.55f, 1.50f, 9, FGroup.NECK, 0.34f)
    repeat(14) {
        val x = (rnd.nextFloat() * 2f - 1f) * 0.30f
        val y = 0.95f + rnd.nextFloat() * 0.5f
        add(x, y, FGroup.NECK, 0.34f + rnd.nextFloat() * 0.2f)
    }

    // ---- nearest-neighbour mesh — connect each node to its closest
    //      few neighbours within a radius, deduped ----
    val edges = ArrayList<IntArray>()
    val maxD = 0.165f
    val maxNeighbours = 4
    for (i in pts.indices) {
        val pi = pts[i]
        val near = ArrayList<Pair<Int, Float>>()
        for (j in pts.indices) {
            if (j == i) continue
            val d = hypot(pi.x - pts[j].x, pi.y - pts[j].y)
            if (d < maxD) near.add(j to d)
        }
        near.sortBy { it.second }
        for (k in 0 until minOf(maxNeighbours, near.size)) {
            val j = near[k].first
            if (i < j) edges.add(intArrayOf(i, j)) else edges.add(intArrayOf(j, i))
        }
    }
    // dedupe
    val seen = HashSet<Long>(edges.size * 2)
    val unique = ArrayList<IntArray>(edges.size)
    for (e in edges) {
        val key = e[0].toLong() * 100000L + e[1]
        if (seen.add(key)) unique.add(e)
    }

    return FModel(pts, unique)
}
