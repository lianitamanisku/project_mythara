package com.mythara.agent

import android.content.Context
import android.util.Log
import com.mythara.mic.LanguageDetector
import com.mythara.mic.Tts
import com.mythara.services.AgentForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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
        scope.launch {
            beginTurn()
            try {
                // Mood pre-pass. With audio: text + acoustic fusion.
                // Without: lexical-only. Either way the just-detected
                // mood is in the vault when AgentLoop.submit calls
                // recall.recentMoodTrend at the top of its iteration —
                // which feeds both the "be warmer / softer" system
                // message and Turn.Finished.userMoodTrend (which
                // drives TTS pitch/rate + ElevenLabs stability/style).
                runCatching {
                    if (pcm != null && pcm.isNotEmpty()) {
                        moodTracker.trackVoice(text, pcm, pcmSampleRate)
                    } else {
                        moodTracker.track(text, fromVoice)
                    }
                }.onFailure { Log.w(TAG, "mood track failed: ${it.message}") }

                agent.submit(text, fromVoice = fromVoice).collect { turn ->
                    _turnEvents.tryEmit(turn)
                    if (turn is AgentLoop.Turn.Finished) {
                        speakIfNeeded(turn)
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

    private suspend fun speakIfNeeded(turn: AgentLoop.Turn.Finished) {
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
        val nosurface = cleaned.trim()
            .equals(AgentLoop.NOSURFACE_TOKEN, ignoreCase = true)
        if (toSpeak.isBlank() || nosurface) return
        // For language detection, always feed a tag-free string so
        // ML Kit doesn't get confused by '[laugh]' style markers.
        val forDetection = if (keepAudioTags) SpokenText.forSpeech(cleaned, keepAudioTags = false) else toSpeak
        val locale = runCatching { languageDetector.identifyLocale(forDetection) }.getOrNull()
        tts.speak(toSpeak, locale, turn.userMoodTrend)
    }

    companion object {
        private const val TAG = "Mythara/Runner"
        private const val IDLE_TIMEOUT_MS = 30_000L
    }
}
