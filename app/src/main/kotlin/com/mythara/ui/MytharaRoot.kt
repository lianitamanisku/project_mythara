package com.mythara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mythara.auth.AuthState
import com.mythara.ui.about.AboutMeScreen
import com.mythara.ui.about.AboutScreen
import com.mythara.ui.amulet.AMULET_SIZE_DP
import com.mythara.ui.amulet.ConstellationSlot
import com.mythara.ui.amulet.PopupAmulet
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.amulet.detectGlobalLongPress
import com.mythara.ui.auth.AuthGate
import com.mythara.ui.auth.AuthViewModel
import com.mythara.ui.chat.ChatScreen
import com.mythara.ui.fold.FoldPosture
import com.mythara.ui.fold.RoseBloomOverlay
import com.mythara.ui.fold.rememberFoldPosture
import com.mythara.ui.permissions.PermissionsScreen
import com.mythara.ui.triage.NotificationTriageScreen
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

                    // Long-press-summon model: the amulet is HIDDEN
                    // by default (was persistent at the bottom; user
                    // reported it overlapped the chat composer + the
                    // gesture-nav home pill). Now the user holds a
                    // finger anywhere on screen for 600ms and the
                    // amulet appears AT the press point with a full
                    // 360° constellation. detectGlobalLongPress runs
                    // on the Final pointer-event pass so it never
                    // wins a gesture a child wanted (TextField cursor
                    // placement, button presses, scrollback drags
                    // all keep working normally).
                    var amuletAnchor by remember {
                        mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .detectGlobalLongPress { pos ->
                                amuletAnchor = pos
                            },
                    ) {
                    if (isCompact) {
                        NavHost(navController = nav, startDestination = Routes.Chat) {
                            composable(Routes.Chat) {
                                ChatScreen(
                                    onOpenSettings = { nav.navigate(Routes.Settings) },
                                    onOpenPeople = { nav.navigate(Routes.People) },
                                    onOpenFace = { nav.navigate(Routes.Face) },
                                    onOpenAboutMe = { nav.navigate(Routes.AboutMe) },
                                    onOpenInsights = { nav.navigate(Routes.Insights) },
                                    onOpenMusicVocab = { nav.navigate(Routes.MusicVocab) },
                                )
                            }
                            composable(Routes.MusicVocab) {
                                com.mythara.ui.music.MusicVocabularyScreen(
                                    onBack = { nav.popBackStack() },
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
                            composable(Routes.Permissions) {
                                PermissionsScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Triage) {
                                NotificationTriageScreen(onBack = { nav.popBackStack() })
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

                    // Slot positions are clock-degrees (0° = 12, 90° = 3).
                    // 10 slots at 36° apart spread evenly around the FULL
                    // CIRCLE — the popup amulet is anchored at the user's
                    // press point, not pinned to the screen bottom, so
                    // every angular position is reachable.
                    //
                    // Slot 0 (12 o'clock / top) = Chat (home), since
                    // the old "tap rose → home" gesture went away with
                    // the persistent amulet. Tap the central rose to
                    // dismiss without navigating.
                    val slots = remember {
                        listOf(
                            ConstellationSlot(0f, "chat", Routes.Chat, MytharaColors.Bok),
                            ConstellationSlot(36f, "settings", Routes.Settings, MytharaColors.SurfaceHigh),
                            ConstellationSlot(72f, "perms", Routes.Permissions, MytharaColors.Charple),
                            ConstellationSlot(108f, "insights", Routes.Insights, MytharaColors.Bok),
                            ConstellationSlot(144f, "face", Routes.Face, MytharaColors.Bok),
                            ConstellationSlot(180f, "triage", Routes.Triage, MytharaColors.Charple),
                            ConstellationSlot(216f, "people", Routes.People, MytharaColors.Charple),
                            ConstellationSlot(252f, "tasks", Routes.Notes, MytharaColors.Mustard),
                            ConstellationSlot(288f, "notes", Routes.Notes, MytharaColors.Bok),
                            ConstellationSlot(324f, "me", Routes.AboutMe, MytharaColors.Malibu),
                        )
                    }

                    // The summon-anywhere popup. Rendered above the
                    // active layout when the user has triggered a
                    // long-press; tap a chip to navigate, tap the
                    // central rose or the scrim to dismiss.
                    amuletAnchor?.let { anchor ->
                        PopupAmulet(
                            anchorPx = anchor,
                            slots = slots,
                            amuletSizeDp = AMULET_SIZE_DP.value.toInt(),
                            onSlotTap = { slot ->
                                amuletAnchor = null
                                nav.navigate(slot.route) {
                                    launchSingleTop = true
                                }
                            },
                            onCenterTap = { amuletAnchor = null },
                            onScrimTap = { amuletAnchor = null },
                        )
                    }

                    // Suppress unused warning — RoseGeometry is used
                    // by Constellation / Amulet / Bloom imports.
                    @Suppress("UNUSED_VARIABLE") val unused = RoseGeometry.OuterRadiusSourceUnits

                    // Fold-open rose bloom — plays every time the
                    // device transitions OUT of a folded posture.
                    // Skippable on tap from the overlay itself.
                    val foldPosture by rememberFoldPosture()
                    var lastPosture by remember { mutableStateOf(foldPosture) }
                    var showBloom by remember { mutableStateOf(false) }
                    LaunchedEffect(foldPosture) {
                        // Trigger the bloom whenever the user opens
                        // the device. Closing it (back to Folded)
                        // does NOT replay the bloom — that direction
                        // is the layout collapsing, not opening.
                        if (lastPosture == FoldPosture.Folded &&
                            foldPosture != FoldPosture.Folded) {
                            showBloom = true
                        }
                        lastPosture = foldPosture
                    }
                    if (showBloom) {
                        RoseBloomOverlay(
                            biasUp = foldPosture == FoldPosture.HalfOpened,
                            onComplete = { showBloom = false },
                        )
                    }
                    } // end Box wrapping the layout pivot

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

/**
 * Ordered list of "primary" destinations the user can step between
 * via swipe-left / swipe-right on the rose amulet. Chat is the
 * anchor; the others rotate around it. Secondary screens (Settings,
 * About Me, Notes, Permissions, Triage, etc.) are reachable via the
 * Constellation but NOT via the swipe-step flow — they'd noise up
 * the gesture too much.
 */
private val PrimaryStepOrder = listOf(
    Routes.Chat,
    Routes.Insights,
    Routes.Face,
    Routes.People,
)

private fun stepPrimary(current: String?, forward: Boolean): String? {
    if (current == null) return null
    val idx = PrimaryStepOrder.indexOf(current)
    if (idx < 0) {
        // Caller is on a secondary screen (Settings / Permissions /
        // etc.) — a swipe pulls us back to Chat as the anchor.
        return Routes.Chat
    }
    val n = PrimaryStepOrder.size
    val nextIdx = if (forward) (idx + 1) % n else (idx - 1 + n) % n
    return PrimaryStepOrder[nextIdx]
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
    const val MusicVocab = "music-vocab"
    /** Dedicated permissions screen — runtime + special perms in one place. */
    const val Permissions = "permissions"
    /** Notification triage — see auto-dismissed, mark important. */
    const val Triage = "triage"
}
