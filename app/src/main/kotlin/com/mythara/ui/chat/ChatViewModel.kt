package com.mythara.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.agent.AgentLoop
import com.mythara.agent.SpokenText
import com.mythara.agent.Thinks
import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.mic.LanguageDetector
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
    private val languageDetector: LanguageDetector,
    lumiListenerStore: com.mythara.wake.LumiListenerStore,
    val micBroker: com.mythara.mic.MicBroker,
    notifAutoProcessStore: com.mythara.services.NotificationAutoProcessStore,
    private val vault: com.mythara.secret.observe.vault.LearningVault,
    private val embedder: com.mythara.secret.observe.embed.LocalEmbedder,
    private val memorySyncScheduler: com.mythara.memory.MemorySyncScheduler,
    val voiceActions: com.mythara.voice.VoiceActionStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appCtx: android.content.Context,
) : ViewModel() {
    // `_ui` is declared up top so any init block can safely call
    // `_ui.update { ... }` — Kotlin runs property initialisers + init
    // blocks in source order, and Tts.speaking is a StateFlow that
    // emits its initial value synchronously on subscribe, which used
    // to NPE when its collector ran before the property below was
    // initialised.
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        tts.init()
        // "Hey Lumi <query>" → submit the query just like a typed
        // message, but flag it as voice-originated so the agent loop
        // injects the "be brief, no markdown" system prompt.
        viewModelScope.launch {
            lumiListenerStore.wakeQueries.collect { wq -> submit(wq.query, fromVoice = true) }
        }
        // Plumb TTS "is speaking right now" up to the UI so the
        // continuous-voice loop can pause while Lumi is replying out
        // loud — otherwise the mic picks up the assistant's own voice
        // and starts transcribing it.
        viewModelScope.launch {
            tts.speaking.collect { sp ->
                _ui.update { it.copy(speaking = sp) }
            }
        }
        // Auto-process new phone notifications. Guarded by:
        //  - the user toggle in Settings → notification access
        //    (default off; opt-in, since this consumes MiniMax tokens)
        //  - skip ongoing (media controls, FGS pings) — they're never
        //    the user-actionable kind
        //  - skip our own package — Mythara's own FGS notif would
        //    otherwise loop the agent on itself
        //  - skip while Lumi is already mid-reply (speaking/thinking)
        //    to avoid stacking spoken summaries on top of an answer
        //    the user is already hearing
        viewModelScope.launch {
            val selfPkg = appCtx.packageName
            notifAutoProcessStore.enabledFlow().collect { enabled ->
                if (!enabled) return@collect
                // Re-collect newNotifications inside the gated branch
                // so flipping the toggle off cancels the inner collect.
                kotlinx.coroutines.coroutineScope {
                    com.mythara.services.NotificationListener.newNotifications
                        .collect { r ->
                            if (r.ongoing) return@collect
                            if (r.packageName == selfPkg) return@collect
                            // ALWAYS write to long-term memory first —
                            // even if we drop the spoken-summary step
                            // because Lumi is mid-reply, the user's
                            // memory of "Mom messaged me earlier today"
                            // should still be recoverable from the
                            // vault and the GitHub backup.
                            persistNotificationToVault(r)
                            // Nudge MemorySyncScheduler to push within
                            // ~an hour. No-op when sync isn't
                            // configured or a recent sync already ran.
                            runCatching { memorySyncScheduler.fireNowIfStale() }
                            // Drop the *agent-loop* dispatch if we're
                            // mid-conversation; the user will hear the
                            // next notification when Lumi is idle. The
                            // buffer is dropped here intentionally — we
                            // don't queue, because stacking up
                            // summaries while the user is talking would
                            // make the device feel possessed.
                            val u = _ui.value
                            if (u.thinking || u.speaking) return@collect
                            val formatted = formatNotificationForAgent(r) ?: return@collect
                            submit(formatted)
                        }
                }
            }
        }
    }

    /**
     * Write a notification into the [LearningVault] as a working-tier
     * record so it ride-alongs the durable-memory pipeline:
     *
     *  - SemanticRecall can surface it on later chat turns ("did Mom
     *    text me earlier?")
     *  - MemorySync backs it up to the user's private GitHub repo, so a
     *    new Mythara install restores the full notification history
     *  - SelfOrganizer's nightly episodic-promotion pass clusters
     *    similar notifications into weekly summaries automatically
     *
     * Embedded with USE-Lite when the model is available so cosine recall
     * works; falls back to facet+content match otherwise.
     *
     * Caller already filtered ongoing + self-package. We also drop
     * notifications with no human-readable content here — those are
     * almost certainly silent system pings (sync done, location used)
     * and aren't worth a vault row.
     */
    private suspend fun persistNotificationToVault(
        r: com.mythara.services.NotificationListener.Recent,
    ) {
        val title = r.title?.trim().orEmpty()
        val body = r.text?.trim().orEmpty()
        val sub = r.subText?.trim().orEmpty()
        if (title.isEmpty() && body.isEmpty() && sub.isEmpty()) return
        val pkg = r.packageName
        // Same format the chat-side dispatch uses, minus the [notif]
        // wire prefix — that's only meaningful inside AgentLoop.
        val content = buildString {
            append(pkg.substringAfterLast('.', pkg))
            if (title.isNotEmpty()) append(" · ").append(title)
            if (sub.isNotEmpty()) append(" · ").append(sub)
            if (body.isNotEmpty()) append(": ").append(body)
        }
        val facets = buildList {
            add("kind:notification")
            add("pkg:$pkg")
            if (title.isNotEmpty()) add("title:${title.take(80)}")
        }
        val embedding = if (embedder.isReady()) {
            runCatching { embedder.embed(content) }.getOrNull()
        } else null
        runCatching {
            vault.add(
                content = content,
                tier = com.mythara.memory.Tier.Working,
                src = "notif:$pkg",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) com.mythara.secret.observe.embed.EmbeddingsModelStore.MODEL_ID else null,
                conf = 0.7,
                ref = "notifkey:${r.key}",
                now = r.postTimeMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
        }.onFailure { e ->
            android.util.Log.w(
                "Mythara/Notif",
                "vault.add failed for ${r.packageName}: ${e.message}",
            )
        }
    }

    private fun formatNotificationForAgent(r: com.mythara.services.NotificationListener.Recent): String? {
        // Build a compact one-line summary the model can triage. App label
        // would be nicer than raw package, but resolving it requires a
        // PackageManager round-trip and we're optimising for "fires fast
        // when the notification lands" — keep it lean.
        val title = r.title?.trim().orEmpty()
        val body = r.text?.trim().orEmpty()
        val sub = r.subText?.trim().orEmpty()
        // Drop notifications with no human-readable content — they're
        // almost certainly silent system pings (sync done, location used).
        if (title.isEmpty() && body.isEmpty() && sub.isEmpty()) return null
        val pkg = r.packageName.substringAfterLast('.', r.packageName)
        val parts = buildString {
            append(com.mythara.agent.AgentLoop.NOTIF_PREFIX).append(' ')
            append(pkg)
            if (title.isNotEmpty()) append(" · ").append(title)
            if (sub.isNotEmpty()) append(" · ").append(sub)
            if (body.isNotEmpty()) append(": ").append(body)
        }
        return parts
    }

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
        /**
         * Continuous voice-chat mode. When on, ChatScreen runs an
         * always-listening on-device SpeechRecognizer loop and submits
         * each final utterance through [submit]. Off by default — opt
         * in via the chat-header pill.
         */
        val continuousMode: Boolean = false,
        /** True between the user's wake utterance and Lumi's TTS reply finishing. */
        val speaking: Boolean = false,
    )

    private val inflightTools = mutableMapOf<String, ChatItem.Tool>()

    init {
        // Observe persisted history; recompose ChatItems each time the table changes.
        viewModelScope.launch {
            history.dao.observeAll().collect { rows -> rebuildItems(rows) }
        }
    }

    /**
     * Public entry. The `fromVoice` flag tells [AgentLoop] this turn
     * originated from speech (Pixel Buds tap, mic button, continuous
     * mode, wake-word). The agent loop injects a system message that
     * pushes the model toward a short, conversational, no-markdown
     * answer — long paragraphs are unlistenable. Typed messages keep
     * the default behaviour where the model can produce as much
     * structured text as it wants.
     */
    fun submit(text: String) = submit(text, fromVoice = false)

    fun submit(text: String, fromVoice: Boolean) {
        if (text.isBlank()) return
        _ui.update { it.copy(thinking = true, streaming = "", needsApiKey = false, errorBanner = null) }
        viewModelScope.launch {
            agent.submit(text, fromVoice = fromVoice).collect { turn ->
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
                        // Three-step normalisation before TTS:
                        //  1. Thinks.strip            — remove <think>…</think> reasoning
                        //  2. SpokenText.forSpeech    — strip markdown / emoji
                        //  3. LanguageDetector        — auto-pick TTS Locale matching
                        //                               the reply language (Hindi reply
                        //                               → Hindi voice). Falls back to
                        //                               the system default if ML Kit
                        //                               returns `und` or the engine
                        //                               doesn't have voice data.
                        // The userMoodTrend on Turn.Finished (M8.5 phase 3)
                        // lets Tts modulate pitch + rate: softer + slower for
                        // anxious/sad/frustrated users, slightly more upbeat
                        // for excited/happy. Default voice otherwise.
                        val cleaned = Thinks.strip(turn.finalText)
                            .removeSuffix(" [hit max iterations]")
                        val spoken = SpokenText.forSpeech(cleaned)
                        // Safety net for voice mode. The agent-loop
                        // system prompt asks the model to be brief on
                        // voice turns, but if it slips and produces a
                        // paragraph, the spoken path truncates at the
                        // last sentence boundary inside DEFAULT_SPEAK_MAX
                        // so the user isn't stuck listening to a
                        // 90-second monologue. Chat UI still shows
                        // the full text unchanged.
                        val toSpeak = SpokenText.truncateForSpeech(spoken)
                        // Suppress TTS when the model emitted only the
                        // NOSURFACE sentinel — this turn was an
                        // auto-processed notification the model decided
                        // wasn't worth surfacing. The user shouldn't
                        // hear anything.
                        val nosurface = cleaned.trim().equals(
                            com.mythara.agent.AgentLoop.NOSURFACE_TOKEN,
                            ignoreCase = true,
                        )
                        if (toSpeak.isNotBlank() && !nosurface) {
                            launch {
                                val locale = languageDetector.identifyLocale(toSpeak)
                                tts.speak(toSpeak, locale, turn.userMoodTrend)
                            }
                        }
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

    fun setContinuousMode(value: Boolean) = _ui.update { it.copy(continuousMode = value) }

    private fun rebuildItems(rows: List<MessageRow>) {
        val items = mutableListOf<ChatItem>()
        for (row in rows) {
            when (row.role) {
                "user" -> items.add(ChatItem.UserText(key = "u:${row.id}", text = row.content.orEmpty()))
                "assistant" -> {
                    if (!row.content.isNullOrEmpty()) {
                        // Hide NOSURFACE replies entirely — they're the
                        // model saying "this auto-processed notification
                        // wasn't worth surfacing", and the corresponding
                        // user `[notif]` turn is hidden too (handled in
                        // the `user` branch below).
                        val stripped = Thinks.strip(row.content).trim()
                        if (!stripped.equals(
                                com.mythara.agent.AgentLoop.NOSURFACE_TOKEN,
                                ignoreCase = true,
                            )
                        ) {
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
