package com.mythara.analytics

import android.util.Log
import com.mythara.data.FavoritesStore
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reduces the raw learning vault into one [ContactProfileRow] per
 * person. Two stages:
 *
 *   1. AGGREGATE — query all vault rows facetted with `contact:<name>`,
 *      group by canonical name key, count messages + images, find first
 *      and last interaction, sample distinctive topics.
 *   2. INFER — for each group, concatenate the content text and feed
 *      Gemma with two prompts:
 *      a) RELATIONSHIP SUMMARY — one short paragraph (3–4 sentences)
 *         about how the user talks with this person — frequency,
 *         topics, recurring patterns, observed tone.
 *      b) BIG FIVE — five 0–1 scores (openness, conscientiousness,
 *         extraversion, agreeableness, neuroticism) + a list of 3–6
 *         notable traits.
 *
 * Stage 2 only runs when the sample is large enough to produce a
 * meaningful read ([ContactProfileRow.MIN_BIG_FIVE_SAMPLE] rows).
 * Below that, the profile is created with raw counts only and Big
 * Five fields stay null; the UI shows "Not enough data yet — Lumi
 * needs more conversations to read this person."
 *
 * Cheap path stays cheap: pure aggregation runs in <100ms over
 * ~1000 rows. The Gemma pass is the expensive bit, and we only
 * call it when (a) the sample crossed the threshold OR (b) it's
 * been > [REINFER_INTERVAL_MS] since the last inference for this
 * contact OR (c) the row count grew by [REINFER_GROW_RATIO].
 *
 * Multi-contact identity matching: the canonical key is
 * `name.trim().lowercase()`. We don't merge by phone yet — that
 * needs deeper rules (same phone, different name spelling is a
 * common case but so is "two friends named Sam"). Future work.
 */
@Singleton
class ContactAnalyticsBuilder @Inject constructor(
    private val vault: LearningVault,
    private val repo: ContactProfileRepository,
    private val favorites: FavoritesStore,
    private val gemma: GemmaExtractor,
    private val userAliases: com.mythara.data.UserAliasesStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class BuildReport(
        val totalContacts: Int,
        val rebuilt: Int,
        val skippedNoData: Int,
        val durationMs: Long,
        val cleanedProfiles: Int = 0,
        val cleanedVaultRows: Int = 0,
    )

    data class CleanupReport(
        val cleanedProfiles: Int,
        val cleanedVaultRows: Int,
    )

    /**
     * One-shot cleanup of phantom contacts whose name matches a user
     * alias. Two destructive operations:
     *   1. Delete vault rows facetted with `contact:<aliasName>` —
     *      these were attributed to a phantom contact by an older
     *      import that predates the user-alias filter.
     *   2. Delete the ContactProfileRow for that name so it stops
     *      appearing on the People screen.
     *
     * Idempotent — running it on an already-clean state does nothing.
     * Called automatically at the start of [rebuildAll] (self-healing)
     * AND exposed standalone so the People-screen "clean up" button
     * can fire it without waiting for a full rebuild.
     */
    suspend fun cleanupAliasMisattributions(): CleanupReport = withContext(Dispatchers.IO) {
        val aliases = runCatching { userAliases.list() }.getOrDefault(emptyList())
        if (aliases.isEmpty()) return@withContext CleanupReport(0, 0)
        var profiles = 0
        var rows = 0
        for (alias in aliases) {
            val name = alias.name.trim()
            if (name.isBlank()) continue
            val key = name.lowercase()
            // Vault rows facetted with `contact:<alias>`.
            val deleted = runCatching { vault.deleteByFacet("contact:$name") }.getOrDefault(0)
            rows += deleted
            // Profile row for this name key (if any).
            val existing = repo.dao.byKey(key)
            if (existing != null) {
                runCatching { repo.dao.deleteByKey(key) }
                profiles++
            }
        }
        if (profiles > 0 || rows > 0) {
            Log.d(TAG, "alias misattribution cleanup: deleted $profiles profile(s) + $rows vault row(s)")
        }
        CleanupReport(cleanedProfiles = profiles, cleanedVaultRows = rows)
    }

    /**
     * Walk the vault and rebuild every contact profile. Called from
     * the analytics screen's pull-to-refresh + on a daily schedule
     * via WorkManager. Force=true skips the staleness gating and
     * re-runs the Gemma inference for every contact (useful for the
     * UI's manual "rebuild" button).
     */
    suspend fun rebuildAll(force: Boolean = false): BuildReport = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        // Self-heal step — purge any phantom-contact rows whose name
        // matches a user alias before we group + rebuild. Older
        // imports (pre alias-filter) attributed user-sent messages to
        // the user's WhatsApp profile name as a "contact", which
        // showed up on the People screen as a duplicate of the user.
        // Running cleanup first means the rebuild doesn't re-create
        // the phantom profile.
        val cleanup = runCatching { cleanupAliasMisattributions() }.getOrDefault(CleanupReport(0, 0))
        // Pull every entry from the vault. Bounded — even a power
        // user's vault stays in the low thousands; tens of thousands
        // would still fit in memory comfortably.
        val all = runCatching { vault.listAll() }.getOrDefault(emptyList())
        val byContact = groupByContact(all)
        val favs = runCatching { favorites.list() }.getOrDefault(emptyList())
        val favByKey = favs.associateBy { it.name.trim().lowercase() }

        var rebuilt = 0
        var skipped = 0
        for ((nameKey, rows) in byContact) {
            if (rows.isEmpty()) { skipped++; continue }
            val existing = repo.dao.byKey(nameKey)
            val displayName = pickDisplayName(rows, favByKey[nameKey]?.name)
            val firstSeen = rows.minOf { it.tsMillis }
            val lastSeen = rows.maxOf { it.tsMillis }
            val imageCount = rows.count {
                val facets = parseFacets(it.facets)
                facets.any { f -> f.startsWith("kind:notification-image") || f.contains("image") }
            }
            val topics = extractTopTopics(rows)

            val needsInference = force ||
                existing == null ||
                rows.size >= ContactProfileRow.MIN_BIG_FIVE_SAMPLE &&
                (existing.openness == null ||
                    (System.currentTimeMillis() - (existing.bigFiveLastUpdatedMs ?: 0)) > REINFER_INTERVAL_MS ||
                    rows.size >= (existing.bigFiveSampleSize * REINFER_GROW_RATIO).toInt())

            val infer = if (needsInference && rows.size >= ContactProfileRow.MIN_BIG_FIVE_SAMPLE && gemma.isReady()) {
                Log.d(TAG, "inference for $displayName: rows=${rows.size} gemmaReady=${gemma.isReady()} → running")
                runCatching { runGemmaInference(displayName, rows) }.getOrNull()
            } else {
                Log.d(
                    TAG,
                    "inference for $displayName SKIPPED — needsInference=$needsInference " +
                        "rows=${rows.size} (need ≥${ContactProfileRow.MIN_BIG_FIVE_SAMPLE}) " +
                        "gemmaReady=${gemma.isReady()}",
                )
                null
            }

            val row = ContactProfileRow(
                nameKey = nameKey,
                displayName = displayName,
                phone = favByKey[nameKey]?.digits?.takeIf { it.isNotBlank() } ?: existing?.phone,
                isFavorite = favByKey[nameKey] != null && favByKey[nameKey]!!.enabled,
                toneLabel = favByKey[nameKey]?.toneLabel ?: existing?.toneLabel,
                firstSeenMs = firstSeen,
                lastInteractionMs = lastSeen,
                messageCount = rows.size,
                imageCount = imageCount,
                topTopicsJson = json.encodeToString(ListSerializer(String.serializer()), topics),
                relationshipSummary = infer?.summary ?: existing?.relationshipSummary,
                openness = infer?.openness ?: existing?.openness,
                conscientiousness = infer?.conscientiousness ?: existing?.conscientiousness,
                extraversion = infer?.extraversion ?: existing?.extraversion,
                agreeableness = infer?.agreeableness ?: existing?.agreeableness,
                neuroticism = infer?.neuroticism ?: existing?.neuroticism,
                bigFiveSampleSize = if (infer != null) rows.size else (existing?.bigFiveSampleSize ?: 0),
                bigFiveLastUpdatedMs = if (infer != null) System.currentTimeMillis() else existing?.bigFiveLastUpdatedMs,
                notableTraitsJson = if (infer != null)
                    json.encodeToString(ListSerializer(String.serializer()), infer.notableTraits)
                else existing?.notableTraitsJson ?: "[]",
                keyPointsJson = if (infer != null)
                    json.encodeToString(ListSerializer(String.serializer()), infer.keyPoints)
                else existing?.keyPointsJson ?: "[]",
                // User-authored notes are NEVER overwritten by the
                // analytics builder — preserve from the previous row.
                userNotes = existing?.userNotes,
                lastBuiltMs = System.currentTimeMillis(),
            )
            repo.dao.upsert(row)
            rebuilt++
        }

        // Carry over orphan favorites (no vault rows yet) so the user
        // sees them in the analytics list with empty stats — better
        // than hiding them.
        for (fav in favs) {
            val key = fav.name.trim().lowercase()
            if (key in byContact || repo.dao.byKey(key) != null) continue
            val row = ContactProfileRow(
                nameKey = key,
                displayName = fav.name,
                phone = fav.digits.takeIf { it.isNotBlank() },
                isFavorite = fav.enabled,
                toneLabel = fav.toneLabel,
                firstSeenMs = System.currentTimeMillis(),
                lastInteractionMs = System.currentTimeMillis(),
                messageCount = 0,
                imageCount = 0,
                topTopicsJson = "[]",
                relationshipSummary = null,
                lastBuiltMs = System.currentTimeMillis(),
            )
            repo.dao.upsert(row)
            rebuilt++
        }

        val dt = System.currentTimeMillis() - t0
        Log.d(TAG, "rebuilt $rebuilt profiles (skipped $skipped, force=$force) in ${dt}ms")
        BuildReport(
            totalContacts = byContact.size,
            rebuilt = rebuilt,
            skippedNoData = skipped,
            durationMs = dt,
            cleanedProfiles = cleanup.cleanedProfiles,
            cleanedVaultRows = cleanup.cleanedVaultRows,
        )
    }

    private fun groupByContact(rows: List<LearningEntity>): Map<String, List<LearningEntity>> {
        val map = HashMap<String, MutableList<LearningEntity>>()
        for (row in rows) {
            val facets = parseFacets(row.facets)
            val contactFacet = facets.firstOrNull { it.startsWith("contact:") } ?: continue
            val name = contactFacet.removePrefix("contact:").trim()
            if (name.isEmpty()) continue
            val key = name.lowercase()
            map.getOrPut(key) { mutableListOf() }.add(row)
        }
        return map
    }

    private fun parseFacets(facetsJson: String): List<String> {
        return runCatching {
            (json.parseToJsonElement(facetsJson) as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.content
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun pickDisplayName(rows: List<LearningEntity>, favName: String?): String {
        if (!favName.isNullOrBlank()) return favName
        // Use the first non-blank contact facet's original casing.
        for (row in rows) {
            val facets = parseFacets(row.facets)
            val contact = facets.firstOrNull { it.startsWith("contact:") }?.removePrefix("contact:")?.trim()
            if (!contact.isNullOrBlank()) return contact
        }
        return "(unknown)"
    }

    private fun extractTopTopics(rows: List<LearningEntity>): List<String> {
        val counts = HashMap<String, Int>()
        for (row in rows) {
            val facets = parseFacets(row.facets)
            for (f in facets) {
                if (f.startsWith("topic:")) {
                    val t = f.removePrefix("topic:").trim().lowercase()
                    if (t.isNotEmpty() && t != "misc") {
                        counts[t] = (counts[t] ?: 0) + 1
                    }
                }
            }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(MAX_TOP_TOPICS)
            .map { it.key }
    }

    private data class InferenceResult(
        val summary: String?,
        val openness: Double?,
        val conscientiousness: Double?,
        val extraversion: Double?,
        val agreeableness: Double?,
        val neuroticism: Double?,
        val notableTraits: List<String>,
        val keyPoints: List<String>,
    )

    /**
     * Run Gemma over the contact's vault content twice: once for a
     * relationship summary paragraph, once for Big Five + traits.
     * Two passes rather than one combined prompt because Gemma's
     * context window is tight and the structured JSON output for
     * Big Five gets cleaner when it doesn't share the prompt with
     * a paragraph generation task.
     */
    private suspend fun runGemmaInference(displayName: String, rows: List<LearningEntity>): InferenceResult {
        // Take the most informative slice: concatenate the most-recent
        // up to MAX_CONTENT_CHARS chars of vault content. Older rows
        // are less representative of the current relationship state.
        val recent = rows.sortedByDescending { it.tsMillis }
        val buf = StringBuilder()
        for (r in recent) {
            if (buf.length + r.content.length > MAX_CONTENT_CHARS) break
            buf.append(r.content).append('\n')
        }
        val text = buf.toString().take(MAX_CONTENT_CHARS)
        if (text.isBlank()) {
            return InferenceResult(null, null, null, null, null, null, emptyList(), emptyList())
        }

        val summary = runCatching {
            gemma.summarise(
                buildSummaryPrompt(displayName, text),
                maxLen = SUMMARY_MAX_LEN,
            )
        }.getOrNull()

        val bigFive = runCatching { runBigFive(displayName, text) }.getOrNull()
        val keyPoints = runCatching { runKeyPoints(displayName, text) }.getOrDefault(emptyList())

        return InferenceResult(
            summary = summary?.trim()?.takeIf { it.isNotEmpty() },
            openness = bigFive?.openness,
            conscientiousness = bigFive?.conscientiousness,
            extraversion = bigFive?.extraversion,
            agreeableness = bigFive?.agreeableness,
            neuroticism = bigFive?.neuroticism,
            notableTraits = bigFive?.traits ?: emptyList(),
            keyPoints = keyPoints,
        )
    }

    /**
     * Extract "things to remember before talking to <Name> again" —
     * recent life events, upcoming dates, sensitive topics, open
     * threads / promises, recurring concerns. Output a JSON string
     * array; parsed forgivingly.
     *
     * Distinct from Big Five (WHO they are) — these are temporal,
     * conversational prep points (WHAT'S HAPPENING). Surfaced at the
     * top of the contact detail screen.
     */
    private suspend fun runKeyPoints(displayName: String, text: String): List<String> {
        val prompt = buildKeyPointsPrompt(displayName, text)
        // Use runRaw — summarise() wraps the prompt as content-to-be-
        // summarised, which would collapse the JSON-array instruction
        // into a paragraph (same bug Big Five hit before this fix).
        val raw = runCatching { gemma.runRaw(prompt, maxLen = KEY_POINTS_MAX_LEN) }.getOrNull()
            ?: return emptyList()
        val arr = extractFirstJsonArray(raw) ?: return emptyList()
        return runCatching {
            (json.parseToJsonElement(arr) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?.map { it.take(MAX_KEY_POINT_LEN) }
                ?.take(MAX_KEY_POINTS)
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun extractFirstJsonArray(text: String): String? {
        val start = text.indexOf('[')
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
            if (c == '[') depth++
            else if (c == ']') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun buildKeyPointsPrompt(displayName: String, text: String): String =
        "Extract 3-7 SHORT actionable points the user would want to remember BEFORE their next conversation with $displayName. " +
            "Examples of good points:\n" +
            "  • recent life events (\"started a new job at Stripe\", \"just had a baby\", \"moving to Boston\")\n" +
            "  • upcoming dates (\"birthday on May 23\", \"trip to Spain next month\", \"exam Friday\")\n" +
            "  • sensitive topics (\"avoid mentioning their ex\", \"recent loss in family\")\n" +
            "  • open threads (\"the user said they'd send the book recommendation\", \"hasn't replied to last 3 messages\")\n" +
            "  • recurring concerns (\"stressed about PhD thesis defense\")\n\n" +
            "Skip:\n" +
            "  • generic personality (that's in Big Five separately)\n" +
            "  • trivial preferences (\"they like coffee\")\n" +
            "  • anything older than 2 months unless still clearly relevant\n" +
            "  • speculation — only points clearly supported by the snippets\n\n" +
            "Return ONLY a JSON array of strings. Each string ≤ 80 chars. No prose, no markdown, no code fences. " +
            "If nothing meaningful is supported by the snippets, return [].\n\n" +
            "Snippets:\n```\n$text\n```\n\nReturn the JSON array now."

    private data class BigFiveOut(
        val openness: Double?,
        val conscientiousness: Double?,
        val extraversion: Double?,
        val agreeableness: Double?,
        val neuroticism: Double?,
        val traits: List<String>,
    )

    /**
     * Use Gemma's facts-extraction pass for Big Five with a custom
     * structured prompt. We ask for JSON with five 0–1 floats + a
     * "traits" array. Parses forgivingly — picks the first balanced
     * { ... } in the response.
     */
    private suspend fun runBigFive(displayName: String, text: String): BigFiveOut? {
        val prompt = buildBigFivePrompt(displayName, text)
        // Reuse extractWithMood as a structured Gemma call; we ignore
        // the facts/mood it returns and look for the raw JSON in the
        // model's response by querying gemma.summarise (which returns
        // the LLM's literal output, not a parsed structure).
        val raw = runCatching { gemma.runRaw(prompt, maxLen = BIG_FIVE_MAX_LEN) }.getOrNull()
        if (raw.isNullOrBlank()) {
            Log.w(TAG, "big-five for $displayName: gemma.runRaw returned ${if (raw == null) "null" else "blank"}")
            return null
        }
        Log.d(TAG, "big-five for $displayName: raw gemma output (first 240 chars): ${raw.take(240)}")
        val obj = extractFirstJsonObject(raw)
        if (obj == null) {
            Log.w(TAG, "big-five for $displayName: no {} object found in gemma output — model returned prose, not JSON")
            return null
        }
        val parsed = runCatching {
            val root = json.parseToJsonElement(obj) as? JsonObject ?: return null
            BigFiveOut(
                openness = (root["openness"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.coerceIn(0.0, 1.0),
                conscientiousness = (root["conscientiousness"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.coerceIn(0.0, 1.0),
                extraversion = (root["extraversion"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.coerceIn(0.0, 1.0),
                agreeableness = (root["agreeableness"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.coerceIn(0.0, 1.0),
                neuroticism = (root["neuroticism"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.coerceIn(0.0, 1.0),
                traits = (root["traits"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    ?.take(MAX_NOTABLE_TRAITS)
                    ?: emptyList(),
            )
        }.getOrElse { e ->
            Log.w(TAG, "big-five for $displayName: JSON parse threw: ${e.message}", e)
            null
        }
        if (parsed != null && parsed.openness == null && parsed.conscientiousness == null) {
            Log.w(TAG, "big-five for $displayName: parsed but all scores null — JSON keys probably mismatched")
        }
        return parsed
    }

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

    private fun buildSummaryPrompt(displayName: String, text: String): String =
        "Summarise how the user relates to $displayName, based on the captured snippets below. " +
            "3-4 sentences. Focus on: typical topics, frequency / cadence of interaction, the user's tone with them, " +
            "any recurring patterns. Third-person, referring to the user as 'the user' and the other person as '$displayName'. " +
            "Don't invent — only what's in the text.\n\nSnippets:\n```\n$text\n```\n\nReturn the summary now."

    private fun buildBigFivePrompt(displayName: String, text: String): String =
        "Estimate $displayName's Big Five personality traits from the conversation snippets below, AS OBSERVED by the user. " +
            "Return ONLY a JSON object with five fields (openness, conscientiousness, extraversion, agreeableness, neuroticism), each a number 0.0 to 1.0, " +
            "plus a 'traits' field: an array of 3-6 short observed-trait strings (e.g. 'curious', 'pragmatic', 'warm', 'easily-stressed'). " +
            "No prose, no markdown, no code fences. Be CONSERVATIVE — middle values (~0.5) when the signal is weak, extreme values only when clearly supported.\n\n" +
            "Snippets:\n```\n$text\n```\n\nReturn the JSON object now."

    companion object {
        private const val TAG = "Mythara/Analytics"
        private const val MAX_TOP_TOPICS = 6
        private const val MAX_NOTABLE_TRAITS = 6
        private const val MAX_CONTENT_CHARS = 4_000
        private const val SUMMARY_MAX_LEN = 600
        private const val BIG_FIVE_MAX_LEN = 600
        private const val KEY_POINTS_MAX_LEN = 800
        private const val MAX_KEY_POINTS = 7
        private const val MAX_KEY_POINT_LEN = 120
        private const val REINFER_INTERVAL_MS = 24L * 3600L * 1000L // re-infer at most once a day
        private const val REINFER_GROW_RATIO = 1.5                  // re-infer when sample grew 50%+
    }
}

