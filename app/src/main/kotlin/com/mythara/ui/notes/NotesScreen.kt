package com.mythara.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactAnalyticsBuilder
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.memory.HeartbeatSyncer
import com.mythara.memory.Tier
import com.mythara.persona.SelfPersonaBuilder
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * "Notes" — a quick-capture surface tucked behind the hidden Secret
 * menu (About → triple-tap the wordmark → unlock).
 *
 * Paste anything you copied, or jot a thought, and file it as one of:
 *  - **General memory** — a durable, embedded fact Lumi actively
 *    recalls on future turns (same path as the agent's `remember` tool).
 *  - **About a person** — attached to a contact: it's added to that
 *    person's profile notes (which feed the Gemma relationship
 *    analysis) AND stored as a contact-facetted memory.
 *  - **Quick note** — a lighter jotting; stored + findable, but not a
 *    hard fact.
 *
 * Everything written here is embedded (when the local embedder is
 * loaded) so semantic recall surfaces it, and an immediate cross-device
 * sync is kicked off.
 */
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
    private val contactRepo: ContactProfileRepository,
    private val analyticsBuilder: ContactAnalyticsBuilder,
    private val selfPersonaBuilder: SelfPersonaBuilder,
    /** dagger.Lazy — HeartbeatSyncer transitively pulls in the agent stack. */
    private val heartbeat: dagger.Lazy<HeartbeatSyncer>,
) : ViewModel() {

    enum class NoteMode { Memory, Person, Note }

    data class SavedNote(
        val content: String,
        val mode: NoteMode,
        val contactName: String?,
        val tsMillis: Long,
    )

    data class Ui(
        val contacts: List<ContactProfileRow> = emptyList(),
        val recent: List<SavedNote> = emptyList(),
        val saving: Boolean = false,
        val lastResult: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            contactRepo.dao.observeAll().collect { list ->
                _ui.value = _ui.value.copy(contacts = list)
            }
        }
        reloadRecent()
    }

    private fun reloadRecent() {
        viewModelScope.launch {
            val recent = withContext(Dispatchers.IO) {
                runCatching { vault.listByTier(Tier.Semantic, limit = 300) }
                    .getOrDefault(emptyList())
                    .filter { "src:user-note" in vault.decodeFacets(it) }
                    .sortedByDescending { it.tsMillis }
                    .take(20)
                    .map { e ->
                        val facets = vault.decodeFacets(e)
                        val mode = when {
                            "note-mode:person" in facets -> NoteMode.Person
                            "note-mode:note" in facets -> NoteMode.Note
                            else -> NoteMode.Memory
                        }
                        val contact = facets.firstOrNull { it.startsWith("contact:") }
                            ?.removePrefix("contact:")
                        SavedNote(e.content, mode, contact, e.tsMillis)
                    }
            }
            _ui.value = _ui.value.copy(recent = recent)
        }
    }

    fun clearResult() {
        _ui.value = _ui.value.copy(lastResult = null)
    }

    fun save(text: String, mode: NoteMode, contact: ContactProfileRow?) {
        val content = text.trim()
        if (content.isEmpty() || _ui.value.saving) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(saving = true, lastResult = null)
            val result = withContext(Dispatchers.IO) { doSave(content, mode, contact) }
            _ui.value = _ui.value.copy(saving = false, lastResult = result)
            reloadRecent()
            runCatching { heartbeat.get().fireNow() }
            // The note is durably in the vault now — force a self-persona
            // rebuild so the About Me Big Five (and the connected-people
            // ranking it feeds) reflect it immediately, rather than
            // waiting for the daily worker. force=true bypasses the 20h
            // freshness gate; the rebuild self-serialises on its own lock.
            runCatching { selfPersonaBuilder.rebuild(force = true) }
        }
    }

    private suspend fun doSave(
        content: String,
        mode: NoteMode,
        contact: ContactProfileRow?,
    ): String {
        val embedding = if (embedder.isReady()) {
            runCatching { embedder.embed(content) }.getOrNull()
        } else {
            null
        }
        val embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null
        return when (mode) {
            NoteMode.Memory -> {
                runCatching {
                    vault.add(
                        content = content,
                        tier = Tier.Semantic,
                        src = "user:note",
                        facets = listOf("kind:user-stated", "src:user-note", "note-mode:memory"),
                        embedding = embedding,
                        embModel = embModel,
                        conf = 1.0,
                    )
                }
                "Saved as a general memory — Lumi will recall it, and your About Me profile is rebuilding."
            }
            NoteMode.Note -> {
                runCatching {
                    vault.add(
                        content = content,
                        tier = Tier.Semantic,
                        src = "user:note",
                        facets = listOf("kind:user-note", "src:user-note", "note-mode:note"),
                        embedding = embedding,
                        embModel = embModel,
                        conf = 0.9,
                    )
                }
                "Note saved — your About Me profile is rebuilding."
            }
            NoteMode.Person -> {
                if (contact == null) return "Pick a person first."
                runCatching {
                    vault.add(
                        content = content,
                        tier = Tier.Semantic,
                        src = "user:note",
                        facets = listOf(
                            "kind:user-stated",
                            "src:user-note",
                            "note-mode:person",
                            "contact:${contact.displayName}",
                        ),
                        embedding = embedding,
                        embModel = embModel,
                        conf = 1.0,
                    )
                }
                // Append to the contact's profile notes — these feed the
                // Gemma relationship analysis (and override its guesses).
                val existing = runCatching { contactRepo.dao.byKey(contact.nameKey) }.getOrNull()
                val merged = listOfNotNull(
                    existing?.userNotes?.takeIf { it.isNotBlank() },
                    content,
                ).joinToString("\n")
                runCatching { contactRepo.dao.updateUserNotes(contact.nameKey, merged) }
                // Re-run the relationship analysis for just this contact.
                runCatching { analyticsBuilder.rebuildContact(contact.nameKey) }
                "Saved to ${contact.displayName}'s profile — relationship analysis updating."
            }
        }
    }
}

@Composable
fun NotesScreen(
    onBack: () -> Unit,
    vm: NotesViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val clipboard = LocalClipboardManager.current

    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(NotesViewModel.NoteMode.Memory) }
    var selectedContactKey by remember { mutableStateOf<String?>(null) }
    val selectedContact = ui.contacts.firstOrNull { it.nameKey == selectedContactKey }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "NOTES",
            style = MaterialTheme.typography.headlineSmall.copy(
                color = MytharaColors.Fg, letterSpacing = 3.sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${Glyph.AccentBar} paste anything you copied, or jot a thought — file it as a memory, a note about a person, or a quick note.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )

        Spacer(Modifier.height(16.dp))

        // ---- capture field ----------------------------------------
        Panel("capture") {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (ui.lastResult != null) vm.clearResult()
                },
                placeholder = {
                    Text(
                        "paste or type — e.g. \"Priya's daughter just started college\", \"wifi password is hunter2\"…",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val clip = clipboard.getText()?.text
                    if (!clip.isNullOrBlank()) {
                        text = if (text.isBlank()) clip else "$text\n$clip"
                        if (ui.lastResult != null) vm.clearResult()
                    }
                }) {
                    Text("${Glyph.DescendingArrow} paste clipboard", color = MytharaColors.Bok, style = MaterialTheme.typography.bodySmall)
                }
                if (text.isNotBlank()) {
                    TextButton(onClick = { text = "" }) {
                        Text("${Glyph.Cross} clear", color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- destination ------------------------------------------
        Panel("file it as") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("memory", mode == NotesViewModel.NoteMode.Memory) {
                    mode = NotesViewModel.NoteMode.Memory
                }
                ModeChip("a person", mode == NotesViewModel.NoteMode.Person) {
                    mode = NotesViewModel.NoteMode.Person
                }
                ModeChip("note", mode == NotesViewModel.NoteMode.Note) {
                    mode = NotesViewModel.NoteMode.Note
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (mode) {
                    NotesViewModel.NoteMode.Memory ->
                        "A durable memory Lumi actively recalls on future turns."
                    NotesViewModel.NoteMode.Person ->
                        "Attached to a contact's profile — it feeds their relationship analysis."
                    NotesViewModel.NoteMode.Note ->
                        "A lighter jotting — stored and findable, but not a hard fact."
                },
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )

            if (mode == NotesViewModel.NoteMode.Person) {
                Spacer(Modifier.height(10.dp))
                if (ui.contacts.isEmpty()) {
                    Text(
                        "No contacts learned yet — Mythara picks people up as you message them.",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = "who is this about?",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ui.contacts, key = { it.nameKey }) { c ->
                            ContactChip(
                                name = c.displayName,
                                selected = c.nameKey == selectedContactKey,
                                onClick = {
                                    selectedContactKey =
                                        if (selectedContactKey == c.nameKey) null else c.nameKey
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val canSave = text.isNotBlank() && !ui.saving &&
            (mode != NotesViewModel.NoteMode.Person || selectedContact != null)
        Button(
            onClick = {
                vm.save(text, mode, selectedContact)
                text = ""
                selectedContactKey = null
            },
            enabled = canSave,
            colors = ButtonDefaults.buttonColors(
                containerColor = MytharaColors.Charple,
                contentColor = MytharaColors.Fg,
                disabledContainerColor = MytharaColors.Surface,
                disabledContentColor = MytharaColors.FgDim,
            ),
        ) {
            Text(if (ui.saving) "${Glyph.Ellipsis} saving" else "${Glyph.Check} save note")
        }

        ui.lastResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${Glyph.DiamondFilled} $result",
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- recent ------------------------------------------------
        Panel("recent notes") {
            if (ui.recent.isEmpty()) {
                Text(
                    "Nothing yet — your captured notes show up here.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                ui.recent.forEachIndexed { i, note ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    RecentNoteRow(note)
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun RecentNoteRow(note: NotesViewModel.SavedNote) {
    val accent = when (note.mode) {
        NotesViewModel.NoteMode.Memory -> MytharaColors.Charple
        NotesViewModel.NoteMode.Person -> MytharaColors.Bok
        NotesViewModel.NoteMode.Note -> MytharaColors.FgMute
    }
    val label = when (note.mode) {
        NotesViewModel.NoteMode.Memory -> "memory"
        NotesViewModel.NoteMode.Person -> note.contactName ?: "person"
        NotesViewModel.NoteMode.Note -> "note"
    }
    Row {
        Text(
            "${Glyph.DiamondFilled} ",
            style = MaterialTheme.typography.bodySmall.copy(color = accent),
        )
        Column {
            Text(
                text = note.content,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "$label · ${RELATIVE_FMT.format(Date(note.tsMillis))}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MytharaColors.Charple else MytharaColors.Surface)
            .border(
                1.dp,
                if (selected) MytharaColors.Charple else MytharaColors.SurfaceHigh,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (selected) MytharaColors.Fg else MytharaColors.FgMute,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ContactChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MytharaColors.Bok else MytharaColors.Surface)
            .border(
                1.dp,
                if (selected) MytharaColors.Bok else MytharaColors.SurfaceHigh,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = name,
            color = if (selected) MytharaColors.Bg else MytharaColors.Fg,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Panel(title: String, body: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} $title",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        body()
    }
}

private val RELATIVE_FMT = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
