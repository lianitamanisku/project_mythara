package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.BrightnessMode
import com.mythara.data.ThemeStore
import com.mythara.data.UiMode
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.SkinId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppearancePanelViewModel @Inject constructor(
    private val themeStore: ThemeStore,
) : ViewModel() {
    val brightnessMode: StateFlow<BrightnessMode> =
        themeStore.brightnessModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, BrightnessMode.TimeOfDay)

    val skin: StateFlow<SkinId> =
        themeStore.skinFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, SkinId.SpatialCards)

    val uiMode: StateFlow<UiMode> =
        themeStore.uiModeFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiMode.Beautiful)

    fun setBrightness(mode: BrightnessMode) {
        viewModelScope.launch { themeStore.setBrightnessMode(mode) }
    }

    fun setSkin(id: SkinId) {
        viewModelScope.launch { themeStore.setSkin(id) }
    }

    fun setUiMode(mode: UiMode) {
        viewModelScope.launch { themeStore.setUiMode(mode) }
    }
}

/**
 * Minimal appearance control — light/dark mode selector. The full
 * Appearance screen (skin picker + live preview) lands in Phase 4;
 * this panel exists so light/dark + auto-by-time-of-day are
 * controllable + testable from Phase 3.
 */
@Composable
fun AppearancePanel(vm: AppearancePanelViewModel = hiltViewModel()) {
    val mode by vm.brightnessMode.collectAsState()
    val skin by vm.skin.collectAsState()
    val uiMode by vm.uiMode.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} appearance",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )

        // ─── Brightness ───
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} brightness — 'auto' follows time of day (light by day, " +
                "dark after 6pm).",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrightnessChip("light", mode == BrightnessMode.Light) { vm.setBrightness(BrightnessMode.Light) }
            BrightnessChip("dark", mode == BrightnessMode.Dark) { vm.setBrightness(BrightnessMode.Dark) }
            BrightnessChip("system", mode == BrightnessMode.System) { vm.setBrightness(BrightnessMode.System) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrightnessChip("auto · time of day", mode == BrightnessMode.TimeOfDay) { vm.setBrightness(BrightnessMode.TimeOfDay) }
        }

        // ─── Skin ───
        Spacer(Modifier.height(14.dp))
        Text(
            text = "${Glyph.AccentBar} skin — the visual language. Each is its own palette + " +
                "backdrop: Spatial (flat), Aurora Glass, Living Rose (HR-breathing), Holographic HUD.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkinChip("spatial", skin == SkinId.SpatialCards) { vm.setSkin(SkinId.SpatialCards) }
            SkinChip("aurora", skin == SkinId.AuroraGlass) { vm.setSkin(SkinId.AuroraGlass) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkinChip("living rose", skin == SkinId.LivingRose) { vm.setSkin(SkinId.LivingRose) }
            SkinChip("hud", skin == SkinId.HolographicHud) { vm.setSkin(SkinId.HolographicHud) }
        }

        // ─── Chat UI mode ───
        Spacer(Modifier.height(14.dp))
        Text(
            text = "${Glyph.AccentBar} chat style — 'beautiful' card bubbles (default) or a " +
                "'terminal' monospace green-on-dark log.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkinChip("beautiful", uiMode == UiMode.Beautiful) { vm.setUiMode(UiMode.Beautiful) }
            SkinChip("terminal", uiMode == UiMode.Terminal) { vm.setUiMode(UiMode.Terminal) }
        }
    }
}

@Composable
private fun SkinChip(label: String, selected: Boolean, onClick: () -> Unit) =
    BrightnessChip(label, selected, onClick)

@Composable
private fun BrightnessChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = (if (selected) "${Glyph.DiamondFilled} " else "") + label,
        color = if (selected) MytharaColors.Bg else MytharaColors.Fg,
        style = MaterialTheme.typography.bodySmall.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MytharaColors.Charple else MytharaColors.Bg)
            .border(
                1.dp,
                if (selected) MytharaColors.Charple else MytharaColors.SurfaceHigh,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
