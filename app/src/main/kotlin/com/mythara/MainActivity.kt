package com.mythara

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.mythara.auth.AppAuth
import com.mythara.auth.AuthManager
import com.mythara.ui.MytharaRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single Compose activity. Routing lives inside [MytharaRoot] — we don't
 * use multiple Activities. Extends [FragmentActivity] (not the bare
 * ComponentActivity) because androidx.biometric:1.1's `BiometricPrompt`
 * needs a FragmentActivity to host its internal fragment.
 *
 * Auth posture:
 *   - cold start: AuthManager starts Locked → AuthGate renders, user
 *     unlocks via face / fingerprint / device PIN, AuthManager → Unlocked,
 *     MytharaRoot pivots to the NavHost.
 *   - foregrounded after background: ProcessLifecycleOwner.onStop fired
 *     while we were away → AuthManager is Locked again → AuthGate shows.
 *   - process death: AuthManager singleton is recreated as Locked → cold
 *     start path applies.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var authManager: AuthManager
    private val appAuth = AppAuth()
    private var lastAuthError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Process-wide lifecycle observer: when Mythara is fully sent to
        // the background, immediately flip the gate to Locked. Foreground
        // brings AuthGate back up.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    authManager.lock()
                }
            },
        )

        setContent {
            MytharaRoot(
                onUnlockRequest = {
                    appAuth.authenticate(this) { result ->
                        when (result) {
                            AppAuth.Result.Success -> {
                                lastAuthError = null
                                authManager.unlock()
                            }
                            AppAuth.Result.Canceled -> {
                                // User dismissed the prompt — stay locked,
                                // they can hit "unlock" again.
                            }
                            is AppAuth.Result.Error -> {
                                lastAuthError = result.message
                                // Force a recomposition by toggling lock state
                                // (no-op if already locked, but updates the
                                // error message captured by the gate).
                                authManager.lock()
                            }
                        }
                    }
                },
                authErrorMessage = lastAuthError,
            )
        }
    }
}
