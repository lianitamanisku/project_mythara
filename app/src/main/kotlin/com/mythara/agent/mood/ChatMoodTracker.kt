package com.mythara.agent.mood

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks up an emotional signal from each user turn and persists it
 * as a working-tier vault record. The agent loop's [SemanticRecall]
 * reads the vault on the very next request build, so the just-
 * detected mood biases the same turn's reply tone — TTS pitch +
 * rate, ElevenLabs stability + style, AND the agent's "be warm"
 * system-message framing all see the up-to-date trend.
 *
 * Two paths:
 *  - `track(text, fromVoice)` — the fast lexical scorer. Sub-ms,
 *    no model deps. Called before every [AgentRunner.submit] so
 *    the current turn's TTS prosody reflects the just-typed mood.
 *  - (Future) async Gemma re-extraction for stronger signal —
 *    runs in the background, replaces the lexical record's
 *    confidence when it lands.
 *
 * Why not use the existing Observe-side mood extractor: that one
 * runs on transcripts captured by the always-listening pipeline.
 * If Observe is off (default for many users), the vault would
 * never see chat mood, and the agent's tone would stay flat even
 * when the user is clearly anxious / frustrated. This class fills
 * that gap.
 *
 * Privacy: lexical mood is derived from text the user already sent
 * to MiniMax; persisting "mood:sad" + the source text excerpt
 * doesn't leak anything new. Records live in the same vault that
 * already syncs to the user's GitHub backup.
 */
@Singleton
class ChatMoodTracker @Inject constructor(
    private val vault: LearningVault,
) {
    /**
     * Score [text], persist as a vault working-tier record, return
     * the detected mood (or null) for caller convenience. Never
     * throws; vault write failures log + return null.
     *
     * @param fromVoice when true, the source facet is `chat:voice`
     *   so downstream queries can filter spoken vs typed mood.
     */
    suspend fun track(text: String, fromVoice: Boolean): String? {
        val mood = LexicalMoodScorer.score(text) ?: return null
        val src = if (fromVoice) "chat:voice" else "chat:typed"
        val excerpt = text.take(MAX_EXCERPT).trim()
        val content = "[$mood] $excerpt"
        val facets = buildList {
            add("mood:$mood")
            add("kind:chat-mood")
            add("source:$src")
        }
        // Tier=Working — mood is ephemeral by design. SelfOrganizer's
        // episodic-promotion pass eventually rolls recurring mood
        // patterns into longer-lived summaries.
        // Confidence 0.5 — lexical scorer is rough; a real Gemma
        // extraction would land at 0.8+ and replace this row via
        // sha-collision dedup if the content matches.
        runCatching {
            vault.add(
                content = content,
                tier = Tier.Working,
                src = src,
                facets = facets,
                conf = 0.5,
                now = System.currentTimeMillis(),
            )
        }.onFailure { Log.w(TAG, "vault.add failed: ${it.message}") }
        Log.d(TAG, "tracked mood=$mood src=$src on '${excerpt.take(40)}…'")
        return mood
    }

    companion object {
        private const val TAG = "Mythara/Mood"
        private const val MAX_EXCERPT = 160
    }
}
