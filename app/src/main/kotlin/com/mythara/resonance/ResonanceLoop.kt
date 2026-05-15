package com.mythara.resonance

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The closed-loop controller for Resonance Mode. Polls
 * [ResonanceStateAnalyzer] every [POLL_INTERVAL_MS] and glides
 * [ResonanceAudioEngine] toward the active protocol's target band; once
 * biometrics sit in the band for [STABLE_POLLS_FOR_DEESCALATE] polls,
 * steps intensity (volume) down so we're not pumping a high tone at
 * someone who's already calm.
 *
 * State machine:
 *
 *   Idle ─► Ramping ─► Holding ⇄ Adjusting ─► DeEscalating ─► Stopping ─► Idle
 *
 * Stop conditions (any one ends the session via [stop]):
 *  - session-cap timer elapses
 *  - explicit hard stop from anywhere (watch combo, app, FGS receivers)
 *  - stale biometrics (HR stream went quiet — watch BT drop)
 *
 * The loop is a LEAF — it doesn't depend on `ResonanceController` (that
 * would close a cycle in the Hilt graph). Instead it publishes its
 * lifecycle on [phase] and the latest fused snapshot on
 * [latestSnapshot]; `ResonanceController` observes both and runs the
 * session-side cleanup when [phase] transitions back to [Phase.Idle].
 *
 * Designed to be HOSTED by [ResonanceForegroundService] so it survives
 * screen-off; the service owns audio focus + the headphone-removal
 * BroadcastReceiver and stops the loop on either signal.
 */
@Singleton
class ResonanceLoop @Inject constructor(
    private val analyzer: ResonanceStateAnalyzer,
    private val hrStore: ResonanceHrStore,
    private val audioEngine: ResonanceAudioEngine,
) {

    enum class Phase { Idle, Ramping, Adjusting, Holding, DeEscalating, Stopping }

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _latestSnapshot = MutableStateFlow<ResonanceSnapshot?>(null)
    val latestSnapshot: StateFlow<ResonanceSnapshot?> = _latestSnapshot.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var loopJob: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var protocol: ResonanceCommand.Protocol? = null
    private var stablePolls = 0
    private var startedAtMs = 0L

    /**
     * Start the loop. [initialProtocol] = null means the session was
     * opened in auto-pick mode; the loop picks Calm as the default
     * target (most common need) once it has a snapshot.
     */
    fun start(initialProtocol: ResonanceCommand.Protocol?) {
        if (loopJob?.isActive == true) {
            // Idempotent retarget on an already-running loop.
            initialProtocol?.let { setProtocol(it) }
            return
        }
        protocol = initialProtocol
        stopRequested = false
        stablePolls = 0
        startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "loop start (protocol=${initialProtocol?.displayName ?: "auto"})")
        _phase.value = Phase.Ramping
        // Engine starts here so an explicit-protocol session is audible
        // even before the first poll. Auto-pick waits for the first
        // snapshot to default to Calm.
        initialProtocol?.let { audioEngine.start(it) }
        loopJob = scope.launch { runLoop() }
    }

    /** Update the active protocol mid-session. The engine glides
     *  toward the new band (no audio drop). */
    fun setProtocol(p: ResonanceCommand.Protocol) {
        Log.d(TAG, "loop retarget → ${p.displayName}")
        protocol = p
        stablePolls = 0
        audioEngine.start(p)
        if (_phase.value != Phase.Stopping) _phase.value = Phase.Adjusting
    }

    /** Initiate a graceful stop — engine ramps down, loop exits, then
     *  [phase] transitions to [Phase.Idle]. Idempotent. */
    fun stop() {
        if (loopJob?.isActive != true) return
        Log.d(TAG, "loop stop requested")
        stopRequested = true
        _phase.value = Phase.Stopping
        audioEngine.stop()
    }

    private suspend fun runLoop() {
        try {
            while (scope.isActive && !stopRequested) {
                tick()
                if (stopRequested) break
                if (System.currentTimeMillis() - startedAtMs > SESSION_CAP_MS) {
                    Log.d(TAG, "session cap reached — stopping")
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            // Ensure the engine has been told to stop even if we
            // exited via cap timer / scope cancellation.
            audioEngine.stop()
            // Restore the volume cap default for the next session.
            audioEngine.setVolumeCap(ResonanceAudioEngine.DEFAULT_VOLUME_CAP)
            _phase.value = Phase.Idle
            loopJob = null
            Log.d(TAG, "loop exited")
        }
    }

    private suspend fun tick() {
        val snap = runCatching { analyzer.snapshot(hrStore) }.getOrNull() ?: return
        _latestSnapshot.value = snap
        Log.d(TAG, "tick snap=$snap phase=${_phase.value}")

        // Stop if biometrics have gone stale (watch BT drop) — never
        // glide on a stale arousal value.
        if (snap.confidence < STALE_CONFIDENCE_FLOOR && snap.liveHrAvgBpm == null) {
            Log.d(TAG, "biometrics stale — initiating stop")
            stopRequested = true
            return
        }

        // Pick a target if we don't have one yet (auto-pick session).
        val active = protocol ?: ResonanceCommand.Protocol.Calm.also {
            protocol = it
            audioEngine.start(it)
        }
        val band = protocolBand(active)
        val mid = (band.start + band.endInclusive) / 2f
        val targetArousal = arousalForProtocol(active)
        val arousalErr = snap.arousal - targetArousal

        if (abs(arousalErr) <= ARROUSAL_TOLERANCE) {
            stablePolls++
            _phase.value = if (stablePolls >= STABLE_POLLS_FOR_DEESCALATE) {
                // Step intensity down once stable — quieter as the user
                // settles, so we're not over-stimulating a calm state.
                val newCap = (DEESCALATE_START_VOLUME -
                    (stablePolls - STABLE_POLLS_FOR_DEESCALATE) * DEESCALATE_STEP)
                    .coerceAtLeast(DEESCALATE_FLOOR)
                audioEngine.setVolumeCap(newCap)
                Phase.DeEscalating
            } else {
                Phase.Holding
            }
            audioEngine.setTargetBeatHz(mid)
        } else {
            stablePolls = 0
            _phase.value = Phase.Adjusting
            audioEngine.setTargetBeatHz(mid)
        }
    }

    private fun protocolBand(p: ResonanceCommand.Protocol): ClosedFloatingPointRange<Float> = when (p) {
        ResonanceCommand.Protocol.Calm -> 6f..10f
        ResonanceCommand.Protocol.Focus -> 14f..18f
        ResonanceCommand.Protocol.WindDown -> 3f..6f
    }

    /** Where we want the user's arousal to LAND for each protocol. */
    private fun arousalForProtocol(p: ResonanceCommand.Protocol): Float = when (p) {
        ResonanceCommand.Protocol.Calm -> -0.30f
        ResonanceCommand.Protocol.Focus -> 0.30f
        ResonanceCommand.Protocol.WindDown -> -0.55f
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "Mythara/ResonanceLoop"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val SESSION_CAP_MS = 25L * 60 * 1000          // 25 min hard cap
        private const val STABLE_POLLS_FOR_DEESCALATE = 3           // ~90s in-band → de-escalate
        private const val ARROUSAL_TOLERANCE = 0.20f
        private const val STALE_CONFIDENCE_FLOOR = 0.15f
        private const val DEESCALATE_START_VOLUME = 0.30f
        private const val DEESCALATE_STEP = 0.04f
        private const val DEESCALATE_FLOOR = 0.18f
    }
}
