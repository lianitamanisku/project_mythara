package com.mythara.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.wake.LumiWakeWordController
import com.mythara.wake.LumiWakeWordService
import com.mythara.wake.WakeWordSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the wake-word panel. Owns:
 *  - the persisted "Listen for Lumi" toggle (DataStore)
 *  - the runtime controller [LumiWakeWordController.state]
 *  - asset-presence detection (paranoid — assets are committed in the repo)
 *  - permission state (the toggle is a no-op without RECORD_AUDIO)
 *
 * Starting/stopping the foreground service goes through
 * [LumiWakeWordService] start/stop intents.
 */
@HiltViewModel
class WakeWordPanelViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: WakeWordSettings,
    private val controller: LumiWakeWordController,
) : ViewModel() {

    data class State(
        val enabled: Boolean = false,
        val controllerState: LumiWakeWordController.State = LumiWakeWordController.State.Idle,
        val micGranted: Boolean = false,
        val assetsPresent: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refreshPermission()
        _state.update { it.copy(assetsPresent = controller.assetsPresent()) }
        viewModelScope.launch {
            settings.enabledFlow().collect { e ->
                _state.update { it.copy(enabled = e) }
            }
        }
        viewModelScope.launch {
            controller.state.collect { s ->
                _state.update { it.copy(controllerState = s) }
            }
        }
    }

    fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        _state.update { it.copy(micGranted = granted) }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            settings.setEnabled(value)
            val intent = Intent(ctx, LumiWakeWordService::class.java)
            if (value) {
                if (!_state.value.assetsPresent) return@launch
                if (!_state.value.micGranted) return@launch
                ContextCompat.startForegroundService(ctx, intent)
            } else {
                ctx.stopService(intent)
            }
        }
    }
}

/**
 * Panel composable. Renders inside [SettingsScreen] between Memory Sync
 * and About. Single toggle + status pill — no per-user configuration
 * since the openWakeWord ONNX bundle ships pre-bundled.
 */
@Composable
fun WakeWordPanel(vm: WakeWordPanelViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.refreshPermission() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} wake word",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        // Status pill
        val (statusGlyph, statusColor, statusText) = when {
            !state.assetsPresent -> Triple(Glyph.Cross, MytharaColors.Mustard,
                "openWakeWord ONNX files missing in assets/")
            !state.micGranted -> Triple(Glyph.CircleOutline, MytharaColors.FgMute,
                "RECORD_AUDIO permission needed")
            state.controllerState is LumiWakeWordController.State.Listening -> Triple(
                Glyph.Dot, MytharaColors.Julep,
                "listening for '${LumiWakeWordController.TRIGGER_PHRASE}'")
            state.controllerState is LumiWakeWordController.State.Error -> Triple(
                Glyph.Cross, MytharaColors.Sriracha,
                "error: ${(state.controllerState as LumiWakeWordController.State.Error).message}")
            else -> Triple(Glyph.CircleOutline, MytharaColors.FgMute, "off")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statusGlyph, color = statusColor, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(end = 6.dp))
            Text(statusText, color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(10.dp))

        // Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = state.assetsPresent) {
                    if (!state.micGranted) {
                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@clickable
                    }
                    vm.setEnabled(!state.enabled)
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.enabled) Glyph.CircleFilled else Glyph.CircleOutline,
                color = if (state.enabled) MytharaColors.Charple else MytharaColors.FgMute,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "always-on listener (foreground service)",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} trigger phrase: '${LumiWakeWordController.TRIGGER_PHRASE}' " +
                "→ ${LumiWakeWordController.AGENT_NAME} takes over. Pre-trained openWakeWord model, no signup. " +
                "Mutually exclusive with Observe mode (only one mic listener at a time). " +
                "Wake events log to logcat as `Mythara/Wake`; chat-handoff polish ships next.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
