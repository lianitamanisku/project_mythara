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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.UserAliasesStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserAliasesViewModel @Inject constructor(
    private val store: UserAliasesStore,
) : ViewModel() {

    val aliases: StateFlow<List<UserAliasesStore.Alias>> =
        store.aliasesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun add(name: String, phone: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch {
            store.upsert(UserAliasesStore.Alias(name = n, phone = phone.trim()))
        }
    }

    /**
     * Bulk add from the multi-picker. Atomic write via
     * [UserAliasesStore.upsertAll] — N separate add() calls race
     * each other because each coroutine reads the same stale list
     * before any save lands, so only the last write persists.
     */
    fun addAll(aliases: List<UserAliasesStore.Alias>) {
        if (aliases.isEmpty()) return
        viewModelScope.launch { store.upsertAll(aliases) }
    }

    fun remove(name: String) {
        viewModelScope.launch { store.remove(name) }
    }

    /** Removes a specific (name, phone) row — used by the list × button. */
    fun removeOne(name: String, phone: String) {
        viewModelScope.launch { store.removeOne(name, phone) }
    }
}

/**
 * User aliases — every name / phone Mythara should treat as "you"
 * when reading imports.
 *
 * Common reason this matters: an old WhatsApp export from a previous
 * device labelled your own messages with a different display name,
 * or with "You", or with your phone number. Without telling Mythara
 * those all = you, the importer creates phantom contact profiles
 * for the user themselves and double-writes every message under
 * mirror-image facets.
 *
 * Primary path is the system contact picker — tap "pick from
 * contacts" and select the entries that represent you (your own
 * vCard, alternative numbers, work profile, etc.). Manual entry
 * covers nicknames + non-contact aliases like "You" / "Me" or
 * an emoji-only profile name.
 */
@Composable
fun UserAliasesPanel(vm: UserAliasesViewModel = hiltViewModel()) {
    val aliases by vm.aliases.collectAsState()
    var manualOpen by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var manualPhone by remember { mutableStateOf("") }
    var multiPickerOpen by remember { mutableStateOf(false) }

    val contactPicker = rememberContactPicker { picked ->
        if (picked != null) {
            vm.add(picked.displayName, picked.phone)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondOutline} user aliases (this is me)",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
            )
            Text(
                text = "${aliases.size}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} names & phone numbers Mythara should treat as YOU when reading message imports. Without this, importing an old WhatsApp export labels your own messages as a phantom contact. Pick from your contacts (your own vCard, alternate numbers, work profile) or add nicknames manually (\"You\", \"Me\", or an emoji you've used).",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )

        Spacer(Modifier.height(10.dp))

        if (aliases.isEmpty()) {
            Text(
                text = "${Glyph.CircleOutline} no aliases yet. Pick at least one before importing chat history.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            aliases.forEach { alias ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MytharaColors.Bg)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = alias.name,
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (alias.phone.isNotBlank()) {
                            Text(
                                text = alias.phone,
                                color = MytharaColors.FgDim,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    TextButton(onClick = { vm.removeOne(alias.name, alias.phone) }) {
                        Text("${Glyph.Cross}", color = MytharaColors.Sriracha)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        // Primary action — multi-select sheet. Built for the exact
        // use case of "I have 3 old numbers, pick them all at once".
        Button(
            onClick = { multiPickerOpen = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MytharaColors.Charple,
                contentColor = MytharaColors.Fg,
            ),
        ) {
            Text("${Glyph.Arrow} pick contacts (multi)")
        }
        Spacer(Modifier.height(6.dp))
        // Secondary — system single picker. Useful when you don't
        // want to grant READ_CONTACTS (system picker doesn't require
        // it; the multi-sheet does).
        TextButton(onClick = { contactPicker.launch(Unit) }) {
            Text(
                text = "${Glyph.Arrow} or pick one at a time (no extra permission)",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (!manualOpen) {
            TextButton(onClick = { manualOpen = true }) {
                Text(
                    text = "${Glyph.Arrow} or add a nickname / 'You' / 'Me' manually",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            OutlinedTextField(
                value = manualName,
                onValueChange = { manualName = it },
                singleLine = true,
                placeholder = { Text("name as it appears in your exports (e.g. 'You', 'Me', a nickname)", color = MytharaColors.FgDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = manualPhone,
                onValueChange = { manualPhone = it },
                singleLine = true,
                placeholder = { Text("optional phone (helps when exports use raw numbers)", color = MytharaColors.FgDim) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        vm.add(manualName, manualPhone)
                        manualName = ""
                        manualPhone = ""
                        manualOpen = false
                    },
                    enabled = manualName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text("${Glyph.Arrow} add")
                }
            }
        }
    }

    if (multiPickerOpen) {
        MultiContactPickerSheet(
            title = "pick the contacts that are YOU",
            onDismiss = { multiPickerOpen = false },
            onApply = { picked ->
                // Batch via addAll so we don't race ourselves —
                // see UserAliasesStore.upsertAll comment for why.
                vm.addAll(
                    picked.map { UserAliasesStore.Alias(name = it.displayName, phone = it.phone) },
                )
            },
        )
    }
}
