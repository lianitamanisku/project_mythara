package com.mythara.minimax

import android.util.Base64
import android.util.Log
import com.mythara.data.SettingsStore
import com.mythara.minimax.models.VisionChatRequest
import com.mythara.minimax.models.VisionContentPart
import com.mythara.minimax.models.VisionImageUrl
import com.mythara.minimax.models.VisionMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot vision queries to MiniMax-VL-01.
 *
 * Used by the `take_photo` agent tool: after CameraX saves the JPEG,
 * this service base64-encodes it inline and asks the VL model what's
 * in the image. The response comes back synchronously as text — the
 * tool returns it to the agent loop as part of its result, so the
 * model on the next iteration "sees" the photo via the captured text
 * description, without any multimodal plumbing in the streaming path.
 *
 * Why MiniMax-VL-01 specifically (and not just the user's selected
 * model): the user picks M2/M2.5/M2.7 for text reasoning + tool
 * use, but those models aren't vision-capable. Routing vision calls
 * to VL-01 transparently keeps the user's text-model choice
 * undisturbed while still giving the assistant eyes.
 *
 * Non-streaming. SSE doesn't buy us anything here — the description
 * is short, and the tool result needs the full text before it can be
 * returned anyway. Single `POST /chat/completions` with `stream=false`.
 *
 * Privacy: the image bytes leave the device as inline base64 in the
 * request body. There's no Mythara backend; this goes straight to
 * MiniMax under the user's own API key. The saved JPEG file stays
 * in private filesDir/photos/ regardless.
 */
@Singleton
class VisionService @Inject constructor(
    private val settings: SettingsStore,
) {
    /** Successful path: free-text description from VL-01. */
    data class Outcome(val ok: Boolean, val text: String, val code: String? = null)

    /**
     * Send `imageFile` plus a textual `prompt` to MiniMax-VL-01.
     * Returns an [Outcome] — never throws. Caller decides whether to
     * surface the error to the model.
     *
     * @param prompt  Short instruction. Defaults to a generic "describe
     *                what's in this photo" — callers should pass a more
     *                specific prompt when the user's intent is known
     *                (e.g. "is the person in this picture wearing a
     *                helmet?").
     */
    suspend fun describeImage(
        imageFile: File,
        prompt: String = DEFAULT_PROMPT,
    ): Outcome = withContext(Dispatchers.IO) {
        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext Outcome(false, "Image file is missing or empty.", code = "no_image")
        }
        val snap = settings.snapshot()
        val apiKey = snap.apiKey
        if (apiKey.isNullOrBlank()) {
            return@withContext Outcome(false, "MiniMax API key not configured.", code = "missing_api_key")
        }

        // Base64-encode the file. We cap at MAX_BYTES (~4MB pre-encode)
        // to keep the JSON body reasonable; CameraCapture's downscale
        // to ≤1024px long-edge usually leaves us well under 200KB.
        val bytes = runCatching { imageFile.readBytes() }.getOrElse {
            return@withContext Outcome(false, "Couldn't read image bytes: ${it.message}", code = "read_failed")
        }
        if (bytes.size > MAX_BYTES) {
            return@withContext Outcome(
                false,
                "Image is too large (${bytes.size} bytes) — vision is capped at $MAX_BYTES.",
                code = "image_too_large",
            )
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dataUri = "data:image/jpeg;base64,$b64"

        val request = VisionChatRequest(
            model = VISION_MODEL,
            stream = false,
            messages = listOf(
                VisionMessage(
                    role = "user",
                    content = listOf(
                        VisionContentPart(type = "text", text = prompt),
                        VisionContentPart(
                            type = "image_url",
                            imageUrl = VisionImageUrl(url = dataUri, detail = "auto"),
                        ),
                    ),
                ),
            ),
            temperature = 0.4,
            maxCompletionTokens = MAX_RESPONSE_TOKENS,
        )

        val client = MiniMaxClient(apiKey = apiKey, region = snap.region)
        val result = runCatching { client.retrofit.chatCompletionNonStreaming(request) }
        if (result.isFailure) {
            val e = result.exceptionOrNull()
            Log.w(TAG, "vision call threw", e)
            return@withContext Outcome(false, e?.message ?: "network failure", code = "network")
        }
        val res = result.getOrThrow()
        if (!res.isSuccessful) {
            val mapped = ErrorMapper.fromHttp(res.code(), res.errorBody()?.string())
            Log.w(TAG, "vision call ${res.code()}: ${mapped.message}")
            return@withContext Outcome(false, mapped.message, code = mapped.code ?: "http_${res.code()}")
        }
        val text = res.body()?.choices?.firstOrNull()?.message?.content
        if (text.isNullOrBlank()) {
            return@withContext Outcome(false, "Empty response from vision model.", code = "empty")
        }
        Outcome(ok = true, text = text.trim())
    }

    companion object {
        private const val TAG = "Mythara/Vision"

        /**
         * MiniMax's vision-capable model. Documented at
         * platform.minimax.io/docs/guides/text-vl. The default text
         * model selected in Settings (M2 / M2.5 / etc.) stays
         * untouched — vision is its own routing.
         */
        const val VISION_MODEL = "MiniMax-VL-01"

        const val DEFAULT_PROMPT =
            "Describe what's in this photo in 1-2 short, natural sentences. " +
                "Focus on the main subject. Don't speculate beyond what's visible."

        /** Pre-encoding cap. Roughly 4MB JPEG → ~5.3MB base64. */
        private const val MAX_BYTES = 4 * 1024 * 1024

        /** Don't let the model rant — 1-2 sentences fits inside ~200 tokens. */
        private const val MAX_RESPONSE_TOKENS = 256
    }
}
