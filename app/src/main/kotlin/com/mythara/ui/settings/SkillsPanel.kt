package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.skills.Skill
import com.mythara.skills.SkillRunner
import com.mythara.skills.SkillStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SkillsPanelViewModel @Inject constructor(
    private val store: SkillStore,
    private val runner: SkillRunner,
    private val patternDetector: com.mythara.agent.SkillPatternDetector,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Per-skill run status surfaced to the UI ("running…", "ok",
     *  "failed: <reason>"). Cleared after [STATUS_CLEAR_MS]. */
    private val _runStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val runStatus: StateFlow<Map<String, String>> = _runStatus.asStateFlow()

    /** Tool-chain shapes the cross-turn detector has seen ≥ 2 times
     *  in the last 30 days. Lets the user see what habits Mythara
     *  has noticed BEFORE the offer fires — useful for early
     *  feedback ("yes save this", "no this is noise"). */
    private val _detectedPatterns =
        MutableStateFlow<List<com.mythara.agent.SkillPatternDetector.PatternHit>>(emptyList())
    val detectedPatterns: StateFlow<List<com.mythara.agent.SkillPatternDetector.PatternHit>> =
        _detectedPatterns.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _skills.value = runCatching { store.list() }.getOrDefault(emptyList())
                .sortedByDescending { it.lastRunMs ?: it.createdMs }
            _detectedPatterns.value = runCatching { patternDetector.frequencyTable(minRepeats = 2) }
                .getOrDefault(emptyList())
            _loading.value = false
        }
    }

    /** Wipe the cross-turn pattern history. Skills you've already
     *  saved are unaffected — this only clears the "what habits
     *  has Mythara noticed" memory. */
    fun clearDetectedPatterns() {
        viewModelScope.launch {
            runCatching { patternDetector.clear() }
            refresh()
        }
    }

    /** Fire-and-forget run from the panel (Phase L). Parameter-less
     *  skills only — anything with required `params` would need a
     *  per-param input dialog which the panel doesn't (yet) build. */
    fun run(skill: Skill) {
        viewModelScope.launch {
            _runStatus.update(skill.name, "${Glyph.Ellipsis} running…")
            val result = runCatching { runner.run(skill, params = emptyMap()) }
            val label = result.fold(
                onSuccess = { res ->
                    if (res.ok) "${Glyph.Check} ok · ${res.stepsExecuted}/${res.totalSteps} step${if (res.totalSteps == 1) "" else "s"}"
                    else "${Glyph.Cross} failed at step ${res.failureAtStep ?: "?"}: ${(res.failureReason ?: "unknown").take(60)}"
                },
                onFailure = { "${Glyph.Cross} threw: ${it.message?.take(60) ?: it.javaClass.simpleName}" },
            )
            _runStatus.update(skill.name, label)
            // Reload so lastRunMs / runCount badges refresh.
            refresh()
            kotlinx.coroutines.delay(STATUS_CLEAR_MS)
            _runStatus.update(skill.name, null)
        }
    }

    /** Wipe a skill (every saved version). Phase L. */
    fun delete(name: String) {
        viewModelScope.launch {
            runCatching { store.delete(name) }
            refresh()
        }
    }

    private fun MutableStateFlow<Map<String, String>>.update(key: String, value: String?) {
        this.value = if (value == null) this.value - key else this.value + (key to value)
    }

    companion object {
        private const val STATUS_CLEAR_MS = 6_000L
    }
}

/**
 * Settings panel that lists every saved skill on this device. Skills
 * sync via the LearningVault semantic tier (topic:skill → memory repo
 * `semantic/skill.jsonl`), so this list reflects skills written on
 * this device AND restored from peers after a heartbeat sync.
 *
 * Tap a row to expand the step list — useful when debugging "why
 * didn't the skill fire?" or just curious about what the agent has
 * learned.
 */
@Composable
fun SkillsPanel(vm: SkillsPanelViewModel = hiltViewModel()) {
    val skills by vm.skills.collectAsState()
    val loading by vm.loading.collectAsState()
    val runStatus by vm.runStatus.collectAsState()
    val detectedPatterns by vm.detectedPatterns.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Skill?>(null) }
    var pendingForgetPatterns by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

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
                text = "${Glyph.DiamondOutline} skills",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
            )
            Text(
                text = if (loading) "${Glyph.Ellipsis} loading…" else "${skills.size} known",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} the procedures Mythara has learned to drive other apps. Saved here, " +
                "synced cross-device via the memory repo. Tap a row to see the steps.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))

        if (skills.isEmpty() && !loading) {
            Text(
                text = "${Glyph.CircleOutline} no skills yet. Mythara writes new ones automatically when she completes a multi-step task — or you can ask her to remember a sequence.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (skill in skills) {
                    SkillRow(
                        skill = skill,
                        expanded = expanded == skill.name,
                        status = runStatus[skill.name],
                        onClick = { expanded = if (expanded == skill.name) null else skill.name },
                        onRun = { vm.run(skill) },
                        onDelete = { pendingDelete = skill },
                    )
                }
            }
        }

        // Detected patterns — what the cross-turn detector has seen
        // ≥ 2 times in the last 30 days. The "offer to save as a
        // skill" prompt fires at REPEAT_THRESHOLD (3); rows below
        // that show what's brewing, rows above are highlighted
        // because Mythara is about to (or already did) surface them.
        if (detectedPatterns.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${Glyph.DiamondOutline} detected patterns",
                    style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
                )
                Text(
                    text = "${detectedPatterns.size} shape${if (detectedPatterns.size == 1) "" else "s"}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} tool-chain shapes Mythara has noticed across turns in the last 30 days. " +
                    "When a shape repeats 3×, the next turn will offer to save it as a skill.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (hit in detectedPatterns) {
                    PatternRow(hit = hit)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.TextButton(
                    onClick = { pendingForgetPatterns = true },
                ) {
                    Text(
                        "${Glyph.Cross} forget patterns",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    pendingDelete?.let { skill ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text("delete \"${skill.name}\"?", color = MytharaColors.Fg)
            },
            text = {
                Text(
                    "wipes every saved version of this skill. " +
                        "Mythara can re-learn it the next time the user walks through the procedure.",
                    color = MytharaColors.FgDim,
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        vm.delete(skill.name)
                        pendingDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Sriracha,
                        contentColor = MytharaColors.Fg,
                    ),
                ) { Text("${Glyph.Cross} delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text("cancel", color = MytharaColors.FgMute)
                }
            },
            containerColor = MytharaColors.Surface,
        )
    }

    if (pendingForgetPatterns) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingForgetPatterns = false },
            title = { Text("forget detected patterns?", color = MytharaColors.Fg) },
            text = {
                Text(
                    "wipes the 30-day rolling history of tool-chain shapes Mythara has been " +
                        "tracking. Already-saved skills are NOT affected — only the " +
                        "\"what habits has she noticed\" memory.",
                    color = MytharaColors.FgDim,
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        vm.clearDetectedPatterns()
                        pendingForgetPatterns = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Sriracha,
                        contentColor = MytharaColors.Fg,
                    ),
                ) { Text("${Glyph.Cross} forget") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingForgetPatterns = false }) {
                    Text("cancel", color = MytharaColors.FgMute)
                }
            },
            containerColor = MytharaColors.Surface,
        )
    }
}

/** Renders one detected tool-chain shape with its repetition count.
 *  Rows that have crossed the offer threshold (`shouldOffer = true`)
 *  are tinted Charple to signal "Mythara is about to / already did
 *  surface this as a save-skill prompt". Sub-threshold rows are
 *  ambient — useful early-feedback affordance but not actionable
 *  yet. */
@Composable
private fun PatternRow(hit: com.mythara.agent.SkillPatternDetector.PatternHit) {
    val accent = if (hit.shouldOffer) MytharaColors.Charple else MytharaColors.SurfaceHigh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Bg)
            .border(1.dp, accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = hit.chainShape.joinToString(" → "),
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${hit.totalRepeats}×",
                color = if (hit.shouldOffer) MytharaColors.Charple else MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        if (hit.shouldOffer) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${Glyph.DiamondFilled} offer-worthy — Mythara will suggest saving this",
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SkillRow(
    skill: Skill,
    expanded: Boolean,
    /** Per-skill status caption from the most recent run / delete
     *  action. Null when nothing's running. */
    status: String?,
    onClick: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Bg)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = skill.name,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "v${skill.version} · ${skill.runCount} runs",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (skill.description.isNotBlank()) {
            Text(
                text = skill.description,
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${skill.steps.size} step${if (skill.steps.size == 1) "" else "s"}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
            if (skill.params.isNotEmpty()) {
                Spacer(Modifier.padding(end = 6.dp))
                Text("│", color = MytharaColors.SurfaceHigh, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.padding(end = 6.dp))
                Text(
                    text = "params: ${skill.params.joinToString(", ")}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            skill.lastRunMs?.let { lr ->
                Spacer(Modifier.padding(end = 6.dp))
                Text("│", color = MytharaColors.SurfaceHigh, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.padding(end = 6.dp))
                Text(
                    text = "last run ${formatDate(lr)}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((idx, step) in skill.steps.withIndex()) {
                    Text(
                        text = "${idx + 1}. ${stepLabel(step)}",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (skill.failures.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${Glyph.Cross} ${skill.failures.size} past failure${if (skill.failures.size == 1) "" else "s"} on record",
                    color = MytharaColors.Sriracha,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            // Run + delete actions, only visible while expanded. Run
            // is disabled for skills with required params (would
            // need a per-param input dialog the panel doesn't build
            // yet — agent calls remain the path for parameterised
            // skills).
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(
                    onClick = onRun,
                    enabled = skill.params.isEmpty(),
                ) {
                    Text(
                        text = if (skill.params.isEmpty())
                            "${Glyph.DiamondFilled} run now"
                        else "${Glyph.DiamondOutline} needs params — ask Mythara",
                        color = if (skill.params.isEmpty()) MytharaColors.Bok else MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                androidx.compose.material3.TextButton(onClick = onDelete) {
                    Text(
                        "${Glyph.Cross} delete",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            status?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = MytharaColors.FgDim, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun stepLabel(step: com.mythara.skills.SkillStep): String = when (step) {
    is com.mythara.skills.SkillStep.OpenApp -> "open_app ${step.pkg}"
    is com.mythara.skills.SkillStep.Wait -> "wait ${step.ms}ms"
    is com.mythara.skills.SkillStep.TapText -> "tap_text \"${step.text}\""
    is com.mythara.skills.SkillStep.TapDesc -> "tap_desc \"${step.desc}\""
    is com.mythara.skills.SkillStep.TapId -> "tap_id ${step.id}"
    is com.mythara.skills.SkillStep.TypeText -> "type_text \"${step.text.take(40)}${if (step.text.length > 40) "…" else ""}\""
    is com.mythara.skills.SkillStep.Swipe -> "swipe (${step.x1},${step.y1}) → (${step.x2},${step.y2})"
    is com.mythara.skills.SkillStep.Tap -> "tap (${step.x},${step.y})"
    is com.mythara.skills.SkillStep.VerifyVisible -> "verify_visible \"${step.text}\""
    is com.mythara.skills.SkillStep.ReadScreen -> "read_screen${step.label?.let { " $it" } ?: ""}"
}

private fun formatDate(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val sdf = if (diff < 24L * 3600 * 1000) SimpleDateFormat("HH:mm", Locale.getDefault())
    else if (diff < 7L * 24 * 3600 * 1000) SimpleDateFormat("EEE HH:mm", Locale.getDefault())
    else SimpleDateFormat("MMM d", Locale.getDefault())
    return sdf.format(Date(ms))
}
