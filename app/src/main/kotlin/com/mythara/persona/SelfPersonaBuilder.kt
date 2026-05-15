package com.mythara.persona

import android.util.Log
import com.mythara.analysis.AnalysisInstructionStore
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the USER'S OWN Big Five — the self-profile the About Me
 * screen surfaces.
 *
 * Where the per-contact Big Five (in
 * [com.mythara.analytics.ContactAnalyticsBuilder]) reads a person's
 * messages, this reads everything Mythara has learned about *the user*:
 *  - persona-trait vault rows — both the app-usage rhythm
 *    ([PersonaBuilder]) AND the Gemma-extracted traits lifted from the
 *    user's own outgoing messages in imported WhatsApp / SMS history
 *    ([com.mythara.imports.MessagePersonaExtractor]).
 *  - the user's own hand-written notes from the hidden Notes screen
 *    (general memories + quick notes) — first-person signal the user
 *    typed directly, so it's weighted in like persona traits.
 *  - the long-range health history + recent 24h snapshot — so the read
 *    is grounded in health metrics too, with insights that factor them
 *    in (sleep, resting-HR trend, activity level).
 *
 * The result — five 0–1 scores + notable traits + a short insights
 * paragraph — lands as a durable `kind:self-profile` vault row.
 *
 * Self-evolving: any `applies:self` analysis instruction the agent has
 * been taught (via `save_analysis_instruction`) is prepended to the
 * Gemma prompt, so the way the self-read is conducted can change as
 * Lumi learns.
 */
@Singleton
class SelfPersonaBuilder @Inject constructor(
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
    private val gemma: GemmaExtractor,
    private val instructions: AnalysisInstructionStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // The on-device Gemma engine supports a single session at a time —
    // serialise our own rebuilds so a double refresh() / import-trigger
    // / daily-worker overlap can't collide on the model.
    private val rebuildLock = Mutex()

    /**
     * Recompute the user's self-profile. No-ops (returns false) when
     * Gemma isn't loaded, there's too little signal, a fresh profile
     * already exists (unless [force]), or another rebuild is already
     * running.
     */
    suspend fun rebuild(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!rebuildLock.tryLock()) {
            Log.d(TAG, "self-profile rebuild already in progress; skipping")
            return@withContext false
        }
        try {
            rebuildLocked(force)
        } finally {
            rebuildLock.unlock()
        }
    }

    private suspend fun rebuildLocked(force: Boolean): Boolean {
        if (!gemma.isReady()) {
            Log.d(TAG, "gemma not ready; skipping self-profile")
            return false
        }
        val semantic = runCatching { vault.listByTier(Tier.Semantic, limit = 600) }
            .getOrDefault(emptyList())

        if (!force) {
            val fresh = semantic.any { e ->
                "kind:self-profile" in vault.decodeFacets(e) &&
                    System.currentTimeMillis() - e.tsMillis < FRESH_MS
            }
            if (fresh) {
                Log.d(TAG, "fresh self-profile exists; skipping")
                return false
            }
        }

        // User-level evidence — persona-trait rows PLUS the user's own
        // hand-written notes from the hidden Notes screen (general
        // memories + quick notes; `src:user-note`). Both are first-person
        // signal about the user. Contact-scoped rows are excluded — a
        // note filed against a person is about THEM, not the user.
        val personaFacts = semantic
            .filter { e ->
                val f = vault.decodeFacets(e)
                ("kind:persona" in f || "src:user-note" in f) &&
                    f.none { it.startsWith("contact:") }
            }
            .sortedByDescending { it.tsMillis }
            .map { it.content.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (personaFacts.size < MIN_FACTS) {
            Log.d(TAG, "only ${personaFacts.size} persona facts (need $MIN_FACTS); skipping self-profile")
            return false
        }

        val healthHistory = semantic.firstOrNull { "kind:health-history" in vault.decodeFacets(it) }?.content
        val healthSnapshot = semantic.firstOrNull { "kind:health-snapshot" in vault.decodeFacets(it) }?.content
        val instructionBlock = runCatching { instructions.promptBlock("self") }.getOrDefault("")

        // Compact evidence string — Gemma's context window is tight, so
        // a handful of short facts beats a wall of text.
        val evidence = buildString {
            personaFacts.take(MAX_FACTS).forEach { append("- ").append(it.take(160)).append('\n') }
        }.take(MAX_EVIDENCE_CHARS)
        val healthLine = buildHealthLine(healthHistory, healthSnapshot)

        // PASS 1 — Big Five scores + traits, strict JSON only (no prose
        // field; the existing per-contact pipeline learned the hard way
        // that mixing a paragraph into the JSON prompt makes Gemma
        // emit prose instead of JSON).
        val b5Prompt = instructionBlock +
            "Below are observed facts about THE USER — how they message people in their chat history, " +
            "their app-usage rhythm" + (if (healthLine.isNotBlank()) ", and their health metrics" else "") + ".\n\n" +
            "Estimate THE USER's Big Five personality. Return ONLY a JSON object with five fields " +
            "(openness, conscientiousness, extraversion, agreeableness, neuroticism — each a number 0.0 to 1.0) " +
            "plus a 'traits' field: an array of 3-6 short observed-trait strings. " +
            "No prose, no markdown, no code fences. Be CONSERVATIVE — middle values (~0.5) when signal is weak.\n\n" +
            "Facts:\n$evidence" +
            (if (healthLine.isNotBlank()) "\nHealth: $healthLine\n" else "") +
            "\nReturn the JSON object now."
        val b5Raw = runCatching { gemma.runRaw(b5Prompt, maxLen = B5_MAX_LEN) }.getOrNull()
        if (b5Raw.isNullOrBlank()) {
            Log.w(TAG, "gemma returned ${if (b5Raw == null) "null" else "blank"} for self big-five")
            return false
        }
        val objText = extractFirstJsonObject(b5Raw) ?: run {
            Log.w(TAG, "no JSON object in gemma self big-five output")
            return false
        }
        val root = runCatching { json.parseToJsonElement(objText) as? JsonObject }.getOrNull()
            ?: return false

        fun score(key: String) = (root[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.coerceIn(0.0, 1.0)
        val openness = score("openness")
        if (openness == null) {
            Log.w(TAG, "self big-five JSON missing 'openness'; treating as failed parse")
            return false
        }
        val traits = (root["traits"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?.take(8)
            ?: emptyList()

        // PASS 2 — the insights paragraph, separate prose call so it
        // doesn't corrupt the JSON pass. Health metrics get woven in here.
        val insightsPrompt = instructionBlock +
            "Here is an estimated Big Five read on THE USER:\n" +
            "  openness=${fmt(openness)} conscientiousness=${fmt(score("conscientiousness"))} " +
            "extraversion=${fmt(score("extraversion"))} agreeableness=${fmt(score("agreeableness"))} " +
            "neuroticism=${fmt(score("neuroticism"))}\n" +
            "  traits: ${traits.joinToString(", ")}\n" +
            (if (healthLine.isNotBlank()) "  health: $healthLine\n" else "") +
            "\nWrite ONE concise paragraph (2-3 sentences) describing the user — a grounded read that " +
            "explicitly factors in their health metrics where relevant (sleep, resting-heart-rate trend, " +
            "activity level). No bullet points, no markdown, no preamble. Just the paragraph."
        val insights = runCatching { gemma.runRaw(insightsPrompt, maxLen = INSIGHTS_MAX_LEN) }
            .getOrNull()?.trim()?.takeIf { it.length >= 20 }.orEmpty()

        val normalized = buildJsonObject {
            put("openness", openness)
            put("conscientiousness", score("conscientiousness") ?: 0.5)
            put("extraversion", score("extraversion") ?: 0.5)
            put("agreeableness", score("agreeableness") ?: 0.5)
            put("neuroticism", score("neuroticism") ?: 0.5)
            put("traits", buildJsonArray { traits.forEach { add(JsonPrimitive(it)) } })
            put("insights", insights)
            put("sample_facts", personaFacts.size)
            put("built_ms", System.currentTimeMillis())
        }.toString()

        val embedding = runCatching {
            if (embedder.isReady()) embedder.embed(insights.ifBlank { normalized }) else null
        }.getOrNull()

        val ok = runCatching {
            vault.add(
                content = normalized,
                tier = Tier.Semantic,
                src = "persona:self-bigfive",
                facets = listOf("kind:self-profile", "topic:persona", "src:self-analysis"),
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = 0.85,
            )
        }.getOrDefault(false)
        Log.d(TAG, "self-profile rebuilt from ${personaFacts.size} facts → ok=$ok (health=${healthHistory != null})")
        return ok
    }

    /** Compact, human-readable one-liner of the user's health metrics
     *  for the prompts — pulls a few key numbers out of the JSON rows
     *  rather than dumping raw JSON the model has to parse. */
    private fun buildHealthLine(history: String?, snapshot: String?): String {
        fun num(src: String?, key: String): Double? {
            src ?: return null
            val o = runCatching { json.parseToJsonElement(src) as? JsonObject }.getOrNull() ?: return null
            return (o[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
        }
        val parts = buildList {
            num(history, "steps_per_day_avg")?.let { add("~${it.toLong()} steps/day") }
            num(history, "sleep_per_night_minutes_avg")?.let { add("~${"%.1f".format(it / 60.0)}h sleep/night") }
            num(history, "hr_avg")?.let { add("avg resting HR ${it.toInt()}") }
            num(history, "hr_trend_delta")?.let {
                if (it > 1) add("resting HR trending up") else if (it < -1) add("resting HR trending down")
            }
            if (none { it.contains("steps") }) {
                num(snapshot, "steps_24h")?.let { add("${it.toLong()} steps today") }
            }
        }
        return parts.joinToString(", ")
    }

    private fun fmt(v: Double?): String = if (v == null) "?" else "%.2f".format(v)

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    companion object {
        private const val TAG = "Mythara/SelfPersona"
        private const val MIN_FACTS = 3
        private const val MAX_FACTS = 16
        private const val MAX_EVIDENCE_CHARS = 1_500
        private const val B5_MAX_LEN = 400
        private const val INSIGHTS_MAX_LEN = 400
        private const val FRESH_MS = 20L * 60 * 60 * 1000 // 20h
    }
}
