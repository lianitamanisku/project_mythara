package com.mythara.agent

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Looks up durable facts that are relevant to whatever the user just
 * typed and surfaces them to the chat agent as a system-prompt
 * addendum. The user-visible half of M8.3 SelfOrganizer — the recall
 * side. Periodic organisation (cluster / promote / demote nightly via
 * WorkManager) ships in a follow-up.
 *
 * Pipeline per user turn:
 *   1. [LocalEmbedder] encodes the user's message into a 100-dim
 *      L2-normalised vector.
 *   2. All semantic-tier records with embeddings are scanned linearly
 *      — small vaults stay well under 1ms; we'll swap for an ANN
 *      index when vaults grow past ~5k records.
 *   3. Each candidate's cosine similarity is multiplied by a
 *      reinforcement boost (high `seen` counter ≈ "the user has
 *      mentioned this multiple times") and an exponential time-decay
 *      (half-life ~30 days, so a fact stays salient for about a
 *      season before it starts fading from default recall).
 *   4. Top-K above [MIN_COSINE] threshold are returned.
 *
 * Format for the system prompt is short and unprefaced: the goal is
 * for the model to weave the facts into a natural answer without
 * quoting them. The string is *never* persisted to history — it
 * exists only for the duration of one user turn.
 */
@Singleton
class SemanticRecall @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val embedder: LocalEmbedder,
    private val vault: LearningVault,
) {

    private val Context.ds: DataStore<Preferences>
        by preferencesDataStore("mythara_semantic_recall")
    private val keyEnabled = booleanPreferencesKey("recall.enabled")

    /**
     * User preference: should locally-stored facts AND mood trend be
     * sent to MiniMax as system-prompt context on every chat turn?
     * Defaults to **true** (the original M8.3 behaviour). Off means
     * chat is "pure" — only the messages the user typed/spoke leave
     * the device, nothing from the Observe vault rides along.
     */
    fun enabledFlow(): Flow<Boolean> = ctx.ds.data.map { it[keyEnabled] ?: true }

    suspend fun setEnabled(value: Boolean) {
        ctx.ds.edit { it[keyEnabled] = value }
    }

    private suspend fun isEnabled(): Boolean =
        ctx.ds.data.first()[keyEnabled] ?: true

    data class RecalledFact(
        val content: String,
        val cosine: Float,
        val finalScore: Float,
        val tsMillis: Long,
        val seen: Int,
        val src: String,
    )

    /**
     * Search the semantic tier for facts most relevant to [query].
     * Returns empty list if the embedder isn't ready (USE-Lite not
     * downloaded yet) or no records cross the similarity threshold.
     */
    suspend fun recall(
        query: String,
        topK: Int = TOP_K,
        threshold: Float = MIN_COSINE,
    ): List<RecalledFact> {
        if (!isEnabled()) {
            Log.d(TAG, "recall disabled by user preference")
            return emptyList()
        }
        if (query.isBlank()) return emptyList()
        if (!embedder.isReady()) {
            Log.d(TAG, "embedder not ready; skipping recall")
            return emptyList()
        }
        val queryVec = runCatching { embedder.embed(query) }.getOrElse {
            Log.w(TAG, "embed failed: ${it.message}")
            return emptyList()
        }
        // Pull from both semantic (durable facts) AND episodic
        // (Gemma-summarised conversation windows). Episodic carries
        // longer time-context — "what we discussed last Tuesday" —
        // which complements the fact-shaped semantic tier nicely.
        val records: List<LearningEntity> = buildList {
            addAll(vault.listByTier(Tier.Semantic, limit = SCAN_LIMIT))
            addAll(vault.listByTier(Tier.Episodic, limit = SCAN_LIMIT))
        }.filter { it.embedding != null }
        if (records.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val scored = records.mapNotNull { entity ->
            val vec = runCatching { LocalEmbedder.decode(entity.embedding!!) }.getOrNull()
                ?: return@mapNotNull null
            if (vec.size != queryVec.size) return@mapNotNull null
            val sim = LocalEmbedder.cosine(queryVec, vec)
            if (sim < threshold) return@mapNotNull null
            val daysOld = ((now - entity.lastSeenMs).coerceAtLeast(0L)) / DAY_MS
            val timeDecay = exp(-daysOld / HALF_LIFE_DAYS).toFloat()
            val seenBoost = 1f + SEEN_WEIGHT * (entity.seen - 1).coerceAtLeast(0)
            RecalledFact(
                content = entity.content,
                cosine = sim,
                finalScore = sim * timeDecay * seenBoost,
                tsMillis = entity.tsMillis,
                seen = entity.seen,
                src = entity.src,
            )
        }
        return scored.sortedByDescending { it.finalScore }.take(topK)
    }

    /**
     * Render a list of recalled facts as a single short system-prompt
     * block. Returns null if nothing to render — caller skips prefacing
     * the prior history with anything.
     */
    fun render(facts: List<RecalledFact>): String? {
        if (facts.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("Context from your durable memory of past conversations with this user. ")
        sb.append("Use it to inform your reply naturally; don't quote it back verbatim.\n\n")
        for (f in facts) {
            sb.append("- ").append(f.content)
            if (f.seen > 1) sb.append(" (reinforced ${f.seen}×)")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Looks at semantic records written in the last [windowMs] and
     * extracts the dominant `mood:` facet pattern. Returns the
     * dominant mood label (e.g. "anxious") if one mood accounts for
     * ≥50% of the window's facets, else null when readings are too
     * mixed to call. Empty vault or no Gemma-extracted records
     * yet → also null.
     *
     * Cheap — linear scan of recent records. Calls listByTier with a
     * window cap. Caller is expected to be on a coroutine (this
     * indirectly hits the Room DAO).
     */
    suspend fun recentMoodTrend(windowMs: Long = 6 * 3600 * 1000L): String? {
        if (!isEnabled()) return null
        val cutoff = System.currentTimeMillis() - windowMs
        // Scan BOTH Working and Semantic tiers. Chat-mood records
        // (from ChatMoodTracker) live in Working; Observe-extracted
        // mood records can be either. Filtering by the `kind:*-mood`
        // facet keeps us from picking up random other working-tier
        // records.
        val recent = (
            vault.listByTier(Tier.Working, limit = MOOD_SCAN_LIMIT) +
                vault.listByTier(Tier.Semantic, limit = MOOD_SCAN_LIMIT)
            )
            .filter { it.tsMillis >= cutoff }
        if (recent.isEmpty()) return null
        val moods = recent.mapNotNull { entity ->
            val facets = vault.decodeFacets(entity)
            val isMoodBearing = facets.any { it.startsWith("kind:") && it.endsWith("-mood") } ||
                facets.any { it.startsWith("kind:mood") }
            if (!isMoodBearing) return@mapNotNull null
            facets.firstOrNull { it.startsWith("mood:") }
                ?.removePrefix("mood:")
        }.filter { it != "unknown" && it.isNotBlank() }
        if (moods.isEmpty()) return null
        val histogram = moods.groupingBy { it }.eachCount()
        val total = moods.size
        val (topMood, topCount) = histogram.maxBy { it.value }
        return if (topCount.toDouble() / total >= MOOD_DOMINANCE_THRESHOLD) topMood else null
    }

    /**
     * The freshest detected mood from the user's recent input (chat
     * lexical, voice acoustic, or Observe extraction). Returns the
     * single most recent mood-bearing vault record's label if it's
     * within [maxAgeMs] — otherwise null.
     *
     * This is the signal that should dominate the CURRENT turn's
     * response prosody / system framing. The 6-hour trend is what
     * shapes longer-arc relational tone; the current mood is what
     * shapes "right now Lumi should sound calmer".
     */
    suspend fun currentMood(maxAgeMs: Long = CURRENT_MOOD_WINDOW_MS): String? {
        if (!isEnabled()) return null
        val cutoff = System.currentTimeMillis() - maxAgeMs
        // listByTier returns DESC by tsMillis — newest first. Walk
        // until we hit either a hit or the cutoff.
        val working = vault.listByTier(Tier.Working, limit = CURRENT_MOOD_SCAN_LIMIT)
        for (entity in working) {
            if (entity.tsMillis < cutoff) break
            val facets = vault.decodeFacets(entity)
            val isMoodBearing = facets.any { it.startsWith("kind:") && it.endsWith("-mood") } ||
                facets.any { it.startsWith("kind:mood") }
            if (!isMoodBearing) continue
            val mood = facets.firstOrNull { it.startsWith("mood:") }
                ?.removePrefix("mood:")
            if (!mood.isNullOrBlank() && mood != "unknown") return mood
        }
        return null
    }

    /**
     * Format mood context as a system-prompt addendum the agent loop
     * prepends to its request. Both `currentMood` (this turn) and
     * `moodTrend` (last 6h) can contribute. The CURRENT mood gets
     * directive language ("the user is X RIGHT NOW — your reply
     * must adapt"); the trend is softer ("they've been X lately").
     *
     * Per-mood guidance is concrete and actionable so the model
     * actually changes behaviour:
     *   - frustrated → shorter, acknowledge, no problem-stacking
     *   - sad        → gentle, validating, no toxic positivity
     *   - anxious    → reassuring, concrete, slow the pace
     *   - excited    → match energy, celebrate
     *   - happy      → warm, light, natural
     */
    fun renderMoodSystemMessage(
        currentMood: String?,
        moodTrend: String?,
    ): String? {
        // Render even when both are null — we want Lumi to know the
        // emotional channel is observed every turn, so she stays
        // realistic rather than defaulting to over-cheerful assistant
        // mode. The "neutral" default still ships personality
        // guidance ("be real, sometimes dry, sometimes warm — not
        // sycophantic").
        val effectiveCurrent = currentMood ?: moodTrend
        val sb = StringBuilder()
        sb.append("EMOTIONAL CONTEXT — read the room. ")
        sb.append("Be a real friend, not a customer-service bot. ")
        sb.append("That means: match the user's energy, don't oversympathise, ")
        sb.append("don't open with 'I'm sorry to hear that' or 'that sounds tough'. ")
        sb.append("Skip emotional analysis — never NAME the user's feelings. ")
        sb.append("Just let the mood shape your tone, pace, word choice, and how much you SAY. ")

        if (!effectiveCurrent.isNullOrBlank()) {
            sb.append("\n\nUser currently reads as: ")
                .append(effectiveCurrent)
                .append(". ")
                .append(directiveFor(effectiveCurrent))
        } else {
            sb.append("\n\nNo strong mood signal — stay natural. Sometimes a dry one-liner is the right reply.")
        }
        if (!moodTrend.isNullOrBlank() && moodTrend != effectiveCurrent) {
            sb.append("\n\nLonger arc: user has been trending ")
                .append(moodTrend)
                .append(" over the past few hours. Background colour only — this turn's signal wins.")
        }
        return sb.toString()
    }

    /** Single-mood backward-compat wrapper for callers passing only a trend. */
    fun renderMoodSystemMessage(moodTrend: String?): String? =
        renderMoodSystemMessage(currentMood = null, moodTrend = moodTrend)

    /**
     * Per-mood directive. Tilted toward REALISTIC over warm — Lumi
     * is a friend, not a therapist or a sycophantic assistant. Each
     * directive includes both DO and DON'T so the model has a sharp
     * edge to push against rather than a fuzzy "be supportive".
     */
    private fun directiveFor(mood: String): String = when (mood) {
        "frustrated" -> "Match the bluntness. Skip caveats, qualifications, and disclaimers. " +
            "Go straight to the useful thing. If the user is being unreasonable, gently push back — " +
            "don't just agree with them. Short replies, no 'I understand your frustration', " +
            "no problem-stacking unless asked. A little dry humour is fine if it fits."
        "sad" -> "Be present, not preachy. Acknowledge briefly (one line at most) and be useful. " +
            "Avoid 'sending hugs', 'I'm sorry you're going through this', or any therapist-speak. " +
            "Don't push them to feel better. If they're venting, let them — answer only what they asked."
        "anxious" -> "Lead with the answer, not the qualifiers. Be the calm friend on the phone: " +
            "concrete, single recommendation, no maybes or hypotheticals. " +
            "If they're spiralling, don't list more variables — narrow the choice for them. " +
            "Don't say 'don't worry'; that's never landed for anyone."
        "excited" -> "Match the energy, mean it. Celebrate the thing. " +
            "Skip cautionary-tale disclaimers unless an actual safety issue. " +
            "Be willing to use exclamation, banter, even a touch of irreverence."
        "happy" -> "Light and casual. Banter is welcome. Keep it short — they don't need much. " +
            "Don't manufacture extra warmth; the room is already warm."
        "calm", "neutral" -> "Be real. Sometimes dry, sometimes warm, sometimes direct. " +
            "Don't default to enthusiastic — that's a tell."
        else -> "Lean toward $mood in tone but stay yourself."
    }

    companion object {
        private const val TAG = "Mythara/Recall"

        /** How many top-scoring facts to surface per turn. */
        const val TOP_K = 6

        /** Records below this raw cosine similarity never make the cut. */
        const val MIN_COSINE = 0.45f

        /** Hard scan cap; bumps to an ANN index when we exceed this. */
        const val SCAN_LIMIT = 500

        /** Cap on records the mood-trend scan walks. */
        const val MOOD_SCAN_LIMIT = 200

        /** Fraction of the window a single mood must own to count as "dominant". */
        const val MOOD_DOMINANCE_THRESHOLD = 0.5

        /**
         * "Right now" mood lookback. 5 minutes is short enough that
         * a record from the just-sent voice/text utterance dominates
         * and stale-but-recent ones don't pollute, long enough that
         * a back-to-back follow-up turn ("wait, also…") still sees
         * the user's emotional state from the original utterance.
         */
        const val CURRENT_MOOD_WINDOW_MS = 5L * 60 * 1000

        /** How many most-recent Working-tier rows the current-mood scan walks. */
        const val CURRENT_MOOD_SCAN_LIMIT = 20

        /** Reinforcement weight: seen=1 → 1.0×, seen=5 → 1.4×, seen=11 → 2.0×. */
        const val SEEN_WEIGHT = 0.1f

        /**
         * Time-decay half-life. A fact last reinforced 30 days ago
         * weighs 0.5× as much as one reinforced today, 0.25× at 60
         * days, etc. Chosen so seasonal context stays warm but old
         * one-off mentions drop out.
         */
        const val HALF_LIFE_DAYS = 30.0
        private const val DAY_MS = 24.0 * 3600.0 * 1000.0
    }
}
