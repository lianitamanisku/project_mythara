package com.mythara.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.agent.AgentLoop
import com.mythara.agent.Thinks
import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.mic.Tts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the chat surface. Owns:
 *  - the persisted message list materialised as composite [ChatItem]s
 *    (user text, assistant text, tool invocations as paired calls+results)
 *  - a transient buffer of in-flight tool calls so the Crush-style
 *    ● running indicator can render before the result lands
 *  - the streaming assistant text being typed into the latest bubble
 *  - thinking / error / missing-key flags
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agent: AgentLoop,
    private val history: HistoryRepository,
    private val tts: Tts,
) : ViewModel() {
    init { tts.init() }

    /**
     * One renderable row in the timeline. The view composes a list of
     * these instead of raw MessageRow entries because tool calls + their
     * results are paired visually — a single composite block, Crush-style.
     */
    sealed interface ChatItem {
        val key: String
        data class UserText(override val key: String, val text: String) : ChatItem
        data class AssistantText(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem
        /** Reasoning trace extracted from `<think>…</think>` in the model's response. */
        data class Thought(
            override val key: String,
            val text: String,
            val streaming: Boolean = false,
        ) : ChatItem
        data class Tool(
            override val key: String,
            val name: String,
            val args: String,
            val state: ToolState,
            val output: String? = null,
            val durationMs: Long? = null,
        ) : ChatItem
    }

    enum class ToolState { Running, Success, Failure }

    data class UiState(
        val items: List<ChatItem> = emptyList(),
        val streaming: String? = null,
        val thinking: Boolean = false,
        val needsApiKey: Boolean = false,
        val errorBanner: String? = null,
        /** Names of the tools currently registered — surfaced for debug + Settings later. */
        val registeredTools: List<String> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val inflightTools = mutableMapOf<String, ChatItem.Tool>()

    init {
        // Observe persisted history; recompose ChatItems each time the table changes.
        viewModelScope.launch {
            history.dao.observeAll().collect { rows -> rebuildItems(rows) }
        }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        _ui.update { it.copy(thinking = true, streaming = "", needsApiKey = false, errorBanner = null) }
        viewModelScope.launch {
            agent.submit(text).collect { turn ->
                when (turn) {
                    is AgentLoop.Turn.Delta -> _ui.update {
                        it.copy(streaming = (it.streaming ?: "") + turn.text)
                    }
                    is AgentLoop.Turn.ToolStart -> {
                        // Render the bubble in ● running state. The persisted
                        // tool MessageRow doesn't exist yet — registry hasn't
                        // executed — so we shadow-track via inflightTools.
                        val item = ChatItem.Tool(
                            key = "tool:${turn.callId}",
                            name = turn.name,
                            args = turn.args,
                            state = ToolState.Running,
                        )
                        inflightTools[turn.callId] = item
                        // Force a recompose so the running bubble appears.
                        viewModelScope.launch { rebuildItems(history.dao.listAll()) }
                    }
                    is AgentLoop.Turn.ToolEnd -> {
                        inflightTools.remove(turn.callId)
                        // The persisted `role:tool` row will arrive via the
                        // history flow and replace the inflight stub. Flush
                        // streaming buffer here too — the assistant might
                        // have emitted text *before* calling the tool.
                        _ui.update { it.copy(streaming = "") }
                    }
                    is AgentLoop.Turn.Finished -> {
                        _ui.update { it.copy(streaming = null, thinking = false) }
                        // Strip <think>…</think> blocks before speaking — the
                        // reasoning trace is rendered as Thought bubbles in
                        // the UI but should not be read aloud.
                        val spoken = Thinks.strip(turn.finalText)
                            .removeSuffix(" [hit max iterations]")
                        if (spoken.isNotBlank()) tts.speak(spoken)
                    }
                    is AgentLoop.Turn.Error -> _ui.update {
                        it.copy(streaming = null, thinking = false, errorBanner = turn.message)
                    }
                    is AgentLoop.Turn.MissingApiKey -> _ui.update {
                        it.copy(streaming = null, thinking = false, needsApiKey = true)
                    }
                }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(errorBanner = null) }
    fun dismissMissingKey() = _ui.update { it.copy(needsApiKey = false) }

    private fun rebuildItems(rows: List<MessageRow>) {
        val items = mutableListOf<ChatItem>()
        for (row in rows) {
            when (row.role) {
                "user" -> items.add(ChatItem.UserText(key = "u:${row.id}", text = row.content.orEmpty()))
                "assistant" -> {
                    if (!row.content.isNullOrEmpty()) {
                        // Split on <think>…</think> blocks so reasoning renders
                        // as its own Crush-styled bubble, separate from the
                        // assistant's actual reply text.
                        val segments = Thinks.parse(row.content)
                        segments.forEachIndexed { idx, seg ->
                            when (seg) {
                                is Thinks.Segment.Text -> items.add(
                                    ChatItem.AssistantText(key = "a:${row.id}:$idx", text = seg.content),
                                )
                                is Thinks.Segment.Thought -> items.add(
                                    ChatItem.Thought(
                                        key = "t:${row.id}:$idx",
                                        text = seg.content,
                                        streaming = !seg.closed,
                                    ),
                                )
                            }
                        }
                    }
                }
                "tool" -> {
                    val callId = row.toolCallId.orEmpty()
                    val toolName = row.name.orEmpty()
                    val isFailure = row.content.isNullOrBlank() ||
                        row.content.startsWith("fetch failed") ||
                        row.content.startsWith("unknown tool") ||
                        row.content.startsWith("http ")
                    items.add(
                        ChatItem.Tool(
                            key = "tool:$callId",
                            name = toolName,
                            args = "",
                            state = if (isFailure) ToolState.Failure else ToolState.Success,
                            output = row.content,
                        ),
                    )
                }
            }
        }
        items.addAll(inflightTools.values)
        _ui.update { it.copy(items = items) }
    }
}
