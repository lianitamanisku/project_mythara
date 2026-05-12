package com.mythara.ui.auth

import androidx.lifecycle.ViewModel
import com.mythara.auth.AuthManager
import com.mythara.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin Compose-facing view of [AuthManager]. Exists so Compose can call
 * `hiltViewModel<AuthViewModel>()` and observe the state via a StateFlow
 * without needing a CompositionLocal handle on the singleton.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val manager: AuthManager,
) : ViewModel() {
    val state: StateFlow<AuthState> = manager.state
    fun unlock() = manager.unlock()
    fun lock() = manager.lock()
}
