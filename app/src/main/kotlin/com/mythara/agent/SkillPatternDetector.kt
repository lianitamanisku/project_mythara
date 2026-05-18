package com.mythara.agent

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-turn skill-pattern detector. Extends the in-turn
 * [SkillSuggestionStore] with a rolling history of tool chains
 * across turns so we can suggest saving a skill only when the
 * SAME shape has been repeated enough times to count as a real
 * habit.
 *
 * Why this matters:
 *
 *   SkillSuggestionStore (in-turn) trips after ≥ 2 automation
 *   tools in a single turn. That's too eager — casual asks like
 *   "set an alarm and open Spotify" hit the threshold once and
 *   the user gets the "save as a skill?" follow-up for something
 *   they'll never repeat.
 *
 *   This detector adds a SECOND independent trigger: when the
 *   user has performed the SAME tool sequence ≥ 3 times within
 *   the last 30 days, that's a real, durable pattern worth
 *   surfacing as a skill. "You open WhatsApp + tap the standup
 *   group + type a message" every weekday morning is the
 *   archetypal case.
 *
 * Storage: DataStore-backed JSON list of recent chains, capped at
 * 200 entries. Old entries age out after [RETENTION_DAYS]. Each
 * chain stores just the tool-name shape (no args), keyed on the
 * deterministic chain signature so look-up is a hash check.
 *
 * Sync: the chain history is LOCAL only — patterns are per-device
 * because the user might use one phone for work + a tablet for
 * personal. When a pattern fires the offer + the user accepts, the
 * resulting Skill IS cross-device synced via the existing
 * SkillStore → semantic/skill.jsonl path. So patterns stay local;
 * skills go everywhere.
 */
@Singleton
class SkillPatternDetector @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /** A captured tool chain — just the shape (ordered tool names),
     *  not args. Timestamps drive the rolling-window expiry. */
    @Serializable
    data class ChainRecord(
        val tools: List<String>,
        val tsMs: Long,
    )

    /** Result of [recordChainAndCheck] — caller uses these counts
     *  to decide whether to fire the cross-turn offer. */
    data class PatternHit(
        val chainShape: List<String>,
        /** Total times this shape has appeared in the window
         *  (including the call that just landed). */
        val totalRepeats: Int,
        /** True when [totalRepeats] crosses [REPEAT_THRESHOLD] —
         *  the caller should surface the offer this turn. */
        val shouldOffer: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val listSer = ListSerializer(ChainRecord.serializer())

    /**
     * Record a tool chain that just executed (in any turn) and
     * return how many times this exact shape has been seen in the
     * recent window. Idempotent on the storage layer — every call
     * appends one record, prunes old + over-capacity entries.
     *
     * Chains shorter than [MIN_INTERESTING_LEN] are ignored to
     * keep the history meaningful (a single-tool "turn" isn't a
     * skill, no matter how often you repeat it).
     */
    suspend fun recordChainAndCheck(chain: List<String>): PatternHit? {
        if (chain.size < MIN_INTERESTING_LEN) return null
        val now = System.currentTimeMillis()
        val cutoff = now - RETENTION_DAYS * 24L * 60L * 60L * 1000L

        val before = readChains()
        val pruned = before
            .filter { it.tsMs >= cutoff }
            .takeLast(MAX_HISTORY - 1) // leave room for the new record
        val updated = pruned + ChainRecord(tools = chain, tsMs = now)
        writeChains(updated)

        val matches = updated.count { sameShape(it.tools, chain) }
        val hit = PatternHit(
            chainShape = chain,
            totalRepeats = matches,
            shouldOffer = matches >= REPEAT_THRESHOLD,
        )
        if (hit.shouldOffer) {
            Log.i(
                TAG,
                "cross-turn pattern detected: ${chain.joinToString(" → ")} " +
                    "seen $matches times in last ${RETENTION_DAYS}d — offer-worthy",
            )
        } else {
            Log.d(
                TAG,
                "recorded chain shape: ${chain.joinToString(" → ")} " +
                    "(seen $matches/$REPEAT_THRESHOLD)",
            )
        }
        return hit
    }

    /** Read the full chain history — exposed for the Settings
     *  "Detected patterns" panel. Returns most-recent first. */
    suspend fun history(): List<ChainRecord> = readChains().asReversed()

    /** Aggregate the history into pattern frequencies — used by the
     *  SkillsPanel to render "you've done X N times this month". */
    suspend fun frequencyTable(minRepeats: Int = 2): List<PatternHit> {
        val now = System.currentTimeMillis()
        val cutoff = now - RETENTION_DAYS * 24L * 60L * 60L * 1000L
        val recent = readChains().filter { it.tsMs >= cutoff }
        val byShape = recent.groupBy { it.tools }
        return byShape
            .mapNotNull { (shape, recs) ->
                if (recs.size < minRepeats) null
                else PatternHit(
                    chainShape = shape,
                    totalRepeats = recs.size,
                    shouldOffer = recs.size >= REPEAT_THRESHOLD,
                )
            }
            .sortedByDescending { it.totalRepeats }
    }

    /** Wipe the cross-turn history — exposed for a "forget detected
     *  patterns" Settings affordance. */
    suspend fun clear() {
        writeChains(emptyList())
    }

    private fun sameShape(a: List<String>, b: List<String>): Boolean =
        a.size == b.size && a == b

    private suspend fun readChains(): List<ChainRecord> {
        val raw = ctx.dataStore.data.first()[KEY_CHAINS_JSON] ?: return emptyList()
        return runCatching { json.decodeFromString(listSer, raw) }.getOrDefault(emptyList())
    }

    private suspend fun writeChains(list: List<ChainRecord>) {
        val encoded = json.encodeToString(listSer, list)
        ctx.dataStore.edit { it[KEY_CHAINS_JSON] = encoded }
    }

    companion object {
        private const val TAG = "Mythara/SkillPattern"
        private val KEY_CHAINS_JSON = stringPreferencesKey("skill_pattern_chains")
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "mythara_skill_patterns",
        )

        /** Minimum chain length to bother recording. 2 = "did one
         *  thing after another"; still cheap to filter on. */
        const val MIN_INTERESTING_LEN = 2

        /** Trigger threshold — same chain seen this many times in
         *  the window before the cross-turn offer fires. 3 feels
         *  right: "you've done this exact sequence three times now,
         *  let's save it" reads as "I noticed", not "I'm guessing". */
        const val REPEAT_THRESHOLD = 3

        /** History window. Anything older than this is pruned and
         *  doesn't count against the threshold. 30 days catches
         *  weekly + monthly habits without growing unbounded. */
        const val RETENTION_DAYS = 30

        /** Hard cap on the number of stored chains. Old ones age
         *  out first on every write; this is the belt-and-braces
         *  ceiling. */
        const val MAX_HISTORY = 200
    }
}
