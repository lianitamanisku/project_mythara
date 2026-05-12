package com.mythara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mythara.auth.AuthState
import com.mythara.ui.auth.AuthGate
import com.mythara.ui.auth.AuthViewModel
import com.mythara.ui.chat.ChatScreen
import com.mythara.ui.settings.SettingsScreen
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaTheme

/**
 * Compose root. Owns the theme. Pivots between the AuthGate and the
 * main NavHost based on [AuthViewModel.state]. The NavHost is only
 * instantiated when the app is Unlocked — that way ChatViewModel /
 * SettingsViewModel never initialise until after auth, so no
 * background flows (history observation, MiniMax client warm-ups) run
 * before the user has authenticated.
 *
 * @param onUnlockRequest Invoked when the user taps "unlock" on the gate.
 *                       The Activity launches BiometricPrompt and flips
 *                       AuthManager → Unlocked on success.
 * @param authErrorMessage Message to surface on the gate from the last
 *                       unsuccessful attempt (e.g., "screen lock missing").
 */
@Composable
fun MytharaRoot(
    onUnlockRequest: () -> Unit,
    authErrorMessage: String? = null,
) {
    val authVm: AuthViewModel = hiltViewModel()
    val authState by authVm.state.collectAsState()
    val nav = rememberNavController()

    MytharaTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg),
        ) {
            when (authState) {
                is AuthState.Locked -> AuthGate(
                    onUnlock = onUnlockRequest,
                    errorMessage = authErrorMessage,
                )
                is AuthState.Unlocked -> {
                    NavHost(navController = nav, startDestination = Routes.Chat) {
                        composable(Routes.Chat) {
                            ChatScreen(onOpenSettings = { nav.navigate(Routes.Settings) })
                        }
                        composable(Routes.Settings) {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

object Routes {
    const val Chat = "chat"
    const val Settings = "settings"
}
