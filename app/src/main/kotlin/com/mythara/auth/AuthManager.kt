package com.mythara.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton store for the outer-app auth state. The `MainActivity` calls
 * [lock]/[unlock] in response to ProcessLifecycleOwner lifecycle events
 * and BiometricPrompt results; Compose observes [state] via
 * [com.mythara.ui.auth.AuthViewModel].
 *
 * Locked on construction so cold starts always require authentication
 * before the rest of the UI composes. Survives configuration changes
 * (because it's a Hilt @Singleton attached to the SingletonComponent)
 * but resets to Locked on process death — exactly the security
 * posture we want.
 */
@Singleton
class AuthManager @Inject constructor() {
    private val _state = MutableStateFlow<AuthState>(AuthState.Locked)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun lock() { _state.value = AuthState.Locked }
    fun unlock() { _state.value = AuthState.Unlocked }

    val isUnlocked: Boolean
        get() = _state.value is AuthState.Unlocked
}
