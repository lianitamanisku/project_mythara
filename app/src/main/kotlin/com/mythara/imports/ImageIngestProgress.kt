package com.mythara.imports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide live progress for [ImageIngestWorker].
 *
 * The worker writes to this; the import panel observes it. We don't
 * use WorkManager's built-in progress data because:
 *   - it's only updated when the worker calls setProgress (which
 *     forces an IPC round-trip every call — too chatty for our
 *     per-image cadence)
 *   - the panel wants to render the "idle / running / done" tristate
 *     even after the worker finishes, until the user dismisses or
 *     starts a new import. WorkManager's state goes back to SUCCEEDED
 *     and loses the counts.
 *
 * Survives process death only inasmuch as the worker itself does — if
 * the OS kills + restarts the worker, the StateFlow starts at Idle
 * and is rebuilt on the next progress publish. Acceptable for a UX
 * affordance; the work itself is durable via WorkManager.
 */
@Singleton
class ImageIngestProgress @Inject constructor() {
    sealed interface State {
        data object Idle : State
        data class Running(val processed: Int, val total: Int, val errors: Int) : State
        data class Done(val processed: Int, val total: Int, val errors: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun publishStart(total: Int) {
        _state.value = State.Running(processed = 0, total = total, errors = 0)
    }

    fun publishProgress(processed: Int, total: Int, errors: Int) {
        _state.value = State.Running(processed = processed, total = total, errors = errors)
    }

    fun publishComplete(processed: Int, errors: Int) {
        val cur = _state.value
        val total = (cur as? State.Running)?.total ?: processed
        _state.value = State.Done(processed = processed, total = total, errors = errors)
    }

    fun reset() {
        _state.value = State.Idle
    }
}
