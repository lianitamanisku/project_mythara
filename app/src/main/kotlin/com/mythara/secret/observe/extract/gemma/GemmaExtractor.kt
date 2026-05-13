package com.mythara.secret.observe.extract.gemma

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
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
 * MediaPipe LLM Inference-backed extractor. Replaces the M8.2.0 regex
 * heuristic with a real on-device Gemma pass. Output shape is the same
 * [LearningExtractor.Extracted] so callers can swap based on
 * availability without conditional branches downstream.
 *
 * The model (Gemma 3 1B IT INT4) is loaded lazily — first [extract]
 * call after the model lands on disk pays the ~1–2s init cost. The
 * resident model is kept across calls; [release] disposes it on
 * shutdown / forget-everything.
 *
 * Prompt design:
 *   - Strict "return ONLY a JSON array" instruction; no markdown, no
 *     prose. Production LLMs ignore this ~5% of the time even with
 *     temperature 0; the parser is robust to a leading code-fence /
 *     trailing newline, ignoring everything outside the first balanced
 *     `[...]`.
 *   - Conservative ask: only durable facts (preferences, identity,
 *     attributes, recurring events) that the user would care to
 *     remember next month. The model is told to return [] when in
 *     doubt — much better than over-extraction.
 *   - Topic facets use slug form ("favourite-colour") so they group
 *     cleanly into `semantic/<topic>.jsonl` files in the memory repo.
 */
@Singleton
class GemmaExtractor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: GemmaModelStore,
) {

    @Volatile private var llm: LlmInference? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isReady(): Boolean = store.isAvailable()

    suspend fun extract(transcript: String): List<LearningExtractor.Extracted> {
        if (transcript.isBlank()) return emptyList()
        if (!store.isAvailable()) return emptyList()
        return withContext(Dispatchers.Default) {
            runCatching {
                val engine = ensureLlm() ?: return@runCatching emptyList()
                val prompt = buildPrompt(transcript)
                val raw = engine.generateResponse(prompt)
                parseFacts(raw)
            }.getOrElse { e ->
                Log.w(TAG, "extract failed: ${e.message}")
                emptyList()
            }
        }
    }

    fun release() {
        runCatching { llm?.close() }
        llm = null
    }

    @Synchronized
    private fun ensureLlm(): LlmInference? {
        llm?.let { return it }
        val path = store.pathOrNull() ?: return null
        return runCatching {
            Log.d(TAG, "loading Gemma from $path")
            val options = LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_TOKENS)
                .build()
            LlmInference.createFromOptions(ctx, options).also { llm = it }
        }.getOrElse { e ->
            Log.e(TAG, "Gemma init failed: ${e.message}", e)
            null
        }
    }

    private fun buildPrompt(transcript: String): String {
        // Gemma 3 chat template; the model is instruction-tuned and
        // responds best when bracketed with the canonical turn tokens.
        return buildString {
            append("<start_of_turn>user\n")
            append(SYSTEM_PROMPT)
            append("\n\nTranscript:\n```\n")
            append(transcript.take(MAX_TRANSCRIPT_CHARS))
            append("\n```\n\n")
            append("Return the JSON array now.")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
    }

    /**
     * Forgiving parser. Looks for the outermost balanced `[...]` in the
     * model's response, parses it as JSON, then walks each object.
     * Anything malformed gets quietly dropped.
     */
    private fun parseFacts(response: String): List<LearningExtractor.Extracted> {
        val arrayText = extractFirstJsonArray(response) ?: return emptyList()
        val arr: JsonArray = runCatching {
            json.parseToJsonElement(arrayText) as? JsonArray
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<LearningExtractor.Extracted>()
        for (el in arr) {
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
            }
            out.add(
                LearningExtractor.Extracted(
                    content = content,
                    facets = facets,
                    // Gemma extractions are far higher quality than regex
                    // — 0.8 default; downgrade later if calibration shows
                    // false positives.
                    conf = 0.8,
                ),
            )
        }
        return out.distinctBy { it.content.lowercase() }
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
        private const val MAX_TOKENS = 512
        private const val MAX_TRANSCRIPT_CHARS = 2_000
        private const val MAX_CONTENT_LEN = 200

        private const val SYSTEM_PROMPT = """You extract durable personal facts from a transcript of speech.

ALL output is in English. If the transcript is in another language, translate the extracted facts into clear English. Never emit non-English content.

Return ONLY a JSON array. No prose, no markdown, no code fences.

Each element is: {"content": "<short statement>", "kind": "<category>", "topic": "<topic-slug>"}

Rules:
- A durable fact is something the user would want remembered for weeks/months (not "I'm hungry now", not "it's raining today").
- Categories ("kind"): preference, identity, attribute, event, fact, schedule, interest.
- "content" is in third-person English describing the user. e.g., "user prefers Python over Java".
- "topic" is a single hyphenated English slug (e.g., "python", "favourite-colour", "morning-routine"). Always English even if the transcript is in another language.
- Skip transcripts that yield nothing. Return [] in that case.
- Do NOT invent facts; only extract what was clearly stated.
- Do NOT include the source-language text in the output; only the English translation."""
    }
}
