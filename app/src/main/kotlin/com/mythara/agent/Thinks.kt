package com.mythara.agent

/**
 * MiniMax M2.7 (with `reasoning_split=false`) embeds its reasoning trace in
 * the assistant message content using `<think>…</think>` blocks. We want
 * two distinct treatments:
 *
 *  - **UI** — render `<think>` blocks as their own Crush-styled "thinking"
 *    bubbles, separate from the assistant's actual answer. The user can see
 *    the reasoning trace as inspectable context, like a tool call.
 *  - **TTS** — speak the answer, not the reasoning. Strip every `<think>`
 *    block before handing the string to Android's TextToSpeech.
 *
 * For history replay we keep the raw `<think>` content in storage so the
 * next-turn request preserves "the entire response_message" verbatim, per
 * MiniMax's function-call guide.
 */
object Thinks {

    /** Greedy match across newlines, non-greedy on the inner. */
    private val THINK = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)

    /** Returns the text with every `<think>…</think>` block removed, trimmed. */
    fun strip(text: String): String =
        THINK.replace(text, "").trim()

    /**
     * Splits a (possibly partial) assistant message into ordered segments.
     * Each segment is either a [Segment.Text] (assistant's actual reply)
     * or a [Segment.Thought] (reasoning trace from a `<think>` block).
     *
     * Robust to:
     *  - Mid-stream tails where the closing `</think>` hasn't arrived yet
     *    (the open block surfaces as a Thought, marked `closed = false`).
     *  - Multiple `<think>` blocks interleaved with text.
     *  - Pure-text messages (returns a single Text segment).
     */
    fun parse(text: String): List<Segment> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<Segment>()
        var cursor = 0
        for (match in THINK.findAll(text)) {
            if (match.range.first > cursor) {
                val prefix = text.substring(cursor, match.range.first)
                if (prefix.isNotBlank()) out.add(Segment.Text(prefix.trim()))
            }
            val body = match.groupValues[1].trim()
            if (body.isNotEmpty()) out.add(Segment.Thought(body, closed = true))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            val tail = text.substring(cursor)
            // Mid-stream: an open `<think>` with no `</think>` yet. Surface as
            // an unclosed Thought so the UI can render it animating.
            val openIdx = tail.indexOf("<think>")
            if (openIdx >= 0) {
                val before = tail.substring(0, openIdx)
                if (before.isNotBlank()) out.add(Segment.Text(before.trim()))
                val openBody = tail.substring(openIdx + "<think>".length).trim()
                if (openBody.isNotEmpty()) out.add(Segment.Thought(openBody, closed = false))
            } else if (tail.isNotBlank()) {
                out.add(Segment.Text(tail.trim()))
            }
        }
        return out
    }

    sealed interface Segment {
        data class Text(val content: String) : Segment
        data class Thought(val content: String, val closed: Boolean) : Segment
    }
}
