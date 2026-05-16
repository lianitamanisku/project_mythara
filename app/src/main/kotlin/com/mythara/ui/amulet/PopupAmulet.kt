package com.mythara.ui.amulet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.MytharaColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The summon-anywhere version of the rose amulet + constellation.
 *
 * Layout:
 *   - Long-press anywhere on screen summons the amulet at the
 *     press point.
 *   - Central rose with menu chips fanning out 360° around it.
 *   - Chips paginated (consecutive taps on the rose cycle pages).
 *
 * NEW (this revision):
 *
 *   - **Glide-to-select**: instead of tap-the-rose-then-tap-a-
 *     chip, the user can press the rose + drag toward any chip
 *     and release over it. The chip nearest the finger is
 *     highlighted live; release fires its action. Tap-to-select
 *     still works for users who prefer it.
 *
 *   - **Link lines**: Canvas-drawn purple lines connecting the
 *     rose centre to every chip on the active page. Makes the
 *     glide gesture's "drag along this line" affordance
 *     obvious.
 *
 *   - **Moving particle glow**: a purple-neon particle travels
 *     along every link line (centre → chip → centre, looping),
 *     plus orbits around the rose's halo and each chip's
 *     circular border. All three particle streams share one
 *     phase variable so the visual reads as one continuous
 *     "energy flow" pulsing through the amulet.
 *
 *   - **Apt emoji glyphs** on every chip (passed in via the
 *     AmuletChip.glyph field — see MytharaRoot.buildAmuletPages
 *     for the icon mappings).
 */

/**
 * One page of the paginated amulet. Each page is a list of chips
 * arranged around the constellation ring + a short label that
 * appears under the central rose so the user knows which page
 * they're on.
 */
data class AmuletPage(
    val label: String,
    val chips: List<AmuletChip>,
)

/**
 * A single chip on an amulet page. Uniform shape across page
 * types (navigation, PTT, app launcher).
 */
data class AmuletChip(
    /** Position on the ring, in clock degrees (0° = 12 o'clock). */
    val angleDeg: Float,
    /** Short caption under the chip (≤ 10 chars). */
    val caption: String,
    /** Accent colour for the chip border + glyph text. */
    val accent: Color,
    /** Optional bitmap (for app icons); when non-null, rendered
     *  inside the circular chip. */
    val icon: ImageBitmap? = null,
    /** Glyph (1-2 chars / emoji) shown when [icon] is null. */
    val glyph: String = caption.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
    /** Tap / glide-release handler. */
    val onTap: () -> Unit,
)

@Composable
fun PopupAmulet(
    anchorPx: Offset,
    pages: List<AmuletPage>,
    amuletSizeDp: Int,
    onScrimTap: () -> Unit,
    /**
     * PTT-as-Rose hook. Fires when the user holds the central rose
     * for at least [PTT_TRIGGER_MS] without dragging toward any chip.
     * The host calls into [com.mythara.voice.VoiceActionStore].fire(
     * RosePress) which starts a one-shot SpeechRecognition listen via
     * ChatViewModel — same path Pixel Buds touch-and-hold uses.
     * Default no-op so callers that don't want PTT can ignore.
     */
    onPttPress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().clickable { onScrimTap() })
        return
    }
    var pageIndex by remember { mutableStateOf(0) }
    val expansion = remember { Animatable(initialValue = 0f) }
    LaunchedEffect(Unit) {
        expansion.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = OPEN_DURATION_MS),
        )
    }

    val density = LocalDensity.current
    val radiusPx = with(density) { CONSTELLATION_RADIUS_DP.dp.toPx() }
    val amuletHalfPx = with(density) { (amuletSizeDp / 2).dp.toPx() }
    val slotHalfPx = with(density) { (SLOT_SIZE_DP / 2).dp.toPx() }

    val cx = anchorPx.x
    val cy = anchorPx.y

    // Particle phase 0→1 looping forever — shared across link
    // lines, rose halo, and chip orbits so the whole amulet
    // pulses to one rhythm.
    val particlePhase by rememberInfiniteTransition(label = "amulet-particle")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PARTICLE_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )

    // Glide state — finger position during a drag from the rose.
    // null when not dragging. The chip whose centre is nearest
    // the finger gets the highlight + fires on release.
    var fingerPos by remember { mutableStateOf<Offset?>(null) }

    val activePage = pages[pageIndex.coerceIn(0, pages.size - 1)]

    // Precompute chip centre offsets once per recomposition so
    // both the rendering loop AND the nearest-chip-to-finger
    // calculation use the same values.
    val chipCenters: List<Offset> = remember(activePage, expansion.value, cx, cy, radiusPx) {
        activePage.chips.map { chip ->
            val r = chip.angleDeg * (PI / 180.0).toFloat()
            val dx = sin(r) * radiusPx * expansion.value
            val dy = -cos(r) * radiusPx * expansion.value
            Offset(cx + dx, cy + dy)
        }
    }

    // Index of the chip currently nearest the finger (during a
    // glide). -1 when not dragging or no chip is close enough.
    val nearestChipIdx: Int = remember(fingerPos, chipCenters) {
        val pos = fingerPos ?: return@remember -1
        var bestIdx = -1
        var bestDist = SELECTION_RADIUS_PX
        chipCenters.forEachIndexed { i, c ->
            val d = hypot(pos.x - c.x, pos.y - c.y)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        bestIdx
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim — full canvas, fades in/out with expansion.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg.copy(alpha = 0.78f * expansion.value))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onScrimTap,
                ),
        )

        // Canvas: link lines + particle glow on lines + rose halo
        // + chip orbits. All in ONE Canvas pass so the particle
        // phase variable is consistent across visuals.
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val baseAlpha = expansion.value
            if (baseAlpha < 0.01f) return@Canvas
            val particleColor = MytharaColors.Charple

            // ─── Link lines + particles ───
            chipCenters.forEachIndexed { i, chipCenter ->
                val highlighted = (i == nearestChipIdx)
                val lineAlpha = (if (highlighted) 0.9f else 0.35f) * baseAlpha
                drawLine(
                    color = particleColor.copy(alpha = lineAlpha),
                    start = Offset(cx, cy),
                    end = chipCenter,
                    strokeWidth = if (highlighted) 3.5f else 2f,
                )
                // Particle on this line. Each line offsets the
                // phase by i/N so the streams don't pulse in
                // lock-step (looks more alive).
                val phase = (particlePhase + i.toFloat() / chipCenters.size) % 1f
                val px = cx + (chipCenter.x - cx) * phase
                val py = cy + (chipCenter.y - cy) * phase
                drawCircle(
                    color = particleColor.copy(alpha = baseAlpha),
                    radius = PARTICLE_DOT_RADIUS_PX,
                    center = Offset(px, py),
                )
                // Soft halo around the moving particle.
                drawCircle(
                    color = particleColor.copy(alpha = 0.25f * baseAlpha),
                    radius = PARTICLE_DOT_RADIUS_PX * 2.5f,
                    center = Offset(px, py),
                )
            }

            // ─── Rose halo orbit ───
            // Two particles 180° apart that orbit around the rose
            // at amulet-radius distance. Gives the rose a sense
            // of motion without re-drawing the rose itself.
            val haloRadius = amuletHalfPx + ROSE_HALO_OFFSET_PX
            for (i in 0 until 2) {
                val phase = (particlePhase + i * 0.5f) % 1f
                val angle = phase * 2f * PI.toFloat()
                val hx = cx + cos(angle) * haloRadius
                val hy = cy + sin(angle) * haloRadius
                drawCircle(
                    color = particleColor.copy(alpha = 0.9f * baseAlpha),
                    radius = PARTICLE_DOT_RADIUS_PX,
                    center = Offset(hx, hy),
                )
                drawCircle(
                    color = particleColor.copy(alpha = 0.30f * baseAlpha),
                    radius = PARTICLE_DOT_RADIUS_PX * 2.5f,
                    center = Offset(hx, hy),
                )
            }

            // ─── Chip ring orbits ───
            // A particle orbits each chip's border. Nearest-chip
            // glow is brighter + faster (more particles).
            chipCenters.forEachIndexed { i, chipCenter ->
                val highlighted = (i == nearestChipIdx)
                val count = if (highlighted) 3 else 1
                for (k in 0 until count) {
                    val phase = (particlePhase + i * 0.2f + k.toFloat() / count) % 1f
                    val angle = phase * 2f * PI.toFloat()
                    val px = chipCenter.x + cos(angle) * slotHalfPx
                    val py = chipCenter.y + sin(angle) * slotHalfPx
                    drawCircle(
                        color = particleColor.copy(alpha = (if (highlighted) 1f else 0.8f) * baseAlpha),
                        radius = PARTICLE_DOT_RADIUS_PX,
                        center = Offset(px, py),
                    )
                }
                // Glow ring on the highlighted chip.
                if (highlighted) {
                    drawCircle(
                        color = particleColor.copy(alpha = 0.35f * baseAlpha),
                        radius = slotHalfPx + 6f,
                        center = chipCenter,
                        style = Stroke(width = 4f),
                    )
                }
            }
        }

        // Render the chips on top of the Canvas overlay so they
        // are tappable + their content (glyph / icon / caption)
        // renders crisply.
        chipCenters.forEachIndexed { i, chipCenter ->
            val chip = activePage.chips[i]
            val chipHalfDp = (SLOT_SIZE_DP / 2).dp
            val chipLeftDp = with(density) { chipCenter.x.toDp() } - chipHalfDp
            val chipTopDp = with(density) { chipCenter.y.toDp() } - chipHalfDp

            Box(
                modifier = Modifier
                    .offset(x = chipLeftDp, y = chipTopDp)
                    .graphicsLayer { alpha = expansion.value },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(SLOT_SIZE_DP.dp)
                            .clip(CircleShape)
                            .background(MytharaColors.Surface)
                            .border(width = 1.5.dp, color = chip.accent, shape = CircleShape)
                            .clickable { chip.onTap() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (chip.icon != null) {
                            Image(
                                bitmap = chip.icon,
                                contentDescription = chip.caption,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size((SLOT_SIZE_DP - 6).dp).clip(CircleShape),
                            )
                        } else {
                            Text(
                                text = chip.glyph,
                                color = chip.accent,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Text(
                        text = chip.caption,
                        color = MytharaColors.FgMute,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(SLOT_LABEL_WIDTH_DP.dp),
                    )
                }
            }
        }

        // Central rose amulet.
        val amuletLeftDp = with(density) { cx.toDp() } - (amuletSizeDp / 2).dp
        val amuletTopDp = with(density) { cy.toDp() } - (amuletSizeDp / 2).dp
        Box(
            modifier = Modifier
                .offset(x = amuletLeftDp, y = amuletTopDp)
                .graphicsLayer { alpha = expansion.value },
        ) {
            RoseAmulet(
                modifier = Modifier.size(amuletSizeDp.dp),
                onTap = {
                    // Routed through the glide-trigger overlay
                    // below, but kept as a fallback in case the
                    // overlay misses (e.g. very fast tap).
                    pageIndex = (pageIndex + 1) % pages.size
                },
            )
        }

        // Glide-gesture overlay — captures the finger position
        // while pressing the rose area. Sized to JUST cover the
        // rose (not fillMaxSize) so taps outside the rose zone
        // fall through to the scrim's clickable for dismissal.
        // Rendered AFTER the rose so this Box sits on top in z
        // order — the rose's own clickable is shadowed within
        // this zone, which is what we want (we route taps here).
        // Once a finger is acquired, Compose continues tracking
        // globally even after it strays outside the box, so the
        // glide-to-chip gesture works even with a small trigger.
        // fingerPos is translated back into root-Box-local coords
        // by adding the box origin so nearestChipIdx aligns with
        // chipCenters' coordinate space.
        val triggerHalfPx = amuletHalfPx + 8f
        val triggerSizePx = triggerHalfPx * 2f
        val triggerOriginX = cx - triggerHalfPx
        val triggerOriginY = cy - triggerHalfPx
        val triggerLeftDp = with(density) { triggerOriginX.toDp() }
        val triggerTopDp = with(density) { triggerOriginY.toDp() }
        val triggerSizeDp = with(density) { triggerSizePx.toDp() }
        Box(
            modifier = Modifier
                .offset(x = triggerLeftDp, y = triggerTopDp)
                .size(triggerSizeDp)
                .pointerInput(activePage) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val downTimeMs = System.currentTimeMillis()
                        fingerPos = Offset(
                            down.position.x + triggerOriginX,
                            down.position.y + triggerOriginY,
                        )
                        var pttFired = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            fingerPos = Offset(
                                change.position.x + triggerOriginX,
                                change.position.y + triggerOriginY,
                            )
                            change.consume()
                            // PTT cross-threshold: still pressed for
                            // PTT_TRIGGER_MS, finger hasn't glided
                            // toward any chip → fire PTT once.
                            if (!pttFired &&
                                change.pressed &&
                                nearestChipIdx < 0 &&
                                (System.currentTimeMillis() - downTimeMs) >= PTT_TRIGGER_MS
                            ) {
                                pttFired = true
                                onPttPress()
                            }
                            if (change.changedToUp()) break
                            if (!change.pressed) break
                        }
                        val idx = nearestChipIdx
                        when {
                            idx >= 0 -> {
                                // Glide-released onto a chip → fire
                                // its action.
                                activePage.chips[idx].onTap()
                            }
                            pttFired -> {
                                // PTT was the gesture — host already
                                // handled it. Suppress the page-
                                // cycle that would otherwise fire on
                                // tap-without-chip.
                            }
                            else -> {
                                // Released without a chip target
                                // before PTT threshold — treat as a
                                // tap-on-rose (cycle pages).
                                pageIndex = (pageIndex + 1) % pages.size
                            }
                        }
                        fingerPos = null
                    }
                },
        )

        // Page label + dot pagination indicator below the rose.
        val labelTopDp = with(density) { (cy + amuletHalfPx).toDp() } + 6.dp
        val labelLeftDp = with(density) { cx.toDp() } - 60.dp
        Column(
            modifier = Modifier
                .offset(x = labelLeftDp, y = labelTopDp)
                .graphicsLayer { alpha = expansion.value }
                .width(120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = activePage.label,
                color = MytharaColors.FgDim,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
            if (pages.size > 1) {
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in pages.indices) {
                        Box(
                            modifier = Modifier
                                .size(if (i == pageIndex) 6.dp else 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == pageIndex) MytharaColors.Charple else MytharaColors.SurfaceHigh,
                                ),
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.changedToUp(): Boolean =
    !pressed && previousPressed

private const val CONSTELLATION_RADIUS_DP = 140
private const val SLOT_SIZE_DP = 44
private const val SLOT_LABEL_WIDTH_DP = 64
private const val OPEN_DURATION_MS = 220

/** Period (ms) for one full loop of the moving particle along a
 *  link line / rose orbit / chip orbit. Slower = more meditative;
 *  faster = more "energetic". 2.6s lands in the calm range. */
private const val PARTICLE_PERIOD_MS = 2600

/** Particle dot radius in px (post-density). Compose's Canvas
 *  works in PX so this is unscaled — looks right at xxhdpi+. */
private const val PARTICLE_DOT_RADIUS_PX = 4f

/** How far from the rose's edge the halo particles orbit. */
private const val ROSE_HALO_OFFSET_PX = 8f

/** Max distance (in px) the finger can be from a chip's centre
 *  before that chip is considered the glide target. Slightly
 *  bigger than the chip's own radius so the user doesn't have
 *  to be pixel-perfect. */
private const val SELECTION_RADIUS_PX = 90f

/** How long the finger has to be held on the central rose (without
 *  gliding toward any chip) before we treat it as a PTT press.
 *  250 ms is short enough that the user can speak immediately yet
 *  long enough that a quick page-cycle tap doesn't accidentally
 *  fire mic capture. */
private const val PTT_TRIGGER_MS = 250L

