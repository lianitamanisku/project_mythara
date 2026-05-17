package com.mythara.ui.amulet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mythara.ui.anim.ParticlePalettes
import com.mythara.ui.anim.ParticleRing
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.delay

/**
 * First-launch coach mark for the new Mythara amulet gesture.
 *
 * Plan §E discoverability: under Chat on first launch, render a
 * 3-second ghosted [ParticleRing] near the bottom-centre with the
 * caption *"press & hold anywhere"* in `FgMute`. Persistence lives
 * in [com.mythara.data.OnboardingStore.markAmuletHintSeen] —
 * dismissed by tap or by the 3-second timeout, whichever happens
 * first. Never shown again unless the user explicitly resets
 * onboarding from Settings.
 *
 * The ring uses the same particle math + brand palette as the
 * actual [PreAmuletRing] that blooms on a real long-press, so the
 * coach mark is a low-fidelity rehearsal of the gesture the user
 * is about to learn — the visual rhymes when they try it for real.
 *
 * Sits on top of every screen via [MytharaRoot] but BELOW the
 * popup amulet + spotlight + bloom overlay so a genuine
 * long-press during the hint window naturally takes over the
 * visual.
 *
 * @param onDismissed Caller persists the seen-flag here.
 */
@Composable
fun AmuletHintOverlay(onDismissed: () -> Unit) {
    val density = LocalDensity.current
    val visible = remember { Animatable(0f) }
    val expansion = remember { Animatable(0f) }
    val dismissed = remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Fade in over 400 ms, ring grows out of nothing.
        visible.animateTo(1f, tween(FADE_IN_MS, easing = FastOutSlowInEasing))
        expansion.animateTo(1f, tween(GROW_MS, easing = FastOutSlowInEasing))
        // Hold for the visible window.
        delay(HOLD_MS)
        if (!dismissed.value) {
            visible.animateTo(0f, tween(FADE_OUT_MS, easing = FastOutSlowInEasing))
            onDismissed()
        }
    }

    // Position the ring centred on the BOTTOM 1/3 of the screen so
    // it's roughly where the user's thumb would press one-handed,
    // and well clear of the chat composer / amulet overlay area.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(visible.value),
    ) {
        // The ghosted ring itself — particle math identical to a
        // real PreAmuletRing, just at lower intensity (alphaMul) so
        // it reads as a hint, not as an active gesture in progress.
        BoxWithSize { sizePx ->
            val ringCenter = Offset(sizePx.x / 2f, sizePx.y * COACH_VERTICAL_FRACTION)
            ParticleRing(
                center = ringCenter,
                radius = RING_RADIUS_DP.dp,
                count = PARTICLE_COUNT,
                palette = ParticlePalettes.BloomDefault,
                progress = expansion.value,
                alphaMul = visible.value * 0.7f,
            )
            // Caption sits below the ring.
            val captionTopDp = with(density) {
                ringCenter.y.toDp() + (RING_RADIUS_DP + 24).dp
            }
            Text(
                text = "${Glyph.AccentBar} press & hold anywhere",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = captionTopDp)
                    .alpha(visible.value),
            )
        }
    }
}

/** Helper to access the parent Box's size in pixels inside a
 *  Compose call — used by the overlay to centre its ring near
 *  the bottom one-third of the screen regardless of device. */
@Composable
private fun BoxWithSize(content: @Composable (Offset) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        content(Offset(w, h))
    }
}

private const val FADE_IN_MS = 400
private const val GROW_MS = 600
private const val HOLD_MS = 3_000L
private const val FADE_OUT_MS = 500
/** Ring sits 70% down the screen — roughly thumb territory for
 *  one-handed phone use. */
private const val COACH_VERTICAL_FRACTION = 0.7f
private const val RING_RADIUS_DP = 72
private const val PARTICLE_COUNT = 18
