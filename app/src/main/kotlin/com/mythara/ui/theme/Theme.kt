package com.mythara.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.mythara.data.BrightnessMode
import com.mythara.data.ThemeStore
import com.mythara.data.UiMode
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint so [MytharaTheme] — a plain @Composable called from
 * an Activity AND a Service (LockscreenIslandService) — can reach the
 * singleton [ThemeStore] without hiltViewModel() (unavailable in a
 * Service compose host).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThemeStoreEntryPoint {
    fun themeStore(): ThemeStore
}

/** Active chat rendering aesthetic, provided by [MytharaTheme].
 *  Defaults to [UiMode.Beautiful]; the chat surface reads it to swap
 *  between card bubbles and terminal log lines. */
val LocalUiMode = staticCompositionLocalOf { UiMode.Beautiful }

/**
 * The single MaterialTheme wrapper, now skin + brightness aware.
 *
 * Resolves the active [SkinId] + [BrightnessMode] from [ThemeStore],
 * turns brightness into isDark (incl. auto-by-time-of-day via
 * [rememberIsDark]), picks the matching [MythPalette] + [SkinSpec],
 * and provides them through [LocalMythPalette] / [LocalSkinSpec] so the
 * theme-aware [MytharaColors] accessors + the scaffold/backdrop layers
 * track the live theme. Material 3 colour roles are mapped from the
 * palette so default Material components stay coherent with the
 * hand-styled surfaces.
 */
@Composable
fun MytharaTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val themeStore = remember(ctx) {
        EntryPointAccessors.fromApplication(
            ctx.applicationContext,
            ThemeStoreEntryPoint::class.java,
        ).themeStore()
    }

    val skin by themeStore.skinFlow().collectAsState(initial = SkinId.SpatialCards)
    val brightness by themeStore.brightnessModeFlow()
        .collectAsState(initial = BrightnessMode.TimeOfDay)
    val uiMode by themeStore.uiModeFlow().collectAsState(initial = UiMode.Beautiful)

    val isDark = rememberIsDark(brightness)
    val palette = PaletteCatalog.forSkin(skin, isDark)
    val spec = SkinCatalog.forSkin(skin)

    CompositionLocalProvider(
        LocalMythPalette provides palette,
        LocalSkinSpec provides spec,
        LocalUiMode provides uiMode,
    ) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(isDark),
            typography = MytharaTypography,
            content = content,
        )
    }
}

/** Map a [MythPalette] to a Material 3 ColorScheme. The role mapping
 *  matches the legacy one (primary=Charple, secondary=Bok, …) so
 *  Material components render coherently with the brand surfaces. */
private fun MythPalette.toColorScheme(dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = Charple, onPrimary = Fg,
            secondary = Bok, onSecondary = Bg,
            tertiary = Malibu,
            background = Bg, onBackground = Fg,
            surface = Surface, onSurface = Fg,
            surfaceVariant = SurfaceMid, onSurfaceVariant = FgMute,
            outline = SurfaceHigh, outlineVariant = SurfaceMid,
            error = Sriracha, onError = Fg,
        )
    } else {
        lightColorScheme(
            primary = Charple, onPrimary = Surface,
            secondary = Bok, onSecondary = Surface,
            tertiary = Malibu,
            background = Bg, onBackground = Fg,
            surface = Surface, onSurface = Fg,
            surfaceVariant = SurfaceMid, onSurfaceVariant = FgMute,
            outline = SurfaceHigh, outlineVariant = SurfaceMid,
            error = Sriracha, onError = Surface,
        )
    }
