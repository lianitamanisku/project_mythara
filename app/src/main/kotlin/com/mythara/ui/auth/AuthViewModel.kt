package com.mythara.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.auth.AuthManager
import com.mythara.auth.AuthSettings
import com.mythara.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Thin Compose-facing view of [AuthManager]. Exists so Compose can call
 * `hiltViewModel<AuthViewModel>()` and observe the state via a StateFlow
 * without needing a CompositionLocal handle on the singleton.
 *
 * [state] is the *effective* lock state: it folds in the user's
 * "biometric lock" master switch ([AuthSettings.lockEnabledFlow]). When
 * the lock is disabled the app reports Unlocked unconditionally — even
 * on cold start — so the AuthGate is skipped entirely.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val manager: AuthManager,
    settings: AuthSettings,
) : ViewModel() {
    val state: StateFlow<AuthState> =
        combine(manager.state, settings.lockEnabledFlow()) { s, lockEnabled ->
            if (!lockEnabled) AuthState.Unlocked else s
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Locked)

    fun unlock() = manager.unlock()
    fun lock() = manager.lock()
}
