package com.mythara.secret.observe.extract

import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the best available transcript → facts extractor. Gemma 4 E2B
 * (loaded via [GemmaExtractor]) is preferred — its outputs are more
 * accurate, structured, and include a [Result.mood] field. When
 * Gemma isn't loaded (model not downloaded, init failed, user
 * cleared the cache), we fall back to the [LearningExtractor]
 * heuristic and return `mood = null`.
 *
 * This facade is what ObserveSession (and any future caller that
 * needs facts-from-text) should depend on.
 */
@Singleton
class SemanticExtractor @Inject constructor(
    private val gemma: GemmaExtractor,
    private val heuristic: LearningExtractor,
) {
    data class Result(
        val facts: List<LearningExtractor.Extracted>,
        val mood: String? = null,
        /** Identifier of which extractor produced this result, for forensics. */
        val source: String,
    )

    suspend fun extract(transcript: String): Result {
        if (gemma.isReady()) {
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
