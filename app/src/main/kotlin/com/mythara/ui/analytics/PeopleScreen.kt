package com.mythara.ui.analytics

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactAnalyticsBuilder
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val repo: ContactProfileRepository,
    private val builder: ContactAnalyticsBuilder,
) : ViewModel() {
    val profiles: StateFlow<List<ContactProfileRow>> =
        repo.dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastReport = MutableStateFlow<ContactAnalyticsBuilder.BuildReport?>(null)
    val lastReport: StateFlow<ContactAnalyticsBuilder.BuildReport?> = _lastReport.asStateFlow()

    fun rebuild(force: Boolean) {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val report = runCatching { builder.rebuildAll(force = force) }.getOrNull()
            _lastReport.value = report
            _refreshing.value = false
        }
    }

    private val _cleanupStatus = MutableStateFlow<String?>(null)
    val cleanupStatus: StateFlow<String?> = _cleanupStatus.asStateFlow()

    /**
     * Standalone cleanup pass — for the "clean up phantom profiles"
     * button. Useful when the user has just added new aliases and
     * wants the old mis-attributed data gone immediately, without
     * waiting for the next rebuild.
     */
    fun cleanupNow() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val report = runCatching { builder.cleanupAliasMisattributions() }.getOrNull()
            _cleanupStatus.value = if (report == null) "${Glyph.Cross} cleanup failed"
            else "${Glyph.Check} cleaned ${report.cleanedProfiles} phantom profile(s) + ${report.cleanedVaultRows} vault row(s)"
            _refreshing.value = false
        }
    }

    /**
     * Save the user-authored notes for a contact. Uses the DAO's
     * partial-update path so we don't race the analytics builder
     * mid-rebuild (an upsert would replace the full row with stale
     * derived fields).
     */
    fun saveUserNotes(nameKey: String, notes: String) {
        viewModelScope.launch {
            val normalized = notes.trim().takeIf { it.isNotEmpty() }
            runCatching { repo.dao.updateUserNotes(nameKey, normalized) }
        }
    }
}

/**
 * Top-level analytics screen — list of every contact Mythara has
 * learned about, favorites first, then by most-recent interaction.
 * Tap a row to see the detail (relationship summary + Big Five +
 * notable traits + topics).
 *
 * Refresh button at the bottom triggers a Gemma-backed rebuild of
 * every contact's profile. Cheap aggregation (counts, topics) runs
 * in <100ms over thousands of vault rows; the LLM passes are where
 * the time goes (~2-5s per contact with > 6 messages).
 */
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    vm: PeopleViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val report by vm.lastReport.collectAsState()
    val cleanupStatus by vm.cleanupStatus.collectAsState()
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = profiles.firstOrNull { it.nameKey == selectedKey }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (selected != null) selectedKey = null else onBack()
            }) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${Glyph.DiamondFilled} people",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (selected != null) {
            ProfileDetail(
                p = selected,
                onSaveNotes = { notes -> vm.saveUserNotes(selected.nameKey, notes) },
            )
        } else {
            ProfileList(
                profiles = profiles,
                onTap = { selectedKey = it.nameKey },
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { vm.rebuild(force = false) },
                    enabled = !refreshing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text(if (refreshing) "${Glyph.Ellipsis} refreshing" else "${Glyph.Refresh} refresh")
                }
                TextButton(
                    onClick = { vm.rebuild(force = true) },
                    enabled = !refreshing,
                ) {
                    Text("force re-infer", color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (refreshing) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MytharaColors.Bok,
                )
            }
            report?.let { r ->
                Spacer(Modifier.height(6.dp))
                val cleanupTail = if (r.cleanedProfiles + r.cleanedVaultRows > 0)
                    " · cleaned ${r.cleanedProfiles} phantom + ${r.cleanedVaultRows} rows"
                else ""
                Text(
                    text = "${Glyph.Check} last refresh: ${r.rebuilt} profiles, ${r.durationMs}ms$cleanupTail",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Dedicated cleanup button — runs ONLY the alias-
            // misattribution sweep, no rebuild. Useful after adding a
            // new alias to quickly purge the phantom profile without
            // a full Gemma pass.
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { vm.cleanupNow() },
                    enabled = !refreshing,
                ) {
                    Text(
                        text = "${Glyph.Cross} clean up phantom profiles (alias matches)",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            cleanupStatus?.let { msg ->
                Text(
                    text = msg,
                    color = if (msg.startsWith(Glyph.Check)) MytharaColors.Julep else MytharaColors.Sriracha,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProfileList(
    profiles: List<ContactProfileRow>,
    onTap: (ContactProfileRow) -> Unit,
) {
    if (profiles.isEmpty()) {
        Text(
            text = "${Glyph.CircleOutline} no contacts yet. Mythara learns people as you exchange messages — once auto-reply has fired a few times for someone, they'll show up here.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(profiles, key = { it.nameKey }) { p ->
            ProfileRow(p, onTap = { onTap(p) })
        }
    }
}

@Composable
private fun ProfileRow(p: ContactProfileRow, onTap: () -> Unit) {
    val borderColor = if (p.isFavorite) MytharaColors.Charple else MytharaColors.SurfaceHigh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(if (p.isFavorite) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onTap() }
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.displayName, p.isFavorite)
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = p.displayName,
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (p.isFavorite) {
                            Text(
                                text = "  ${Glyph.DiamondFilled}",
                                color = MytharaColors.Charple,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    val sub = buildString {
                        append("${p.messageCount} interactions")
                        if (p.imageCount > 0) append(" · ${p.imageCount} photos")
                        p.toneLabel?.let { append(" · ").append(it) }
                    }
                    Text(sub, color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                text = formatRelativeTs(p.lastInteractionMs),
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Prefer a single key-point teaser over topics on the row —
        // the user landed on this list to remember WHAT'S HAPPENING
        // before messaging, not browse interests. Falls back to topics
        // when no key points are available.
        val keyPoints = parseStringList(p.keyPointsJson)
        if (keyPoints.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} ${keyPoints.first()}",
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val topics = parseStringList(p.topTopicsJson)
            if (topics.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = topics.joinToString(" · ") { it },
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProfileDetail(
    p: ContactProfileRow,
    onSaveNotes: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.displayName, p.isFavorite, large = true)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = p.displayName,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (p.isFavorite) {
                    Text(
                        text = "${Glyph.DiamondFilled} favorite · ${p.toneLabel ?: "realistic"}",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                p.phone?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // USER NOTES — top of the detail view. The user's authoritative
        // statements about this contact. Editable; saved on tap.
        // Charple-bordered so it reads as user-curated (vs the Bok of
        // key points, which is Gemma-derived).
        Spacer(Modifier.height(16.dp))
        UserNotesCard(
            initial = p.userNotes.orEmpty(),
            displayName = p.displayName,
            onSave = onSaveNotes,
        )
        Spacer(Modifier.height(12.dp))

        // Key points — Gemma-derived "what's happening" prep.
        val keyPoints = parseStringList(p.keyPointsJson)
        if (keyPoints.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MytharaColors.Surface)
                    .border(1.5.dp, MytharaColors.Bok, RoundedCornerShape(10.dp))
                    .padding(14.dp),
            ) {
                Text(
                    text = "${Glyph.DiamondFilled} before you message them",
                    style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.Bok),
                )
                Spacer(Modifier.height(8.dp))
                keyPoints.forEach { point ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${Glyph.AccentBar} ",
                            color = MytharaColors.Bok,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = point,
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        } else if (p.relationshipSummary != null) {
            // Profile exists but no key-points extracted — show a soft
            // hint so the user knows the section can populate.
            Text(
                text = "${Glyph.CircleOutline} no specific prep notes for ${p.displayName} right now — relationship summary below.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        DetailCard("${Glyph.DiamondOutline} stats") {
            val days = ((System.currentTimeMillis() - p.firstSeenMs) / (86_400_000L)).coerceAtLeast(0)
            DetailRow("interactions", "${p.messageCount}")
            DetailRow("photos shared", "${p.imageCount}")
            DetailRow("known for", "$days day${if (days == 1L) "" else "s"}")
            DetailRow("last contact", formatTs(p.lastInteractionMs))
        }

        Spacer(Modifier.height(12.dp))
        DetailCard("${Glyph.DiamondOutline} relationship summary") {
            Text(
                text = p.relationshipSummary
                    ?: "Not enough data yet — Lumi needs a few more conversations before she can describe this relationship.",
                color = if (p.relationshipSummary != null) MytharaColors.Fg else MytharaColors.FgDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        val topics = parseStringList(p.topTopicsJson)
        if (topics.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            DetailCard("${Glyph.DiamondOutline} top topics") {
                Text(
                    text = topics.joinToString(" · "),
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        DetailCard("${Glyph.DiamondOutline} big five — Lumi's read on this person") {
            if (p.openness == null) {
                Text(
                    text = "Big Five estimation needs at least ${ContactProfileRow.MIN_BIG_FIVE_SAMPLE} captured snippets. Currently ${p.messageCount}. Keep chatting — the read sharpens with more samples.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Big5Bar("openness", p.openness)
                Big5Bar("conscientiousness", p.conscientiousness ?: 0.5)
                Big5Bar("extraversion", p.extraversion ?: 0.5)
                Big5Bar("agreeableness", p.agreeableness ?: 0.5)
                Big5Bar("neuroticism", p.neuroticism ?: 0.5)
                p.bigFiveLastUpdatedMs?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${Glyph.AccentBar} estimated from ${p.bigFiveSampleSize} captured snippets · ${formatTs(it)}",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        val traits = parseStringList(p.notableTraitsJson)
        if (traits.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            DetailCard("${Glyph.DiamondOutline} notable traits") {
                Text(
                    text = traits.joinToString(" · "),
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun UserNotesCard(
    initial: String,
    displayName: String,
    onSave: (String) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val dirty = draft.trim() != initial.trim()
    val hasNotes = initial.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.5.dp, MytharaColors.Charple, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondFilled} your notes on $displayName",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.Charple),
            )
            if (hasNotes) {
                Text(
                    text = "${Glyph.Check} saved",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} anything you want Lumi to remember about this person — preferences, sensitive topics, relationship context, corrections to what she inferred. These notes override any LLM guesses and survive every refresh.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = {
                Text(
                    "e.g. \"allergic to nuts — don't suggest restaurants without checking\"; \"knows her from college\"; \"avoid bringing up her brother\"",
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
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dirty) {
                Text(
                    text = "${Glyph.Ellipsis} unsaved changes",
                    color = MytharaColors.Mustard,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.width(1.dp))
            }
            androidx.compose.material3.Button(
                onClick = { onSave(draft) },
                enabled = dirty,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple,
                    contentColor = MytharaColors.Fg,
                    disabledContainerColor = MytharaColors.Surface,
                    disabledContentColor = MytharaColors.FgDim,
                ),
            ) {
                Text("${Glyph.Check} save")
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, body: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        body()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
        Text(value, color = MytharaColors.Fg, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Big5Bar(label: String, value: Double) {
    val pct = (value.coerceIn(0.0, 1.0) * 100).toInt()
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = MytharaColors.Fg, style = MaterialTheme.typography.bodySmall)
            Text("$pct", color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MytharaColors.Bg),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.toFloat().coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(big5Color(label, value)),
            )
        }
    }
}

private fun big5Color(label: String, value: Double): Color = when {
    label == "neuroticism" && value > 0.65 -> MytharaColors.Sriracha
    value > 0.65 -> MytharaColors.Bok
    value < 0.35 -> MytharaColors.FgMute
    else -> MytharaColors.Charple
}

@Composable
private fun Avatar(name: String, isFavorite: Boolean, large: Boolean = false) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val side = if (large) 48.dp else 36.dp
    val color = if (isFavorite) MytharaColors.Charple else MytharaColors.SurfaceHigh
    Box(
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(side / 2))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MytharaColors.Fg,
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        )
    }
}

private val TIME_FMT = SimpleDateFormat("MMM d, HH:mm", Locale.US)

private fun formatTs(ts: Long): String = TIME_FMT.format(Date(ts))

private fun formatRelativeTs(ts: Long): String {
    val delta = System.currentTimeMillis() - ts
    return when {
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
        delta < 30L * 86_400_000L -> "${delta / 86_400_000L}d ago"
        else -> TIME_FMT.format(Date(ts))
    }
}

private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseStringList(s: String): List<String> =
    runCatching { JSON.decodeFromString(ListSerializer(String.serializer()), s) }.getOrDefault(emptyList())
