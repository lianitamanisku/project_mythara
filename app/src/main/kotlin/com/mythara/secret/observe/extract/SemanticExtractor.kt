package com.mythara.secret.observe.extract

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the best available transcript → facts extractor.
 *
 * **Heuristic by default.** LiteRT-LM 0.8.0 currently SIGABRTs in
 * native code partway through Gemma 4 E2B model init (see crash
 * captured 2026-05-12: process aborts after walking section_index 3
 * in `litert_lm_loader.cc`, can't be caught from Kotlin since native
 * abort kills the whole process). Until that's understood and we
 * either bump LiteRT-LM, bump the model, or find the config we're
 * missing, Gemma extraction is gated behind an explicit user toggle
 * in Secret Settings and defaults to **off**.
 *
 * When the toggle IS on and the Gemma model is loaded, this returns
 * Gemma's richer output (facts + mood label). When off (or Gemma
 * model not present), falls back to [LearningExtractor] heuristic
 * regex matching and `mood = null`.
 */
@Singleton
class SemanticExtractor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val gemma: GemmaExtractor,
    private val heuristic: LearningExtractor,
) {
    data class Result(
        val facts: List<LearningExtractor.Extracted>,
        val mood: String? = null,
        /** Identifier of which extractor produced this result, for forensics. */
        val source: String,
    )

    private val Context.ds: DataStore<Preferences>
        by preferencesDataStore("mythara_semantic_extractor")
    private val keyGemmaEnabled = booleanPreferencesKey("gemma.enabled")

    /** Live flag for the Settings UI toggle. Default false until proven safe. */
    fun gemmaEnabledFlow(): Flow<Boolean> = ctx.ds.data.map { it[keyGemmaEnabled] ?: false }

    suspend fun setGemmaEnabled(value: Boolean) {
        ctx.ds.edit { it[keyGemmaEnabled] = value }
    }

    suspend fun extract(transcript: String): Result {
        val gemmaOn = ctx.ds.data.first()[keyGemmaEnabled] ?: false
        if (gemmaOn && gemma.isReady()) {
            val r = gemma.extractWithMood(transcript)
            return Result(facts = r.facts, mood = r.mood, source = "gemma")
        }
        return Result(
            facts = heuristic.extract(transcript),
            mood = null,
            source = "heuristic",
        )
    }
}
