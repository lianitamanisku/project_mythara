package com.mythara.auth

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Wraps Android's [BiometricPrompt] for our outer-app gate. Asks for
 * `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` — that single combination
 * surfaces face / fingerprint when enrolled, and falls back to the
 * user's PIN / pattern / password as the negative-button action.
 *
 * Three outcomes the caller cares about, expressed via callback:
 *  - **success**  — the user authenticated; flip [AuthManager] to Unlocked.
 *  - **error**    — a hard failure the user can't recover from (no
 *                   credential set up, hardware unavailable). The caller
 *                   should surface a message; we still gate the app.
 *  - **cancel**   — user dismissed the prompt. The caller stays on the
 *                   AuthGate and lets the user retry; we don't auto-exit.
 *
 * "No credential set up" is special: we deliberately do *not* let the
 * user bypass the lock just because their phone has no PIN. Instead we
 * surface a clear message asking them to enable a screen lock first.
 */
class AppAuth {

    private val TAG = "Mythara/Auth"

    sealed interface Result {
        data object Success : Result
        data object Canceled : Result
        data class Error(val message: String, val needsScreenLock: Boolean) : Result
    }

    /**
     * Check whether the device can satisfy our auth requirement *before*
     * we try to prompt. Useful for pre-flight in the AuthGate UI.
     */
    fun status(activity: FragmentActivity): Status {
        val mgr = BiometricManager.from(activity)
        return when (mgr.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Status.Ready
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> Status.Unsupported
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Status.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Status.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Status.NoScreenLock
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> Status.UpdateRequired
            else -> Status.Unsupported
        }
    }

    enum class Status { Ready, NoScreenLock, NoHardware, HardwareUnavailable, UpdateRequired, Unsupported }

    fun authenticate(activity: FragmentActivity, callback: (Result) -> Unit) {
        when (val s = status(activity)) {
            Status.Ready -> doPrompt(activity, callback)
            Status.NoScreenLock -> callback(
                Result.Error(
                    "Set a device screen lock (PIN, pattern, or password) in Settings to use Mythara.",
                    needsScreenLock = true,
                ),
            )
            else -> {
                Log.w(TAG, "biometric status=$s — gating with error")
                callback(
                    Result.Error(
                        "Device authentication is unavailable ($s). Mythara requires a screen lock.",
                        needsScreenLock = true,
                    ),
                )
            }
        }
    }

    private fun doPrompt(activity: FragmentActivity, callback: (Result) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.d(TAG, "auth success")
                callback(Result.Success)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.d(TAG, "auth error code=$errorCode msg=$errString")
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> callback(Result.Canceled)
                    else -> callback(Result.Error(errString.toString(), needsScreenLock = false))
                }
            }
            override fun onAuthenticationFailed() {
                // A specific biometric attempt failed (wrong finger, face mismatch).
                // System UI surfaces this already; we don't pop our own message.
                // The user can retry within the same prompt session.
            }
        })

        // When BIOMETRIC_STRONG | DEVICE_CREDENTIAL is set, the system surfaces
        // the device credential as the fallback affordance and the negative
        // button is suppressed — so we deliberately don't call
        // setNegativeButtonText(). (Setting it throws IllegalArgumentException.)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Mythara")
            .setSubtitle("Authenticate to continue")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}
