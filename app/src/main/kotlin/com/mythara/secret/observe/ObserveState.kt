package com.mythara.secret.observe

/**
 * State of the Observe pipeline (M8). Transitions:
 *
 *   Idle  -- user toggles ON          -->  Starting
 *   Starting -- service onStartCommand -->  Running
 *   Running -- user pauses             -->  Paused
 *   Paused -- user resumes             -->  Running
 *   any   -- user toggles OFF or
 *           "forget everything"        -->  Stopping -> Idle
 *   Running -- mic perm revoked etc.   -->  Error
 *
 * The state is held in [ObserveStore] (a Hilt @Singleton) and observed
 * by Compose via a StateFlow.
 */
sealed interface ObserveState {
    data object Idle : ObserveState
    data object Starting : ObserveState
    data class Running(val sinceTsMs: Long) : ObserveState
    data class Paused(val sinceTsMs: Long) : ObserveState
    data object Stopping : ObserveState
    data class Error(val message: String) : ObserveState

    val isActive: Boolean get() = this is Running || this is Paused || this is Starting

    val displayLabel: String get() = when (this) {
        is Idle -> "off"
        is Starting -> "starting…"
        is Running -> "listening"
        is Paused -> "paused"
        is Stopping -> "stopping…"
        is Error -> "error: $message"
    }
}
