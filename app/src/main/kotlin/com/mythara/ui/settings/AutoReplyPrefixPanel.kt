package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.AutoReplyPrefixStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutoReplyPrefixViewModel @Inject constructor(
    private val store: AutoReplyPrefixStore,
) : ViewModel() {
    val prefix: StateFlow<String> =
        store.prefixFlow().stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun set(value: String) {
        viewModelScope.launch { store.setPrefix(value) }
    }
}

/**
 * Optional auto-reply prefix. Whatever the user types here gets
 * prepended to every message Mythara sends through SMS/WhatsApp
 * during an autopilot auto-reply turn. Blank = no prefix.
 *
 * Common use case: marking agent-sent messages so recipients can
 * distinguish them from messages the user typed manually — e.g.
 * "LUMI (autopilot): I'll be there at 5".
 *
 * Saves on each keystroke (DataStore preferences write is cheap)
 * so the user doesn't have to remember to tap a save button.
 */
@Composable
fun AutoReplyPrefixPanel(vm: AutoReplyPrefixViewModel = hiltViewModel()) {
    val stored by vm.prefix.collectAsState()
    var input by remember { mutableStateOf(stored) }
    // Re-sync local input field when the persisted value changes
    // (e.g. another process / restored DataStore on cold start).
    LaunchedEffect(stored) { if (stored != input) input = stored }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} auto-reply prefix",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} optional text that gets prepended to every SMS / WhatsApp message Mythara sends during an autopilot auto-reply. Leave blank to send messages as-is. Use this to mark agent-sent messages so recipients can tell them apart from your own.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                vm.set(it)
            },
            singleLine = true,
            placeholder = { Text("LUMI (autopilot): ", color = MytharaColors.FgDim) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MytharaColors.Fg,
                unfocusedTextColor = MytharaColors.Fg,
                focusedBorderColor = MytharaColors.Charple,
                unfocusedBorderColor = MytharaColors.SurfaceHigh,
                cursorColor = MytharaColors.Charple,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Clear button — only when a non-blank prefix is set. Tapping
        // it empties the prefix so messages send with no prepend.
        if (input.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    input = ""
                    vm.set("")
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Surface,
                    contentColor = MytharaColors.FgMute,
                ),
            ) {
                Text("${Glyph.Cross} clear")
            }
        }
    }
}
