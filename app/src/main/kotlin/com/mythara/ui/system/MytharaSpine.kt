package com.mythara.ui.system

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythara.ui.anim.EdgeGlow
import com.mythara.ui.anim.EdgeGlowSpec
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.delay

/**
 * Phase E.1 — Vertical breathing spine.
 *
 * Replaces the legacy top-anchored circle+teardrop chrome from
 * [MytharaStatusBar] with a thin 3 dp bar on the right edge that
 * breathes Charple alpha 0.18 → 0.32 at 0.25 Hz. Frees the entire
 * top + bottom edges of every screen and matches the Mythara
 * Minimal aesthetic: ambient, unobtrusive, alive.
 *
 * Interactions:
 *   • **tap spine** → slides out a launcher panel from the right
 *     edge with the same 8 navigation chips the old teardrop had
 *     (Me / People / Memory / Tasks / Usage / Settings / Triage /
 *     close). Tap any chip → fires its `onOpen*` callback +
 *     auto-collapses.
 *   • **tap scrim** → collapses without navigating.
 *   • **tap rose pip at the top of the spine** → quick-shortcut
 *     to Chat (same as the legacy top circle's tap).
 *
 * Sits as a top-z overlay over every screen, NOT as part of a
 * Column. MytharaRoot mounts it inside the same Box that holds
 * the NavHost so the spine reads as "always-present chrome"
 * regardless of which route is active.
 *
 * API surface intentionally matches the public callbacks of the
 * legacy [MytharaStatusBar] so MytharaRoot's wire-up is a
 * single-line composable swap; the deprecated status bar stays
 * in the codebase one release for safe rollback.
 */
@Composable
fun MytharaSpine(
    onRoseTap: () -> Unit = {},
    onOpenAboutMe: () -> Unit = {},
    onOpenPeople: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenTriage: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // ── Breathing spine VISUAL — the 3 dp glow ────────────────
        // Painted across the FULL screen height so the bar looks
        // edge-to-edge against the wallpaper. No gesture handler
        // on the visual itself: input lives on a separate wider
        // invisible hit zone below so we can dodge the system
        // status-bar / nav-bar insets without making the visual
        // look chopped at the top + bottom.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(SPINE_WIDTH_DP.dp),
        ) {
            EdgeGlow(
                spec = EdgeGlowSpec(
                    edge = EdgeGlowSpec.Edge.Right,
                    thicknessDp = SPINE_WIDTH_DP.dp,
                    startColor = MytharaColors.Charple,
                    endColor = MytharaColors.Charple,
                    breathingHz = 0.25f,
                    minAlpha = 0.18f,
                    maxAlpha = 0.32f,
                ),
            )
        }

        // ── Breathing spine HIT ZONE — invisible, wider, inset ────
        // 24 dp wide so a finger can land easily; inset-padded
        // through systemBars so:
        //   • the top portion is BELOW the system status bar /
        //     Dynamic Island — without this, the OS consumes any
        //     tap near the top edge to pull down the notification
        //     shade and the spine never sees those events.
        //   • the bottom portion is ABOVE the gesture-nav home
        //     indicator — without this, swipes near the bottom
        //     get stolen by gesture-nav-home.
        // Mounted BEFORE the rose pip in z-order so the pip's
        // clickable still wins for taps that land on the pip.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .fillMaxHeight()
                .width(SPINE_HIT_WIDTH_DP.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { expanded = !expanded })
                },
        )

        // Tiny rose pip at the top of the spine — quick-tap to Chat.
        // Sits inside the systemBars top inset so it never collides
        // with the camera cutout.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(end = 4.dp, top = 6.dp)
                .size(SPINE_PIP_DP.dp)
                .clip(CircleShape)
                .background(MytharaColors.Charple.copy(alpha = 0.6f))
                .clickable {
                    expanded = false
                    onRoseTap()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = Glyph.DiamondFilled,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            )
        }

        // ── Launcher panel — slides out from the right when tapped ─
        AnimatedVisibility(
            visible = expanded,
            enter = slideInHorizontally(
                animationSpec = tween(PANEL_OPEN_MS),
                initialOffsetX = { full -> full },
            ) + fadeIn(tween(PANEL_OPEN_MS)),
            exit = slideOutHorizontally(
                animationSpec = tween(PANEL_CLOSE_MS),
                targetOffsetX = { full -> full },
            ) + fadeOut(tween(PANEL_CLOSE_MS)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            LauncherPanel(
                onCollapse = { expanded = false },
                onOpenAboutMe = { expanded = false; onOpenAboutMe() },
                onOpenPeople = { expanded = false; onOpenPeople() },
                onOpenMemory = { expanded = false; onOpenMemory() },
                onOpenTasks = { expanded = false; onOpenTasks() },
                onOpenUsage = { expanded = false; onOpenUsage() },
                onOpenSettings = { expanded = false; onOpenSettings() },
                onOpenTriage = { expanded = false; onOpenTriage() },
                onOpenAlerts = { expanded = false; onOpenAlerts() },
            )
        }

        // ── Auto-collapse after inactivity ────────────────────────
        if (expanded) {
            LaunchedEffect(expanded) {
                delay(AUTO_COLLAPSE_MS)
                expanded = false
            }
        }
    }
}

/**
 * The panel that slides out when the user taps the spine. A
 * vertical Card with the 2×4 launcher grid, anchored to the right
 * edge so it feels like it grew out of the spine.
 */
@Composable
private fun LauncherPanel(
    onCollapse: () -> Unit,
    onOpenAboutMe: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTriage: () -> Unit,
    onOpenAlerts: () -> Unit,
) {
    Box(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(end = (SPINE_WIDTH_DP + 4).dp, top = 8.dp, bottom = 8.dp)
            .widthIn(min = PANEL_MIN_WIDTH_DP.dp, max = PANEL_MAX_WIDTH_DP.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${Glyph.DiamondOutline} launcher",
                    color = MytharaColors.Charple,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                )
                TextButton(onClick = onCollapse, contentPadding = PaddingValues(4.dp)) {
                    Text(Glyph.Cross, color = MytharaColors.FgMute)
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            LauncherEntry("me", "🙂", onOpenAboutMe)
            LauncherEntry("people", "●", onOpenPeople)
            LauncherEntry("memory", "┃", onOpenMemory)
            LauncherEntry("tasks", "✓", onOpenTasks)
            LauncherEntry("alerts", "◆", onOpenAlerts)
            LauncherEntry("usage", "◇", onOpenUsage)
            LauncherEntry("settings", "◆", onOpenSettings)
            LauncherEntry("triage", "✓", onOpenTriage)
        }
    }
}

@Composable
private fun LauncherEntry(label: String, glyph: String, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.SurfaceMid)
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = glyph,
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(20.dp),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = label,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val SPINE_WIDTH_DP = 3
/** Invisible tap-target width for the spine. ~24 dp is the
 *  Material minimum-target heuristic — wider than the 3 dp
 *  visual so a finger can actually land on it. Extends INTO
 *  the screen from the right edge; the user just sees the
 *  3 dp glow, but taps anywhere in this band register. */
private const val SPINE_HIT_WIDTH_DP = 24
private const val SPINE_PIP_DP = 24
private const val PANEL_OPEN_MS = 220
private const val PANEL_CLOSE_MS = 160
/** Auto-collapse the launcher after this idle window — matches
 *  the legacy MytharaStatusBar.AUTO_COLLAPSE_MS for muscle-memory. */
private const val AUTO_COLLAPSE_MS = 5_000L
private const val PANEL_MIN_WIDTH_DP = 180
private const val PANEL_MAX_WIDTH_DP = 240
