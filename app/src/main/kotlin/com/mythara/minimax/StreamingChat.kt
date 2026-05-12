package com.mythara.minimax

import android.util.Log
import com.mythara.minimax.models.ChatChunk
import com.mythara.minimax.models.ChatRequest
import com.mythara.minimax.models.ToolCall
import com.mythara.minimax.models.ToolCallFunction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

private const val TAG = "Mythara/Stream"

/**
 * Streaming chat over SSE. Builds a `POST /chat/completions` request with
 * the OpenAI-compat body shape, opens an SSE connection, and emits one
 * [StreamEvent] per parsed chunk. Tool-call argument deltas are
 * concatenated into a single string per (index → id) — *not* parsed as
 * JSON — and surfaced complete when [StreamEvent.ToolCallsReady] fires.
 *
 * Callers consume the Flow and drive the agent loop: text deltas update
 * the on-screen bubble, tool-calls trigger tool execution, completion
 * signals end-of-turn.
 */
class StreamingChat(private val client: MiniMaxClient) {

    sealed interface StreamEvent {
        /** A streamed text delta to append to the assistant bubble. */
        data class Text(val delta: String) : StreamEvent

        /** Tool calls finalised at end-of-turn. Each entry's `arguments`
         *  is the concatenated JSON string ready to deserialize. */
        data class ToolCallsReady(val calls: List<ToolCall>) : StreamEvent

        /** Stream completed cleanly. `finishReason` is one of
         *  "stop" | "tool_calls" | "length" | "content_filter". */
        data class Done(val finishReason: String?) : StreamEvent

        /** Network or API failure — agent loop should surface this. */
        data class Failure(val mapped: ErrorMapper.Mapped) : StreamEvent
    }

    /**
     * Issue a streaming chat completion and emit [StreamEvent]s.
     * The Flow is cold — collecting it opens the connection; cancelling
     * the collector closes the SSE source.
     */
    fun stream(region: Region, request: ChatRequest): Flow<StreamEvent> = callbackFlow {
        val bodyJson = MiniMaxClient.json.encodeToString(ChatRequest.serializer(), request)
        // Redacted log: surface the model + tool count + message count so we can
        // verify the wire payload without printing the user's keys or content.
        Log.d(TAG, "POST chat/completions model=${request.model} tools=${request.tools?.size ?: 0} msgs=${request.messages.size} stream=${request.stream}")
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val httpReq = Request.Builder()
            .url(region.baseUrl + "chat/completions")
            .post(body)
            .header("Accept", "text/event-stream")
            .build()

        // Tool-call deltas arrive incrementally; we accumulate by index
        // until finish_reason == "tool_calls", then emit them all at once.
        val toolBuf = mutableMapOf<Int, ToolCallAccumulator>()

        val listener = object : EventSourceListener() {
            override fun onOpen(es: EventSource, response: okhttp3.Response) {
                Log.d(TAG, "SSE open http=${response.code} ct=${response.header("Content-Type")}")
            }

            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                // MiniMax (OpenAI-compat) uses unnamed `data:` events.
                if (data == "[DONE]") {
                    Log.d(TAG, "SSE [DONE]")
                    trySend(StreamEvent.Done(finishReason = null))
                    close()
                    return
                }
                val chunk = runCatching {
                    MiniMaxClient.json.decodeFromString(ChatChunk.serializer(), data)
                }.getOrElse {
                    Log.w(TAG, "SSE decode failed: ${it.message ?: it.javaClass.simpleName}; raw=${data.take(200)}")
                    return
                }
                val choice = chunk.choices.firstOrNull() ?: return
                choice.delta.content?.let { if (it.isNotEmpty()) trySend(StreamEvent.Text(it)) }
                choice.delta.toolCalls?.forEach { td ->
                    val acc = toolBuf.getOrPut(td.index) { ToolCallAccumulator() }
                    td.id?.let { acc.id = it }
                    td.type?.let { acc.type = it }
                    td.function?.name?.let { acc.name = it }
                    td.function?.arguments?.let { acc.args.append(it) }
                    Log.d(TAG, "tool delta idx=${td.index} name=${acc.name} argsLen=${acc.args.length}")
                }
                choice.finishReason?.let { reason ->
                    Log.d(TAG, "SSE finish_reason=$reason toolBuf=${toolBuf.size}")
                    // MiniMax may emit either "tool_use" (per their function-call
                    // guide) or "tool_calls" (OpenAI canonical). Both mean the
                    // model invoked tools and the loop should resume after we
                    // execute them.
                    val isToolFinish = reason == "tool_calls" || reason == "tool_use"
                    if (isToolFinish && toolBuf.isNotEmpty()) {
                        val finalised = toolBuf.values
                            .sortedBy { it.id }
                            .mapNotNull { it.toToolCall() }
                        Log.d(TAG, "tool_calls ready n=${finalised.size}")
                        trySend(StreamEvent.ToolCallsReady(finalised))
                    }
                    trySend(StreamEvent.Done(reason))
                    close()
                }
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val status = response?.code ?: 0
                val errBody = response?.body?.string()
                Log.e(TAG, "SSE failure http=$status err=${t?.message} body=${errBody?.take(300)}")
                val mapped = ErrorMapper.fromHttp(status, errBody)
                trySend(StreamEvent.Failure(mapped))
                close()
            }
        }

        val source = EventSources.createFactory(client.okHttp).newEventSource(httpReq, listener)
        awaitClose { source.cancel() }
    }

    private class ToolCallAccumulator(
        var id: String = "",
        var type: String = "function",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    ) {
        fun toToolCall(): ToolCall? = if (name.isEmpty()) null else ToolCall(
            id = id.ifEmpty { "call_${name}_${System.nanoTime()}" },
            type = type,
            function = ToolCallFunction(name = name, arguments = args.toString()),
        )
    }
}
