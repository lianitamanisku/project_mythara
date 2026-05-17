package com.mythara.ui.about

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactClassifier
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.health.HealthAccess
import com.mythara.me.MeProfileStore
import com.mythara.memory.Tier
import com.mythara.persona.SelfPersonaBuilder
import com.mythara.persona.UsageAccessHelper
import com.mythara.persona.UsageStatsCollector
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * "About Me" — a single self-profile surface aggregating everything
 * Mythara has learned about the user:
 *  - the user's own Big Five (from imported WhatsApp/SMS history +
 *    health metrics, via [SelfPersonaBuilder])
 *  - persona analysis (messaging style, app rhythm)
 *  - health analytics — watch HR + a rolling 24h snapshot + a
 *    long-range 6-month history
 *  - recommended people — the contacts that read as most genuine,
 *    re-ranked from the daily contact analysis
 *  - suggested apps — from on-device usage stats
 *  - heart-rate ↔ contact correlations, device sensors, and the
 *    facts the user explicitly asked Mythara to remember
 *
 * Read-only — the background workers populate the vault; this screen
 * surfaces the digest. The header refresh also kicks a self-profile
 * rebuild.
 */
@HiltViewModel
class AboutMeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val vault: LearningVault,
    private val contactRepo: ContactProfileRepository,
    private val usageCollector: UsageStatsCollector,
    private val usageAccess: UsageAccessHelper,
    private val selfPersonaBuilder: SelfPersonaBuilder,
    private val meProfile: MeProfileStore,
) : ViewModel() {

    /**
     * Live "Me" profile — display name, self-photo path, cross-app
     * aliases, phone numbers — distinct from the per-contact rows
     * in [ContactProfileRepository]. The status-bar Me avatar reads
     * from the same flow so a change here repaints there.
     */
    val meProfileFlow: StateFlow<MeProfileStore.Profile> = meProfile.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, MeProfileStore.Profile())

    fun setMeName(name: String) = viewModelScope.launch { meProfile.setDisplayName(name) }
    fun setMePhoto(uri: android.net.Uri) = viewModelScope.launch { meProfile.setPhoto(uri) }
    fun clearMePhoto() = viewModelScope.launch { meProfile.clearPhoto() }
    fun addMeAlias(alias: String) = viewModelScope.launch { meProfile.addAlias(alias) }
    fun removeMeAlias(alias: String) = viewModelScope.launch { meProfile.removeAlias(alias) }
    fun addMePhone(phone: String) = viewModelScope.launch { meProfile.addPhone(phone) }
    fun removeMePhone(phone: String) = viewModelScope.launch { meProfile.removePhone(phone) }

    data class HrSummary(
        val count: Int,
        val latestBpm: Int?,
        val avgBpm: Int?,
        val minBpm: Int?,
        val maxBpm: Int?,
        val latestMs: Long?,
    )

    data class SelfBigFive(
        val openness: Double,
        val conscientiousness: Double,
        val extraversion: Double,
        val agreeableness: Double,
        val neuroticism: Double,
        val traits: List<String>,
        val insights: String,
        val sampleFacts: Int,
    )

    data class HealthHistory(
        val windowDays: Int,
        val headline: String,
        val trend: String?,
        val weight: String?,
    )

    data class RecommendedPerson(val name: String, val reason: String)
    data class SuggestedApp(val label: String, val detail: String)

    /**
     * "Live persona" — what [com.mythara.analytics.PersonaTraitExtractor]
     * has inferred from per-turn lexical analysis, aggregated across
     * the [LearningVault]. Distinct from [SelfBigFive] which comes
     * from the batch [com.mythara.persona.SelfPersonaBuilder] (chat-
     * history imports + Gemma). The per-turn pipeline runs default-on
     * after every chat turn and accumulates faceted records under
     * `kind:trait` + `target:self`.
     *
     * `seen` (the number of distinct turns the observation has been
     * reinforced across) is the confidence proxy — UI shows it as
     * a small caption beside each item so the user can tell a
     * one-off remark from a stable pattern.
     */
    data class LivePersona(
        /** Big Five derived as `pos - neg` counts per trait, signed
         *  (negative = leans low on the trait). */
        val bigFive: List<TraitScore>,
        /** Schwartz values — top by total lexical hits. */
        val values: List<TraitScore>,
        /** Likes / dislikes / wants / avoids. */
        val preferences: List<PreferenceEntry>,
        /** Things the user keeps worrying about. */
        val concerns: List<TraitScore>,
        /** Most-frequent label per comm-style axis (length / register
         *  / mode / emoji / energy). */
        val commStyle: Map<String, String>,
        /** Total persona-trait records the extractor has written so
         *  far; surfaced as a "based on N observations" caption. */
        val observations: Int,
    )

    data class TraitScore(val name: String, val score: Int, val seen: Int)
    data class PreferenceEntry(
        /** "likes" / "dislikes" / "wants" / "avoids" */
        val predicate: String,
        val obj: String,
        val seen: Int,
    )

    data class Ui(
        val loading: Boolean = true,
        val selfBigFive: SelfBigFive? = null,
        val personality: List<String> = emptyList(),
        val hrFromWatch: HrSummary? = null,
        val healthSnapshot: String? = null,
        val healthHistory: HealthHistory? = null,
        val healthGranted: Int = 0,
        val healthAvailable: Boolean = false,
        val hrTriggers: List<String> = emptyList(),
        val recommendedPeople: List<RecommendedPerson> = emptyList(),
        val suggestedApps: List<SuggestedApp> = emptyList(),
        val usageGranted: Boolean = false,
        val deviceSensors: String? = null,
        val knownFacts: List<String> = emptyList(),
        val vaultTotal: Int = 0,
        /** Per-turn-derived persona snapshot from [PersonaTraitExtractor].
         *  Null until at least one persona-trait record has been
         *  written; renders an empty-state hint when null. */
        val livePersona: LivePersona? = null,
        /** Live HR — most recent sample read directly from Health
         *  Connect, refreshed every [LIVE_HR_POLL_MS] while the
         *  About-Me screen is alive. Null until the first read
         *  succeeds (or stays null if HC has no recent data). */
        val liveHrBpm: Int? = null,
        /** Epoch ms timestamp of [liveHrBpm], for the "as of <when>"
         *  caption. */
        val liveHrTsMs: Long? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init {
        refresh()
        startLiveHrPolling()
    }

    /** Background loop that reads the latest HR sample from Health
     *  Connect every [LIVE_HR_POLL_MS] and pushes it onto [Ui.liveHrBpm].
     *  Works alongside the (session-scoped) ResonanceHcHrPoller; both
     *  read the same HC data, but this one runs whenever the About-Me
     *  screen is open. Quietly no-ops when HC isn't available or HR
     *  read permission isn't granted — same gating as the rest of the
     *  health section. */
    private fun startLiveHrPolling() {
        viewModelScope.launch {
            while (isActive) {
                runCatching { readLatestHrFromHc() }
                    .getOrNull()
                    ?.let { (bpm, tsMs) ->
                        _ui.update { it.copy(liveHrBpm = bpm, liveHrTsMs = tsMs) }
                    }
                delay(LIVE_HR_POLL_MS)
            }
        }
    }

    private suspend fun readLatestHrFromHc(): Pair<Int, Long>? {
        return withContext(Dispatchers.IO) {
            if (HealthConnectClient.getSdkStatus(appContext) != HealthConnectClient.SDK_AVAILABLE) {
                return@withContext null
            }
            val client = HealthConnectClient.getOrCreate(appContext)
            val granted = client.permissionController.getGrantedPermissions()
            if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) {
                return@withContext null
            }
            val now = Instant.now()
            val since = now.minusSeconds(LIVE_HR_LOOKBACK_SEC)
            val records = runCatching {
                client.readRecords(
                    ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(since, now)),
                ).records
            }.getOrNull() ?: return@withContext null
            val latest = records.flatMap { rec ->
                rec.samples.map { s -> s.time.toEpochMilli() to s.beatsPerMinute.toInt() }
            }.maxByOrNull { it.first } ?: return@withContext null
            latest.second to latest.first
        }
    }

    fun refresh() {
        viewModelScope.launch {
            // Show whatever's cached now, instantly.
            _ui.value = withContext(Dispatchers.IO) { build() }
            // Then kick a self-profile rebuild (self-gates on freshness
            // + Gemma availability) and re-render if it produced a new
            // read.
            val changed = withContext(Dispatchers.IO) {
                runCatching { selfPersonaBuilder.rebuild() }.getOrDefault(false)
            }
            if (changed) {
                _ui.value = withContext(Dispatchers.IO) { build() }
            }
        }
    }

    private suspend fun build(): Ui {
        val semantic = runCatching { vault.listByTier(Tier.Semantic, limit = 500) }
            .getOrDefault(emptyList())
        val working = runCatching { vault.listByTier(Tier.Working, limit = 400) }
            .getOrDefault(emptyList())
        val total = runCatching {
            vault.countByTier(Tier.Working) + vault.countByTier(Tier.Episodic) +
                vault.countByTier(Tier.Semantic) + vault.countByTier(Tier.Procedural)
        }.getOrDefault(semantic.size + working.size)

        fun List<LearningEntity>.withFacet(facet: String) =
            filter { facet in vault.decodeFacets(it) }

        // 1) The user's own Big Five.
        val selfBigFive = semantic.withFacet("kind:self-profile")
            .firstOrNull()
            ?.let { parseSelfBigFive(it.content) }

        // 2) Personality — persona statements (messaging style from
        //    imported WhatsApp/SMS history + app-usage rhythm). Excludes
        //    contact-scoped rows.
        val personality = semantic.withFacet("kind:persona")
            .filter { vault.decodeFacets(it).none { f -> f.startsWith("contact:") } }
            .take(10)
            .map { it.content.trim() }

        // 3) Health — watch HR + 24h snapshot + long-range history.
        val hrRows = working.withFacet("kind:heart-rate")
        val bpms = hrRows.mapNotNull { extractBpm(it.content) }
        val hrSummary = if (bpms.isNotEmpty()) {
            HrSummary(
                count = bpms.size,
                latestBpm = bpms.first(),
                avgBpm = bpms.average().toInt(),
                minBpm = bpms.min(),
                maxBpm = bpms.max(),
                latestMs = hrRows.firstOrNull()?.tsMillis,
            )
        } else {
            null
        }
        val healthSnapshot = semantic.withFacet("kind:health-snapshot")
            .firstOrNull()
            ?.let { formatHealthSnapshot(it.content) }
        val healthHistory = semantic.withFacet("kind:health-history")
            .firstOrNull()
            ?.let { formatHealthHistory(it.content) }
        val healthGranted = runCatching { HealthAccess.grantedCount(appContext) }.getOrDefault(0)
        val healthAvailable = HealthAccess.isAvailable(appContext)

        // 4) Heart-rate triggers.
        val hrTriggers = semantic.withFacet("topic:hr-correlation")
            .take(6)
            .mapNotNull { formatHrCorrelation(it.content, it.tsMillis) }

        // 5) Recommended people — most-genuine connections.
        val profiles = runCatching { contactRepo.dao.listAll() }.getOrDefault(emptyList())
        val recommended = scoreRecommended(profiles)

        // 6) Suggested apps — from on-device usage stats.
        val usageGranted = runCatching { usageAccess.isGranted() }.getOrDefault(false)
        val suggestedApps = if (usageGranted) suggestApps() else emptyList()

        // 7) Device sensors + known facts.
        val deviceSensors = semantic.withFacet("kind:sensor-snapshot")
            .firstOrNull()
            ?.let { formatSensorSnapshot(it.content) }
        val knownFacts = semantic.withFacet("src:user-asked")
            .take(10)
            .map { it.content.trim() }

        // 8) Live persona — aggregate the per-turn records the
        //    PersonaTraitExtractor has been writing since the last
        //    Capability Expansion v2 commit. All rows are `kind:trait`
        //    + `target:self` in the Semantic tier.
        val livePersona = buildLivePersona(semantic)

        return Ui(
            loading = false,
            selfBigFive = selfBigFive,
            personality = personality,
            hrFromWatch = hrSummary,
            healthSnapshot = healthSnapshot,
            healthHistory = healthHistory,
            healthGranted = healthGranted,
            healthAvailable = healthAvailable,
            hrTriggers = hrTriggers,
            recommendedPeople = recommended,
            suggestedApps = suggestedApps,
            usageGranted = usageGranted,
            deviceSensors = deviceSensors,
            knownFacts = knownFacts,
            vaultTotal = total,
            livePersona = livePersona,
        )
    }

    /**
     * Roll up every `kind:trait` + `target:self` row into a compact
     * snapshot for the AboutMe panels. Aggregation rules:
     *
     *  • Big Five: each row carries `trait:<name>` + `polarity:high|low`.
     *    Sum (highCount - lowCount) per trait, weighted by `seen`. Sort
     *    by absolute magnitude so the strongest signals surface first.
     *  • Values: each row carries `value:<name>`. Sum `seen` per value.
     *  • Preferences: each row carries `predicate:<verb>` + `object:<noun>`.
     *    Group by (predicate, object), sum `seen`. Most-reinforced first.
     *  • Concerns: each row carries `topic:<noun>`. Sum `seen` per topic.
     *  • Comm-style: each row carries `axis:<axis>` + `label:<value>`.
     *    Pick the most-frequent label per axis.
     */
    private fun buildLivePersona(semantic: List<LearningEntity>): LivePersona? {
        val rows = semantic
            .filter { vault.decodeFacets(it).any { f -> f == "kind:trait" } }
            .filter { vault.decodeFacets(it).any { f -> f == "target:self" } }
        if (rows.isEmpty()) return null

        data class Bucket(var score: Int = 0, var seen: Int = 0)
        val bigFiveBuckets = mutableMapOf<String, Bucket>()
        val valueBuckets = mutableMapOf<String, Bucket>()
        val prefBuckets = mutableMapOf<Pair<String, String>, Int>()
        val concernBuckets = mutableMapOf<String, Int>()
        val styleVotes = mutableMapOf<String, MutableMap<String, Int>>()

        for (row in rows) {
            val facets = vault.decodeFacets(row)
            val dim = facets.firstOrNull { it.startsWith("dim:") }?.removePrefix("dim:")
            val seen = row.seen.coerceAtLeast(1)
            when (dim) {
                "big5" -> {
                    val trait = facets.firstOrNull { it.startsWith("trait:") }?.removePrefix("trait:") ?: continue
                    val polarity = facets.firstOrNull { it.startsWith("polarity:") }?.removePrefix("polarity:")
                    val sign = if (polarity == "high") 1 else if (polarity == "low") -1 else 0
                    val b = bigFiveBuckets.getOrPut(trait) { Bucket() }
                    b.score += sign * seen
                    b.seen += seen
                }
                "values" -> {
                    val v = facets.firstOrNull { it.startsWith("value:") }?.removePrefix("value:") ?: continue
                    val b = valueBuckets.getOrPut(v) { Bucket() }
                    b.score += seen
                    b.seen += seen
                }
                "preference" -> {
                    val pred = facets.firstOrNull { it.startsWith("predicate:") }?.removePrefix("predicate:") ?: continue
                    val obj = facets.firstOrNull { it.startsWith("object:") }?.removePrefix("object:") ?: continue
                    prefBuckets[pred to obj] = (prefBuckets[pred to obj] ?: 0) + seen
                }
                "concern" -> {
                    val topic = facets.firstOrNull { it.startsWith("topic:") }?.removePrefix("topic:") ?: continue
                    concernBuckets[topic] = (concernBuckets[topic] ?: 0) + seen
                }
                "comm-style" -> {
                    val axis = facets.firstOrNull { it.startsWith("axis:") }?.removePrefix("axis:") ?: continue
                    val label = facets.firstOrNull { it.startsWith("label:") }?.removePrefix("label:") ?: continue
                    styleVotes.getOrPut(axis) { mutableMapOf() }
                        .let { it[label] = (it[label] ?: 0) + seen }
                }
            }
        }

        val bigFive = bigFiveBuckets.entries
            .map { (trait, b) -> TraitScore(trait, b.score, b.seen) }
            .sortedByDescending { kotlin.math.abs(it.score) }
        val values = valueBuckets.entries
            .map { (name, b) -> TraitScore(name, b.score, b.seen) }
            .sortedByDescending { it.score }
            .take(8)
        val preferences = prefBuckets.entries
            .map { (k, seen) -> PreferenceEntry(k.first, k.second, seen) }
            .sortedByDescending { it.seen }
            .take(12)
        val concerns = concernBuckets.entries
            .map { (topic, seen) -> TraitScore(topic, seen, seen) }
            .sortedByDescending { it.score }
            .take(6)
        val commStyle = styleVotes.mapValues { (_, votes) ->
            votes.maxByOrNull { it.value }?.key.orEmpty()
        }.filterValues { it.isNotEmpty() }

        return LivePersona(
            bigFive = bigFive,
            values = values,
            preferences = preferences,
            concerns = concerns,
            commStyle = commStyle,
            observations = rows.size,
        )
    }

    /**
     * Rank the contacts that read as most genuine: real named people
     * (promotional / short-code senders are excluded outright),
     * weighted by interaction volume, favorite status, whether the
     * user has written notes on them, and recency. Re-runs every
     * refresh off the daily contact-analysis output, so it shifts as
     * the relationships shift.
     */
    private fun scoreRecommended(profiles: List<ContactProfileRow>): List<RecommendedPerson> {
        val now = System.currentTimeMillis()
        return profiles
            .filter { ContactClassifier.isPersonal(it.displayName, it.phone) }
            .filter { it.messageCount > 0 }
            .map { p ->
                var score = p.messageCount.coerceAtMost(60)
                val reasons = mutableListOf<String>()
                if (p.isFavorite) { score += 35; reasons += "favorite" }
                if (!p.userNotes.isNullOrBlank()) { score += 28; reasons += "you've noted them" }
                val age = now - p.lastInteractionMs
                when {
                    age < 7L * 86_400_000 -> { score += 18; reasons += "in touch this week" }
                    age < 30L * 86_400_000 -> score += 8
                }
                if (!p.relationshipSummary.isNullOrBlank()) score += 8
                if (p.openness != null) score += 6
                reasons += "${p.messageCount} interactions"
                p to RecommendedPerson(p.displayName, reasons.take(3).joinToString(" · "))
            }
            .sortedByDescending { computeScore(it.first, now) }
            .map { it.second }
            .take(6)
    }

    private fun computeScore(p: ContactProfileRow, now: Long): Int {
        var score = p.messageCount.coerceAtMost(60)
        if (p.isFavorite) score += 35
        if (!p.userNotes.isNullOrBlank()) score += 28
        val age = now - p.lastInteractionMs
        when {
            age < 7L * 86_400_000 -> score += 18
            age < 30L * 86_400_000 -> score += 8
        }
        if (!p.relationshipSummary.isNullOrBlank()) score += 8
        if (p.openness != null) score += 6
        return score
    }

    private fun suggestApps(): List<SuggestedApp> {
        val rows = runCatching { usageCollector.collect() }.getOrDefault(emptyList())
        return rows.take(6).map { u ->
            SuggestedApp(
                label = u.label,
                detail = "${formatMs(u.totalForegroundMs)} today · ${u.launchCount} opens",
            )
        }
    }

    /** "Heart rate 72 bpm (captured on watch)." → 72 */
    private fun extractBpm(content: String): Int? =
        Regex("""(\d{2,3})\s*bpm""").find(content)?.groupValues?.get(1)?.toIntOrNull()

    private fun obj(content: String): JsonObject? =
        runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun parseSelfBigFive(content: String): SelfBigFive? {
        val o = obj(content) ?: return null
        val openness = o.num("openness") ?: return null
        return SelfBigFive(
            openness = openness,
            conscientiousness = o.num("conscientiousness") ?: 0.5,
            extraversion = o.num("extraversion") ?: 0.5,
            agreeableness = o.num("agreeableness") ?: 0.5,
            neuroticism = o.num("neuroticism") ?: 0.5,
            traits = (o["traits"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?: emptyList(),
            insights = (o["insights"] as? JsonPrimitive)?.content?.trim().orEmpty(),
            sampleFacts = o.num("sample_facts")?.toInt() ?: 0,
        )
    }

    private fun formatHealthSnapshot(content: String): String? {
        val o = obj(content) ?: return content.take(120)
        val parts = buildList {
            o.num("steps_24h")?.let { add("Steps ${humanCount(it.toLong())}") }
            o.num("hr_24h_avg")?.let { add("Avg HR ${it.toInt()}") }
            o.num("hr_24h_min")?.let { lo ->
                o.num("hr_24h_max")?.let { hi -> add("HR ${lo.toInt()}–${hi.toInt()}") }
            }
            o.num("sleep_24h_minutes")?.let { add("Sleep ${"%.1f".format(it / 60.0)}h") }
            o.num("kcal_24h")?.let { add("${it.toInt()} kcal") }
            o.num("weight_kg")?.let { add("${"%.1f".format(it)} kg") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun formatHealthHistory(content: String): HealthHistory? {
        val o = obj(content) ?: return null
        val days = o.num("window_days")?.toInt() ?: 180
        val headline = buildList {
            o.num("steps_per_day_avg")?.let { add("${humanCount(it.toLong())} steps/day") }
            o.num("sleep_per_night_minutes_avg")?.let { add("${"%.1f".format(it / 60.0)}h sleep/night") }
            o.num("hr_avg")?.let { add("avg HR ${it.toInt()}") }
            o.num("kcal_per_day_avg")?.let { add("${it.toInt()} kcal/day") }
        }.joinToString(" · ")
        val trend = o.num("hr_trend_delta")?.let { d ->
            val arrow = when {
                d > 1 -> "${Glyph.Arrow} climbing"
                d < -1 -> "${Glyph.Arrow} easing"
                else -> "steady"
            }
            "resting HR $arrow (${"%+.0f".format(d)} bpm over $days days)"
        }
        val weight = run {
            val first = o.num("weight_kg_first")
            val last = o.num("weight_kg_latest")
            if (first != null && last != null) {
                "weight ${"%.1f".format(first)} → ${"%.1f".format(last)} kg"
            } else {
                null
            }
        }
        if (headline.isBlank() && trend == null && weight == null) return null
        return HealthHistory(windowDays = days, headline = headline, trend = trend, weight = weight)
    }

    private fun formatHrCorrelation(content: String, tsMillis: Long): String? {
        val o = obj(content) ?: return null
        val spike = o.num("spike_bpm")?.toInt() ?: return null
        val baseline = o.num("baseline_bpm")?.toInt()
        val contact = runCatching {
            (o["candidates"] as? JsonArray)
                ?.firstOrNull()?.jsonObject?.get("contact")?.jsonPrimitive?.content
        }.getOrNull()
        val who = contact?.takeIf { it.isNotBlank() } ?: "an unknown ping"
        val delta = if (baseline != null) " (from $baseline)" else ""
        return "$spike bpm$delta → $who · ${shortDate(tsMillis)}"
    }

    private fun formatSensorSnapshot(content: String): String? {
        val o = obj(content) ?: return content.take(120)
        val parts = buildList {
            (o["battery"] as? JsonObject)?.let { b ->
                b.num("percent")?.let { add("Battery ${it.toInt()}%") }
                b.num("temperature_c")?.let { add("${"%.0f".format(it * 9.0 / 5.0 + 32.0)}°F") }
            }
            (o["environment"] as? JsonObject)?.let { e ->
                e.num("light_lux")?.let { add("Light ${it.toInt()} lux") }
                e.num("pressure_hpa")?.let { add("${it.toInt()} hPa") }
            }
            (o["connectivity_type"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() && it != "unknown" }
                ?.let { add(it) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun humanCount(n: Long): String = when {
        n >= 1_000 -> "${"%.1f".format(n / 1000.0)}k"
        else -> n.toString()
    }

    private fun formatMs(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    private fun shortDate(ms: Long): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))

    companion object {
        /** How often to re-read HC for the live HR readout. The
         *  upstream sources (Fitbit, Samsung Health) batch their
         *  writes every ~1 min, so polling much faster than this
         *  just spins for no new data. */
        private const val LIVE_HR_POLL_MS = 30_000L

        /** How far back to scan for the latest HC sample. Wide
         *  enough to catch a Samsung-Health-batched write that
         *  arrived a few minutes ago without confusing genuinely
         *  stale readings for live ones. */
        private const val LIVE_HR_LOOKBACK_SEC = 600L
    }
}

@Composable
fun AboutMeScreen(
    onBack: () -> Unit,
    vm: AboutMeViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { vm.refresh() }

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
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.refresh() }) {
                Text("${Glyph.Refresh} refresh", color = MytharaColors.FgDim)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "ABOUT ME",
            style = MaterialTheme.typography.headlineSmall.copy(
                color = MytharaColors.Fg, letterSpacing = 3.sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${Glyph.AccentBar} everything Mythara has learned about you · ${ui.vaultTotal} learnings",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )

        Spacer(Modifier.height(20.dp))

        // 0) "Me" identity panel — name, self-photo (won't be
        //    overridden by phone contact lookup), cross-app
        //    aliases. The status-bar Me avatar reads from the
        //    same store, so changes here repaint there too.
        val meProfile by vm.meProfileFlow.collectAsState()
        MeIdentityPanel(
            profile = meProfile,
            onSetName = vm::setMeName,
            onSetPhoto = vm::setMePhoto,
            onClearPhoto = { vm.clearMePhoto() },
            onAddAlias = vm::addMeAlias,
            onRemoveAlias = vm::removeMeAlias,
            onAddPhone = vm::addMePhone,
            onRemovePhone = vm::removeMePhone,
        )

        Spacer(Modifier.height(12.dp))

        if (ui.loading) {
            Panel("loading") {
                Text("reading the vault…", color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        // 1) The user's own Big Five.
        Panel("your big five") {
            val b5 = ui.selfBigFive
            if (b5 == null) {
                Empty(
                    "Not built yet. Mythara estimates your Big Five from your imported WhatsApp/SMS history " +
                        "plus your health metrics — import a chat export from Settings, then refresh.",
                )
            } else {
                Big5Row("openness", b5.openness)
                Big5Row("conscientiousness", b5.conscientiousness)
                Big5Row("extraversion", b5.extraversion)
                Big5Row("agreeableness", b5.agreeableness)
                Big5Row("neuroticism", b5.neuroticism)
                if (b5.traits.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = b5.traits.joinToString(" · "),
                        color = MytharaColors.Bok,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (b5.insights.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(b5.insights, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${Glyph.AccentBar} estimated from ${b5.sampleFacts} observed facts",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 2) Personality analysis.
        Panel("personality analysis") {
            if (ui.personality.isEmpty()) {
                Empty("No persona data yet — import your chat history or grant Usage Access, then refresh.")
            } else {
                ui.personality.forEachIndexed { i, line ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    Bullet(line)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 2.5) Live persona — derived per chat turn, distinct from the
        //      batch "your big five" above which comes from the
        //      SelfPersonaBuilder import flow.
        ui.livePersona?.let { lp ->
            Spacer(Modifier.height(12.dp))
            LivePersonaPanel(lp)
        }

        Spacer(Modifier.height(12.dp))

        // 3) Recommended people.
        Panel("recommended people") {
            if (ui.recommendedPeople.isEmpty()) {
                Empty("No genuine connections surfaced yet — they appear once Mythara has learned a few real contacts.")
            } else {
                Text(
                    "The people who read as your most genuine connections, re-ranked daily:",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                ui.recommendedPeople.forEachIndexed { i, p ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    Row {
                        Text(
                            "${Glyph.DiamondFilled} ",
                            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.Charple),
                        )
                        Column {
                            Text(p.name, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
                            Text(p.reason, color = MytharaColors.FgDim, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 4) Health analytics.
        Panel("health analytics") {
            // Grant affordance — the workers never prompt; this is the
            // only place the user can wire up Health Connect.
            if (ui.healthAvailable && ui.healthGranted == 0) {
                Text(
                    "Mythara can pull your synced health history (steps, heart rate, sleep, weight) — " +
                        "it just needs Health Connect read access.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { healthLauncher.launch(HealthAccess.PERMISSIONS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text("grant health access")
                }
                Spacer(Modifier.height(10.dp))
            } else if (!ui.healthAvailable) {
                Text(
                    "Health Connect isn't available on this device.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Live HR — read directly from Health Connect every ~30 s
            // by the VM. Shown above the rolling-stats card so the
            // user always has a current bpm at a glance, even when
            // no Resonance session is active.
            if (ui.liveHrBpm != null) {
                Stat("Heart rate (live)", "${ui.liveHrBpm} bpm")
                Spacer(Modifier.height(4.dp))
                Text(
                    "as of ${shortRelative(ui.liveHrTsMs)}",
                    color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
            }

            val hr = ui.hrFromWatch
            if (hr != null) {
                Stat("Heart rate (rolling)", "${hr.latestBpm ?: "–"} bpm")
                Spacer(Modifier.height(4.dp))
                Text(
                    "avg ${hr.avgBpm ?: "–"} · range ${hr.minBpm ?: "–"}–${hr.maxBpm ?: "–"} · ${hr.count} samples",
                    color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall,
                )
            } else if (ui.liveHrBpm == null) {
                // Only show the "no samples" empty-state when neither
                // live nor rolling has anything — having only live is
                // a perfectly normal early-state.
                Empty("No watch heart-rate samples yet — wear the watch and make sure Samsung Health / Fitbit is sharing HR with Health Connect.")
            }
            ui.healthSnapshot?.let {
                Spacer(Modifier.height(10.dp))
                Stat("Last 24h (Health Connect)", "")
                Spacer(Modifier.height(2.dp))
                Text(it, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(12.dp))

        // 5) Health history (long-range).
        Panel("health history") {
            val h = ui.healthHistory
            if (h == null) {
                Empty(
                    if (ui.healthGranted == 0) {
                        "Grant health access above — Mythara then pulls your last 6 months of synced data."
                    } else {
                        "Building — your long-range health history appears here within a day of granting access."
                    },
                )
            } else {
                Stat("Last ${h.windowDays} days", "")
                if (h.headline.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(h.headline, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
                }
                h.trend?.let {
                    Spacer(Modifier.height(6.dp))
                    Bullet(it)
                }
                h.weight?.let {
                    Spacer(Modifier.height(4.dp))
                    Bullet(it)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 6) Suggested apps.
        Panel("suggested apps") {
            if (!ui.usageGranted) {
                Empty("Grant Usage Access (Settings → Special access) so Mythara can suggest apps from your usage.")
            } else if (ui.suggestedApps.isEmpty()) {
                Empty("No usage data yet — check back after you've used the phone for a bit.")
            } else {
                Text(
                    "Your most-used apps — quick picks from today's usage:",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                ui.suggestedApps.forEachIndexed { i, app ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    Row {
                        Text(
                            "${Glyph.DiamondFilled} ",
                            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.Mustard),
                        )
                        Column {
                            Text(app.label, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
                            Text(app.detail, color = MytharaColors.FgDim, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 7) Heart-rate triggers.
        Panel("heart-rate triggers") {
            if (ui.hrTriggers.isEmpty()) {
                Empty("No heart-rate spikes correlated yet — Mythara links HR jumps to who pinged you in the moments before.")
            } else {
                ui.hrTriggers.forEachIndexed { i, line ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    Bullet(line)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 8) Device & sensors.
        Panel("device & sensors") {
            ui.deviceSensors?.let {
                Text(it, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
            } ?: Empty("No sensor snapshot captured yet.")
        }

        Spacer(Modifier.height(12.dp))

        // 9) What Mythara remembers.
        Panel("what Mythara remembers") {
            if (ui.knownFacts.isEmpty()) {
                Empty("Nothing yet — say \"remember that…\" and Mythara will keep it here.")
            } else {
                ui.knownFacts.forEachIndexed { i, line ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    Bullet(line)
                }
            }
        }

        Spacer(Modifier.height(40.dp))
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

@Composable
private fun Bullet(text: String) {
    Row {
        Text(
            "${Glyph.AccentBar} ",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.Charple),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.Fg),
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgMute))
        if (value.isNotBlank()) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(color = MytharaColors.Bok),
            )
        }
    }
}

@Composable
private fun Big5Row(label: String, value: Double) {
    val pct = (value.coerceIn(0.0, 1.0) * 100).toInt()
    val barColor = when {
        label == "neuroticism" && value > 0.65 -> MytharaColors.Sriracha
        value > 0.65 -> MytharaColors.Bok
        value < 0.35 -> MytharaColors.FgMute
        else -> MytharaColors.Charple
    }
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
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
}

/**
 * Render the per-turn-derived persona snapshot from
 * [AboutMeViewModel.LivePersona]. Distinct from the batch
 * "your big five" panel above — this one shows what Mythara has
 * learned from your actual chats, refreshed every turn.
 *
 * Four sub-sections, each elided when empty:
 *   • Big Five — signed score per trait + sign-direction badge
 *   • Top values — Schwartz dimensions ranked by lexical hits
 *   • Likes / dislikes — predicate-grouped preference list
 *   • Concerns + comm-style — short caption tail
 */
@Composable
private fun LivePersonaPanel(lp: AboutMeViewModel.LivePersona) {
    Panel("what I've learned from our chats") {
        Text(
            text = "${Glyph.AccentBar} live persona — derived per turn from your conversations. " +
                "Distinct from the batch profile above (which comes from imported chat history + Gemma).",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))

        if (lp.bigFive.isNotEmpty()) {
            Text(
                "${Glyph.DiamondFilled} big five tendencies",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            lp.bigFive.forEach { t ->
                val arrow = when {
                    t.score > 0 -> "↑"
                    t.score < 0 -> "↓"
                    else -> "→"
                }
                val polarity = when {
                    t.score > 0 -> "high"
                    t.score < 0 -> "low"
                    else -> "neutral"
                }
                val color = when {
                    t.score > 0 -> MytharaColors.Bok
                    t.score < 0 -> MytharaColors.Charple
                    else -> MytharaColors.FgMute
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$arrow ${t.name}",
                        color = color,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$polarity · ${t.seen} obs",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (lp.values.isNotEmpty()) {
            Text(
                "${Glyph.DiamondFilled} top values",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = lp.values.joinToString("  ·  ") { "${it.name} (${it.seen})" },
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
        }

        if (lp.preferences.isNotEmpty()) {
            Text(
                "${Glyph.DiamondFilled} likes & dislikes",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            // Group by predicate for cleaner reading.
            val grouped = lp.preferences.groupBy { it.predicate }
            grouped.forEach { (predicate, entries) ->
                val color = when (predicate) {
                    "likes" -> MytharaColors.Bok
                    "dislikes" -> MytharaColors.Sriracha
                    "wants" -> MytharaColors.Mustard
                    "avoids" -> MytharaColors.FgMute
                    else -> MytharaColors.Fg
                }
                Text(
                    text = "$predicate: " + entries.joinToString(", ") { it.obj },
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        if (lp.concerns.isNotEmpty()) {
            Text(
                "${Glyph.DiamondFilled} on your mind",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = lp.concerns.joinToString("  ·  ") { it.name },
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (lp.commStyle.isNotEmpty()) {
            Text(
                "${Glyph.DiamondFilled} how you write to me",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = lp.commStyle.entries.joinToString("  ·  ") { (k, v) -> "$k=$v" },
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
        }

        Text(
            "${Glyph.AccentBar} based on ${lp.observations} observations from your chats",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Compact "5 s ago / 12 m ago / 3 h ago / Mar 4, 2:15 PM" formatter
 *  for the live-HR caption. Falls back to "—" on null. */
private fun shortRelative(ms: Long?): String {
    if (ms == null) return "—"
    val ageMs = (System.currentTimeMillis() - ms).coerceAtLeast(0)
    val s = ageMs / 1000
    val m = s / 60
    val h = m / 60
    val d = h / 24
    return when {
        s < 5 -> "just now"
        s < 60 -> "${s}s ago"
        m < 60 -> "${m}m ago"
        h < 24 -> "${h}h ago"
        d < 7 -> "${d}d ago"
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))
    }
}

/**
 * "Me" identity panel — name + photo + cross-app aliases.
 *
 * Why this lives here (not in PeopleScreen):
 *   - About Me is the canonical "this is YOU" surface; every other
 *     person lives in People. Keeping the self-profile here mirrors
 *     that mental model.
 *   - The status-bar Me avatar reads from the same MeProfileStore
 *     and tapping it lands on this panel — so the user has a single
 *     edit surface they can reach from any screen.
 *
 * The panel exposes:
 *   - Avatar (tap to pick a new photo from the system picker; long
 *     tap not implemented — clear button beneath instead)
 *   - Display name (single-line text field)
 *   - Aliases (each row = name + remove × ; bottom row = add input)
 *   - Phones (same shape as aliases, treated as last-7-digits-suffix
 *     for matching)
 *
 * The cross-app person observer reads this profile to skip self-
 * notifications (so you don't end up as a People row), and the
 * contact-photo resolver reads it to ensure your set photo isn't
 * overwritten by ContactsContract.
 */
@Composable
private fun MeIdentityPanel(
    profile: com.mythara.me.MeProfileStore.Profile,
    onSetName: (String) -> Unit,
    onSetPhoto: (android.net.Uri) -> Unit,
    onClearPhoto: () -> Unit,
    onAddAlias: (String) -> Unit,
    onRemoveAlias: (String) -> Unit,
    onAddPhone: (String) -> Unit,
    onRemovePhone: (String) -> Unit,
) {
    val photoPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onSetPhoto(uri) }
    val ctx = LocalContext.current

    var nameDraft by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var aliasDraft by remember { mutableStateOf("") }
    var phoneDraft by remember { mutableStateOf("") }

    Panel(title = "${Glyph.DiamondFilled} you") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Self-avatar — tap to pick.
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MytharaColors.SurfaceHigh)
                    .border(
                        2.dp,
                        MytharaColors.Charple,
                        androidx.compose.foundation.shape.CircleShape,
                    )
                    .clickable {
                        photoPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val path = profile.photoPath
                val bmp = remember(path, profile.updatedAtMs) {
                    if (path.isBlank()) null
                    else runCatching {
                        android.graphics.BitmapFactory.decodeFile(path)
                    }.getOrNull()
                }
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "your avatar",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape),
                    )
                } else {
                    Text(
                        text = profile.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "Me",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                androidx.compose.material3.OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("your name", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    TextButton(onClick = { onSetName(nameDraft) }) {
                        Text("${Glyph.Check} save name", style = MaterialTheme.typography.bodySmall)
                    }
                    if (profile.photoPath.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = onClearPhoto) {
                            Text(
                                "${Glyph.Cross} clear photo",
                                color = MytharaColors.Sriracha,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "${Glyph.AccentBar} other names you go by — Mythara will treat notifications from these as YOU and not auto-add them as People rows.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        for (alias in profile.aliases) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "  $alias",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRemoveAlias(alias) }) {
                    Text("${Glyph.Cross}", color = MytharaColors.Sriracha)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = aliasDraft,
                onValueChange = { aliasDraft = it },
                placeholder = { Text("e.g. \"Anurag K.\" (Teams display name)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = {
                if (aliasDraft.isNotBlank()) {
                    onAddAlias(aliasDraft.trim())
                    aliasDraft = ""
                }
            }) {
                Text("${Glyph.Check} add", color = MytharaColors.Bok)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "${Glyph.AccentBar} phone numbers used by you (any format works — only the last 7 digits are matched).",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        for (phone in profile.phones) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "  $phone",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRemovePhone(phone) }) {
                    Text("${Glyph.Cross}", color = MytharaColors.Sriracha)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = phoneDraft,
                onValueChange = { phoneDraft = it },
                placeholder = { Text("+1 555 123 4567") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = {
                if (phoneDraft.isNotBlank()) {
                    onAddPhone(phoneDraft.trim())
                    phoneDraft = ""
                }
            }) {
                Text("${Glyph.Check} add", color = MytharaColors.Bok)
            }
        }
    }
}
