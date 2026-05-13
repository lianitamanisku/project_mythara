package com.mythara.agent

import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.data.SettingsStore
import com.mythara.minimax.ErrorMapper
import com.mythara.minimax.MiniMaxClient
import com.mythara.minimax.StreamingChat
import com.mythara.minimax.models.ChatMessage
import com.mythara.minimax.models.ChatRequest
import com.mythara.minimax.models.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mythara's agentic runtime — same shape as Crush's main loop.
 *
 * Per user turn:
 *   1. Persist the user message to history.
 *   2. Snapshot the entire conversation, hand it to MiniMax with the
 *      tools-array attached.
 *   3. Stream the model's response. If it ends in `finish_reason=stop`,
 *      we're done — emit Finished.
 *   4. If it ends in `finish_reason=tool_calls`, persist the assistant
 *      message (text + tool_calls), execute each tool, persist a
 *      `role:tool` message per result, then loop back to step 2 with
 *      the enlarged history. The model sees its tool results and either
 *      generates text or calls more tools.
 *   5. MAX_ITERATIONS caps the loop so a buggy model can't burn the
 *      user's quota forever; the cap shows up as Turn.Finished with a
 *      sentinel suffix.
 *
 * The emitted [Turn] flow lets the UI render tool-call cards in real
 * time — Crush-style "● Reading file…" → "✓ Reading file (0.4s)".
 */
@Singleton
class AgentLoop @Inject constructor(
    private val settings: SettingsStore,
    private val history: HistoryRepository,
    private val registry: ToolRegistry,
    private val recall: SemanticRecall,
) {

    sealed interface Turn {
        /** Streamed text fragment to append to the active assistant bubble. */
        data class Delta(val text: String) : Turn

        /** A tool call is about to run. Render the bubble in "● running" state. */
        data class ToolStart(val callId: String, val name: String, val args: String) : Turn

        /** Tool finished. Render "✓ done (durationMs)" or "× failed". */
        data class ToolEnd(
            val callId: String,
            val name: String,
            val ok: Boolean,
            val output: String,
            val durationMs: Long,
        ) : Turn

        /** End of the entire turn (post-loop). */
        data class Finished(val finalText: String, val iterations: Int) : Turn

        /** Stream-level failure (HTTP / SSE / mapped MiniMax code). */
        data class Error(val message: String, val retryable: Boolean) : Turn

        /** No API key configured yet — UI surfaces a "Settings" prompt. */
        data object MissingApiKey : Turn
    }

    fun submit(userText: String): Flow<Turn> = flow {
        val snap = settings.snapshot()
        val apiKey = snap.apiKey
        if (apiKey.isNullOrBlank()) {
            emit(Turn.MissingApiKey); return@flow
        }

        history.dao.insert(
            MessageRow(tsMillis = System.currentTimeMillis(), role = "user", content = userText),
        )

        // One-shot semantic recall over the user's latest message. The
        // result lasts for the duration of this turn — never persisted
        // to history, never re-computed per tool-use iteration.
        val recalledFacts = recall.recall(userText)
        val recallSystem: ChatMessage? = recall.render(recalledFacts)?.let { rendered ->
            android.util.Log.d(TAG, "injecting ${recalledFacts.size} recalled facts")
            ChatMessage(role = "system", content = rendered)
        }

        val client = MiniMaxClient(apiKey = apiKey, region = snap.region)
        val streaming = StreamingChat(client)

        var iter = 0
        var lastAssistantText = ""

        loop@ while (iter < MAX_ITERATIONS) {
            iter++

            val historyMessages: List<ChatMessage> = history.dao.listAll().map { row ->
                ChatMessage(
                    role = row.role,
                    content = row.content,
                    toolCalls = row.toolCallsJson?.let { decodeToolCalls(it) },
                    toolCallId = row.toolCallId,
                    name = row.name,
                )
            }
            // Prepend the recall system message (if any) so MiniMax sees
            // durable memory before persisted chat history.
            val prior: List<ChatMessage> = if (recallSystem != null) {
                listOf(recallSystem) + historyMessages
            } else historyMessages

            val req = ChatRequest(
                model = snap.model,
                messages = prior,
                tools = registry.apiSchema().takeIf { it.isNotEmpty() },
                toolChoice = if (registry.apiSchema().isNotEmpty()) "auto" else null,
                stream = true,
                // MiniMax M2.7 is a reasoning model; without reasoning_split=false
                // it emits thinking tokens through a side channel
                // (delta.reasoning_details) that the SSE parser doesn't surface
                // and the tool-use loop can't round-trip. Keep reasoning baked
                // into `content` so history replay works verbatim.
                reasoningSplit = false,
            )

            val streamedText = StringBuilder()
            var toolCalls: List<ToolCall> = emptyList()
            var finishReason: String? = null
            var failure: ErrorMapper.Mapped? = null

            streaming.stream(snap.region, req).collect { ev ->
                when (ev) {
                    is StreamingChat.StreamEvent.Text -> {
                        streamedText.append(ev.delta)
                        emit(Turn.Delta(ev.delta))
                    }
                    is StreamingChat.StreamEvent.ToolCallsReady -> toolCalls = ev.calls
                    is StreamingChat.StreamEvent.Done -> finishReason = ev.finishReason
                    is StreamingChat.StreamEvent.Failure -> failure = ev.mapped
                }
            }

            if (failure != null) {
                val f = failure!!
                emit(Turn.Error(f.message, retryable = f.isRetryable))
                return@flow
            }

            lastAssistantText = streamedText.toString()

            // Persist the assistant turn (with tool_calls if present) so the
            // next iteration includes it verbatim — MiniMax requires the
            // assistant `tool_calls` message in history before each
            // `role:tool` reply, or the next call 400s.
            history.dao.insert(
                MessageRow(
                    tsMillis = System.currentTimeMillis(),
                    role = "assistant",
                    content = lastAssistantText.takeIf { it.isNotEmpty() },
                    toolCallsJson = if (toolCalls.isNotEmpty()) encodeToolCalls(toolCalls) else null,
                ),
            )

            // MiniMax (per function-call docs) reports `tool_use`; OpenAI-compat
            // implementations also return `tool_calls`. Treat both as the signal
            // that the next iteration should execute tools + resume.
            val toolFinish = finishReason == "tool_calls" || finishReason == "tool_use"
            if (toolCalls.isEmpty() || !toolFinish) {
                emit(Turn.Finished(lastAssistantText, iterations = iter))
                return@flow
            }

            // Execute every requested tool sequentially; emit start/end so
            // the UI can render Crush-style ● / ✓ glyphs in real time.
            for (call in toolCalls) {
                emit(Turn.ToolStart(call.id, call.function.name, call.function.arguments))
                val t0 = System.nanoTime()
                val result = registry.execute(call.function.name, call.function.arguments)
                val dt = (System.nanoTime() - t0) / 1_000_000
                history.dao.insert(
                    MessageRow(
                        tsMillis = System.currentTimeMillis(),
                        role = "tool",
                        content = result.output,
                        toolCallId = call.id,
                        name = call.function.name,
                    ),
                )
                emit(Turn.ToolEnd(call.id, call.function.name, result.ok, result.output, dt))
            }
            // Continue the outer loop — the next iteration re-streams with
            // the enlarged context (including all tool results).
        }

        // Hit the iteration cap. Surface a soft-stop so the user sees a
        // bounded conversation instead of an infinite-loop bill.
        emit(Turn.Finished(lastAssistantText + " [hit max iterations]", iterations = iter))
    }

    private fun encodeToolCalls(calls: List<ToolCall>): String =
        MiniMaxClient.json.encodeToString(ListSerializer(ToolCall.serializer()), calls)

    private fun decodeToolCalls(s: String): List<ToolCall>? =
        runCatching { MiniMaxClient.json.decodeFromString(ListSerializer(ToolCall.serializer()), s) }
            .getOrNull()

    companion object {
        private const val TAG = "Mythara/Agent"

        /**
         * Safety cap on tool-use iterations per user turn. 8 is generous
         * for genuine multi-step tasks (most stop after 1–3) but stops a
         * broken model from spinning forever on a malformed function call.
         */
        const val MAX_ITERATIONS = 8
    }
}
