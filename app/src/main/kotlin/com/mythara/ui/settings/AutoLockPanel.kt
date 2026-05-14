package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.auth.AuthSettings
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutoLockPanelViewModel @Inject constructor(
    private val store: AuthSettings,
) : ViewModel() {
    val timeoutMs: StateFlow<Long> = store.timeoutFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), AuthSettings.DEFAULT_TIMEOUT_MS)

    val lockEnabled: StateFlow<Boolean> = store.lockEnabledFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), true)

    fun setTimeout(ms: Long) {
        viewModelScope.launch { store.setTimeoutMs(ms) }
    }

    fun setLockEnabled(value: Boolean) {
        viewModelScope.launch { store.setLockEnabled(value) }
    }
}

/**
 * Auto-lock timeout picker. The session re-locks (next foreground
 * transition requires biometric prompt) after the app has been in
 * background for at least the configured duration. "Immediate"
 * locks every time the app loses foreground; "never" only
 * re-locks when the OS kills the process.
 *
 * 5 minutes is the default — covers checking a notification or
 * replying to a text without forcing a re-auth, while still
 * re-locking an unattended phone before someone else grabs it.
 */
@Composable
fun AutoLockPanel(vm: AutoLockPanelViewModel = hiltViewModel()) {
    val timeoutMs by vm.timeoutMs.collectAsState()
    val lockEnabled by vm.lockEnabled.collectAsState()
    var open by remember { mutableStateOf(false) }
    val currentLabel = AuthSettings.PRESETS.firstOrNull { it.first == timeoutMs }?.second
        ?: "${timeoutMs}ms (custom)"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} biometric lock",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        // Master switch — when off, the app skips the AuthGate entirely
        // (including cold start). For trusted-environment / testing use.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.setLockEnabled(!lockEnabled) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (lockEnabled) Glyph.CircleFilled else Glyph.CircleOutline,
                color = if (lockEnabled) MytharaColors.Charple else MytharaColors.FgDim,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = if (lockEnabled) {
                    "lock enabled — biometric required to enter"
                } else {
                    "lock DISABLED — app opens straight to chat"
                },
                color = if (lockEnabled) MytharaColors.Fg else MytharaColors.Sriracha,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))

        Text(
            text = "auto-lock after",
            style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.FgDim),
        )
        Spacer(Modifier.height(6.dp))

        Box {
            Button(
                onClick = { open = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Surface,
                    contentColor = MytharaColors.Fg,
                ),
            ) {
                Text("$currentLabel  ${Glyph.Arrow}")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                AuthSettings.PRESETS.forEach { (ms, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(label, color = MytharaColors.Fg)
                        },
                        onClick = {
                            vm.setTimeout(ms)
                            open = false
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} Mythara locks (requires biometric to re-enter) when it's been in the background for this long. Process death always re-locks regardless. Background services (agent loop, notification listener, accessibility) keep running while locked — only the chat UI is gated.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
