package com.mythara.resonance

import android.content.Context
import android.util.Log
import com.mythara.agent.AgentRunner
import com.mythara.data.ResonanceSettings
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone-side brain for Resonance Mode. Three responsibilities:
 *
 *  - **Combo dispatch.** Decodes wire payloads from the watch via
 *    [ResonanceComboMap] and either fires a one-shot
 *    [ResonanceCommand.FireCommand] or opens / retargets a session.
 *  - **HR + toggle routing.** Receives `RESONANCE_HR` / `RESONANCE_TOGGLE`
 *    payloads from `MytharaWearListenerService`; HR samples land in
 *    [ResonanceHrStore], toggle on/off opens/closes a session.
 *  - **Session lifecycle.** Holds at most one [ResonanceSession],
 *    delegates audio + the closed loop to [ResonanceForegroundService]
 *    (which hosts [ResonanceLoop]). Observes `loop.phase` to clean up
 *    the session-side state when the loop ends.
 *
 * Sessions can be started by EITHER:
 *  - a `Protocol` combo (Calm / Focus / WindDown), which sets the
 *    target explicitly and the loop starts audio immediately, OR
 *  - the watch's `RESONANCE_TOGGLE = "on"`, which opens an auto-pick
 *    session — the loop waits for its first analyzer snapshot, then
 *    defaults to Calm if no protocol was set explicitly.
 *
 * Sessions end on EITHER `RESONANCE_TOGGLE = "off"`, an `EndSession`
 * combo, the loop's session-cap timer, the FGS's headphone-removal /
 * audio-focus-loss hard stops, or stale biometrics.
 */
@Singleton
class ResonanceController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val agentRunner: AgentRunner,
    private val vault: LearningVault,
    private val hrStore: ResonanceHrStore,
    private val analyzer: ResonanceStateAnalyzer,
    private val loop: ResonanceLoop,
    private val settings: ResonanceSettings,
    private val watchNotifier: ResonanceWatchNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var lastCode: Int = -1
    @Volatile private var lastAtMs: Long = 0L
    @Volatile private var analyzeJob: Job? = null
    /** Hot cache of [settings.enabledFlow] so the synchronous
     *  combo / toggle / HR receivers can short-circuit without
     *  blocking on the DataStore. */
    @Volatile private var enabledCached: Boolean = false

    /** Diagnostic counters — keep the HR stream visible in logcat
     *  without firehosing every sample. */
    @Volatile private var hrPushCount: Int = 0
    @Volatile private var lastStrayHrLogMs: Long = 0L

    /** Active session, or null when no Resonance session is open. */
    private val _session = MutableStateFlow<ResonanceSession?>(null)
    val session: StateFlow<ResonanceSession?> = _session.asStateFlow()

    init {
        // Mirror the secret-menu enable flag onto the watch (drives
        // the toggle-dot's visibility) AND keep the local hot-cache
        // in sync so the synchronous receivers can gate cheaply.
        // Also: if the user disables the feature mid-session, hard
        // stop whatever's running.
        scope.launch {
            Log.d(TAG, "controller init — collecting settings.enabledFlow()")
            settings.enabledFlow().collect { enabled ->
                Log.d(TAG, "settings.enabled = $enabled (was cached=$enabledCached)")
                enabledCached = enabled
                watchNotifier.pushAvailability(enabled)
                if (!enabled && _session.value != null) {
                    Log.d(TAG, "settings.enabled flipped off mid-session — stopping")
                    endSession()
                }
            }
        }
        // Forward analyzer snapshots from the loop into the active
        // session's StateFlow so anything observing the session sees
        // the current `(valence, arousal)`.
        scope.launch {
            loop.latestSnapshot.collect { snap ->
                if (snap != null) _session.value?.publishSnapshot(snap)
            }
        }
        // Clean up session-side state whenever the loop returns to
        // Idle — covers EVERY stop path: explicit user stop, watch
        // toggle off, headphone removal, audio-focus loss, session
        // cap, stale biometrics. The controller doesn't need to know
        // which one fired.
        scope.launch {
            var prev = ResonanceLoop.Phase.Idle
            loop.phase.collect { p ->
                if (p == ResonanceLoop.Phase.Idle && prev != ResonanceLoop.Phase.Idle) {
                    onLoopFinished()
                }
                prev = p
            }
        }
    }

    // ----------------------------------------------------- combo entry

    /**
     * Decode a combo payload (`"<code>|<epochMs>"` shipped by
     * `ResonancePad`) and dispatch the corresponding [ResonanceCommand].
     * Safe to call from any thread.
     */
    fun onComboPayload(payload: String?) {
        if (!enabledCached) {
            Log.d(TAG, "combo ignored — Resonance disabled in settings")
            return
        }
        val (code, watchTs) = parsePayload(payload) ?: run {
            Log.w(TAG, "combo payload unparseable: $payload")
            return
        }
        val now = System.currentTimeMillis()
        if (code == lastCode && now - lastAtMs < DUPLICATE_WINDOW_MS) {
            Log.d(TAG, "combo $code dropped — duplicate within ${DUPLICATE_WINDOW_MS}ms")
            return
        }
        lastCode = code
        lastAtMs = now

        val command = ResonanceComboMap.decode(code) ?: run {
            Log.w(TAG, "combo code $code has no v1 mapping — ignoring")
            return
        }
        Log.d(TAG, "combo $code → $command (watch ts=$watchTs)")
        scope.launch { dispatch(command, watchTs) }
    }

    // ------------------------------------------------------ HR + toggle

    /** Streamed HR sample from the watch. Forwards into
     *  [ResonanceHrStore]; ignored silently when no session is open. */
    fun onHrPayload(payload: String?) {
        val s = payload?.trim().orEmpty()
        if (s.isEmpty()) return
        val parts = s.split('|')
        val bpm = parts.getOrNull(0)?.toIntOrNull() ?: return
        val ts = parts.getOrNull(1)?.toLongOrNull() ?: System.currentTimeMillis()
        if (_session.value == null) {
            // Log once every ~15 stray samples so we can spot a watch
            // that's streaming when the phone has no session open.
            val now = System.currentTimeMillis()
            if (now - lastStrayHrLogMs > 15_000L) {
                Log.d(TAG, "HR sample $bpm bpm ARRIVED but no session open — dropping")
                lastStrayHrLogMs = now
            }
            return
        }
        hrStore.push(bpm, ts)
        // Log every ~10 samples so the stream is visible in logcat
        // without being a firehose.
        hrPushCount++
        if (hrPushCount % 10 == 1) {
            Log.d(TAG, "HR stream: $bpm bpm (sample #$hrPushCount this session)")
        }
    }

    /** Watch's discreet `on` / `off` toggle. */
    fun onTogglePayload(payload: String?) {
        when (payload?.trim()?.lowercase()) {
            "on" -> {
                if (!enabledCached) {
                    Log.d(TAG, "watch toggle ON ignored — Resonance disabled in settings")
                    return
                }
                Log.d(TAG, "watch toggle ON → starting session (auto-pick)")
                if (_session.value == null) startSession(protocol = null)
            }
            "off" -> {
                Log.d(TAG, "watch toggle OFF → ending session")
                endSession()
            }
            else -> Log.w(TAG, "unknown resonance toggle payload: $payload")
        }
    }

    /** App-side entry point — used by the secret-menu panel's manual
     *  start/stop button. Same effect as a watch Protocol combo. */
    fun startProtocolFromApp(protocol: ResonanceCommand.Protocol?) {
        if (!enabledCached) {
            Log.d(TAG, "app start ignored — Resonance disabled in settings")
            return
        }
        if (_session.value != null) {
            protocol?.let { loop.setProtocol(it) }
        } else {
            startSession(protocol)
        }
    }

    /** App-side stop — same effect as the watch End-Session combo. */
    fun stopFromApp() {
        endSession()
    }

    // -------------------------------------------------------- dispatch

    private suspend fun dispatch(command: ResonanceCommand, watchTs: Long) {
        when (command) {
            is ResonanceCommand.FireCommand -> when (command.command) {
                ResonanceCommand.Command.CheckIn -> fireCheckIn()
                ResonanceCommand.Command.MarkMoment -> fireMarkMoment(watchTs)
                ResonanceCommand.Command.StartPtt -> {
                    // Handled watch-side — the pad invokes the existing
                    // PttScreen.startPtt() closure locally before
                    // shipping the combo. We log here for symmetry +
                    // to leave a trace in the audit log.
                    Log.d(TAG, "Start-PTT combo received (watch handles locally)")
                }
                ResonanceCommand.Command.EndSession -> endSession()
            }
            is ResonanceCommand.StartProtocol -> {
                val current = _session.value
                if (current != null) {
                    Log.d(TAG, "retargeting active session → ${command.protocol.displayName}")
                    current.setTarget(command.protocol)
                    // Loop is shared with the FGS — call directly.
                    loop.setProtocol(command.protocol)
                } else {
                    startSession(command.protocol)
                }
            }
        }
    }

    // ------------------------------------------------------- sessions

    /**
     * Open a new session. [protocol] = null → auto-pick (the loop
     * defaults to Calm after its first snapshot). Idempotent: a second
     * call with an active session retargets instead of starting fresh.
     */
    private fun startSession(protocol: ResonanceCommand.Protocol?) {
        if (_session.value != null) {
            _session.value?.setTarget(protocol)
            if (protocol != null) loop.setProtocol(protocol)
            return
        }
        val s = ResonanceSession()
        s.setTarget(protocol)
        _session.value = s
        hrPushCount = 0
        hrStore.start()
        Log.d(TAG, "session started (target=${protocol?.displayName ?: "auto"})")
        // Hand the audio + closed loop off to the FGS; it owns
        // headphone-removal + audio-focus stops.
        ResonanceForegroundService.start(ctx, protocol)
        // Confirm to the watch — drives a short haptic.
        watchNotifier.pushState(active = true)
        // Kick an initial snapshot so the session.state has a value
        // before the loop's 30s poll fires; the loop will overwrite
        // shortly afterward via [loop.latestSnapshot].
        analyzeJob = scope.launch {
            runCatching {
                val snap = analyzer.snapshot(hrStore)
                s.publishSnapshot(snap)
                Log.d(TAG, "initial snapshot: $snap")
            }.onFailure { Log.w(TAG, "initial analyze failed: ${it.message}") }
        }
    }

    /** Ask the loop to wind down. The actual session cleanup happens
     *  in [onLoopFinished] when the loop's phase reaches Idle. */
    private fun endSession() {
        val s = _session.value ?: return
        Log.d(TAG, "session end requested")
        s.beginWindDown()
        ResonanceForegroundService.stop(ctx)
    }

    /** Called when [loop.phase] returns to Idle — every stop path
     *  funnels here, so all the session-side cleanup lives in one
     *  place. */
    private fun onLoopFinished() {
        val s = _session.value
        Log.d(TAG, "loop finished — clearing session state")
        analyzeJob?.cancel()
        analyzeJob = null
        s?.end()
        hrStore.stop()
        _session.value = null
        // Tell the watch — drives a confirm haptic + the pad collapses
        // (mirroring the local active flag) regardless of which stop
        // path actually fired.
        watchNotifier.pushState(active = false)
    }

    // ------------------------------------------------------- commands

    private suspend fun fireCheckIn() {
        // Templated natural-language ask — `AgentRunner` routes through
        // tool selection so the agent picks SendNoteToDeviceTool (or
        // TeamCallTool if no favorite is set). Keeping the prompt
        // explicit about the silent / discreet intent so the model
        // doesn't try to start a voice call instead.
        val prompt = "[resonance:check-in] Send a short, warm silent check-in note to my favorite person. " +
            "Keep it to one sentence — something like 'thinking of you, no need to reply.' Do NOT initiate a voice call."
        Log.d(TAG, "dispatching Check-in via AgentRunner")
        runCatching { agentRunner.submit(text = prompt, fromVoice = false) }
            .onFailure { Log.w(TAG, "check-in submit failed: ${it.message}") }
    }

    private suspend fun fireMarkMoment(watchTs: Long) {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(now))
        val hrSuffix = hrStore.latest()?.let { " · HR ${it} bpm" } ?: ""
        val content = "Marked moment at $stamp (from watch)$hrSuffix."
        Log.d(TAG, "writing mark-moment vault row: $stamp$hrSuffix")
        runCatching {
            vault.add(
                content = content,
                tier = Tier.Episodic,
                src = "resonance:mark",
                facets = listOf(
                    "kind:moment-mark",
                    "source:watch",
                    "topic:resonance",
                ),
                conf = 1.0,
            )
        }.onFailure { Log.w(TAG, "mark-moment vault write failed: ${it.message}") }
    }

    // ------------------------------------------------------- helpers

    private fun parsePayload(payload: String?): Pair<Int, Long>? {
        val s = payload?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split('|')
        val code = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val ts = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return code to ts
    }

    companion object {
        private const val TAG = "Mythara/Resonance"
        private const val DUPLICATE_WINDOW_MS = 500L
    }
}
