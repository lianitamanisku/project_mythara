package com.mythara.wake

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-process state holder for the Lumi always-listen pipeline.
 * The [MytharaWakeListenerService] writes here; [WakeWordPanelViewModel] and
 * [com.mythara.ui.MytharaRoot] read.
 *
 * The contract:
 *  - [state] follows the foreground service's lifecycle so the
 *    Settings status pill reflects what's actually happening on the
 *    audio path.
 *  - [wakeQueries] fires once per matched "Hey Lumi <query>" transcript.
 *    Subscribers can either navigate-to-Chat (MytharaRoot) or submit
 *    the query to MiniMax (ChatViewModel). The buffered capacity lets
 *    multiple subscribers consume the same event without rendezvous.
 */
@Singleton
class WakeListenerStore @Inject constructor() {

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data object Listening : State
        data object Stopping : State
        data class Error(val message: String) : State
    }

    /** A successfully-parsed "Hey Lumi <query>" event. */
    data class WakeQuery(val query: String, val tsMillis: Long)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _wakeQueries = MutableSharedFlow<WakeQuery>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val wakeQueries: SharedFlow<WakeQuery> = _wakeQueries.asSharedFlow()

    fun setState(s: State) {
        _state.value = s
    }

    fun emitWake(query: String, tsMillis: Long = System.currentTimeMillis()) {
        _wakeQueries.tryEmit(WakeQuery(query = query, tsMillis = tsMillis))
    }
}
