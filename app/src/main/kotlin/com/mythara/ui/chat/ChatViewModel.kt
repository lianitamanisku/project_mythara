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
    private val runner: com.mythara.agent.AgentRunner,
    private val history: HistoryRepository,
    private val tts: Tts,
    @Suppress("unused") private val languageDetector: LanguageDetector,
    lumiListenerStore: com.mythara.wake.LumiListenerStore,
    val micBroker: com.mythara.mic.MicBroker,
    notifAutoProcessStore: com.mythara.services.NotificationAutoProcessStore,
    private val autopilotStore: com.mythara.data.AutopilotStore,
    private val notifDecisionEngine: com.mythara.services.NotificationDecisionEngine,
    private val vault: com.mythara.secret.observe.vault.LearningVault,
    private val embedder: com.mythara.secret.observe.embed.LocalEmbedder,
    private val memorySyncScheduler: com.mythara.memory.MemorySyncScheduler,
    val voiceActions: com.mythara.voice.VoiceActionStore,
    val confirmationGate: com.mythara.agent.ConfirmationGate,
    private val allowlist: com.mythara.data.AllowlistStore,
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
            lumiListenerStore.wakeQueries.collect { wq ->
                // Autopilot gate — wake-word triggers are an "auto"
                // path; if the user has flipped autopilot off, drop
                // the query silently. Their next tap on the mic
                // button still works (explicit action, not auto).
                if (!autopilotStore.isEnabled()) return@collect
                submit(wq.query, fromVoice = true)
            }
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

                            // SMART DECISION FIRST. If the auto-
                            // action store has learned to dismiss
                            // this package (≥3 manual dismisses with
                            // ≥70% dismiss-rate), cancel the notif
                            // and skip the agent loop entirely. The
                            // dismissal is logged so list_dismissed_
                            // notifications can surface it later
                            // ("you also missed a Slack ping").
                            val decision = runCatching {
                                notifDecisionEngine.decide(r)
                            }.getOrDefault(com.mythara.services.NotificationDecisionEngine.Decision.Announce)
                            if (decision == com.mythara.services.NotificationDecisionEngine.Decision.AutoDismiss) {
                                runCatching { notifDecisionEngine.applyDismiss(r) }
                                return@collect
                            }

                            // ANNOUNCE path — write to vault for
                            // long-term recall + fire agent loop.
                            persistNotificationToVault(r)
                            runCatching { memorySyncScheduler.fireNowIfStale() }
                            val u = _ui.value
                            if (u.thinking || u.speaking) return@collect
                            // Autopilot gate — even when notification
                            // auto-process is on, master autopilot can
                            // pause the loop without forcing the user
                            // to flip multiple toggles.
                            if (!autopilotStore.isEnabled()) return@collect
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
     * Public entry. Hands the submit off to [AgentRunner] (which
     * runs the agent loop in a process-wide scope so it survives
     * activity death + background). This VM just listens to the
     * runner's turn-event flow for live UI rendering.
     *
     * The `fromVoice` flag tells [AgentLoop] this turn originated
     * from speech (Pixel Buds tap, mic button, continuous mode,
     * wake-word) — the loop injects a system message that pushes
     * the model toward a short, conversational, no-markdown answer.
     */
    fun submit(text: String) = submit(text, fromVoice = false)

    fun submit(text: String, fromVoice: Boolean) {
        if (text.isBlank()) return
        _ui.update { it.copy(thinking = true, streaming = "", needsApiKey = false, errorBanner = null) }
        runner.submit(text, fromVoice = fromVoice)
    }

    /**
     * Voice-input variant carrying the raw PCM that
     * [com.mythara.mic.VoicePcmRecorder] captured alongside the
     * SpeechRecognizer. AgentRunner forwards to the acoustic-aware
     * ChatMoodTracker.trackVoice so the fused mood (text + pitch +
     * energy + rate) drives the response prosody.
     */
    fun submitVoice(text: String, pcm: ShortArray?, pcmSampleRate: Int) {
        if (text.isBlank()) return
        _ui.update { it.copy(thinking = true, streaming = "", needsApiKey = false, errorBanner = null) }
        runner.submit(text, fromVoice = true, pcm = pcm, pcmSampleRate = pcmSampleRate)
    }

    init {
        // Subscribe to AgentRunner's process-wide event stream so the
        // chat UI reflects whatever's happening regardless of which
        // path triggered the turn (composer, voice trigger, wake
        // word, notification auto-process). When this VM dies the
        // collection cancels — but the agent loop itself keeps running
        // in the runner's scope and persists to Room, so a future
        // recomposition of ChatScreen rebuilds the timeline from
        // history.dao without missing turns.
        viewModelScope.launch {
            runner.turnEvents.collect { turn -> handleTurn(turn) }
        }
    }

    private fun handleTurn(turn: AgentLoop.Turn) {
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
                viewModelScope.launch { rebuildItems(history.dao.listAll()) }
            }
            is AgentLoop.Turn.ToolEnd -> {
                inflightTools.remove(turn.callId)
                _ui.update { it.copy(streaming = "") }
            }
            is AgentLoop.Turn.Finished -> {
                // TTS now fires from AgentRunner so a long reply
                // finishes speaking even if the user navigates away
                // mid-utterance. We just clear the UI flags here.
                _ui.update { it.copy(streaming = null, thinking = false) }
            }
            is AgentLoop.Turn.Error -> _ui.update {
                it.copy(streaming = null, thinking = false, errorBanner = turn.message)
            }
            is AgentLoop.Turn.MissingApiKey -> _ui.update {
                it.copy(streaming = null, thinking = false, needsApiKey = true)
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(errorBanner = null) }
    fun dismissMissingKey() = _ui.update { it.copy(needsApiKey = false) }

    /**
     * Resolve a pending ConfirmationGate prompt. Called by
     * [ConfirmationDialog] when the user taps Allow / Deny.
     * Persists the allowlist entry asynchronously when
     * [alwaysAllow] is true; the gate itself is freed immediately.
     */
    fun resolveConfirmation(
        request: com.mythara.agent.ConfirmationGate.ConfirmRequest,
        decision: com.mythara.agent.ConfirmationGate.Decision,
        alwaysAllow: Boolean,
    ) {
        if (alwaysAllow &&
            decision == com.mythara.agent.ConfirmationGate.Decision.Allow &&
            request.allowlistKey != null
        ) {
            viewModelScope.launch { allowlist.allow(request.allowlistKey) }
        }
        confirmationGate.resolve(request.id, decision)
    }

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
                                    is Thinks.Segment.Text -> {
                                        // Backstop: flatten any markdown the
                                        // model emitted into spoken-style
                                        // prose before showing in chat. The
                                        // system prompt forbids markdown but
                                        // models sometimes regress, and the
                                        // user reading "| Time | Event |..."
                                        // in a chat bubble is a bad
                                        // experience. SpokenText.forSpeech
                                        // converts tables/lists/headers into
                                        // flowing sentences. We keep audio
                                        // tags so [laugh]/[sigh] remain
                                        // visible — that's intentional
                                        // emotion context the user can read
                                        // as "Lumi laughed".
                                        val display = SpokenText.forSpeech(
                                            input = seg.content,
                                            keepAudioTags = true,
                                        ).ifBlank { seg.content }
                                        items.add(
                                            ChatItem.AssistantText(key = "a:${row.id}:$idx", text = display),
                                        )
                                    }
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
