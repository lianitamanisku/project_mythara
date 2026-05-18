package com.mythara.lifeline

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a one-shot "re-caption every old photo in the archive" run
 * with live progress reporting for the SecretSettings UI.
 *
 * Wraps [LifelineCaptioner.recaptionAll] in a singleton CoroutineScope
 * so the work survives Compose recomposition and screen rotation —
 * the runner stays in the process and the UI re-attaches to the same
 * [progress] StateFlow each time the panel is visited.
 *
 * Does NOT survive process death — if the user force-stops Mythara
 * mid-run the next start begins from where the worker left off
 * (markAllLocalPending already cleared each row's caption, so the
 * existing periodic [LifelineWorker] will gradually catch up at
 * its 12-hour cadence).
 */
@Singleton
class RecaptionAllRunner @Inject constructor(
    private val captioner: LifelineCaptioner,
) {

    sealed interface State {
        /** Never run since process start. */
        data object Idle : State

        /** Running — `captioned` and `attempted` track progress against `total`. */
        data class Running(
            val captioned: Int,
            val attempted: Int,
            val total: Int,
            val startedMs: Long,
        ) : State

        /** Finished — `captioned` new captions out of `total` attempted. */
        data class Done(
            val captioned: Int,
            val total: Int,
            val durationMs: Long,
        ) : State

        /** Aborted by the user or threw before completing. */
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    /** Kick off a bulk re-caption. Safe to call when already running
     *  (no-op). Returns immediately; observe [state] for progress. */
    fun start() {
        if (isRunning()) {
            Log.d(TAG, "recaptionAll already running; ignoring duplicate start")
            return
        }
        val started = System.currentTimeMillis()
        _state.value = State.Running(captioned = 0, attempted = 0, total = 0, startedMs = started)
        job = scope.launch {
            runCatching {
                val captioned = captioner.recaptionAll { c, a, t ->
                    _state.update { State.Running(captioned = c, attempted = a, total = t, startedMs = started) }
                }
                val total = (_state.value as? State.Running)?.total ?: 0
                _state.value = State.Done(
                    captioned = captioned,
                    total = total,
                    durationMs = System.currentTimeMillis() - started,
                )
                Log.d(TAG, "recaptionAll finished: $captioned/$total in ${System.currentTimeMillis() - started}ms")
            }.onFailure { e ->
                Log.w(TAG, "recaptionAll failed: ${e.message}", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Cancel any in-flight run. The vault keeps whatever captions
     *  landed already; remaining rows stay PENDING and the periodic
     *  worker will pick them up at its next fire. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.update { current ->
            if (current is State.Running) {
                State.Failed("Cancelled by user after ${current.attempted}/${current.total}")
            } else {
                current
            }
        }
    }

    /** Reset the visible state back to Idle without affecting any
     *  in-flight run. Used by the UI's "dismiss" action on a Done /
     *  Failed card so the panel collapses back to the trigger button. */
    fun acknowledge() {
        if (!isRunning()) _state.value = State.Idle
    }

    companion object {
        private const val TAG = "Mythara/Recaption"
    }
}
