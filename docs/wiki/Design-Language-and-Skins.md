# Design Language & Skins

Mythara has a **multi-skin theme engine**: every screen reads its colours, surface treatment, backdrop, and motion profile from a single `LocalMythPalette` + `LocalSkinSpec` CompositionLocal pair. Skins ship as data — adding one is ~80 LOC.

## Visual languages

| Skin | Backdrop | Surface | Use case |
|---|---|---|---|
| **Spatial** *(default)* | flat gradient | solid cards | low-contrast everyday, lowest GPU cost |
| **Aurora Glass** | animated gradient | translucent + blur | aesthetic-first, API 31+ only |
| **Living Rose** | breath-pulsing geometric rose petals | solid cards | brand-forward, HR-reactive via `LiveWallpaperPulseSink` |
| **Holographic HUD** | line-art + concentric rings | line-bordered | sci-fi, dashboard-style |

Each skin has light + dark variants AND an "auto" brightness mode that flips at 06:00 / 18:00 local time.

## Brightness modes

- **Light** — every skin's light palette
- **Dark** — every skin's dark palette
- **System** — follows OS theme
- **Auto · time of day** — boundary-only flip at 06:00 / 18:00

## Chat surface modes

- **Beautiful** *(default)* — themed card bubbles with markdown rendering, glyph-prefixed sender (`◆` user / `◇` agent)
- **Terminal** — opt-in monospace green-on-near-black log line per message, glyph-prefixed, full-width. Same message tree, swapped look. (For users who think AIs should look like `bash`.)

## Components every skin gets for free

- **`MythScaffold`** — top sliver with glyph + title + back affordance, bottom edge-glow band, breathing alpha animation
- **`MythCard`** — surface-treatment-aware card (solid / translucent / line-bordered)
- **`MythBackdrop`** — full-screen layer behind the NavHost, switched on `spec.backdrop`
- **`MythNavTransitions`** — three families: sibling (horizontal slide + fade 220 ms), drilldown (Charple curtain flash 180 ms), modal (scale 0.96 → 1.0 + fade)

## Brand primitives

- **`RoseGeometry`** (`ui/amulet/RoseGeometry.kt`) — petal + hex constants shared with the live wallpaper + watch face
- **`Glyph`** (`ui/theme/Glyphs.kt`) — `◆ ◇ ● ✓ × ⋯ ⟳ ▌ │ → ← ⇣ ○ ◉ ┃` + more — the glyph set every screen draws from
- **`Wordmark`** (`ui/theme/Wordmark.kt`) — animated MYTHARA wordmark + inline variant
- **`Type`** (`ui/theme/Type.kt`) — JetBrainsMono throughout, weight + size scales

## Adding a new skin

1. **Palette** — `app/src/main/kotlin/com/mythara/ui/theme/PaletteCatalog.kt`
   ```kotlin
   val CrtPaletteDark = MythPalette(
       Bg = Color(0xFF001100),
       Surface = Color(0xFF002200),
       Charple = Color(0xFF00FF00),
       Malibu = Color(0xFF00FFCC),
       // ... rest
   )
   ```
2. **Spec** — `SkinSpec.kt`
   ```kotlin
   val CrtSpec = SkinSpec(
       cornerRadius = 0.dp,
       blurRadius = 0.dp,
       surfaceTreatment = SurfaceTreatment.LineArt,
       backdrop = BackdropKind.Crt,
       accentIntensity = 0.95f,
   )
   ```
3. **Backdrop** — extend `MythBackdrop` when `spec.backdrop == BackdropKind.Crt` to render scanlines, phosphor glow, raster bands.
4. **Catalog** — register in `SkinCatalog.kt` + add it to the Appearance picker.

Most skin-distinctive identity lives in (a) the backdrop and (b) the `MythCard` surface treatment. **Screens don't change.**

## Where to dig

- `ui/theme/MythPalette.kt` — palette + CompositionLocal
- `ui/theme/SkinSpec.kt` — spec data class + enums
- `ui/theme/Theme.kt` — `MytharaTheme` host
- `ui/theme/MythBackdrop.kt` — backdrop renderer (per-skin branches)
- `ui/theme/PaletteCatalog.kt` — 8 palettes (4 skins × {light, dark})
- `ui/theme/Color.kt` — `MytharaColors` accessors (CompositionLocal-backed)
- `ui/scaffold/MytharaScaffold.kt` — shared header/footer chrome
- `ui/scaffold/MytharaNavTransitions.kt` — route-family transitions

See also: [Architecture](Architecture), [Mobile UX Patterns](Mobile-UX-Patterns).
