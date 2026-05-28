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
    private val pickupDetector: com.mythara.camera.PhonePickupDetector,
) : ViewModel() {
    val speaking: StateFlow<Boolean> =
        tts.speaking.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Smoothed front-camera head pose. `present = false` when no face. */
    val pose: StateFlow<FaceTracker.Pose> = tracker.pose

    /** True while the pickup detector says the camera is allowed to
     *  run. The screen binds/unbinds CameraX based on this so a
     *  phone-down idle never streams frames. */
    val cameraActive: StateFlow<Boolean> = pickupDetector.activeWindow

    /** Start / stop the front-camera stream — driven by the screen's
     *  composition lifetime so the camera only runs while it's open. */
    fun bindCamera() = tracker.bind()
    fun unbindCamera() = tracker.unbind()

    /** Start / stop the pickup-detection sensor. Tied to the
     *  Composable's DisposableEffect so it runs only while Home /
     *  Face is in the foreground. */
    fun enablePickupDetector() = pickupDetector.enable()
    fun disablePickupDetector() = pickupDetector.disable()

    override fun onCleared() {
        tracker.unbind()
        pickupDetector.disable()
    }
}

// Status-text colour constants kept here so the FaceScreen wrapper's
// status pill at the bottom of the cinema view can use them. The
// legacy point-cloud model classes (FGroup, FPoint, FModel,
// EYE_CY / EYE_DX, drawFaceCloud, buildFaceModel) were removed in
// v7 P7 polish — the particle FaceMesh below replaced them.

private val CLOUD = Color(0xFF4FE2FF)      // electric cyan — used by FaceScreen status text
private val EYE_GLOW = Color(0xFFE4FBFF)   // near-white cyan — used by FaceScreen status text

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

    // Phone-pickup detector — same low-power gate as Home. The
    // camera only binds while the pickup window is open; without
    // it, the cinema view would burn the lens continuously even
    // when the phone is sitting on a desk.
    DisposableEffect(Unit) {
        vm.enablePickupDetector()
        onDispose { vm.disablePickupDetector() }
    }
    val cameraActive by vm.cameraActive.collectAsState()

    // Camera runs ONLY while this screen is composed AND the
    // permission is held AND a pickup window is open.
    DisposableEffect(hasCam, cameraActive) {
        if (hasCam && cameraActive) vm.bindCamera() else vm.unbindCamera()
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

    // Assembly 0 (scattered) → 1 (formed). v7 P7+: was a snappy
    // spring (dampingRatio=0.62, stiffness=190) — particles
    // appeared to teleport into the ring. The user asked for a
    // slow, smooth gathering so the formation reads as deliberate.
    // Asymmetric easing: ~1.8 s to gather (FastOutSlowInEasing —
    // accelerate, then settle), ~0.8 s to disperse (snappier so
    // looking away feels responsive). Drives the `ease` field in
    // the Canvas below where every particle's final position is
    // sxN + (txN - sxN) * ease.
    val assembly by animateFloatAsState(
        targetValue = if (pose.present) 1f else 0f,
        animationSpec = if (pose.present) {
            tween(
                durationMillis = 1800,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            )
        } else {
            tween(
                durationMillis = 800,
                easing = androidx.compose.animation.core.LinearOutSlowInEasing,
            )
        },
        label = "assembly",
    )

    // Gather phase — drives a short "drift toward centre" pre-pass
    // before particles snap onto their final ring / sphere
    // positions. The Canvas blends each particle's scatter point
    // first toward CIRCLE_CX/CY (gather), then onto its assembled
    // target (form). Curve: rises fast to ~0.55 (particles converge
    // toward middle), then dips back to 0 as `assembly` itself
    // finishes settling onto the ring. Net effect: "dust drifts
    // inward → coalesces into the ring", not "dust teleports".
    val gather = remember(assembly) {
        // 0 → 1.5 region maps to: rapid rise from 0 to ~0.55, then
        // tapering back to 0. Implemented inline so it never lags
        // the assembly state.
        val a = assembly.coerceIn(0f, 1f)
        // Triangular pulse: peaks at a=0.5, zero at 0 and 1.
        val peak = 0.55f
        (1f - kotlin.math.abs(a - 0.5f) * 2f).coerceIn(0f, 1f) * peak
    }

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
            // CIRCLE / SPHERE, ease=0 (no face) → particles scatter and
            // drift; ease=1 (face detected) → particles form the ring
            // (or the sunburst, when also speaking).
            //
            // GATHER pre-pass — pull each particle a small amount
            // toward the centre while the assembly anim is mid-flight.
            // `gather` peaks at 0.55 when assembly ≈ 0.5, falls back
            // to 0 at the ends. So the visible motion reads as:
            //   1) particles all drift inward toward CIRCLE_CX/CY
            //   2) they coalesce, then push outward onto the ring
            // instead of teleporting in a single straight line.
            // HALO ignored (its `txN==sxN` so the lerp below is a
            // no-op for it; only CIRCLE/SPHERE see the pull).
            val scatterPullX = if (p.role == PRole.HALO) sxN
                else sxN + (CIRCLE_CX - sxN) * gather
            val scatterPullY = if (p.role == PRole.HALO) syN
                else syN + (CIRCLE_CY - syN) * gather
            val nx = scatterPullX + (txN - scatterPullX) * ease
            val ny = scatterPullY + (tyN - scatterPullY) * ease
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

