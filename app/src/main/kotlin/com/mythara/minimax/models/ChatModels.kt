package com.mythara.minimax.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * MiniMax's OpenAI-compatible chat completion shapes. These DTOs marshal
 * the wire format on `POST /v1/chat/completions`. We deliberately mirror
 * the OpenAI schema so a future swap to OpenAI/Anthropic only requires a
 * new endpoint, not a new model layer.
 */

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<Tool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,    // "auto" | "none" | …
    val stream: Boolean = true,
    /** MiniMax range is (0, 1]; default 1.0 on M2.7. Leaving null defers to model default. */
    val temperature: Double? = null,
    /** MiniMax docs use `max_completion_tokens` (OpenAI's newer name). Cap = 2048 on M2.7. */
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    /**
     * MiniMax-specific. When `false`, reasoning tokens are embedded in the
     * assistant message `content`. When `true`, they arrive as a separate
     * `reasoning_details` field on the delta and we'd have to surface them
     * ourselves.
     *
     * The official function-calling example
     * (platform.minimax.io/docs/guides/text-m2-function-call) sets this to
     * `false` for tool use because the loop has to round-trip the assistant
     * message verbatim — splitting reasoning out of `content` would break
     * the next turn. We default to false for the same reason.
     */
    @SerialName("reasoning_split") val reasoningSplit: Boolean? = null,
)

@Serializable
data class ChatMessage(
    val role: String,                                              // "system" | "user" | "assistant" | "tool"
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,    // present on role=tool
    val name: String? = null,                                       // present on role=tool (function name)
)

@Serializable
data class Tool(
    val type: String = "function",
    val function: ToolFunction,
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
data class ToolCallFunction(
    val name: String,
    /** JSON string — *not* a parsed object, per OpenAI/MiniMax convention. */
    val arguments: String,
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

// ---------- Streaming (SSE delta) shapes ----------

/**
 * SSE chunk shape. Each `data: { … }` line decodes to one of these.
 * `choices[0].delta.content` is the streamed text; `delta.tool_calls`
 * carries partial tool-call invocations (arguments arrive concatenated
 * as a string across chunks — buffer until `finish_reason` is set).
 */
@Serializable
data class ChatChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ToolCallFunctionDelta? = null,
)

@Serializable
data class ToolCallFunctionDelta(
    val name: String? = null,
    /** Partial JSON-string fragment. Concatenate across chunks then parse. */
    val arguments: String? = null,
)

// ---------- /models response (used to validate API key on save) ----------

@Serializable
data class ModelsResponse(
    val data: List<ModelEntry> = emptyList(),
)

@Serializable
data class ModelEntry(
    val id: String,
    @SerialName("object") val obj: String? = null,
)
