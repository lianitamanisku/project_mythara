package com.mythara.minimax.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Multimodal chat-completion request — same `POST /v1/chat/completions`
 * endpoint as the text path, but `messages[].content` is an array of
 * content parts (text + image) instead of a plain string. Used by the
 * `take_photo` tool to ask MiniMax-VL-01 to describe a JPEG it just
 * captured.
 *
 * Why this is a separate DTO from [ChatRequest] / [ChatMessage]:
 *  - The existing text path persists `role:user` rows in the Room
 *    history table with `content` as a single TEXT column. Letting
 *    `content` switch between `String` and `List<ContentPart>` would
 *    require either a polymorphic serialiser or a schema migration on
 *    every chat turn — neither buys us anything since vision-mode is a
 *    single non-streaming round-trip per tool call, never persisted.
 *  - The vision endpoint is non-streaming; the agent loop's SSE
 *    machinery (StreamingChat) would have to grow a branch. Cleaner
 *    to issue a standalone request and inline the response into the
 *    tool result.
 *
 * Wire format mirrors OpenAI's `vision` shape (which MiniMax-VL-01
 * accepts verbatim per their docs at platform.minimax.io):
 *
 * ```json
 * {
 *   "model": "MiniMax-VL-01",
 *   "messages": [
 *     { "role": "user", "content": [
 *       {"type": "text", "text": "describe what's in this image"},
 *       {"type": "image_url",
 *        "image_url": {"url": "data:image/jpeg;base64,/9j/4AAQSk..."}}
 *     ]}
 *   ]
 * }
 * ```
 */
@Serializable
data class VisionChatRequest(
    val model: String,
    val messages: List<VisionMessage>,
    val stream: Boolean = false,
    /** MiniMax range is (0, 1]. Null defers to model default. */
    val temperature: Double? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
)

@Serializable
data class VisionMessage(
    val role: String,
    val content: List<VisionContentPart>,
)

@Serializable
data class VisionContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: VisionImageUrl? = null,
)

@Serializable
data class VisionImageUrl(
    /** "data:image/jpeg;base64,…" — inline data URI; no external fetch. */
    val url: String,
    /** "auto" | "low" | "high"; default "auto". */
    val detail: String? = null,
)
