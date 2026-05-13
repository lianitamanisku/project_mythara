package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.AutopilotStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutopilotViewModel @Inject constructor(
    private val store: AutopilotStore,
) : ViewModel() {
    val enabled: StateFlow<Boolean> =
        store.enabledFlow().stateIn(viewModelScope, SharingStarted.Eagerly, AutopilotStore.DEFAULT_ENABLED)

    fun set(value: Boolean) {
        viewModelScope.launch { store.setEnabled(value) }
    }
}

/**
 * Master autopilot toggle. When ON, Lumi acts on her own — wake-word
 * triggers, notification auto-process, future passive cues all fire
 * the agent loop. When OFF, every "auto" path returns early at
 * dispatch; the user has to explicitly tap the mic or type to talk
 * to Lumi.
 *
 * Explicitly NOT a wake-word toggle and NOT a notification-listener
 * toggle — those have their own panels. Autopilot is the umbrella
 * pause button.
 *
 * Foreground services (LumiListenerService, etc.) keep running while
 * autopilot is off — so flipping it back on doesn't pay the
 * cold-start cost. The flag is checked at dispatch time, not at
 * subscription time.
 */
@Composable
fun AutopilotPanel(vm: AutopilotViewModel = hiltViewModel()) {
    val on by vm.enabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} autopilot",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.set(!on) }) {
                Text(
                    text = if (on) Glyph.CircleFilled else Glyph.CircleOutline,
                    color = if (on) MytharaColors.Bok else MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "  ${if (on) "autopilot on" else "autopilot off"}",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} when on, Lumi responds on her own — wake word, notifications, anything passive — and keeps running in the background and on the lock screen. When off, only explicit taps (mic, typed message) talk to Lumi. The wake-word service and notification listener keep running so flipping autopilot back on is instant.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )
    }
}
