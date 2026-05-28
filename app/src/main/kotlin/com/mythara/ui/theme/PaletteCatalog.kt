package com.mythara.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette library — every (skin × brightness) combination resolves
 * here. Phase 3 ships the Spatial skin's light + dark palettes; the
 * Aurora / Rose / HUD skins reuse Spatial until their phases (6–8)
 * give them bespoke palettes, so the skin picker works end-to-end
 * while the distinct looks land incrementally.
 */
object PaletteCatalog {

    /** Dark Spatial — the original Charmtone Pantera values verbatim,
     *  so the default theme is pixel-identical to the pre-v6 app. */
    val SpatialDark = MythPalette(
        Bg = Color(0xFF201F26),
        Surface = Color(0xFF2D2C35),
        SurfaceMid = Color(0xFF3A3943),
        SurfaceHigh = Color(0xFF4D4C57),
        Fg = Color(0xFFDFDBDD),
        FgMute = Color(0xFFA8A4AB),
        FgDim = Color(0xFF605F6B),
        Charple = Color(0xFF6B50FF),
        Bok = Color(0xFF68FFD6),
        Sriracha = Color(0xFFEB4268),
        Mustard = Color(0xFFF5EF34),
        Citron = Color(0xFFE8FF27),
        Malibu = Color(0xFF00A4FF),
        Julep = Color(0xFF00FFB2),
    )

    /** Light Spatial — near-white surfaces with a faint violet cast,
     *  near-black text, and brand/semantic accents darkened so the
     *  neon mints + yellows stay legible on a light background. */
    val SpatialLight = MythPalette(
        Bg = Color(0xFFF5F3FA),         // near-white, faint violet cast
        Surface = Color(0xFFFFFFFF),    // cards
        SurfaceMid = Color(0xFFEAE7F1), // bubbles, hairline
        SurfaceHigh = Color(0xFFD7D2E2),// raised panels / borders
        Fg = Color(0xFF1B1A22),         // near-black primary
        FgMute = Color(0xFF565260),     // de-emphasis
        FgDim = Color(0xFF8E8A99),      // metadata
        Charple = Color(0xFF5A3FE0),    // slightly deeper violet for contrast
        Bok = Color(0xFF00A98C),        // mint darkened — pure #68FFD6 is invisible on white
        Sriracha = Color(0xFFD42F54),   // error
        Mustard = Color(0xFFB89500),    // warning — yellow darkened
        Citron = Color(0xFF7E9400),     // busy — darkened
        Malibu = Color(0xFF0083CC),     // info
        Julep = Color(0xFF00936E),      // success — darkened
    )

    /** Dark Aurora — a deep, distinctly VIOLET base (vs Spatial's
     *  neutral charcoal) so every screen reads as "aurora" even
     *  before the animated backdrop is visible. Surfaces carry a
     *  violet cast; the brand charple is pushed brighter to glow. */
    val AuroraDark = MythPalette(
        Bg = Color(0xFF120E1F),         // deep violet-black
        Surface = Color(0xFF1E1733),    // violet-tinted card
        SurfaceMid = Color(0xFF2B2147),
        SurfaceHigh = Color(0xFF3C2F5E),
        Fg = Color(0xFFEDE8F7),
        FgMute = Color(0xFFB3A8C8),
        FgDim = Color(0xFF6E6488),
        Charple = Color(0xFF8B6BFF),    // brighter violet — glows on the dark base
        Bok = Color(0xFF68FFD6),
        Sriracha = Color(0xFFEB4268),
        Mustard = Color(0xFFF5EF34),
        Citron = Color(0xFFE8FF27),
        Malibu = Color(0xFF5AB6FF),
        Julep = Color(0xFF00FFB2),
    )

    /** Light Aurora — lavender-tinted near-white with the same
     *  legibility-adjusted accents as Spatial light. */
    val AuroraLight = MythPalette(
        Bg = Color(0xFFF1ECFB),         // lavender-white
        Surface = Color(0xFFFBF9FF),
        SurfaceMid = Color(0xFFE6DEF6),
        SurfaceHigh = Color(0xFFCFC4E6),
        Fg = Color(0xFF1B1530),
        FgMute = Color(0xFF564E6E),
        FgDim = Color(0xFF8C84A6),
        Charple = Color(0xFF5A3FE0),
        Bok = Color(0xFF00A98C),
        Sriracha = Color(0xFFD42F54),
        Mustard = Color(0xFFB89500),
        Citron = Color(0xFF7E9400),
        Malibu = Color(0xFF0083CC),
        Julep = Color(0xFF00936E),
    )

    /** Dark Living Rose — a warm wine-black base with a rose-pink
     *  brand accent (Charple retinted to rose), so the WHOLE app reads
     *  as "rose" — not just the breathing geometric rose painted on
     *  the backdrop. The cyan nucleus stays so the rose mark keeps its
     *  brand-consistent heart. */
    val LivingRoseDark = MythPalette(
        Bg = Color(0xFF1B0E15),         // deep wine-black
        Surface = Color(0xFF2B1A22),    // rose-tinted card
        SurfaceMid = Color(0xFF3A2430),
        SurfaceHigh = Color(0xFF4E2F3F),
        Fg = Color(0xFFF6E6EC),         // rosy near-white
        FgMute = Color(0xFFCBA7B4),
        FgDim = Color(0xFF8C6571),
        Charple = Color(0xFFFF4F87),    // brand accent → rose-pink
        Bok = Color(0xFF68FFD6),        // nucleus / success — cyan kept
        Sriracha = Color(0xFFFF5470),
        Mustard = Color(0xFFF5D33A),
        Citron = Color(0xFFC8E84A),
        Malibu = Color(0xFF6FB4FF),
        Julep = Color(0xFF4FE0A0),
    )

    /** Light Living Rose — rose-white surfaces, deep-rose accent. */
    val LivingRoseLight = MythPalette(
        Bg = Color(0xFFFFF3F6),         // rose-white
        Surface = Color(0xFFFFFFFF),
        SurfaceMid = Color(0xFFFBE6EC),
        SurfaceHigh = Color(0xFFF3D2DC),
        Fg = Color(0xFF2A1018),         // near-black, warm
        FgMute = Color(0xFF6E4954),
        FgDim = Color(0xFF9C7480),
        Charple = Color(0xFFD6336C),    // deep rose for contrast on white
        Bok = Color(0xFF00A98C),
        Sriracha = Color(0xFFD42F54),
        Mustard = Color(0xFFB89500),
        Citron = Color(0xFF7E9400),
        Malibu = Color(0xFF0083CC),
        Julep = Color(0xFF00936E),
    )

    /** Dark Holographic HUD — a deep navy-black "cockpit glass" base
     *  with a bright holographic-cyan brand accent and line-art teal
     *  semantics, so the whole app reads like a heads-up display.
     *  Pairs with the concentric-ring [HudBackdrop] + LineArt cards. */
    val HolographicHudDark = MythPalette(
        Bg = Color(0xFF060A12),         // near-black navy
        Surface = Color(0xFF0C1420),    // glass panel
        SurfaceMid = Color(0xFF142030),
        SurfaceHigh = Color(0xFF1E3247),
        Fg = Color(0xFFCFF6FF),         // cyan-white
        FgMute = Color(0xFF7FA6B8),
        FgDim = Color(0xFF4A6577),
        Charple = Color(0xFF18E0FF),    // holographic cyan — brand accent
        Bok = Color(0xFF36F1CD),        // teal nucleus / success
        Sriracha = Color(0xFFFF4D6D),
        Mustard = Color(0xFFFFC24B),
        Citron = Color(0xFFB6FF3C),
        Malibu = Color(0xFF38B6FF),
        Julep = Color(0xFF2BE5A0),
    )

    /** Light Holographic HUD — bright cyan-tinted near-white with
     *  deep-teal line-art accents (a daylight cockpit readout). */
    val HolographicHudLight = MythPalette(
        Bg = Color(0xFFEAF6FA),         // cyan-white
        Surface = Color(0xFFFFFFFF),
        SurfaceMid = Color(0xFFDCEEF4),
        SurfaceHigh = Color(0xFFC4E0EA),
        Fg = Color(0xFF08222E),         // deep teal-black
        FgMute = Color(0xFF3F6675),
        FgDim = Color(0xFF6E909E),
        Charple = Color(0xFF0096B5),    // deep cyan for contrast on white
        Bok = Color(0xFF00A98C),
        Sriracha = Color(0xFFD42F54),
        Mustard = Color(0xFFB8860B),
        Citron = Color(0xFF5E8C00),
        Malibu = Color(0xFF0083CC),
        Julep = Color(0xFF00936E),
    )

    /** Resolve the palette for a skin + brightness. */
    fun forSkin(skin: SkinId, dark: Boolean): MythPalette = when (skin) {
        SkinId.SpatialCards -> if (dark) SpatialDark else SpatialLight
        SkinId.AuroraGlass -> if (dark) AuroraDark else AuroraLight
        SkinId.LivingRose -> if (dark) LivingRoseDark else LivingRoseLight
        SkinId.HolographicHud -> if (dark) HolographicHudDark else HolographicHudLight
    }
}
