package com.mythara.minimax

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini vision backend. An optional alternative to MiniMax-VL-01 for
 * the `take_photo` tool's image-analysis pass.
 *
 * Why a separate service:
 *  - Gemini's wire format is its own thing (REST `:generateContent`,
 *    not the OpenAI-compatible `chat/completions`). Trying to share
 *    DTOs with the MiniMax path means polymorphism we don't need.
 *  - The auth model is different — query-param `?key=…` rather than
 *    a Bearer header.
 *  - Endpoint is fixed to `generativelanguage.googleapis.com`; there
 *    is no region toggle.
 *
 * The key itself is encrypted at rest via Tink in [SettingsStore]
 * exactly like the MiniMax key — same Keystore wrapping. The user
 * provides it through a separate Settings panel.
 *
 * Free tier note: Gemini API offers a generous free tier for personal
 * projects (rate-limited per-minute / per-day). The user creates a
 * key at https://aistudio.google.com/app/apikey and pastes it in.
 */
@Singleton
class GeminiVisionService @Inject constructor() {

    data class Outcome(val ok: Boolean, val text: String, val code: String? = null)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    suspend fun describeImage(
        imageFile: File,
        prompt: String,
        apiKey: String,
        model: String = DEFAULT_MODEL,
    ): Outcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Outcome(false, "Gemini API key not set.", "missing_api_key")
        }
        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext Outcome(false, "Image file missing or empty.", "no_image")
        }
        val bytes = runCatching { imageFile.readBytes() }.getOrElse {
            return@withContext Outcome(false, "Couldn't read image: ${it.message}", "read_failed")
        }
        if (bytes.size > MAX_BYTES) {
            return@withContext Outcome(
                false,
                "Image too large (${bytes.size} bytes), capped at $MAX_BYTES.",
                "image_too_large",
            )
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val body = GenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = prompt),
                        GeminiPart(
                            inlineData = GeminiInlineData(mimeType = "image/jpeg", data = b64),
                        ),
                    ),
                ),
            ),
            generationConfig = GenerationConfig(
                temperature = 0.4,
                maxOutputTokens = MAX_RESPONSE_TOKENS,
            ),
        )
        val bodyJson = runCatching { json.encodeToString(GenerateContentRequest.serializer(), body) }
            .getOrElse {
                return@withContext Outcome(false, "Couldn't serialise request: ${it.message}", "serialise")
            }

        val url = "$BASE_URL/v1beta/models/$model:generateContent?key=$apiKey"
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val result = runCatching { http.newCall(req).execute() }
        if (result.isFailure) {
            val e = result.exceptionOrNull()
            Log.w(TAG, "Gemini call threw", e)
            return@withContext Outcome(false, e?.message ?: "network failure", "network")
        }
        val response = result.getOrThrow()
        response.use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                Log.w(TAG, "Gemini ${res.code}: ${raw.take(400)}")
                val parsedMsg = runCatching {
                    json.decodeFromString(GeminiErrorEnvelope.serializer(), raw).error?.message
                }.getOrNull()
                return@withContext Outcome(
                    false,
                    parsedMsg ?: "HTTP ${res.code}",
                    code = "http_${res.code}",
                )
            }
            val parsed = runCatching {
                json.decodeFromString(GenerateContentResponse.serializer(), raw)
            }.getOrNull()
            val text = parsed
                ?.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(" ")
                ?.trim()
                ?.ifBlank { null }
            if (text.isNullOrBlank()) {
                return@withContext Outcome(false, "Empty response.", "empty")
            }
            Outcome(ok = true, text = text)
        }
    }

    /**
     * Cheap one-shot key validity probe. Hits `:generateContent` with a
     * minimal prompt — same surface the vision path will use, so we
     * verify both that the key is valid AND that the model is
     * reachable for THIS account (some accounts gate vision behind
     * billing setup).
     */
    suspend fun validate(apiKey: String, model: String = DEFAULT_MODEL): Outcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Outcome(false, "Empty key.", "empty")
        val body = GenerateContentRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = "Reply with the single word: ok"))),
            ),
            generationConfig = GenerationConfig(temperature = 0.0, maxOutputTokens = 8),
        )
        val bodyJson = json.encodeToString(GenerateContentRequest.serializer(), body)
        val req = Request.Builder()
            .url("$BASE_URL/v1beta/models/$model:generateContent?key=$apiKey")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val res = runCatching { http.newCall(req).execute() }.getOrElse {
            return@withContext Outcome(false, it.message ?: "network failure", "network")
        }
        res.use {
            if (it.isSuccessful) return@withContext Outcome(true, "key OK · model $model reachable")
            val raw = it.body?.string().orEmpty()
            val msg = runCatching {
                json.decodeFromString(GeminiErrorEnvelope.serializer(), raw).error?.message
            }.getOrNull()
            Outcome(false, msg ?: "HTTP ${it.code}", "http_${it.code}")
        }
    }

    // ---------- wire format DTOs (Gemini-specific) ----------

    @Serializable
    private data class GenerateContentRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GenerationConfig? = null,
    )

    @Serializable
    private data class GeminiContent(
        val role: String? = null,
        val parts: List<GeminiPart>,
    )

    @Serializable
    private data class GeminiPart(
        val text: String? = null,
        val inlineData: GeminiInlineData? = null,
    )

    @Serializable
    private data class GeminiInlineData(
        val mimeType: String,
        val data: String,
    )

    @Serializable
    private data class GenerationConfig(
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
    )

    @Serializable
    private data class GenerateContentResponse(
        val candidates: List<GeminiCandidate> = emptyList(),
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null,
        val finishReason: String? = null,
    )

    @Serializable
    private data class GeminiErrorEnvelope(
        val error: GeminiError? = null,
    )

    @Serializable
    private data class GeminiError(
        val code: Int? = null,
        val message: String? = null,
        val status: String? = null,
    )

    companion object {
        private const val TAG = "Mythara/Gemini"
        private const val BASE_URL = "https://generativelanguage.googleapis.com"

        /**
         * Default to Gemini 2.5 Flash — fast, cheap, good vision, the
         * recommended target for free-tier vision workloads as of
         * May 2026. Users can change via the Settings UI once we
         * surface a model picker (out of scope for the first pass).
         */
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_RESPONSE_TOKENS = 256
    }
}
