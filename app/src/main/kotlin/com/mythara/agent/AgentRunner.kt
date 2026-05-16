package com.mythara.agent

import android.content.Context
import android.util.Log
import com.mythara.branding.MoodSink
import com.mythara.branding.ThoughtRippleSink
import com.mythara.mic.LanguageDetector
import com.mythara.mic.Tts
import com.mythara.services.AgentForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide host for [AgentLoop] execution. Replaces the previous
 * "run inside ChatViewModel.viewModelScope" pattern that died with the
 * activity — and broke long-running automation, voice triggers when
 * the chat surface wasn't visible, and TTS replies that started but
 * never finished after the user backgrounded the app.
 *
 * Responsibilities:
 *  - Owns a [SupervisorJob]-scoped CoroutineScope alive for the whole
 *    process. Agent loops run here, surviving activity recreation /
 *    backgrounding.
 *  - Mirrors the streaming Turn events into a process-wide
 *    [turnEvents] SharedFlow that [com.mythara.ui.chat.ChatViewModel]
 *    subscribes to for live UI rendering.
 *  - Owns the TTS handoff. When a turn ends, this object decides
 *    whether to speak, applies SpokenText post-processing, and routes
 *    to Tts. Previously ChatViewModel did this, which meant a reply
 *    started in foreground would stop speaking mid-sentence if the
 *    activity got killed.
 *  - Drives the [AgentForegroundService] lifecycle: starts the FGS
 *    when there's work to do (so background mic + process keepalive
 *    are granted), stops it after [IDLE_TIMEOUT_MS] of no active turns.
 *
 * Thread model:
 *  - `submit()` is non-blocking — launches a coroutine in [scope].
 *  - Concurrent submits run in parallel (mirrors the old viewModelScope
 *    behavior). The agent's sanitizeHistory pass guards against the
 *    orphan-tool-message problem if turns overlap mid-history-write.
 */
@Singleton
class AgentRunner @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val agent: AgentLoop,
    private val tts: Tts,
    private val languageDetector: LanguageDetector,
    private val moodTracker: com.mythara.agent.mood.ChatMoodTracker,
    private val settings: com.mythara.data.SettingsStore,
    private val replyNotification: ReplyNotification,
    /** When the user has Music Mode on, the agent's reply is voiced
     *  as a tone phrase instead of being spoken via TTS. Reading this
     *  here keeps the gating in one place — both the TTS path and the
     *  ChatViewModel's tone-playback path observe the same flag. */
    private val musicMode: com.mythara.data.MusicModeStore,
    private val autoContinue: com.mythara.agent.todo.AgentAutoContinueController,
    private val todoIntentExtractor: com.mythara.agent.todo.TodoIntentExtractor,
    private val graphTurnExtractor: com.mythara.memory.graph.GraphTurnExtractor,
    /** Capability Expansion v2 — per-turn lexical Big Five + Schwartz
     *  values + preferences extractor. Default-on; runs on the same
     *  parallel-coroutine pattern as the other post-turn extractors so
     *  a slow pass on one side doesn't stall the others. */
    private val personaTraitExtractor: com.mythara.analytics.PersonaTraitExtractor,
) {
    /**
     * Process-wide scope. SupervisorJob so one failing turn doesn't
     * cancel the others; Dispatchers.IO because everything down the
     * stack (MiniMax SSE, Room writes, OkHttp calls) is I/O-bound.
     */
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fired for every Turn event from every concurrent agent loop.
     * UI collectors filter by active turn / discriminate. Buffer 64
     * with DROP_OLDEST so a Delta-heavy stream during a long reply
     * doesn't back-pressure the agent loop.
     */
    private val _turnEvents = MutableSharedFlow<AgentLoop.Turn>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val turnEvents: SharedFlow<AgentLoop.Turn> = _turnEvents.asSharedFlow()

    init {
        // Bind auto-continue: the controller calls back here to
        // submit the next item from AgentTodoStore after a turn
        // finishes (with a quiet window so we don't steal the
        // floor from the user). Init lives BELOW the turnEvents
        // property so it's already initialized by the time
        // start(turnEvents) consumes it.
        autoContinue.bindSubmitCallback { promptText ->
            submit(text = promptText, fromVoice = false)
        }
        autoContinue.start(turnEvents)
    }

    /** True when any agent loop is mid-flight. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val activeTurns = AtomicInteger(0)
    @Volatile private var idleStopJob: Job? = null

    /**
     * Submit a user message to the agent. Non-blocking — returns
     * immediately, the actual loop runs on [scope]. Caller observes
     * [turnEvents] for progress.
     *
     * The lifecycle wrap is the keepalive contract:
     *   1. Increment activeTurns and cancel any pending FGS-stop job
     *   2. Start the foreground service (idempotent) — this is what
     *      grants background mic + tells the OS to keep our process
     *      alive through screen-off
     *   3. Run agent.submit().collect, mirror events to _turnEvents
     *   4. On Finished: post-process for TTS, speak via Tts (works
     *      even if no activity is visible)
     *   5. Decrement activeTurns; if zero, schedule a delayed
     *      service-stop after IDLE_TIMEOUT_MS
     */
    fun submit(text: String, fromVoice: Boolean) {
        submit(text = text, fromVoice = fromVoice, pcm = null, pcmSampleRate = 0)
    }

    /**
     * Variant called by voice paths that captured raw audio alongside
     * SpeechRecognition. Runs the acoustic-aware mood tracker so the
     * fused mood (text + pitch/energy/rate) feeds the recall trend
     * and the agent's response prosody.
     */
    fun submit(text: String, fromVoice: Boolean, pcm: ShortArray?, pcmSampleRate: Int) {
        if (text.isBlank()) return
        // Notification-triage turns ([notif]-prefixed, fed by the
        // auto-reply queue) still deliver to the chat + the notification
        // shade — but they're NEVER spoken. TTS is reserved for the
        // user's own direct turns; auto-triaging the notification
        // stream out loud is noise.
        val fromNotification = text.startsWith(AgentLoop.NOTIF_PREFIX)
        // Manual turn → reset the auto-continue cap so the agent
        // can pick up another batch of todo items after THIS
        // manual turn settles. Auto-prompted turns (text starts
        // with "[auto-continue]") don't reset — that'd defeat
        // the cap's runaway protection.
        if (!text.startsWith("[auto-continue]")) {
            autoContinue.noteManualSubmit()
        }
        scope.launch {
            beginTurn()
            // Live wallpaper acknowledgement — fires the moment the
            // agent receives a turn so the wallpaper can ripple even
            // if no UI is visible (background voice trigger, watch
            // PTT, automation). Origin -1f / -1f means "centre on
            // the rose" inside WallpaperRenderer.
            ThoughtRippleSink.ping()
            try {
                // Mood pre-pass. With audio: text + acoustic fusion.
                // Without: lexical-only. Either way the just-detected
                // mood is in the vault when AgentLoop.submit calls
                // recall.recentMoodTrend at the top of its iteration —
                // which feeds both the "be warmer / softer" system
                // message and Turn.Finished.userMoodTrend (which
                // drives TTS pitch/rate + ElevenLabs stability/style).
                runCatching {
                    val mood = if (pcm != null && pcm.isNotEmpty()) {
                        moodTracker.trackVoice(text, pcm, pcmSampleRate)
                    } else {
                        moodTracker.track(text, fromVoice)
                    }
                    // Pipe the just-detected label to the live
                    // wallpaper so the gradient drifts toward this
                    // mood's palette over the next few seconds.
                    if (!mood.isNullOrBlank()) MoodSink.update(mood)
                }.onFailure { Log.w(TAG, "mood track failed: ${it.message}") }

                // Status-bar Dynamic Island acknowledgement —
                // push "thinking…" the moment the user submits.
                com.mythara.ui.system.DynamicIslandSink.push(
                    text = "thinking…",
                    accent = com.mythara.ui.theme.MytharaColors.Charple,
                    ttlMs = 30_000L,
                )
                agent.submit(text, fromVoice = fromVoice).collect { turn ->
                    _turnEvents.tryEmit(turn)
                    // Surface tool starts + finished states in the
                    // pill so the user can glance up and see what
                    // Mythara is doing without opening the app.
                    when (turn) {
                        is AgentLoop.Turn.ToolStart -> {
                            com.mythara.ui.system.DynamicIslandSink.push(
                                text = "running ${turn.name}",
                                accent = com.mythara.ui.theme.MytharaColors.Mustard,
                                ttlMs = 8_000L,
                            )
                        }
                        is AgentLoop.Turn.Finished -> {
                            com.mythara.ui.system.DynamicIslandSink.push(
                                text = "done · ${turn.iterations} steps",
                                accent = com.mythara.ui.theme.MytharaColors.Bok,
                                ttlMs = 4_000L,
                            )
                            deliverFinished(turn, fromNotification)
                            // Extract any IMPLIED follow-up actions
                            // from this exchange and queue them in
                            // AgentTodoStore. Only run on direct
                            // user turns — auto-continue / auto-
                            // reply / auto-triage / notif / no-
                            // surface turns shouldn't recursively
                            // generate more items (would cascade
                            // and blow past the autoContinue cap
                            // every cycle).
                            maybeExtractIntent(text, turn)
                        }
                        is AgentLoop.Turn.Error -> {
                            com.mythara.ui.system.DynamicIslandSink.push(
                                text = "error · ${turn.message.take(20)}",
                                accent = com.mythara.ui.theme.MytharaColors.Sriracha,
                                ttlMs = 6_000L,
                            )
                        }
                        else -> { /* Delta streams + ToolEnd are already in chat */ }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "agent loop threw", t)
                _turnEvents.tryEmit(AgentLoop.Turn.Error(t.message ?: "agent threw", retryable = false))
            } finally {
                endTurn()
            }
        }
    }

    /**
     * Blocking variant of [submit]. Runs the identical lifecycle wrap
     * (FGS keepalive, mood pre-pass, turnEvents mirror, TTS + reply
     * notification on Finished) but SUSPENDS until the turn completes
     * and returns the agent's final text.
     *
     * Returns null when the turn ended without a [AgentLoop.Turn.Finished]
     * event — i.e. it hit [AgentLoop.Turn.Error] or [AgentLoop.Turn.MissingApiKey],
     * or `text` was blank — so the caller can record a failure.
     *
     * Used by [com.mythara.tasks.TaskExecutor]: a cross-device task
     * needs the agent's actual answer written into its `result_text`,
     * not the old optimistic "submitted to agent" placeholder.
     *
     * The agent turn runs in [scope] (not the caller's), so if the
     * caller is cancelled while awaiting, the turn keeps running to
     * completion — the same detachment guarantee [submit] gives.
     */
    suspend fun submitAndAwait(text: String, fromVoice: Boolean): String? {
        if (text.isBlank()) return null
        val fromNotification = text.startsWith(AgentLoop.NOTIF_PREFIX)
        val done = CompletableDeferred<String?>()
        scope.launch {
            beginTurn()
            var finalText: String? = null
            try {
                runCatching { moodTracker.track(text, fromVoice) }
                    .onFailure { Log.w(TAG, "mood track failed: ${it.message}") }
                agent.submit(text, fromVoice = fromVoice).collect { turn ->
                    _turnEvents.tryEmit(turn)
                    if (turn is AgentLoop.Turn.Finished) {
                        finalText = turn.finalText
                        deliverFinished(turn, fromNotification)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "agent loop threw", t)
                _turnEvents.tryEmit(AgentLoop.Turn.Error(t.message ?: "agent threw", retryable = false))
            } finally {
                endTurn()
                done.complete(finalText)
            }
        }
        return done.await()
    }

    private fun beginTurn() {
        val n = activeTurns.incrementAndGet()
        _busy.value = true
        idleStopJob?.cancel()
        idleStopJob = null
        if (n == 1) {
            // Transition idle → busy: spin up the FGS so we get
            // background mic + process keepalive. Idempotent — the
            // service no-ops if already running.
            AgentForegroundService.start(ctx)
        }
    }

    private fun endTurn() {
        val n = activeTurns.decrementAndGet()
        if (n <= 0) {
            _busy.value = false
            // Schedule auto-stop. We delay so a back-to-back follow-up
            // (e.g. user immediately asks a clarifying question, or
            // wake-word fires again) doesn't pay the FGS spin-up cost
            // a second time.
            idleStopJob = scope.launch {
                delay(IDLE_TIMEOUT_MS)
                if (activeTurns.get() == 0) {
                    AgentForegroundService.stop(ctx)
                }
            }
        }
    }

    /**
     * After every Finished turn, two deliveries happen:
     *  - TTS (always — drives the audio reply path)
     *  - ReplyNotification (only when Mythara isn't visible — covers
     *    the case where a tool call sent the user to another app,
     *    or the screen is off entirely, so they don't have to
     *    navigate back to find the answer)
     */
    private suspend fun deliverFinished(turn: AgentLoop.Turn.Finished, fromNotification: Boolean) {
        speakIfNeeded(turn, fromNotification)
        // Reply notification: strip <think> + audio tags + markdown
        // before showing so the shade text is human-readable.
        val cleaned = Thinks.strip(turn.finalText)
            .removeSuffix(" [hit max iterations]")
        val displayText = SpokenText.forSpeech(cleaned, keepAudioTags = false)
        if (displayText.isNotBlank() && !isNoSurface(cleaned)) {
            replyNotification.postIfBackgrounded(displayText)
        }
    }

    /**
     * True when the agent declined to surface this turn. The model is
     * told to emit the bare [AgentLoop.NOSURFACE_TOKEN] for noise, but
     * in practice it sometimes trails punctuation or a stray newline +
     * reasoning after it — so match a leading token, not just an exact
     * equality, to keep that noise out of TTS + the notification shade.
     */
    private fun isNoSurface(cleaned: String): Boolean {
        val t = cleaned.trim()
        return t.equals(AgentLoop.NOSURFACE_TOKEN, ignoreCase = true) ||
            t.startsWith(AgentLoop.NOSURFACE_TOKEN, ignoreCase = true)
    }

    private suspend fun speakIfNeeded(turn: AgentLoop.Turn.Finished, fromNotification: Boolean) {
        // Never speak notification-triage turns — they belong in the
        // chat + shade, not the speaker.
        if (fromNotification) return
        // Music Mode is the user's tone-encoded secret language; while
        // it's on, the reply is voiced as motifs by MusicToneEngine
        // and we suppress TTS so the two don't talk over each other.
        // Reading the flag synchronously via first() — the
        // MusicModeStore is backed by DataStore so this is cheap.
        val musicOn = runCatching { musicMode.enabledFlow().first() }
            .getOrDefault(false)
        if (musicOn) return
        // Mirror the previous ChatViewModel.Finished handling: strip
        // <think> reasoning, the max-iter sentinel, markdown, then
        // truncate for TTS and pick the right locale for the reply.
        // When ElevenLabs is the active route, KEEP audio tags so the
        // hosted voice can render [laugh] / [sigh] / [hmm] as real
        // vocal expressions. Tts.speak's Android fallback path then
        // strips them on its own side; locale detection is run on
        // the stripped form so the language picker doesn't choke on
        // bracketed pseudo-words.
        val snap = runCatching { settings.snapshot() }.getOrNull()
        val keepAudioTags = snap?.useElevenLabs == true && !snap.elevenLabsKey.isNullOrBlank()
        val cleaned = Thinks.strip(turn.finalText)
            .removeSuffix(" [hit max iterations]")
        val spoken = SpokenText.forSpeech(cleaned, keepAudioTags = keepAudioTags)
        val toSpeak = SpokenText.truncateForSpeech(spoken)
        if (toSpeak.isBlank() || isNoSurface(cleaned)) return
        // For language detection, always feed a tag-free string so
        // ML Kit doesn't get confused by '[laugh]' style markers.
        val forDetection = if (keepAudioTags) SpokenText.forSpeech(cleaned, keepAudioTags = false) else toSpeak
        val locale = runCatching { languageDetector.identifyLocale(forDetection) }.getOrNull()
        tts.speak(toSpeak, locale, turn.userMoodTrend)
    }

    /**
     * Run the user-intent extractor on this finished turn so any
     * implied follow-ups land in [com.mythara.agent.todo.AgentTodoStore].
     * Skips:
     *   - auto-continue prompts (we'd cascade and burn the cap)
     *   - notification-triage turns (the user didn't initiate them)
     *   - auto-reply / auto-triage turns (same — agent-initiated)
     *   - turns the agent declined to surface (NOSURFACE)
     *
     * Runs in a fire-and-forget coroutine on [scope] — the extractor
     * uses the LIGHT model path (local Gemma) but we don't want to
     * block Turn.Finished delivery on it.
     */
    private fun maybeExtractIntent(userText: String, turn: AgentLoop.Turn.Finished) {
        if (userText.startsWith("[auto-continue]")) return
        if (userText.startsWith(AgentLoop.NOTIF_PREFIX)) return
        if (userText.startsWith(AutoReplyDispatcher.AUTO_REPLY_PREFIX)) return
        if (userText.startsWith(AutoReplyDispatcher.AUTO_TRIAGE_PREFIX)) return
        val cleaned = Thinks.strip(turn.finalText).removeSuffix(" [hit max iterations]")
        if (isNoSurface(cleaned)) return
        if (cleaned.isBlank()) return
        scope.launch {
            runCatching {
                val n = todoIntentExtractor.extract(userText = userText, agentReply = cleaned)
                if (n > 0) Log.d(TAG, "queued $n intent-derived todo items")
            }.onFailure { Log.w(TAG, "intent extraction failed: ${it.message}") }
        }
        // Parallel graph extraction — read the same exchange and
        // emit Graphiti-style entities + edges into the temporal
        // knowledge graph. Independent of intent extraction; runs
        // on its own coroutine so a slow LLM pass on one side
        // doesn't stall the other.
        scope.launch {
            runCatching {
                val n = graphTurnExtractor.extract(userText = userText, agentReply = cleaned)
                if (n > 0) Log.d(TAG, "wrote $n graph rows from this turn")
            }.onFailure { Log.w(TAG, "graph extraction failed: ${it.message}") }
        }
        // Parallel persona-trait extraction — lexical Big Five +
        // Schwartz values + preferences + concerns + comm-style for
        // the user (and any contacts they mentioned). Default-on per
        // the v2 plan. Independent coroutine so the LLM-driven
        // graph pass doesn't gate trait recording.
        scope.launch {
            runCatching {
                personaTraitExtractor.extract(
                    userText = userText,
                    assistantText = cleaned,
                    mentionedContacts = emptyList(),
                )
            }.onFailure { Log.w(TAG, "persona-trait extraction failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "Mythara/Runner"
        private const val IDLE_TIMEOUT_MS = 30_000L
    }
}
