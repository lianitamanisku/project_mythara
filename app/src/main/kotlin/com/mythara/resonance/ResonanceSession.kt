package com.mythara.resonance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One in-flight Resonance Mode session. Held by [ResonanceController];
 * read by the (Phase 4) closed loop + audio engine.
 *
 * The session is a small state holder — [state] / [target] / [phase]
 * StateFlows let the loop observe what the analyzer last produced and
 * which protocol the user picked, without coupling the analyzer to the
 * loop directly.
 */
class ResonanceSession internal constructor() {

    /** Latest fused emotional-state estimate, or null until the
     *  analyzer's first pass returns. */
    private val _state = MutableStateFlow<ResonanceSnapshot?>(null)
    val state: StateFlow<ResonanceSnapshot?> = _state.asStateFlow()

    /** The protocol the user explicitly picked via combo, or null when
     *  the session is running in auto-pick mode (no explicit target). */
    private val _target = MutableStateFlow<ResonanceCommand.Protocol?>(null)
    val target: StateFlow<ResonanceCommand.Protocol?> = _target.asStateFlow()

    /** Lifecycle phase the loop reads to decide what to do next. */
    private val _phase = MutableStateFlow(Phase.Analyzing)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    enum class Phase { Analyzing, Active, WindingDown, Ended }

    internal fun publishSnapshot(snapshot: ResonanceSnapshot) {
        _state.value = snapshot
        if (_phase.value == Phase.Analyzing) _phase.value = Phase.Active
    }

    internal fun setTarget(protocol: ResonanceCommand.Protocol?) {
        _target.value = protocol
    }

    internal fun beginWindDown() {
        if (_phase.value != Phase.Ended) _phase.value = Phase.WindingDown
    }

    internal fun end() {
        _phase.value = Phase.Ended
    }
}

/**
 * Fused emotional-state estimate published by [ResonanceStateAnalyzer]
 * onto [ResonanceSession.state]. Coordinates use the Russell circumplex
 * convention: valence = pleasant→unpleasant, arousal = high→low.
 *
 * Confidence collapses toward zero when signals are missing or
 * conflicting; the (Phase 4) loop should glide cautiously while
 * confidence is low.
 */
data class ResonanceSnapshot(
    val tsMillis: Long,
    /** -1 (unpleasant) ... +1 (pleasant). */
    val valence: Float,
    /** -1 (low arousal / sleepy) ... +1 (high arousal / activated). */
    val arousal: Float,
    /** Discrete label drawn from the quadrant — for UI / vault facets. */
    val label: String,
    /** Most recent valid HR sample in the analysis window. Null if the
     *  watch hasn't streamed any yet. */
    val liveHrBpm: Int?,
    /** Mean of valid HR samples across the analysis window. */
    val liveHrAvgBpm: Int?,
    /** User's resting-HR baseline (best available; ~70 fallback). */
    val baselineBpm: Int?,
    /** Whether a fresh acoustic sample fed this snapshot. False means
     *  the mic was busy / mic-off / no permission and arousal came
     *  from HR alone. */
    val acousticAvailable: Boolean,
    /** 0 (signal-starved guess) ... 1 (HR + acoustic both fresh). */
    val confidence: Float,
)
