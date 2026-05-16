package com.mythara.secret.observe.extract

/**
 * Detects explicit "talk to Mythara" prefixes in Observe transcripts and
 * extracts the tail as a deliberate user note.
 *
 *   "Mythara, remember that the new wifi password is xyz"
 *     → note: "remember that the new wifi password is xyz"
 *
 *   "Hey Mythara note this down — meeting moved to 4pm"
 *     → note: "note this down meeting moved to 4pm"
 *
 * The match is anchored at start-of-utterance only (we don't want
 * "I told Mythara about the bug" to fire). Vosk transcripts are
 * lowercase by default, but the regex is case-insensitive anyway.
 *
 * False-positive surface area is low: Mythara is "Mythara" specifically
 * because the syllable count + vowel pattern is rare in English
 * conversation. If the user starts a sentence with "Mythara" they almost
 * certainly mean the assistant.
 *
 * Pairs with M8.3a's wake-word listener: same trigger phrase, different
 * mode. The wake word opens the chat surface; the note detector lets
 * Observe-mode quietly capture asks-while-passing without needing the
 * user to switch contexts.
 */
object QuickNoteDetector {

    /**
     * Returns the note text (with the "Mythara" prefix stripped) if the
     * transcript begins with a Mythara address, else null.
     *
     * Recognised prefixes:
     *   - "Mythara[,/:] <note>"                  (bare address)
     *   - "Hey Mythara[,/:] <note>"
     *   - "Hi Mythara[,/:] <note>"
     *   - "Okay Mythara[,/:] <note>"
     *   - "Hello Mythara[,/:] <note>"
     *   - Same forms with no comma/colon, just whitespace
     *   - "Mythara please <note>" / "Mythara remember <note>" (imperatives)
     *
     * Also tolerates common Vosk mishears for the proper noun
     * ("loomi", "lumy", "loomie") since "Mythara" is OOV for the en-us
     * small model and likely to get transcribed phonetically.
     */
    fun detect(transcript: String): String? {
        val s = transcript.trim()
        if (s.isEmpty()) return null
        val m = PREFIX_RE.find(s) ?: return null
        return s.substring(m.range.last + 1).trim().ifBlank { null }
    }

    /**
     * Pattern, in order:
     *   - one of two alternations covering Vosk-en-us mishears of
     *     the proper noun "Mythara" (which is out-of-vocab and gets
     *     hallucinated as nearby phonemes):
     *
     *     a) "<opener> me" — Vosk drops the L and renders the
     *        remaining "-uh-mee" as just "me", typically with a short
     *        opener like `a`, `hey`, `hello`, `hi`, `the` from the
     *        original "Hey Mythara". Real samples captured in field test:
     *          "a me" (was "Hey Mythara")
     *          "hello me what time is it" (was "Hey Mythara what time is it")
     *
     *     b) An L-vowel-token whose phonemes are close to "loomy":
     *        `lumi / loomi / loomy / lumey / leumi / lumy / lumie /
     *        loomie / lou me / lou mi`, with an optional standard
     *        opener. Real samples:
     *          "leumi hello" (was "Mythara hello")
     *
     *   - optional connector words (please / can you / remember /
     *     note / jot down) consumed by the prefix so the returned
     *     tail is the actual user query.
     *   - optional `,` `:` `-` or whitespace before the query.
     *
     * Trade-off: pattern (a) makes the regex broader and admits some
     * legitimate "hey me ..." utterances as Mythara commands. Acceptable
     * because that phrasing is unusual in natural speech, and users
     * who say "hey me" deliberately probably want to address the
     * assistant anyway.
     *
     * Mythara phonetic variants (Capability Expansion v2): the wake
     * word is now "Hey Mythara" but Vosk's en-us small model has zero
     * exposure to either "Mythara" or its sibling Lumi, so the same
     * out-of-vocab problem applies. We list common renderings the
     * recognizer produces so the user gets a wake even when the
     * transcription is mangled:
     *   • "my tara" (Vosk splits at the vowel)
     *   • "mithara / methara / mathara / metara / metora" (vowel drift)
     *   • "mai tara / my tarah / my tarra / my tora"
     *   • "mid era / matera" (consonant collapse)
     *   • "mit hara / matt hara" (h-onset rendering)
     * The plain "mythara" form is included too in case a future Vosk
     * model knows the word, and as a hint for users who type the
     * note instead of speaking it.
     */
    private val PREFIX_RE = Regex(
        pattern = """^\s*(?:(?:hey|hi|hello|okay|ok|yo|a|the)\s+me|(?:hey\s+|hi\s+|hello\s+|okay\s+|ok\s+|yo\s+)?(?:lumi|loomi|loomy|lumey|leumi|lumy|lumie|loomie|lou\s+me|lou\s+mi|mythara|my\s+tara|my\s+tarah|my\s+tarra|my\s+tora|mai\s+tara|mithara|methara|mathara|metara|metora|mid\s+era|matera|mit\s+hara|matt\s+hara))\b[\s,:\-]*(?:please\s+|can\s+you\s+|could\s+you\s+|remember\s+|note\s+|jot\s+down\s+)?""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
}
