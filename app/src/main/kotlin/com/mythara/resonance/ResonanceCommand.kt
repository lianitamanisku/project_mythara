package com.mythara.resonance

/**
 * The vocabulary the watch's `ResonancePad` can ship to the phone.
 * Two flavours:
 *
 *  - [Protocol]   — opens (or retargets) a closed-loop self-regulation
 *                   session aimed at a specific brainwave-entrainment
 *                   target band. Phase 1 only logs these; the analyzer
 *                   + audio-engine + closed loop come in Phases 2–4.
 *  - [Command]    — discreet, fire-and-forget assistant actions. These
 *                   never start a session and are dispatched
 *                   immediately by `ResonanceController`.
 */
sealed interface ResonanceCommand {

    /** Closed-loop regulation targets (used by Phase 4 `ResonanceLoop`). */
    enum class Protocol(val displayName: String) {
        Calm("calm"),
        Focus("focus"),
        WindDown("wind-down"),
    }

    /** Discreet, no-session-needed actions. */
    enum class Command(val displayName: String) {
        CheckIn("check-in"),
        MarkMoment("mark moment"),
        StartPtt("start ptt"),
        EndSession("end session"),
    }

    data class StartProtocol(val protocol: Protocol) : ResonanceCommand
    data class FireCommand(val command: Command) : ResonanceCommand
}
