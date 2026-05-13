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
import com.mythara.data.AutoTriageStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutoTriageViewModel @Inject constructor(
    private val store: AutoTriageStore,
) : ViewModel() {
    val enabled: StateFlow<Boolean> =
        store.enabledFlow().stateIn(viewModelScope, SharingStarted.Eagerly, AutoTriageStore.DEFAULT_ENABLED)

    fun set(value: Boolean) { viewModelScope.launch { store.setEnabled(value) } }
}

/**
 * Toggle for the "smart triage" behaviour on incoming messages from
 * non-favorites. Default ON because the user asked for this.
 *
 * Behaviour described in [com.mythara.data.AutoTriageStore] doc;
 * panel UX matches the other autopilot-family panels (Bok-bordered
 * when on so the user can scan Settings and see what's live).
 */
@Composable
fun AutoTriagePanel(vm: AutoTriageViewModel = hiltViewModel()) {
    val on by vm.enabled.collectAsState()

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
                text = "${if (on) Glyph.DiamondFilled else Glyph.DiamondOutline} smart triage (non-favorites)",
                style = MaterialTheme.typography.labelLarge.copy(color = titleColor),
            )
            Text(
                text = if (on) "ON" else "OFF",
                color = if (on) MytharaColors.Bok else MytharaColors.FgMute,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
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
            text = "${Glyph.AccentBar} when ON, Lumi also analyses messages from people NOT in your favorites. She decides if a reply is needed (skips marketing, OTPs, shipping updates, group chats, anything ambiguous) and when she does reply, she mirrors the sender's tone. URLs from unknown senders are NEVER opened — strict security. Same isolation rules as favorite auto-replies: never leaks info from one conversation into another. Requires master autopilot to be ON.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )
    }
}
