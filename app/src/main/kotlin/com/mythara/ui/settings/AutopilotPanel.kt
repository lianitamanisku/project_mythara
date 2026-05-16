package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * Master autopilot toggle. When ON, Mythara acts on her own — wake-word
 * triggers, notification auto-process, future passive cues all fire
 * the agent loop. When OFF, every "auto" path returns early at
 * dispatch; the user has to explicitly tap the mic or type to talk
 * to Mythara.
 *
 * Explicitly NOT a wake-word toggle and NOT a notification-listener
 * toggle — those have their own panels. Autopilot is the umbrella
 * pause button.
 *
 * Foreground services (MytharaWakeListenerService, etc.) keep running while
 * autopilot is off — so flipping it back on doesn't pay the
 * cold-start cost. The flag is checked at dispatch time, not at
 * subscription time.
 */
@Composable
fun AutopilotPanel(vm: AutopilotViewModel = hiltViewModel()) {
    val on by vm.enabled.collectAsState()

    // Border + glyph turn Bok when on so it's instantly findable in
    // the Settings scroll. Off state is muted so the user sees "this
    // is a thing but it's currently paused".
    val borderColor = if (on) MytharaColors.Bok else MytharaColors.SurfaceHigh
    val titleColor = if (on) MytharaColors.Bok else MytharaColors.FgMute

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(if (on) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${if (on) Glyph.DiamondFilled else Glyph.DiamondOutline} autopilot",
                style = MaterialTheme.typography.labelLarge.copy(color = titleColor),
            )
            Text(
                text = if (on) "ON" else "OFF",
                color = if (on) MytharaColors.Bok else MytharaColors.FgMute,
                style = MaterialTheme.typography.labelLarge,
            )
        }
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
                Text(
                    text = "  tap to turn ${if (on) "off" else "on"}",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} when ON, Mythara acts on her own — wake word, auto-process notifications, proactively books calendar events when meetings are arranged, fires every other side-effect tool without asking. Keeps running in the background and on the lock screen. When OFF, only explicit taps (mic, typed message) talk to Mythara — the agent goes passive. Services stay alive either way so flipping back on is instant.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )
    }
}
