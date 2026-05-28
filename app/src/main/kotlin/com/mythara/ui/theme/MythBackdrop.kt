package com.mythara.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The single full-screen backdrop layer, mounted ONCE in MytharaRoot
 * behind the NavHost. Each skin paints its signature backdrop here, so
 * the ~20 screens render their content on top unchanged — this layer +
 * [MythCard] / [MythScaffold] carry the skin identity, not per-screen
 * forks.
 *
 * Phase 4 ships the layer + the Spatial (Plain) backdrop = solid base
 * colour (visually identical to today). The Aurora gradient (P6), Rose
 * particle field (P7), and HUD rings (P8) plug into the `when` branches
 * below without touching any screen.
 */
@Composable
fun MythBackdrop(
    modifier: Modifier = Modifier,
    spec: SkinSpec = LocalSkinSpec.current,
    palette: MythPalette = LocalMythPalette.current,
) {
    Box(modifier = modifier.fillMaxSize().background(palette.Bg)) {
        when (spec.backdrop) {
            Backdrop.Plain -> Unit // solid base — Spatial
            Backdrop.Aurora -> AuroraBackdrop(palette = palette) // P6
            Backdrop.Rose -> RoseBackdrop(palette = palette) // P7: HR-driven breathing rose
            Backdrop.Hud -> HudBackdrop(palette = palette) // P8: concentric rings + radar sweep
        }
    }
}
