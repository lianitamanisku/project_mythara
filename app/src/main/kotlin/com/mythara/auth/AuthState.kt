package com.mythara.auth

/**
 * Outer app-entry lock state.
 *
 * - [Locked]   — we need the user to authenticate via the system device
 *                credential (PIN/pattern/password) or an enrolled biometric
 *                (face/fingerprint) before composing the main UI.
 * - [Unlocked] — auth has succeeded for this foreground session; the chat,
 *                settings, etc. are visible. The next [Locked] transition
 *                happens when the app is sent to the background
 *                (ProcessLifecycleOwner.onStop) or the process is rebuilt.
 *
 * This is separate from the (M8) Secret-mode password gate. Two layered
 * authentications: device credential → enter Mythara at all; Secret
 * password → enter Observe-mode controls.
 */
sealed interface AuthState {
    data object Locked : AuthState
    data object Unlocked : AuthState
}
