package com.mythara.agent.mood

/**
 * Sub-millisecond lexical mood scorer. Maps a user utterance to one
 * of the moods the rest of the system already understands
 * (anxious / sad / frustrated / excited / happy / null).
 *
 * Why not run Gemma instead: Gemma's mood extraction on Tensor G4 is
 * 1–2s — too slow to inject into the same turn's reply prosody. The
 * lexical scorer is dumb but fires before the user finishes blinking,
 * and the result is good enough to bias the very next TTS reply's
 * pitch + rate. Gemma can still run in the background to enrich
 * vault records with deeper signal (left as a follow-up).
 *
 * Signals weighed:
 *  1. Keyword hits per category — direct emotion vocabulary.
 *  2. Emoji codepoints — typed messages, and Observe → chat
 *     transcripts that came through Gemma already.
 *  3. Punctuation patterns — !!! and ALL-CAPS indicate intensity;
 *     direction is then chosen by which category had any other hit.
 *  4. Negation handling — "not happy" doesn't count as happy. We
 *     scan a 2-token window for negators before each keyword.
 *
 * Result is a single category string or null when nothing decisive
 * showed up. Callers persist the result as `mood:<category>` facet
 * on a vault record so [com.mythara.agent.SemanticRecall.recentMoodTrend]
 * picks it up on the same turn's request build.
 */
object LexicalMoodScorer {

    fun score(text: String): String? {
        if (text.isBlank()) return null
        val normalised = text.lowercase()
        val tokens = TOKEN_SPLIT.split(normalised).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        val raw = mutableMapOf<String, Int>().withDefault { 0 }

        // 1. Keyword scan with simple negation window.
        for ((idx, token) in tokens.withIndex()) {
            for ((mood, set) in KEYWORDS) {
                if (token in set) {
                    if (!isNegatedAt(tokens, idx)) {
                        raw[mood] = raw.getValue(mood) + 1
                    } else {
                        // "not happy" — opposite-ish. Bump the
                        // counter-mood lightly so "I'm not happy"
                        // reads as sad rather than mood-neutral.
                        val flip = OPPOSITE[mood] ?: continue
                        raw[flip] = raw.getValue(flip) + 1
                    }
                }
            }
        }

        // 2. Emoji-as-mood. Iterate codepoints (emoji live above
        //    U+FFFF, java chars are surrogate pairs). Each emoji
        //    counted once per occurrence.
        var i = 0
        while (i < normalised.length) {
            val cp = normalised.codePointAt(i)
            EMOJI_TO_MOOD[cp]?.let { mood -> raw[mood] = raw.getValue(mood) + 1 }
            i += Character.charCount(cp)
        }

        // 3. Intensity boost from screaming punctuation / all-caps.
        //    Direction is taken from whichever mood already has a
        //    hit; pure punctuation alone doesn't pick a mood.
        val screaming = SCREAMING_PUNCT.containsMatchIn(text) || ALL_CAPS_RUN.containsMatchIn(text)
        if (screaming) {
            val leader = raw.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
            if (leader != null) raw[leader] = raw.getValue(leader) + 1
        }

        val (dominant, count) = raw.maxByOrNull { it.value } ?: return null
        if (count == 0) return null
        // Tie-break: if multiple categories tied at the top, return
        // null (mixed signal — don't push prosody in any direction).
        val tied = raw.count { it.value == count } > 1
        return if (tied) null else dominant
    }

    /** Returns true if the token at [idx] is preceded by a negator within 2 tokens. */
    private fun isNegatedAt(tokens: List<String>, idx: Int): Boolean {
        val lo = (idx - 2).coerceAtLeast(0)
        for (j in lo until idx) {
            if (tokens[j] in NEGATORS) return true
        }
        return false
    }

    private val TOKEN_SPLIT = Regex("""[^A-Za-z0-9']+""")

    /** Negation markers within a 2-token window flip the keyword. */
    private val NEGATORS = setOf("not", "n't", "no", "never", "without")

    /** Mood category → emotion vocabulary. */
    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "anxious" to setOf(
            "anxious", "anxiety", "worried", "worry", "stressed", "stress",
            "nervous", "scared", "afraid", "panic", "panicked", "overwhelmed",
            "uneasy", "tense", "freaking", "spiraling",
        ),
        "sad" to setOf(
            "sad", "depressed", "depressing", "down", "miserable", "lonely",
            "empty", "heartbroken", "crying", "tears", "grief", "hurt",
            "disappointed", "blue", "exhausted", "tired",
        ),
        "frustrated" to setOf(
            "frustrated", "annoyed", "irritated", "angry", "mad", "pissed",
            "furious", "fed", "wtf", "ugh", "argh", "stupid", "ridiculous",
            "broken", "useless", "hate",
        ),
        "excited" to setOf(
            "excited", "amazing", "awesome", "incredible", "fantastic",
            "brilliant", "thrilled", "stoked", "pumped", "hyped", "yes",
            "yay", "woohoo", "let's", "finally",
        ),
        "happy" to setOf(
            "happy", "great", "good", "wonderful", "lovely", "nice",
            "thanks", "thank", "appreciate", "grateful", "love", "cool",
            "perfect", "glad", "pleased",
        ),
    )

    /** Used by negation flip — "not sad" reads happy-ish, "not happy" reads sad. */
    private val OPPOSITE: Map<String, String> = mapOf(
        "happy" to "sad",
        "sad" to "happy",
        "excited" to "frustrated",
        "frustrated" to "happy",
        "anxious" to "happy",
    )

    /** Common emoji → mood. Subset; expand as we see things in the wild. */
    private val EMOJI_TO_MOOD: Map<Int, String> = mapOf(
        0x1F600 to "happy",   // 😀
        0x1F603 to "happy",   // 😃
        0x1F604 to "happy",   // 😄
        0x1F642 to "happy",   // 🙂
        0x1F60A to "happy",   // 😊
        0x1F60D to "happy",   // 😍
        0x1F970 to "happy",   // 🥰
        0x1F389 to "excited", // 🎉
        0x1F525 to "excited", // 🔥
        0x1F680 to "excited", // 🚀
        0x1F44F to "excited", // 👏
        0x1F622 to "sad",     // 😢
        0x1F62D to "sad",     // 😭
        0x1F614 to "sad",     // 😔
        0x1F61E to "sad",     // 😞
        0x1F494 to "sad",     // 💔
        0x1F620 to "frustrated", // 😠
        0x1F621 to "frustrated", // 😡
        0x1F624 to "frustrated", // 😤
        0x1F62C to "frustrated", // 😬
        0x1F62B to "anxious", // 😫
        0x1F628 to "anxious", // 😨
        0x1F630 to "anxious", // 😰
        0x1F613 to "anxious", // 😓
    )

    /** Three or more trailing terminators → screaming intensity. */
    private val SCREAMING_PUNCT = Regex("""[!?]{3,}""")
    /** Two+ all-caps tokens in a row → screaming intensity. */
    private val ALL_CAPS_RUN = Regex("""\b[A-Z]{2,}(?:\s+[A-Z]{2,}){1,}\b""")
}
