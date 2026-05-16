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
import com.mythara.secret.observe.vosk.VoskModelStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.wake.MytharaWakeListenerService
import com.mythara.wake.WakeListenerSettings
import com.mythara.wake.WakeListenerStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the always-listen panel. Owns:
 *  - the persisted toggle (DataStore)
 *  - the runtime service state flow
 *  - asset check (Vosk model present?) — without the model the
 *    listener can't transcribe anything
 *  - mic-permission state
 */
@HiltViewModel
class WakeListenerPanelViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: WakeListenerSettings,
    private val store: WakeListenerStore,
    private val voskModelStore: VoskModelStore,
) : ViewModel() {

    data class State(
        val enabled: Boolean = false,
        val serviceState: WakeListenerStore.State = WakeListenerStore.State.Idle,
        val micGranted: Boolean = false,
        val voskReady: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refreshPermission()
        refreshVoskReady()
        viewModelScope.launch {
            settings.enabledFlow().collect { e -> _state.update { it.copy(enabled = e) } }
        }
        viewModelScope.launch {
            store.state.collect { s -> _state.update { it.copy(serviceState = s) } }
        }
        viewModelScope.launch {
            voskModelStore.state.collect { _ -> refreshVoskReady() }
        }
    }

    fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        _state.update { it.copy(micGranted = granted) }
    }

    private fun refreshVoskReady() {
        _state.update { it.copy(voskReady = voskModelStore.isActiveReady()) }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            settings.setEnabled(value)
            val intent = Intent(ctx, MytharaWakeListenerService::class.java)
            if (value) {
                if (!_state.value.voskReady) return@launch
                if (!_state.value.micGranted) return@launch
                ContextCompat.startForegroundService(ctx, intent)
            } else {
                ctx.stopService(intent)
            }
        }
    }
}

/**
 * Settings panel for the always-listen path. Sits in main Settings —
 * deliberately not behind the Secret-mode gate, because the privacy
 * contract here is much tighter than Observe (no transcripts ever
 * saved, no semantic extraction, no GitHub sync; queries leave only
 * via the normal MiniMax chat flow).
 */
@Composable
fun WakeListenerPanel(vm: WakeListenerPanelViewModel = hiltViewModel()) {
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
            text = "${Glyph.DiamondOutline} 'Hey Mythara' always-listen (experimental)",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        // Status pill — prioritise the first thing that needs fixing.
        val (statusGlyph, statusColor, statusText) = when {
            !state.voskReady -> Triple(
                Glyph.Cross, MytharaColors.Mustard,
                "Vosk model not downloaded — open Secret Settings to fetch it",
            )
            !state.micGranted -> Triple(
                Glyph.CircleOutline, MytharaColors.FgMute,
                "RECORD_AUDIO permission needed",
            )
            state.serviceState is WakeListenerStore.State.Listening -> Triple(
                Glyph.Dot, MytharaColors.Julep, "listening for 'Hey Mythara …'",
            )
            state.serviceState is WakeListenerStore.State.Starting -> Triple(
                Glyph.Ellipsis, MytharaColors.Citron, "starting…",
            )
            state.serviceState is WakeListenerStore.State.Stopping -> Triple(
                Glyph.Ellipsis, MytharaColors.FgDim, "stopping…",
            )
            state.serviceState is WakeListenerStore.State.Error -> Triple(
                Glyph.Cross, MytharaColors.Sriracha,
                "error: ${(state.serviceState as WakeListenerStore.State.Error).message}",
            )
            else -> Triple(Glyph.CircleOutline, MytharaColors.FgMute, "off")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(statusGlyph, color = statusColor, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(end = 6.dp))
            Text(
                statusText, color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = state.voskReady) {
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
                text = "always-on (foreground service)",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} say \"Hey Mythara, <your question>\" — the listener transcribes locally with Vosk and submits only the matched query to MiniMax. Non-matching speech is dropped on the floor (never saved, never synced). Mutually exclusive with Observe mode at the mic hardware layer. **Recognition is unreliable** — Vosk's en-us small model doesn't know the proper noun \"Mythara\" and frequently mishears it (\"a me\", \"hello me\", etc.). The detector tries several common mishears but misses some. Best path until we add a Vosk grammar constraint: tap the mic button in chat and use push-to-talk instead.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
