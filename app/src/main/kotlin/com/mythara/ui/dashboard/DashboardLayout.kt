package com.mythara.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mythara.ui.RightPaneRoutes
import com.mythara.ui.about.AboutScreen
import com.mythara.ui.chat.ChatScreen
import com.mythara.ui.secret.SecretSettingsScreen
import com.mythara.ui.settings.SettingsScreen
import com.mythara.ui.theme.MytharaColors

/**
 * Tablet-only command-center layout. Same shape as
 * [com.mythara.ui.TwoPaneLayout] (chat left, NavController-driven
 * content right) but the right pane's startDestination is the
 * [DashboardHome] tile grid instead of the empty foldable welcome
 * placeholder.
 *
 * All existing right-pane screens (Tasks, Timeline, People, Settings,
 * About, SecretSettings, AppDrawer) are reused verbatim — the only
 * behavioural difference is that their `onBack` / `onClose` falls back
 * to `RightPaneRoutes.Dashboard` instead of `RightPaneRoutes.Welcome`
 * so the user always lands back on the dashboard.
 *
 * Dashboard tile taps route through the same NavController the chat-
 * header pills use, so tiles and pills are interchangeable entry
 * points into the right-pane subsystems.
 */
@Composable
fun DashboardLayout(
    onSecretUnlockRequest: () -> Unit,
) {
    val rightNav = rememberNavController()

    Row(modifier = Modifier.fillMaxSize()) {
        // LEFT PANE — chat, always present.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MytharaColors.Bg),
        ) {
            ChatScreen(
                onOpenSettings = { navTo(rightNav, RightPaneRoutes.Settings) },
                onOpenPeople = { navTo(rightNav, RightPaneRoutes.People) },
                onOpenAppDrawer = { navTo(rightNav, RightPaneRoutes.AppDrawer) },
                onOpenTimeline = { navTo(rightNav, RightPaneRoutes.Timeline) },
                onOpenTasks = { navTo(rightNav, RightPaneRoutes.Tasks) },
            )
        }

        // Hairline divider — single dp accent so the two surfaces
        // feel related rather than awkwardly siloed.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MytharaColors.SurfaceHigh),
        )

        // RIGHT PANE — dashboard + secondary destinations.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MytharaColors.Bg),
        ) {
            NavHost(
                navController = rightNav,
                startDestination = RightPaneRoutes.Dashboard,
            ) {
                composable(RightPaneRoutes.Dashboard) {
                    DashboardHome(
                        onOpenTasks = { navTo(rightNav, RightPaneRoutes.Tasks) },
                        onOpenTimeline = { navTo(rightNav, RightPaneRoutes.Timeline) },
                        // Devices / HR / Health / Sensors don't have
                        // dedicated full screens yet — tapping their
                        // tile routes to People (closest semantic fit
                        // for relationship signals) or Settings (where
                        // the panels live). Real per-subsystem screens
                        // can land in follow-ups; the dashboard tiles
                        // themselves are the live surface for v1.
                        onOpenDevices = { navTo(rightNav, RightPaneRoutes.People) },
                        onOpenHr = { navTo(rightNav, RightPaneRoutes.People) },
                        onOpenHealth = { navTo(rightNav, RightPaneRoutes.Settings) },
                        onOpenSensors = { navTo(rightNav, RightPaneRoutes.Settings) },
                        onOpenSkills = { navTo(rightNav, RightPaneRoutes.Settings) },
                        onOpenAppDrawer = { navTo(rightNav, RightPaneRoutes.AppDrawer) },
                    )
                }
                composable(RightPaneRoutes.Settings) {
                    SettingsScreen(
                        onBack = { popToDashboard(rightNav) },
                        onOpenAbout = { navTo(rightNav, RightPaneRoutes.About) },
                        onOpenPeople = { navTo(rightNav, RightPaneRoutes.People) },
                    )
                }
                composable(RightPaneRoutes.People) {
                    com.mythara.ui.analytics.PeopleScreen(
                        onBack = { popToDashboard(rightNav) },
                    )
                }
                composable(RightPaneRoutes.About) {
                    AboutScreen(
                        onBack = { popToDashboard(rightNav) },
                        onSecretRequest = onSecretUnlockRequest,
                    )
                }
                composable(RightPaneRoutes.SecretSettings) {
                    SecretSettingsScreen(onBack = { popToDashboard(rightNav) })
                }
                composable(RightPaneRoutes.AppDrawer) {
                    com.mythara.ui.launcher.AppDrawerPane(
                        onClose = { popToDashboard(rightNav) },
                    )
                }
                composable(RightPaneRoutes.Timeline) {
                    com.mythara.ui.lifeline.TimelineGridPane(
                        onClose = { popToDashboard(rightNav) },
                    )
                }
                composable(RightPaneRoutes.Tasks) {
                    com.mythara.ui.tasks.TasksScreenPane(
                        onClose = { popToDashboard(rightNav) },
                    )
                }
            }
        }
    }
}

private fun navTo(nav: androidx.navigation.NavController, route: String) {
    nav.navigate(route) {
        popUpTo(RightPaneRoutes.Dashboard) { inclusive = false }
        launchSingleTop = true
    }
}

private fun popToDashboard(nav: androidx.navigation.NavController) {
    if (!nav.popBackStack()) {
        nav.navigate(RightPaneRoutes.Dashboard) { launchSingleTop = true }
    }
}
