package com.mythara.ui.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.mythara.ui.anim.EdgeGlow
import com.mythara.ui.anim.EdgeGlowSpec
import com.mythara.ui.anim.rememberAlphaPulse
import com.mythara.ui.theme.Backdrop
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.LocalSkinSpec
import com.mythara.ui.theme.MytharaColors

/**
 * Shared chrome wrapper every redesigned route adopts.
 *
 * Layout (top to bottom):
 *   1. Header sliver (44 dp) — glyph + title in JetBrainsMono Bold +
 *      optional back affordance, hairline divider beneath. The sliver
 *      "breathes" alpha 0.85 → 1.0 over 4 s on mount so the chrome
 *      feels alive on entry.
 *   2. Content body (fills remaining space) — the caller's lambda
 *      with a [BoxScope] receiver. Any per-screen layout (LazyColumn,
 *      Column, Box, ...) goes here.
 *   3. Edge-glow overlay (default: bottom 2-dp Charple→Bok gradient)
 *      breathing at 0.25 Hz. Pass `edgeGlow = null` to suppress on
 *      surfaces that have their own bottom UI (e.g. Chat composer).
 *
 * Adoption is a one-line wrap in MytharaRoot.kt:
 * ```
 * composable(Routes.Settings) {
 *     MytharaScaffold(title = "Settings", glyph = Glyph.DiamondFilled) {
 *         SettingsScreen(...)
 *     }
 * }
 * ```
 *
 * Backwards-compatible: deleting the wrapper falls back to the
 * legacy chrome (no header, no edge-glow). Screen bodies stay
 * untouched until their phase.
 *
 * Does NOT mount the status bar / NavHost / amulet / RoseBloomOverlay
 * — those stay in MytharaRoot so global pointer wiring + fold
 * machinery remains untouched.
 *
 * @param title Optional header text. When null, the sliver is hidden.
 * @param glyph Single Unicode glyph rendered before the title.
 * @param onBack Optional back tap on the title; when null, no back
 *   chip renders. Most routes rely on the global gesture + amulet
 *   nav rather than a chevron, so back is optional.
 * @param edgeGlow Optional edge-glow overlay; pass `EdgeGlowSpec()`
 *   for the brand default (bottom Charple→Bok) or null to suppress.
 * @param content Screen body. `BoxScope` receiver so callers can
 *   align inner content if desired.
 */
@Composable
fun MytharaScaffold(
    title: String? = null,
    glyph: String? = null,
    onBack: (() -> Unit)? = null,
    edgeGlow: EdgeGlowSpec? = EdgeGlowSpec(),
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // Skins with a signature backdrop (Aurora blobs / Living-Rose
    // breath / HUD rings) must let MythBackdrop show THROUGH the
    // scaffold — so paint NO opaque fill for them. Spatial (Plain
    // backdrop) keeps the solid base so it reads exactly like the
    // pre-v6 flat dark UI.
    val hasBackdrop = LocalSkinSpec.current.backdrop != Backdrop.Plain
    val scaffoldFill = if (hasBackdrop) Color.Transparent else MytharaColors.Bg
    Box(modifier = modifier.fillMaxSize().background(scaffoldFill)) {
        // The inner Column is status-bar-inset-padded so the 44 dp
        // header sliver (glyph + title + back chip) always sits
        // BELOW the system status bar / Dynamic Island. Without
        // this, the header was occluded by the cutout on every
        // Pixel + foldable — and on screens whose "logo" lives in
        // the header (e.g. the ◉ glyph on SecretSettings) the tap
        // target was unreachable behind the Island.
        //
        // We DON'T inset the outer Box itself so the body still
        // paints edge-to-edge against the wallpaper — only the
        // chrome stack is pushed down. Inner screens that want
        // their content edge-to-edge can opt back out with
        // `.consumeWindowInsets(...)` inside their own body.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            if (title != null || glyph != null || onBack != null) {
                ScaffoldHeader(title = title, glyph = glyph, onBack = onBack)
            }
            // Body — receives BoxScope so callers can align overlays.
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
        edgeGlow?.let { EdgeGlow(it) }
    }
}

/**
 * 44-dp header sliver with glyph + title + optional back chip,
 * breathing alpha 0.85 → 1.0 over 4 s on mount.
 *
 * Stays out of the amulet's gesture path by occupying a fixed slice
 * at the top — the body Box below still receives long-press events
 * normally via the root's `detectGlobalLongPress`.
 */
@Composable
private fun ScaffoldHeader(
    title: String?,
    glyph: String?,
    onBack: (() -> Unit)?,
) {
    val alpha = rememberAlphaPulse(active = true, hz = HEADER_BREATHING_HZ, minAlpha = 0.85f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT_DP.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Text(
                    text = Glyph.LeftArrow,
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(end = 10.dp),
                )
            }
            if (glyph != null) {
                Text(
                    text = glyph,
                    color = MytharaColors.Charple,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (title != null) {
                Text(
                    text = title,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
    // Hairline divider beneath the sliver.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MytharaColors.SurfaceHigh.copy(alpha = alpha * 0.6f)),
    )
}

private const val HEADER_HEIGHT_DP = 44
/** Slow breathing — one inhale every 4 s. Quiet enough to feel
 *  ambient, not enough to nag. */
private const val HEADER_BREATHING_HZ = 0.25f
