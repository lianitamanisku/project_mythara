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
    private val processCallNotifs: com.mythara.data.ProcessCallNotificationsStore,
    private val notifDecisionEngine: com.mythara.services.NotificationDecisionEngine,
    private val vault: com.mythara.secret.observe.vault.LearningVault,
    private val embedder: com.mythara.secret.observe.embed.LocalEmbedder,
    private val memorySyncScheduler: com.mythara.memory.MemorySyncScheduler,
    val voiceActions: com.mythara.voice.VoiceActionStore,
    val confirmationGate: com.mythara.agent.ConfirmationGate,
    private val allowlist: com.mythara.data.AllowlistStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appCtx: android.content.Context,
    private val deviceIdStore: com.mythara.memory.DeviceIdStore,
    private val lifelineRepo: com.mythara.lifeline.LifelineRepository,
    private val taskRepo: com.mythara.tasks.TaskRepository,
    private val auditRepo: com.mythara.audit.AuditRepository,
) : ViewModel() {
    /** Local device id, cached once on init. Used to identify
     *  foreign-device chat rows for the FromOtherDevice card render. */
    @Volatile private var cachedLocalDeviceId: String? = null
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
        // Cache the local device id once at view-model init so
        // rebuildItems can compare per-row deviceId synchronously.
        viewModelScope.launch {
            cachedLocalDeviceId = runCatching { deviceIdStore.id() }.getOrNull()
            // Re-render after the id resolves so any rows already
            // observed during init get re-bucketed correctly.
            rebuildItems(
                rows = history.dao.listAll(),
                lifeline = runCatching { lifelineRepo.dao.listRecent(limit = 500) }.getOrDefault(emptyList()),
                tasks = runCatching { taskRepo.dao.listRecent(limit = 200) }.getOrDefault(emptyList()),
            )
        }
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
                            // Call notifications skipped unless the user
                            // explicitly opted in (default off — calls
                            // are a different interaction mode the
                            // agent doesn't touch).
                            if (r.looksLikeCall && !processCallNotifs.isEnabled()) return@collect

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
    /**
     * Visual classification for plain text bubbles, so the chat
     * color-codes by WHERE the text came from:
     *  - [User]         — a message the user typed / spoke
     *  - [Notification] — an app notification routed into the agent
     *                     (the `[notif]` turns); muted, Mustard-framed
     *  - [Reply]        — the agent answering a real user message
     *  - [Update]       — the agent reacting to a notification
     *                     (auto-triage output); Malibu-framed
     */
    enum class TextKind { User, Notification, Reply, Update }

    sealed interface ChatItem {
        val key: String
        data class UserText(
            override val key: String,
            val text: String,
            val kind: TextKind = TextKind.User,
        ) : ChatItem
        data class AssistantText(
            override val key: String,
            val text: String,
            val streaming: Boolean = false,
            val kind: TextKind = TextKind.Reply,
        ) : ChatItem
        /**
         * A chat message that originated on a DIFFERENT device and
         * landed locally via memory-sync restore. Rendered as a
         * distinct card (similar shape to tool-call cards) with the
         * authoring device's short id and the role so the user sees
         * "you said X on dev:abc123" rather than mixing it inline as
         * if it happened on this device.
         */
        data class FromOtherDevice(
            override val key: String,
            val role: String,                 // user | assistant
            val text: String,
            val deviceShortId: String,        // last 6 chars of the device UUID
            val tsMillis: Long,
        ) : ChatItem
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
        /**
         * Scheduled reminder. Interleaved by [scheduledForMs] so the
         * card sits in the timeline where it'll fire. Three live
         * states drive visual treatment: future (Malibu), live/now
         * (Citron), terminal (muted SurfaceHigh).
         *
         * Actions on the card (Done, +15m, +1h, +3h) broadcast the
         * same REMINDER_ACTION intent as the notification's actions —
         * single state-machine entry point in ReminderAlarmReceiver.
         */
        data class ReminderCard(
            override val key: String,
            val id: String,
            val title: String,
            val body: String,
            val scheduledForMs: Long,
            val status: String,
            val terminal: Boolean,
            val resultText: String? = null,
        ) : ChatItem

        /**
         * A camera photo from the user's life timeline, interleaved into
         * the chat scrollback by timestamp. The image bytes live in the
         * device's MediaStore (referenced by [uri] when [isLocal]).
         * Cross-device entries arrive via memory-sync as is_local=false
         * rows — UI shows a placeholder card with caption + device label
         * since the bytes aren't reachable from here.
         */
        data class LifelinePhoto(
            override val key: String,
            val isLocal: Boolean,
            val uri: String,
            val captionText: String?,
            val captionStatus: String,
            val takenMs: Long,
            val width: Int,
            val height: Int,
            val deviceShortId: String,
            val placeLabel: String? = null,
        ) : ChatItem

        /**
         * A logged interaction with a contact — a call, message, or
         * WhatsApp Lumi sent, or a contact lookup. Interleaved into the
         * timeline by the interaction's timestamp, like photos and
         * reminders, so the scrollback shows who was contacted when.
         */
        data class PersonInteraction(
            override val key: String,
            val contactName: String,
            val action: String,
            val tsMillis: Long,
            val ok: Boolean,
        ) : ChatItem
    }

    enum class ToolState { Running, Success, Failure }

    /**
     * Filter applied to LifelinePhoto cards in the scrollback. The
     * user picks via the chip strip above the transcript when there's
     * more than one device contributing to the timeline.
     */
    enum class LifelineFilter { ALL, LOCAL, REMOTE }

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
        /** Current per-device filter for lifeline photos. */
        val lifelineFilter: LifelineFilter = LifelineFilter.ALL,
        /** True when the timeline contains photos from devices other
         *  than this one — the filter chip strip is hidden when false
         *  (no point offering a filter that does nothing). */
        val hasRemoteLifeline: Boolean = false,
    )

    private val inflightTools = mutableMapOf<String, ChatItem.Tool>()

    init {
        // Observe chat history + lifeline (photos) + tasks (for
        // ReminderCards). All three streams merge + sort by timestamp
        // in rebuildItems so photos AND scheduled reminders interleave
        // naturally with chat turns — "home of the launcher = life's
        // timeline" relies on this.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                history.dao.observeAll(),
                lifelineRepo.dao.observeRecent(),
                taskRepo.dao.observeRecent(),
                auditRepo.dao.observeRecent(),
            ) { rows, lifeline, tasks, audit ->
                rebuildItems(rows, lifeline, tasks, audit)
            }.collect { }
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

    fun setLifelineFilter(value: LifelineFilter) {
        _ui.update { it.copy(lifelineFilter = value) }
        // Re-render so the new filter applies immediately. Cheap —
        // rebuildItems reads from in-memory state; no DB hit needed.
        viewModelScope.launch {
            rebuildItems(
                rows = runCatching { history.dao.listAll() }.getOrDefault(emptyList()),
                lifeline = runCatching { lifelineRepo.dao.listRecent(limit = 500) }.getOrDefault(emptyList()),
            )
        }
    }

    private fun rebuildItems(
        rows: List<MessageRow>,
        lifeline: List<com.mythara.lifeline.LifelineEntity> = emptyList(),
        tasks: List<com.mythara.tasks.TaskEntity> = emptyList(),
        audit: List<com.mythara.audit.AuditEntry> = emptyList(),
    ) {
        // Build chat items into a tagged list (ts → item) so we can
        // interleave lifeline photo cards by timestamp at the end.
        val tagged = mutableListOf<Pair<Long, ChatItem>>()
        val localDev = cachedLocalDeviceId
        // Tracks whether the most recent user turn was an app
        // notification, so the assistant reply that follows can be
        // classified as an auto-triage "update" vs a real "reply".
        var lastUserWasNotif = false
        for (row in rows) {
            // Foreign-device rows (origin device id differs from this
            // install's id) render as a distinct card, not inline. The
            // user / assistant role is preserved on the card itself so
            // the reader sees BOTH "this came from elsewhere" AND
            // "what was said".
            val rowDev = row.deviceId?.takeIf { it.isNotBlank() }
            if (rowDev != null && localDev != null && rowDev != localDev) {
                if (row.role == "user" || row.role == "assistant") {
                    if (row.role == "user") {
                        lastUserWasNotif = row.content.orEmpty()
                            .startsWith(com.mythara.agent.AgentLoop.NOTIF_PREFIX)
                    }
                    tagged.add(
                        row.tsMillis to ChatItem.FromOtherDevice(
                            key = "x:${row.id}",
                            role = row.role,
                            text = row.content.orEmpty(),
                            deviceShortId = rowDev.takeLast(6),
                            tsMillis = row.tsMillis,
                        ),
                    )
                    continue
                }
                // tool / system rows from other devices are skipped —
                // out of context (tool args reference local IDs).
                if (row.role == "tool") continue
            }
            when (row.role) {
                "user" -> {
                    val txt = row.content.orEmpty()
                    val isNotif = txt.startsWith(com.mythara.agent.AgentLoop.NOTIF_PREFIX)
                    lastUserWasNotif = isNotif
                    tagged.add(
                        row.tsMillis to ChatItem.UserText(
                            key = "u:${row.id}",
                            text = txt,
                            kind = if (isNotif) TextKind.Notification else TextKind.User,
                        ),
                    )
                }
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
                                        tagged.add(
                                            row.tsMillis to ChatItem.AssistantText(
                                                key = "a:${row.id}:$idx",
                                                text = display,
                                                kind = if (lastUserWasNotif) TextKind.Update else TextKind.Reply,
                                            ),
                                        )
                                    }
                                    is Thinks.Segment.Thought -> tagged.add(
                                        row.tsMillis to ChatItem.Thought(
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
                    tagged.add(
                        row.tsMillis to ChatItem.Tool(
                            // Key on the row's own primary id, not the
                            // tool call id: callIds are only unique within
                            // a single turn, and a cross-device history
                            // merge can legitimately bring in two rows
                            // sharing one — keying on callId then crashes
                            // the LazyColumn with a duplicate-key error.
                            key = "tool:${row.id}:$callId",
                            name = toolName,
                            args = "",
                            state = if (isFailure) ToolState.Failure else ToolState.Success,
                            output = row.content,
                        ),
                    )
                }
            }
        }
        // Splice lifeline photos into the timeline. Each row is tagged
        // with takenMs and merged with chat items so the surface reads
        // as one chronological feed — "what I did + what I shot."
        // Per-device filter is applied here so the chip strip works
        // without a re-fetch from disk.
        val filter = _ui.value.lifelineFilter
        val localDevForLifeline = cachedLocalDeviceId
        var hasRemote = false
        for (photo in lifeline) {
            val isLocalPhoto = !photo.isRemote && photo.uri.isNotBlank() && photo.deviceId == localDevForLifeline
            if (!isLocalPhoto) hasRemote = true
            val keep = when (filter) {
                LifelineFilter.ALL -> true
                LifelineFilter.LOCAL -> isLocalPhoto
                LifelineFilter.REMOTE -> !isLocalPhoto
            }
            if (!keep) continue
            tagged.add(
                photo.takenMs to ChatItem.LifelinePhoto(
                    key = "lf:${photo.deviceId}:${photo.mediaStoreId}",
                    isLocal = isLocalPhoto,
                    uri = photo.uri,
                    captionText = photo.captionText,
                    captionStatus = photo.captionStatus,
                    takenMs = photo.takenMs,
                    width = photo.width,
                    height = photo.height,
                    deviceShortId = photo.deviceId.takeLast(8),
                    placeLabel = photo.placeLabel,
                ),
            )
        }
        // Scheduled-task reminders interleaved by their scheduled
        // wall-clock time. Only those with a scheduled_for_ms surface
        // as cards; tasks without a schedule live entirely in the
        // tasks panel.
        val terminalStatuses = setOf(
            com.mythara.tasks.TaskStatus.DONE.name,
            com.mythara.tasks.TaskStatus.FAILED.name,
            com.mythara.tasks.TaskStatus.CANCELED.name,
        )
        for (task in tasks) {
            val sched = task.scheduledForMs ?: continue
            val terminal = task.status in terminalStatuses
            tagged.add(
                sched to ChatItem.ReminderCard(
                    key = "rem:${task.id}",
                    id = task.id,
                    title = task.title,
                    body = task.body,
                    scheduledForMs = sched,
                    status = task.status,
                    terminal = terminal,
                    resultText = task.resultText,
                ),
            )
        }
        // People-interaction cards — audit entries that touched a
        // contact (call / message / WhatsApp / lookup) interleave by
        // timestamp, same as photos + reminders.
        for (entry in audit) {
            val contact = entry.contactName?.takeIf { it.isNotBlank() } ?: continue
            tagged.add(
                entry.tsMillis to ChatItem.PersonInteraction(
                    key = "audit:${entry.id}",
                    contactName = contact,
                    action = interactionLabel(entry.toolName, entry.resultOk),
                    tsMillis = entry.tsMillis,
                    ok = entry.resultOk,
                ),
            )
        }
        // Stable sort by timestamp; same-ts items keep insertion order
        // so a tool's stdout still follows its own start row.
        val items = tagged.sortedBy { it.first }.map { it.second }.toMutableList()
        items.addAll(inflightTools.values)
        _ui.update { it.copy(items = items, hasRemoteLifeline = hasRemote) }
    }

    /** Human label for an audit entry that touched a contact. */
    private fun interactionLabel(toolName: String?, ok: Boolean): String {
        val verb = when {
            toolName == null -> "interacted with"
            toolName.contains("call", ignoreCase = true) -> "called"
            toolName.contains("whatsapp", ignoreCase = true) -> "messaged on WhatsApp"
            toolName.contains("sms", ignoreCase = true) -> "texted"
            toolName.contains("contact", ignoreCase = true) -> "looked up"
            else -> "interacted with"
        }
        return if (ok) verb else "tried to $verb"
    }
}
