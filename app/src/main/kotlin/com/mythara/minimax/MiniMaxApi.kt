package com.mythara.minimax

import com.mythara.minimax.models.ChatResponse
import com.mythara.minimax.models.ModelsResponse
import com.mythara.minimax.models.VisionChatRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit surface for the *non-streaming* endpoints we hit.
 *
 *  - `GET /models` — used to validate the user's API key when they
 *    save it in Settings.
 *  - `POST /chat/completions` for one-shot multimodal queries (vision)
 *    where SSE-streaming isn't worth the round-trip machinery. The
 *    streaming text-completion path lives in [StreamingChat] using
 *    okhttp-sse directly, since Retrofit doesn't model SSE cleanly.
 */
interface MiniMaxApi {
    @GET("models")
    suspend fun listModels(): Response<ModelsResponse>

    /**
     * Non-streaming chat completion for vision (and one-shot text). The
     * full response arrives in a single body; we read `choices[0].message.content`.
     */
    @POST("chat/completions")
    suspend fun chatCompletionNonStreaming(@Body request: VisionChatRequest): Response<ChatResponse>
}
