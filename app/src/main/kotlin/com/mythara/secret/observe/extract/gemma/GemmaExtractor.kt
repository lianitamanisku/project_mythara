package com.mythara.secret.observe.extract.gemma

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.mythara.secret.observe.extract.LearningExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM-backed extractor. Replaces the M8.2.1 MediaPipe Tasks-GenAI
 * path with Google's current on-device runtime (LiteRT-LM 0.11+), which
 * consumes the new `.litertlm` bundle format and auto-dispatches to
 * GPU on Pixel Tensor G3/G4 and NPU on Snapdragon 8 Elite. CPU is the
 * fallback elsewhere.
 *
 * Output shape stays as [LearningExtractor.Extracted] so callers (and
 * the heuristic fallback) compose identically downstream.
 *
 * The model (Gemma 4 E2B, ~2.6GB) is loaded lazily — first [extract]
 * call after the bundle lands on disk pays the ~5–10s init cost. The
 * resident engine is kept across calls; [release] disposes it on
 * shutdown / forget-everything.
 *
 * Prompt design:
 *   - Strict "return ONLY a JSON array" instruction; no markdown, no
 *     prose. Gemma 4 ignores this <5% of the time even with greedy
 *     decoding; the parser tolerates a leading fence / trailing newline
 *     by extracting the first balanced `[...]`.
 *   - Conservative ask: only durable facts (preferences, identity,
 *     attributes, recurring events) that the user would care to
 *     remember next month. The model is told to return [] when in
 *     doubt — much better than over-extraction.
 *   - Topic facets use slug form ("favourite-colour") so they group
 *     cleanly into `semantic/<topic>.jsonl` files in the memory repo.
 *   - The chat template is bundled inside the `.litertlm` file and
 *     applied automatically by [com.google.ai.edge.litertlm.Conversation]
 *     — we pass raw text, not `<start_of_turn>…<end_of_turn>` tokens.
 */
@Singleton
class GemmaExtractor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: GemmaModelStore,
) {

    @Volatile private var engine: Engine? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * What [extractWithMood] returns: durable facts (same shape as
     * the heuristic extractor) plus a single mood label inferred from
     * the whole transcript. Mood labels are constrained to a small
     * set ([MOOD_LABELS]) so they're queryable as a facet without an
     * explosion of synonyms.
     */
    data class Result(
        val facts: List<LearningExtractor.Extracted>,
        val mood: String? = null,
    )

    fun isReady(): Boolean = store.isAvailable()

    /**
     * Lift facts + mood from a transcript in one Gemma inference.
     * Returns empty facts + null mood if Gemma isn't loaded yet.
     */
    suspend fun extractWithMood(transcript: String): Result {
        if (transcript.isBlank()) return Result(emptyList(), null)
        if (!store.isAvailable()) return Result(emptyList(), null)
        return withContext(Dispatchers.Default) {
            runCatching {
                val eng = ensureEngine() ?: return@runCatching Result(emptyList(), null)
                val prompt = buildPrompt(transcript)
                val reply: Message = eng.createConversation().use { conv ->
                    conv.sendMessage(Message.of(prompt))
                }
                parseResult(reply.text())
            }.getOrElse { e ->
                Log.w(TAG, "extract failed: ${e.message}")
                Result(emptyList(), null)
            }
        }
    }

    /** Back-compat for callers that only care about facts. */
    suspend fun extract(transcript: String): List<LearningExtractor.Extracted> =
        extractWithMood(transcript).facts

    fun release() {
        runCatching { engine?.close() }
        engine = null
    }

    @Synchronized
    private fun ensureEngine(): Engine? {
        engine?.let { return it }
        val path = store.pathOrNull() ?: return null
        return runCatching {
            Log.d(TAG, "loading Gemma 4 E2B from $path")
            // CPU backend is the universally-supported path. GPU/NPU
            // dispatch is a follow-up optimisation — Backend is an enum
            // in 0.8.0, so swapping is a one-line change once we trust
            // the GPU path on Tensor G3/G4.
            val config = EngineConfig(
                modelPath = path,
                backend = Backend.CPU,
            )
            Engine(config).also { eng ->
                eng.initialize()
                engine = eng
            }
        }.getOrElse { e ->
            Log.e(TAG, "Gemma init failed: ${e.message}", e)
            null
        }
    }

    /**
     * Flatten a [Message] into the concatenated text of its [Content.Text]
     * parts. Gemma 4 only emits text for extraction prompts, but the API
     * supports mixed image/audio content; we ignore non-text parts.
     */
    private fun Message.text(): String =
        contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun buildPrompt(transcript: String): String {
        // LiteRT-LM's Conversation applies the model's chat template
        // automatically (chat_template.jinja inside the bundle), so we
        // just send our system + transcript content as a single user
        // turn.
        return buildString {
            append(SYSTEM_PROMPT)
            append("\n\nTranscript:\n```\n")
            append(transcript.take(MAX_TRANSCRIPT_CHARS))
            append("\n```\n\n")
            append("Return the JSON object now.")
        }
    }

    /**
     * Forgiving parser. Expects an object response of the form:
     *   {"facts": [...], "mood": "calm"}
     * If the LLM emits just an array (no wrapping object) — which can
     * happen for older prompts or when it forgets the schema — fall
     * back to treating that as the facts array with no mood. Any
     * other shape returns empty.
     */
    private fun parseResult(response: String): Result {
        val objText = extractFirstJsonObject(response)
        val arrayText = if (objText == null) extractFirstJsonArray(response) else null

        val factsJson: JsonArray = when {
            objText != null -> runCatching {
                val root = json.parseToJsonElement(objText) as? JsonObject
                root?.get("facts") as? JsonArray
            }.getOrNull()
            arrayText != null -> runCatching {
                json.parseToJsonElement(arrayText) as? JsonArray
            }.getOrNull()
            else -> null
        } ?: return Result(emptyList(), null)

        val mood = if (objText != null) {
            runCatching {
                val root = json.parseToJsonElement(objText) as? JsonObject
                root?.get("mood")?.jsonPrimitive?.contentOrNullSafe()
                    ?.trim()?.lowercase()?.takeIf { it in MOOD_LABELS }
            }.getOrNull()
        } else null

        val moodFacet = mood?.let { "mood:$it" }

        val out = mutableListOf<LearningExtractor.Extracted>()
        for (el in factsJson) {
            val obj = (el as? JsonObject) ?: continue
            val content = obj["content"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
            if (content.isBlank() || content.length > MAX_CONTENT_LEN) continue
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.lowercase().orEmpty()
            val topicRaw = obj["topic"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
            val topic = slug(topicRaw)
            val facets = buildList {
                if (kind.isNotBlank()) add("kind:$kind")
                if (topic.isNotBlank()) add("topic:$topic")
                add("extractor:${GemmaModelStore.MODEL_ID}")
                // Attach mood to every fact derived from this
                // transcript so retrieval-by-mood works
                // ("things I said when frustrated").
                if (moodFacet != null) add(moodFacet)
            }
            out.add(
                LearningExtractor.Extracted(
                    content = content,
                    facets = facets,
                    conf = 0.85,
                ),
            )
        }
        return Result(facts = out.distinctBy { it.content.lowercase() }, mood = mood)
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

    private fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "misc" }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { this.content }.getOrNull()

    @Serializable
    private data class Fact(val content: String, val kind: String? = null, val topic: String? = null)

    companion object {
        private const val TAG = "Mythara/Gemma"
        private const val MAX_TRANSCRIPT_CHARS = 2_000
        private const val MAX_CONTENT_LEN = 200

        /**
         * Allowed mood labels. Conservative set — large enough to be
         * useful as a queryable facet ("things I said when anxious")
         * without exploding into a long tail of synonyms.
         */
        val MOOD_LABELS = setOf(
            "calm", "happy", "excited", "anxious", "frustrated",
            "sad", "neutral", "unknown",
        )

        private const val SYSTEM_PROMPT = """You extract durable personal facts AND the user's emotional state from a transcript of speech.

ALL output is in English. If the transcript is in another language, translate the extracted facts into clear English. Never emit non-English content.

Return ONLY a JSON object with two fields: "facts" (array) and "mood" (string). No prose, no markdown, no code fences.

Schema:
  {"facts": [{"content": "<short statement>", "kind": "<category>", "topic": "<topic-slug>"}], "mood": "<label>"}

Mood labels (pick exactly one): calm, happy, excited, anxious, frustrated, sad, neutral, unknown. Use "unknown" if you can't tell.

Rules:
- A durable fact is something the user would want remembered for weeks/months (not "I'm hungry now", not "it's raining today").
- Categories ("kind"): preference, identity, attribute, event, fact, schedule, interest.
- "content" is in third-person English describing the user. e.g., "user prefers Python over Java".
- "topic" is a single hyphenated English slug (e.g., "python", "favourite-colour", "morning-routine"). Always English even if the transcript is in another language.
- "mood" reflects how the USER (the speaker) seems to be feeling in this transcript, based on word choice and content. Not the topic's emotional valence.
- Skip transcripts that yield no facts. Return {"facts": [], "mood": "<your guess>"} in that case.
- Do NOT invent facts; only extract what was clearly stated.
- Do NOT include the source-language text in the output; only the English translation."""
    }
}
