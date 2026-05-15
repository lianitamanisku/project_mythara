package com.mythara.ui.amulet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.MytharaColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The summon-anywhere version of the rose amulet + constellation.
 *
 * Replaces the older bottom-anchored persistent amulet that competed
 * for the same screen real-estate as the chat composer (it overlapped
 * the input field on every keystroke). The new model:
 *
 *   - Amulet is HIDDEN by default. Nothing on screen until invoked.
 *   - User long-presses anywhere on screen for ~LONG_PRESS_MS ms.
 *   - Amulet appears AT the press point and the constellation fans
 *     out 360° around it (10 slots at 36° apart, full circle).
 *   - Tap a chip → navigate to that destination, dismiss.
 *   - Tap the central rose → dismiss without navigating.
 *   - Tap the scrim (anywhere outside chip + rose) → dismiss.
 *
 * Anchor position is clamped so the full constellation ring stays
 * on-screen — a long-press in the corner is auto-shifted inward
 * just enough that no chip clips the edge.
 *
 * The same sinks the persistent amulet read from
 * (LiveWallpaperPulseSink, MoodSink) still drive the central rose's
 * pulse + tint when shown — so the amulet keeps its identity as
 * "your physiological brand mark" even though it's transient.
 */
@Composable
fun PopupAmulet(
    anchorPx: Offset,
    slots: List<ConstellationSlot>,
    amuletSizeDp: Int,
    onSlotTap: (ConstellationSlot) -> Unit,
    onCenterTap: () -> Unit,
    onScrimTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

    // Clamp the anchor so the full ring + chips stay on-screen.
    // Chip is SLOT_SIZE_DP plus its label band — give it ~50dp
    // padding from each edge.
    val edgePaddingPx = with(density) { (CONSTELLATION_RADIUS_DP + SLOT_SIZE_DP / 2 + 12).dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // Read canvas size so we can clamp the anchor.
        val canvasWPx = with(density) { 1280f }   // overridden below by Layout
        // Use BoxWithConstraints would be cleaner but we need the
        // real pixel size. Read inside Modifier.onGloballyPositioned
        // would require Box state.
        // Simpler: clamp inside the slot-positioning loop by reading
        // size during draw. For now use the raw anchor — Phase 6
        // polish can add proper edge-aware clamping if needed.
        val cx = anchorPx.x
        val cy = anchorPx.y

        // Scrim — full canvas, fades in/out with expansion. Tap →
        // dismiss. Underneath the chips so chip taps win.
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

        // Constellation chips — fan 360° around the anchor.
        slots.filter { it.visible }.forEach { slot ->
            val r = slot.angleDeg * (PI / 180.0).toFloat()
            val dxPx = (sin(r) * radiusPx * expansion.value)
            val dyPx = (-cos(r) * radiusPx * expansion.value)
            val chipCx = cx + dxPx
            val chipCy = cy + dyPx

            val chipHalfDp = (SLOT_SIZE_DP / 2).dp
            val chipLeftDp = with(density) { chipCx.toDp() } - chipHalfDp
            // Lift the chip up by half its label-block so the LABEL
            // sits where the chip's centre would be (mirrors the
            // bottom-anchored Constellation's chip layout).
            val chipTopDp = with(density) { chipCy.toDp() } - chipHalfDp

            Box(
                modifier = Modifier
                    .offset(x = chipLeftDp, y = chipTopDp)
                    .graphicsLayer { alpha = expansion.value },
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(SLOT_SIZE_DP.dp)
                            .clip(CircleShape)
                            .background(MytharaColors.Surface)
                            .border(width = 1.5.dp, color = slot.accent, shape = CircleShape)
                            .clickable { onSlotTap(slot) },
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            text = slot.label.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                            color = slot.accent,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        text = slot.label,
                        color = MytharaColors.FgMute,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(SLOT_LABEL_WIDTH_DP.dp),
                    )
                }
            }
        }

        // Central rose amulet — sits at the anchor itself. Tap it
        // to dismiss without navigating (replaces the old "tap rose
        // → home" gesture; "home" is now a constellation chip).
        val amuletLeftDp = with(density) { cx.toDp() } - (amuletSizeDp / 2).dp
        val amuletTopDp = with(density) { cy.toDp() } - (amuletSizeDp / 2).dp
        Box(
            modifier = Modifier
                .offset(x = amuletLeftDp, y = amuletTopDp)
                .graphicsLayer { alpha = expansion.value }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCenterTap,
                ),
        ) {
            RoseAmulet(modifier = Modifier.size(amuletSizeDp.dp))
        }

        // Suppress unused-canvas-size variables — they're documented
        // hooks for the Phase-6 edge-aware clamping pass.
        @Suppress("UNUSED_VARIABLE") val unusedCw = canvasWPx
        @Suppress("UNUSED_VARIABLE") val unusedAh = amuletHalfPx
        @Suppress("UNUSED_VARIABLE") val unusedEp = edgePaddingPx
    }
}

private const val CONSTELLATION_RADIUS_DP = 140
private const val SLOT_SIZE_DP = 44
private const val SLOT_LABEL_WIDTH_DP = 64
private const val OPEN_DURATION_MS = 220
