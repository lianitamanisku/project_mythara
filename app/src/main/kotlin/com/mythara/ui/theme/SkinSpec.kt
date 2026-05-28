package com.mythara.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The four selectable visual skins. */
enum class SkinId { SpatialCards, AuroraGlass, LivingRose, HolographicHud }

/** Full-screen backdrop style a skin paints behind the NavHost. */
enum class Backdrop { Plain, Aurora, Rose, Hud }

/** How cards / panels are surfaced. */
enum class SurfaceTreatment { Solid, Translucent, LineArt }

/**
 * Per-skin design tokens BEYOND colour (colour lives in [MythPalette]).
 * Consumed mostly by the shared `MythBackdrop` / `MythScaffold` /
 * `MythCard` (Phase 4) — individual screens don't read this, which is
 * how one scaffold serves all four skins without per-screen forks.
 */
data class SkinSpec(
    val id: SkinId,
    val cornerRadius: Dp,
    val hairlineWidth: Dp,
    /** Glass blur radius; 0 = no blur. Gated to API 31+ at use site. */
    val blurRadius: Dp,
    val surfaceTreatment: SurfaceTreatment,
    val backdrop: Backdrop,
    /** 0..1 — how strongly the skin leans on the brand accent. */
    val accentIntensity: Float,
    val elevation: Dp,
)

/**
 * Active skin spec, provided by [MytharaTheme]. Defaults to Spatial —
 * the safe, solid-surface, no-backdrop spec closest to today's flat
 * dark UI, so Phase 3 ships with zero perceived change.
 */
val LocalSkinSpec = staticCompositionLocalOf { SkinCatalog.Spatial }

object SkinCatalog {
    /** Default skin — solid cards, standard radius, no backdrop, no
     *  blur. Visually ≈ the pre-v6 app. */
    val Spatial = SkinSpec(
        id = SkinId.SpatialCards,
        cornerRadius = 14.dp,
        hairlineWidth = 1.dp,
        blurRadius = 0.dp,
        surfaceTreatment = SurfaceTreatment.Solid,
        backdrop = Backdrop.Plain,
        accentIntensity = 1.0f,
        elevation = 2.dp,
    )

    /** Aurora Glass — frosted translucent cards floating over a slow
     *  charple→bok aurora gradient. Softer corners, blur, translucent
     *  surfaces. */
    val Aurora = SkinSpec(
        id = SkinId.AuroraGlass,
        cornerRadius = 22.dp,
        hairlineWidth = 1.dp,
        blurRadius = 28.dp,
        surfaceTreatment = SurfaceTreatment.Translucent,
        backdrop = Backdrop.Aurora,
        accentIntensity = 1.0f,
        elevation = 0.dp,
    )

    // Rose / HUD specs land in Phases 7–8. Until then they resolve to
    // Spatial so the picker is wired but the visual swap ships
    // incrementally.
    fun forSkin(id: SkinId): SkinSpec = when (id) {
        SkinId.SpatialCards -> Spatial
        SkinId.AuroraGlass -> Aurora
        SkinId.LivingRose -> Spatial
        SkinId.HolographicHud -> Spatial
    }
}
