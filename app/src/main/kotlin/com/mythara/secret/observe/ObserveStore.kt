package com.mythara.secret.observe

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mythara.growth.LearningJournal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton coordinator for Observe mode. Owns the state machine,
 * brokers start/pause/stop intents to [ObserveForegroundService], and
 * appends lifecycle entries to the LearningJournal so the user can
 * verify the service actually fired (useful before the M8.1b audio +
 * ASR pipeline is wired).
 *
 * Thread model: the StateFlow is published from the main thread (UI
 * driver); journal writes happen on a private supervised IO scope so
 * the user doesn't see a hitch when toggling.
 */
@Singleton
class ObserveStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val journal: LearningJournal,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ObserveState>(ObserveState.Idle)
    val state: StateFlow<ObserveState> = _state.asStateFlow()

    fun start() {
        if (_state.value.isActive) return
        _state.value = ObserveState.Starting
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, ObserveForegroundService::class.java)
                .setAction(ObserveForegroundService.ACTION_START),
        )
        ioScope.launch {
            journal.append(LearningJournal.Entry(
                tsMillis = System.currentTimeMillis(),
                kind = "observe",
                note = "Observe service start requested.",
            ))
        }
    }

    fun pause() {
        if (_state.value !is ObserveState.Running) return
        ctx.startService(
            Intent(ctx, ObserveForegroundService::class.java)
                .setAction(ObserveForegroundService.ACTION_PAUSE),
        )
    }

    fun resume() {
        if (_state.value !is ObserveState.Paused) return
        ctx.startService(
            Intent(ctx, ObserveForegroundService::class.java)
                .setAction(ObserveForegroundService.ACTION_RESUME),
        )
    }

    fun stop() {
        if (_state.value is ObserveState.Idle) return
        _state.value = ObserveState.Stopping
        ctx.startService(
            Intent(ctx, ObserveForegroundService::class.java)
                .setAction(ObserveForegroundService.ACTION_STOP),
        )
        ioScope.launch {
            journal.append(LearningJournal.Entry(
                tsMillis = System.currentTimeMillis(),
                kind = "observe",
                note = "Observe service stop requested.",
            ))
        }
    }

    /** Called by the service as its lifecycle progresses. */
    internal fun publish(next: ObserveState) {
        _state.value = next
    }

    /** Wipe every Observe artefact on disk + journal entries. Defensive. */
    fun forgetEverything() {
        stop()
        ioScope.launch {
            // Working-tier journal entries (today the only Observe surface).
            journal.forgetEverything()
            // The audio scratch / transcripts directory — created lazily by
            // M8.1b's AudioRecorder; deleting recursively is safe even when
            // it doesn't exist.
            ctx.filesDir.resolve("observe").let { dir ->
                if (dir.exists()) dir.deleteRecursively()
            }
            journal.append(LearningJournal.Entry(
                tsMillis = System.currentTimeMillis(),
                kind = "observe",
                note = "Forget everything: wiped Observe scratch + journal.",
            ))
        }
    }
}
