package com.mythara.agent

/**
 * Markdown → plain spoken text. The chat surface renders the model's
 * markdown as-is (bold, bullets, code, links) because that's how the
 * model wants to format itself; but Android TextToSpeech will literally
 * read "asterisk asterisk yes asterisk asterisk", which is grating.
 *
 * This transformer:
 *  - Drops fenced code blocks entirely (you don't listen to code; you
 *    read it).
 *  - Unwraps inline code, bold, italic, headings, blockquotes — keeps
 *    the inner text but strips the decoration.
 *  - Resolves `[label](url)` → `label` so URLs aren't spelled out.
 *  - Flattens bullets and numbered lists to plain sentences with
 *    trailing periods so the TTS engine inserts natural pauses.
 *  - Collapses tables into commas (best effort — tables don't speak
 *    well no matter what; the user can read those on screen).
 *  - Collapses multiple blank lines and runs of whitespace.
 *
 * Strip [Thinks.strip] first if the input may contain reasoning blocks —
 * this function does not handle `<think>…</think>`.
 */
object SpokenText {

    // Multi-line regex flags so `^`/`$` anchor per line, not per string.
    private val CODE_BLOCK     = Regex("""```[^\n]*\n.*?```""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE    = Regex("""`([^`\n]+)`""")
    private val IMAGE          = Regex("""!\[([^\]]*)\]\([^)]*\)""")
    private val LINK           = Regex("""\[([^\]]+)\]\([^)]*\)""")
    private val BOLD_STAR      = Regex("""\*\*([^*\n]+)\*\*""")
    private val ITALIC_STAR    = Regex("""\*([^*\n]+)\*""")
    private val BOLD_UNDER     = Regex("""__([^_\n]+)__""")
    private val ITALIC_UNDER   = Regex("""(?<![A-Za-z0-9_])_([^_\n]+)_(?![A-Za-z0-9_])""")
    private val HEADING_MARK   = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
    private val BLOCKQUOTE_MARK = Regex("""^\s*>+\s*""", RegexOption.MULTILINE)
    private val HRULE          = Regex("""^\s*[-*_]{3,}\s*$""", RegexOption.MULTILINE)
    private val TABLE_SEP_ROW  = Regex("""^\s*\|?[\s:|-]+\|[\s:|-]+\|?\s*$""", RegexOption.MULTILINE)
    private val BULLET_MARK    = Regex("""^\s*[-*+•]\s+""", RegexOption.MULTILINE)
    private val NUMBERED_MARK  = Regex("""^\s*\d+[.)]\s+""", RegexOption.MULTILINE)
    private val PIPE           = Regex("""\s*\|\s*""")
    private val MULTI_NL       = Regex("""\n{2,}""")
    private val MULTI_SPACE    = Regex("""[ \t]{2,}""")
    private val SOFT_TERMINATORS = setOf('.', '!', '?', ',', ':', ';')

    /**
     * ElevenLabs inline audio tags: `[laugh]`, `[sighs]`,
     * `[hmm]`, `[whisper]…[/whisper]`, etc. ElevenLabs renders these
     * as real vocal expressions; Android TTS would read them
     * literally ("open bracket laugh close bracket"), so we strip
     * them on the Android path. Conservative pattern: matches
     * lowercase letters + optional /, surrounded by square brackets,
     * no nested brackets.
     */
    private val AUDIO_TAG = Regex("""\[/?[a-z][a-z\s'-]{0,20}\]""")

    fun forSpeech(input: String): String = forSpeech(input, keepAudioTags = false)

    /**
     * @param keepAudioTags when true, ElevenLabs inline tags like
     *   `[laugh]` or `[sigh]` survive — used on the EL path so the
     *   hosted voice can render them as real vocal expressions. The
     *   Android engine path strips them so it doesn't read them out.
     */
    fun forSpeech(input: String, keepAudioTags: Boolean): String {
        if (input.isBlank()) return ""
        var s = input

        // -1. Audio tags — strip on the Android path (we'd hear
        //     "open bracket laugh close bracket"); keep on EL.
        if (!keepAudioTags) {
            s = AUDIO_TAG.replace(s, " ")
        }

        // 0. Drop emoji + emoji-modifier codepoints before any other pass.
        //    Android TTS otherwise reads their CLDR names ("face with tears
        //    of joy", "thumbs up sign"); models love sprinkling them.
        s = stripEmoji(s)

        // 1. Drop fenced code blocks wholesale — meaningless to listen to.
        s = CODE_BLOCK.replace(s, " ")

        // 2. Unwrap inline media + links + emphasis. Order matters: bold-pair
        //    before italic-single so `**x**` doesn't get eaten by the italic rule.
        s = INLINE_CODE.replace(s) { it.groupValues[1] }
        s = IMAGE.replace(s) { it.groupValues[1] }
        s = LINK.replace(s) { it.groupValues[1] }
        s = BOLD_STAR.replace(s) { it.groupValues[1] }
        s = BOLD_UNDER.replace(s) { it.groupValues[1] }
        s = ITALIC_STAR.replace(s) { it.groupValues[1] }
        s = ITALIC_UNDER.replace(s) { it.groupValues[1] }

        // 3. Strip line-prefix decorations.
        s = HEADING_MARK.replace(s, "")
        s = BLOCKQUOTE_MARK.replace(s, "")
        s = HRULE.replace(s, "")
        s = TABLE_SEP_ROW.replace(s, "")
        s = BULLET_MARK.replace(s, "")
        s = NUMBERED_MARK.replace(s, "")

        // 4. Tables → commas. Crude but better than reading "pipe pipe pipe".
        s = PIPE.replace(s, ", ")

        // 5. Per-line: append a soft period if there isn't already terminal
        //    punctuation, so TTS inserts a natural pause between list items.
        s = s.split('\n').joinToString("\n") { line ->
            val t = line.trimEnd()
            if (t.isBlank()) ""
            else if (t.last() in SOFT_TERMINATORS) t
            else "$t."
        }

        // 6. Compact whitespace.
        s = MULTI_NL.replace(s, " ")
        s = s.replace('\n', ' ')
        s = MULTI_SPACE.replace(s, " ")

        return s.trim()
    }

    /**
     * Bound the text read aloud to roughly [maxChars] worth of speech,
     * cutting at the last sentence boundary within that window so the
     * speech doesn't end mid-thought. Used by [com.mythara.mic.Tts]
     * when the agent loop's voice-mode system prompt failed to keep
     * the model brief — a long markdown-stripped paragraph would still
     * take 90+ seconds to read aloud, which is unusable.
     *
     * The chat UI shows the full text; only the TTS path is truncated.
     * Returns the input unchanged when it's already short enough.
     */
    fun truncateForSpeech(text: String, maxChars: Int = DEFAULT_SPEAK_MAX): String {
        if (text.length <= maxChars) return text
        val window = text.take(maxChars)
        // Find the last sentence terminator within the window. If we
        // find one in the back half, cut there for a clean trailing
        // sentence. Otherwise fall back to the last whitespace so we
        // don't break a word in half.
        val terminators = listOf('.', '!', '?')
        val lastTerm = terminators
            .map { ch -> window.lastIndexOf(ch) }
            .maxOrNull() ?: -1
        return if (lastTerm > maxChars / 2) {
            window.substring(0, lastTerm + 1).trimEnd()
        } else {
            val lastSpace = window.lastIndexOf(' ')
            if (lastSpace > maxChars / 2) window.substring(0, lastSpace).trimEnd() + "…"
            else window.trimEnd() + "…"
        }
    }

    /**
     * Default character budget for spoken output. Tuned so a typical
     * Android TTS pace (~150 wpm, ~5 chars/word) renders in under
     * 30 seconds — long enough for any reasonable conversational
     * reply, short enough that a runaway model can't lock the user
     * into a minute-long monologue.
     */
    const val DEFAULT_SPEAK_MAX = 700

    /**
     * Removes emoji + emoji-modifier codepoints. Iterates by Unicode
     * codepoint (not Java `char`) because most emoji live above U+FFFF
     * and would otherwise be processed as surrogate-pair halves.
     *
     * Blocks stripped:
     *  - 0x1F000–0x1FFFF — most emoji, dingbats supplement, pictographs
     *  - 0x2600–0x27BF   — misc symbols (☀ ⚠ ★ …) + dingbats (✂ ✈ ❤ …)
     *  - 0x20D0–0x20FF   — combining marks for symbols (keycap U+20E3)
     *  - 0xFE00–0xFE0F   — variation selectors (esp. VS-16 emoji presentation)
     *  - 0x200D          — zero-width joiner (used in emoji sequences)
     *  - 0xE0020–0xE007F — tag characters (used in flag emoji sequences)
     *
     * Preserved (intentionally outside the stripped ranges):
     *  - ASCII + Latin Extended
     *  - CJK ideographs (0x4E00–0x9FFF)
     *  - Arabic / Hebrew / Devanagari / etc.
     *  - General punctuation (smart quotes, em dashes)
     *  - Arrows (0x2190–0x21FF — keep → ← ↑ ↓ for natural reading)
     *  - Geometric shapes (0x25A0–0x25FF — keep ◆ ● ○ for our own glyphs)
     */
    private fun stripEmoji(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isEmojiCp(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun isEmojiCp(cp: Int): Boolean =
        cp in 0x1F000..0x1FFFF ||
        cp in 0x2600..0x27BF ||
        cp in 0x20D0..0x20FF ||
        cp in 0xFE00..0xFE0F ||
        cp == 0x200D ||
        cp in 0xE0020..0xE007F
}
