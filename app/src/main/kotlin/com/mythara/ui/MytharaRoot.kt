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
import com.mythara.ui.amulet.AmuletChip
import com.mythara.ui.amulet.AmuletPage
import com.mythara.ui.amulet.PopupAmulet
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.amulet.detectGlobalLongPress
import com.mythara.ui.dashboard.DashboardHome
import com.mythara.ui.auth.AuthGate
import com.mythara.ui.auth.AuthViewModel
import com.mythara.ui.chat.ChatScreen
import com.mythara.ui.fold.FoldPosture
import com.mythara.ui.fold.RoseBloomOverlay
import com.mythara.ui.fold.rememberFoldPosture
import com.mythara.ui.launcher.SpotlightDrawer
import com.mythara.ui.permissions.PermissionsScreen
import com.mythara.ui.system.MytharaStatusBar
import com.mythara.ui.triage.NotificationTriageScreen
import com.mythara.ui.usage.MiniMaxWebSignInScreen
import com.mythara.ui.usage.UsageScreen
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
    /**
     * Optional initial route the Activity wants us to land on
     * after the NavHost mounts — set by the overlay's
     * Me-avatar / PTT taps, which call back into MainActivity
     * with [com.mythara.services.LockscreenIslandService.EXTRA_OPEN_ROUTE].
     * One-shot: we navigate once and then ignore further changes
     * (the LaunchedEffect is keyed on the route string so a
     * repeated route value still re-triggers).
     */
    initialRoute: String? = null,
) {
    val authVm: AuthViewModel = hiltViewModel()
    val authState by authVm.state.collectAsState()
    val nav = rememberNavController()
    // Honour the activity-provided deep-link route. Runs once
    // after composition; the NavHost destinations all live below
    // so by the time this fires the host is mounted.
    LaunchedEffect(initialRoute) {
        if (!initialRoute.isNullOrBlank()) {
            runCatching { nav.navigate(initialRoute) { launchSingleTop = true } }
        }
    }

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
                    // Spotlight drawer overlay — pull-down sheet
                    // surfaced from a constellation chip. State
                    // lives at the root so it can render above
                    // every NavHost destination.
                    var spotlightOpen by remember { mutableStateOf(false) }
                    // Two-row layout: status bar TAKES its own row
                    // at the top (so screen content beneath isn't
                    // overlapped by it), then the active layout +
                    // overlays fill the remaining space below.
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                    MytharaStatusBar(
                        // In-app pill ALSO uses the black-zone
                        // wrapper now so the visual continuity
                        // between the overlay and the in-app
                        // surface is preserved (same height,
                        // same cutout-hiding bar). User's spec:
                        // "move it up and turn the complete
                        // upper portion black".
                        blackZoneHeightDp = com.mythara.ui.system.OVERLAY_BLACK_ZONE_HEIGHT_DP,
                        onRoseTap = {
                            nav.navigate(Routes.Chat) {
                                launchSingleTop = true
                                popUpTo(Routes.Chat) { inclusive = false }
                            }
                        },
                        onOpenAboutMe = { nav.navigate(Routes.AboutMe) },
                        onOpenUsage = { nav.navigate(Routes.Usage) },
                    )
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
                            composable(Routes.Memory) {
                                // Lifeline / memory grid — full-pane
                                // version of the right-pane TimelineGridPane.
                                com.mythara.ui.lifeline.TimelineGridPane(
                                    onClose = { nav.popBackStack() },
                                )
                            }
                            composable(Routes.Tasks) {
                                // Cross-device task list — full-pane
                                // version of the right-pane TasksScreenPane.
                                com.mythara.ui.tasks.TasksScreenPane(
                                    onClose = { nav.popBackStack() },
                                )
                            }
                            composable(Routes.Permissions) {
                                PermissionsScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Triage) {
                                NotificationTriageScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Usage) {
                                UsageScreen(
                                    onBack = { nav.popBackStack() },
                                    onSignIn = { nav.navigate(Routes.MiniMaxSignIn) },
                                )
                            }
                            composable(Routes.MiniMaxSignIn) {
                                MiniMaxWebSignInScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Dashboard) {
                                // Compact-mode Dashboard — the same
                                // tile grid the tablet/foldable
                                // [DashboardLayout] surfaces, but
                                // standalone. Each tile maps to an
                                // existing destination via this
                                // single-pane NavController.
                                DashboardHome(
                                    onOpenTasks = { nav.navigate(Routes.Tasks) },
                                    onOpenTimeline = { nav.navigate(Routes.Memory) },
                                    onOpenDevices = { nav.navigate(Routes.People) },
                                    onOpenHr = { nav.navigate(Routes.Insights) },
                                    onOpenHealth = { nav.navigate(Routes.Insights) },
                                    onOpenSensors = { nav.navigate(Routes.Settings) },
                                    onOpenSkills = { nav.navigate(Routes.Settings) },
                                    onOpenAppDrawer = {
                                        // Pop back to chat first so
                                        // the amulet's spotlight
                                        // sentinel can fire from a
                                        // clean state.
                                        nav.popBackStack(Routes.Chat, inclusive = false)
                                    },
                                )
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

                    // Build the paginated amulet pages. Each page is a
                    // ring of chips around the rose; the user cycles
                    // pages by tapping the central rose. Page order
                    // (cycles forward, wraps):
                    //
                    //   0. Menu — primary navigation destinations
                    //   1. PTT  — voice / mic quick actions
                    //   2. More — secondary screens (Memory, Tasks,
                    //              Notes, Music vocab, About me)
                    //   3+. Apps — installed launcher apps, paginated
                    //              8 per ring
                    //
                    // Page list is `remember`ed lazily — InstalledApps
                    // takes ~50ms to enumerate, so we want it computed
                    // once per amulet open, not per recomposition.
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val appsProvider = remember {
                        dagger.hilt.android.EntryPointAccessors.fromApplication(
                            ctx.applicationContext,
                            MytharaRootEntryPoint::class.java,
                        ).installedApps()
                    }
                    var amuletPages by remember { mutableStateOf<List<AmuletPage>>(emptyList()) }
                    LaunchedEffect(amuletAnchor) {
                        if (amuletAnchor != null && amuletPages.isEmpty()) {
                            amuletPages = buildAmuletPages(
                                appsProvider = appsProvider,
                                onNavigate = { route ->
                                    amuletAnchor = null
                                    if (route == ROUTE_SPOTLIGHT) {
                                        spotlightOpen = true
                                    } else {
                                        nav.navigate(route) { launchSingleTop = true }
                                    }
                                },
                                onLaunchPackage = { pkg ->
                                    amuletAnchor = null
                                    appsProvider.launchIntent(pkg)?.let {
                                        runCatching { ctx.startActivity(it) }
                                    }
                                },
                                onPttPlaceholder = { amuletAnchor = null },
                            )
                        } else if (amuletAnchor == null) {
                            // Re-build next time so a freshly-installed
                            // app shows up in the wheel without an
                            // app restart.
                            amuletPages = emptyList()
                        }
                    }

                    amuletAnchor?.let { anchor ->
                        if (amuletPages.isNotEmpty()) {
                            PopupAmulet(
                                anchorPx = anchor,
                                pages = amuletPages,
                                amuletSizeDp = AMULET_SIZE_DP.value.toInt(),
                                onScrimTap = { amuletAnchor = null },
                            )
                        }
                    }

                    // Spotlight pull-down drawer — sliding-from-top
                    // sheet with type-search across every installed
                    // launcher app. Rendered ABOVE the popup amulet
                    // since the user can summon Spotlight while a
                    // constellation is closing (and we want it on
                    // top during that animation too).
                    if (spotlightOpen) {
                        SpotlightDrawer(
                            onDismiss = { spotlightOpen = false },
                        )
                    }

                    } // end inner Box (layout + overlays)
                    } // end outer Column (status bar + inner Box)

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
 * Sentinel constellation-slot "route" for chips that open an
 * overlay (Spotlight drawer, Resonance toggle sheet, etc.) instead
 * of triggering NavController.navigate. Handled in MytharaRoot's
 * onSlotTap dispatcher; pure-string sentinel so chips can carry it
 * in the same data shape as a real Routes constant.
 */
private const val ROUTE_SPOTLIGHT = "__overlay_spotlight"

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
    /** Lifeline / memory grid — the chronological photo + caption
     *  feed Mythara built from the user's camera roll + chat
     *  imports. Was reachable only from the right pane in two-
     *  pane mode; now a top-level route so the amulet can surface
     *  it on the "more" page. */
    const val Memory = "memory"
    /** Cross-device task list. Was reachable only from the right
     *  pane; promoted to a top-level route alongside Memory. */
    const val Tasks = "tasks"
    /** Notification triage — see auto-dismissed, mark important. */
    const val Triage = "triage"
    /** MiniMax API usage / quota dashboard. */
    const val Usage = "usage"
    /** Dashboard / command center — surfaced from the amulet as a
     *  separate destination so users can reach the tile grid even
     *  on compact phones (previously tablet/foldable-only). */
    const val Dashboard = "dashboard"
    /** WebView sign-in flow that captures the user's MiniMax web
     *  session cookies so the Usage screen can show the same data
     *  as the platform's token-plan dashboard. */
    const val MiniMaxSignIn = "minimax-signin"
}

/** Hilt accessor for plain composables (no ViewModel) — lets the
 *  root resolve singletons like InstalledAppsProvider that the
 *  amulet pages need. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface MytharaRootEntryPoint {
    fun installedApps(): com.mythara.launcher.InstalledAppsProvider
}

/**
 * Build the paginated amulet's page list. Pages cycle on each
 * center-rose tap, in this order:
 *
 *   0. Menu   — top primary destinations
 *   1. PTT    — voice / mic quick actions
 *   2. More   — secondary screens (Memory, Tasks, Notes, Music
 *               vocab, About me, About)
 *   3+. Apps  — installed apps split into 8-per-page rings,
 *               sorted A→Z. Lazy: only fetched when the amulet
 *               opens.
 *
 * Suspending — the apps lookup hits PackageManager.
 */
private suspend fun buildAmuletPages(
    appsProvider: com.mythara.launcher.InstalledAppsProvider,
    onNavigate: (String) -> Unit,
    onLaunchPackage: (String) -> Unit,
    onPttPlaceholder: () -> Unit,
): List<AmuletPage> {
    // Page 0: primary nav destinations. Same 9 + spotlight that
    // the previous amulet exposed, just shaped as AmuletChip.
    val menuChips = listOf(
        chipAt(0f, "chat", MytharaColors.Bok) { onNavigate(Routes.Chat) },
        chipAt(36f, "settings", MytharaColors.SurfaceHigh) { onNavigate(Routes.Settings) },
        chipAt(72f, "perms", MytharaColors.Charple) { onNavigate(Routes.Permissions) },
        chipAt(108f, "insights", MytharaColors.Bok) { onNavigate(Routes.Insights) },
        chipAt(144f, "face", MytharaColors.Bok) { onNavigate(Routes.Face) },
        chipAt(180f, "triage", MytharaColors.Charple) { onNavigate(Routes.Triage) },
        chipAt(216f, "people", MytharaColors.Charple) { onNavigate(Routes.People) },
        chipAt(252f, "drawer", MytharaColors.Mustard) { onNavigate(ROUTE_SPOTLIGHT) },
        chipAt(288f, "usage", MytharaColors.Mustard) { onNavigate(Routes.Usage) },
        chipAt(324f, "dash", MytharaColors.Malibu) { onNavigate(Routes.Dashboard) },
    )

    // Page 1: PTT actions — current placeholder dispatches to
    // the existing handler. Wiring through to the actual
    // composer mic / STT-mute / music / continuous-voice
    // toggles is a separate piece (those tools live in
    // ChatScreen's composer; an event-bus or callback hop is
    // the next step).
    val pttChips = listOf(
        chipGlyph(0f, "mic", "🎤", MytharaColors.Charple, onPttPlaceholder),
        chipGlyph(90f, "mute", "🤫", MytharaColors.Charple, onPttPlaceholder),
        chipGlyph(180f, "music", "♪", MytharaColors.Bok, onPttPlaceholder),
        chipGlyph(270f, "voice", "∞", MytharaColors.Bok, onPttPlaceholder),
    )

    // Page 2: previously-orphan secondary screens. These exist
    // as routes but weren't in the menu wheel; surfacing them
    // here gives the user a path to every non-tablet-only
    // screen Mythara ships.
    val moreChips = listOf(
        chipAt(0f, "memory", MytharaColors.Bok) { onNavigate(Routes.Memory) },
        chipAt(60f, "tasks", MytharaColors.Mustard) { onNavigate(Routes.Tasks) },
        chipAt(120f, "notes", MytharaColors.Charple) { onNavigate(Routes.Notes) },
        chipAt(180f, "music vocab", MytharaColors.Bok) { onNavigate(Routes.MusicVocab) },
        chipAt(240f, "about me", MytharaColors.Charple) { onNavigate(Routes.AboutMe) },
        chipAt(300f, "about", MytharaColors.SurfaceHigh) { onNavigate(Routes.About) },
    )

    // Page 3+: installed launcher apps, 8 per page. Each chip
    // lazily fetches its icon AT FIRST PAINT so a 100-app phone
    // doesn't pay the icon-decode cost up-front. Until the icon
    // resolves, the chip's glyph (initial letter) shows.
    val apps = runCatching { appsProvider.list() }.getOrDefault(emptyList())
    val appPages = mutableListOf<AmuletPage>()
    if (apps.isNotEmpty()) {
        val perPage = 8
        val total = (apps.size + perPage - 1) / perPage
        apps.chunked(perPage).forEachIndexed { pageIdx, chunk ->
            val n = chunk.size
            val chips = chunk.mapIndexed { i, app ->
                val angle = (i * 360f) / n
                AmuletChip(
                    angleDeg = angle,
                    caption = app.label.take(10),
                    accent = MytharaColors.Bok,
                    glyph = app.initial,
                    onTap = { onLaunchPackage(app.packageName) },
                )
            }
            appPages += AmuletPage(
                label = "apps · ${pageIdx + 1}/$total",
                chips = chips,
            )
        }
    }

    return buildList {
        add(AmuletPage("menu", menuChips))
        add(AmuletPage("ptt", pttChips))
        add(AmuletPage("more", moreChips))
        addAll(appPages)
    }
}

private fun chipAt(angleDeg: Float, caption: String, accent: androidx.compose.ui.graphics.Color, onTap: () -> Unit): AmuletChip =
    AmuletChip(angleDeg = angleDeg, caption = caption, accent = accent, onTap = onTap)

private fun chipGlyph(angleDeg: Float, caption: String, glyph: String, accent: androidx.compose.ui.graphics.Color, onTap: () -> Unit): AmuletChip =
    AmuletChip(angleDeg = angleDeg, caption = caption, accent = accent, glyph = glyph, onTap = onTap)
