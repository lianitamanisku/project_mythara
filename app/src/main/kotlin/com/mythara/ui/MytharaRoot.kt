package com.mythara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mythara.auth.AuthState
import com.mythara.ui.about.AboutMeScreen
import com.mythara.ui.about.AboutScreen
import com.mythara.ui.auth.AuthGate
import com.mythara.ui.auth.AuthViewModel
import com.mythara.ui.chat.ChatScreen
import com.mythara.ui.dashboard.DashboardLayout
import com.mythara.ui.face.FaceScreen
import com.mythara.ui.insights.InsightsScreen
import com.mythara.ui.notes.NotesScreen
import com.mythara.ui.onboarding.OnboardingScreen
import com.mythara.ui.util.isTabletDisplay
import com.mythara.ui.secret.SecretSettingsScreen
import com.mythara.ui.secret.SecretUnlockDialog
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
    /**
     * Triggered when the Secret-mode unlock dialog wants to authenticate via
     * the device biometric / credential. The Activity wires this through
     * [com.mythara.auth.AppAuth] with a Secret-specific title.
     */
    onSecretAuthRequest: (onSuccess: () -> Unit, onFailure: (String?) -> Unit) -> Unit,
    authErrorMessage: String? = null,
    /**
     * WindowSizeClass from the activity. When width is Medium or Expanded
     * (unfolded foldables, tablets, wide windows on Chrome OS / DeX),
     * MytharaRoot renders a two-pane layout — chat always on the left,
     * settings / people / about / secret on the right. Compact width
     * (typical phone portrait) keeps the existing single-pane NavHost.
     */
    windowSize: androidx.compose.material3.windowsizeclass.WindowSizeClass? = null,
) {
    val authVm: AuthViewModel = hiltViewModel()
    val authState by authVm.state.collectAsState()
    val nav = rememberNavController()

    // First-run onboarding pivot. Sits OUTSIDE the AuthGate because
    // half the steps deep-link to system Settings (Accessibility,
    // Notification access, Usage access), and bouncing back through
    // a re-lock + biometric every time would make the walkthrough
    // unusable. Once OnboardingStore.markCompleted() lands the flag
    // becomes true and subsequent launches go straight to the
    // AuthGate as normal.
    //
    // The flag is null until DataStore resolves; we render a blank
    // Bg-coloured surface during that one-frame window so the
    // AuthGate doesn't briefly flash before pivoting to onboarding.
    val rootVmEarly: RootViewModel = hiltViewModel()
    val onboardingCompleted by rootVmEarly.onboardingCompleted.collectAsState()

    MytharaTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg),
        ) {
            when {
                onboardingCompleted == null -> {
                    // DataStore not resolved yet — empty bg surface for
                    // a single frame. Keeps the AuthGate from flashing.
                }
                onboardingCompleted == false -> {
                    OnboardingScreen(onComplete = { /* state flips via flow */ })
                }
                else -> when (authState) {
                is AuthState.Locked -> AuthGate(
                    onUnlock = onUnlockRequest,
                    errorMessage = authErrorMessage,
                )
                is AuthState.Unlocked -> {
                    var secretUnlockOpen by remember { mutableStateOf(false) }

                    // "Hey Lumi <query>" → navigate to Chat. The actual
                    // submission to MiniMax happens inside ChatViewModel
                    // (which collects the same wake-queries flow); our
                    // job here is just routing — pop the user from
                    // Settings / About / SecretSettings back to Chat
                    // so the agent's response is visible.
                    //
                    // Only collected while Unlocked — wakes that fire
                    // while the app is Locked (just-backgrounded) are
                    // deliberately not auto-actioned; the persistent
                    // service notification is the surface to re-engage.
                    val rootVm: RootViewModel = hiltViewModel()
                    LaunchedEffect(Unit) {
                        rootVm.wakeQueries.collect {
                            val current = nav.currentDestination?.route
                            if (current != Routes.Chat) {
                                nav.navigate(Routes.Chat) {
                                    popUpTo(Routes.Chat) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }

                    // 3-way layout pivot:
                    //   Compact            → single-pane NavHost (phones)
                    //   Non-compact + tablet (smallestScreenWidthDp ≥ 720)
                    //                     → DashboardLayout (command center)
                    //   Non-compact + not tablet (i.e. unfolded foldable
                    //                              or wide window)
                    //                     → TwoPaneLayout (chat + welcome)
                    // The tablet check is intentionally NOT the WindowSizeClass
                    // alone — unfolded foldables also land in Medium/Expanded
                    // but should keep their existing two-pane layout (their
                    // form factor doesn't suit a six-tile dashboard).
                    val isCompact = windowSize == null ||
                        windowSize.widthSizeClass == androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val isTablet = !isCompact && ctx.isTabletDisplay()

                    if (isCompact) {
                        NavHost(navController = nav, startDestination = Routes.Chat) {
                            composable(Routes.Chat) {
                                ChatScreen(
                                    onOpenSettings = { nav.navigate(Routes.Settings) },
                                    onOpenPeople = { nav.navigate(Routes.People) },
                                    onOpenFace = { nav.navigate(Routes.Face) },
                                    onOpenAboutMe = { nav.navigate(Routes.AboutMe) },
                                    onOpenInsights = { nav.navigate(Routes.Insights) },
                                )
                            }
                            composable(Routes.Face) {
                                FaceScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.AboutMe) {
                                AboutMeScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Insights) {
                                InsightsScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Settings) {
                                SettingsScreen(
                                    onBack = { nav.popBackStack() },
                                    onOpenAbout = { nav.navigate(Routes.About) },
                                    onOpenPeople = { nav.navigate(Routes.People) },
                                )
                            }
                            composable(Routes.People) {
                                com.mythara.ui.analytics.PeopleScreen(
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable(Routes.About) {
                                AboutScreen(
                                    onBack = { nav.popBackStack() },
                                    onSecretRequest = { secretUnlockOpen = true },
                                )
                            }
                            composable(Routes.SecretSettings) {
                                SecretSettingsScreen(
                                    onBack = { nav.popBackStack() },
                                    onOpenNotes = { nav.navigate(Routes.Notes) },
                                )
                            }
                            composable(Routes.Notes) {
                                NotesScreen(onBack = { nav.popBackStack() })
                            }
                        }
                    } else if (isTablet) {
                        DashboardLayout(
                            onSecretUnlockRequest = { secretUnlockOpen = true },
                        )
                    } else {
                        TwoPaneLayout(
                            onSecretUnlockRequest = { secretUnlockOpen = true },
                        )
                    }

                    if (secretUnlockOpen) {
                        SecretUnlockDialog(
                            onUnlocked = {
                                secretUnlockOpen = false
                                nav.navigate(Routes.SecretSettings)
                            },
                            onDismiss = { secretUnlockOpen = false },
                            onBiometricRequest = onSecretAuthRequest,
                        )
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
    const val About = "about"
    const val AboutMe = "about-me"
    const val Insights = "insights"
    const val SecretSettings = "secret"
    const val Notes = "notes"
    const val People = "people"
    const val Face = "face"
}
