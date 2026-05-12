package com.mythara.ui.secret

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.secret.observe.ObserveState
import com.mythara.secret.observe.ObserveStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * VM behind the Secret Settings screen. Wraps [ObserveStore] for the
 * Observe pipeline controls and surfaces RECORD_AUDIO permission state
 * — the user must grant the mic before we can flip the toggle on.
 */
@HiltViewModel
class SecretSettingsViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: ObserveStore,
) : ViewModel() {

    data class State(
        val observeState: ObserveState = ObserveState.Idle,
        val micGranted: Boolean = false,
        val confirmingForget: Boolean = false,
    )

    private val _state = MutableStateFlow(State(observeState = store.state.value))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collect { s ->
                _state.update { it.copy(observeState = s) }
            }
        }
        refreshPermission()
    }

    fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        _state.update { it.copy(micGranted = granted) }
    }

    fun toggleObserve() {
        when (val s = _state.value.observeState) {
            is ObserveState.Idle -> store.start()
            is ObserveState.Starting,
            is ObserveState.Running,
            is ObserveState.Paused -> store.stop()
            is ObserveState.Stopping -> {} // no-op, transitioning
            is ObserveState.Error -> {
                // Reset + try again
                store.stop(); store.start()
            }
            else -> {}
        }
    }

    fun pauseOrResume() {
        when (_state.value.observeState) {
            is ObserveState.Running -> store.pause()
            is ObserveState.Paused -> store.resume()
            else -> {}
        }
    }

    fun openForgetConfirm() = _state.update { it.copy(confirmingForget = true) }
    fun cancelForget()       = _state.update { it.copy(confirmingForget = false) }
    fun confirmForget() {
        _state.update { it.copy(confirmingForget = false) }
        store.forgetEverything()
    }
}
