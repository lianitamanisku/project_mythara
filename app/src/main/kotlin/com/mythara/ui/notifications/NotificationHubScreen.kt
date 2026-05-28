package com.mythara.ui.notifications

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.agent.AgentRunner
import com.mythara.services.NotificationActionStore
import com.mythara.services.NotificationFeedRepository
import com.mythara.services.NotificationListener
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.triage.NotificationTriageScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One app's worth of live notifications, for the grouped hub list. */
data class NotifGroup(
    val packageName: String,
    val appLabel: String,
    val important: Boolean,
    val items: List<NotificationListener.Recent>,
)

@HiltViewModel
class NotificationHubViewModel @Inject constructor(
    private val feedRepo: NotificationFeedRepository,
    private val actionStore: NotificationActionStore,
    private val agentRunner: AgentRunner,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    val listenerEnabled: StateFlow<Boolean> = NotificationListener.isEnabled

    /** Feed grouped by app, ongoing/self filtered, important apps first. */
    val groups: StateFlow<List<NotifGroup>> =
        combine(feedRepo.feed, actionStore.exemptFlow()) { feed, exempt ->
            feed.filterNot { it.ongoing }
                .filter { it.packageName.isNotEmpty() && it.packageName != ctx.packageName }
                .groupBy { it.packageName }
                .map { (pkg, items) ->
                    NotifGroup(
                        packageName = pkg,
                        appLabel = appLabel(pkg),
                        important = pkg in exempt,
                        items = items.sortedByDescending { it.postTimeMs },
                    )
                }
                .sortedWith(
                    compareByDescending<NotifGroup> { it.important }
                        .thenByDescending { it.items.firstOrNull()?.postTimeMs ?: 0L },
                )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Seed from the listener's current buffer so the hub isn't
        // empty until the next notification posts/dismisses.
        NotificationListener.instance?.let { feedRepo.publish(it.snapshot()) }
    }

    fun dismiss(key: String) {
        runCatching { NotificationListener.instance?.cancelNotification(key) }
        // Re-publish immediately so the row leaves the list even before
        // the system fires onNotificationRemoved.
        NotificationListener.instance?.let { feedRepo.publish(it.snapshot()) }
    }

    fun toggleImportant(pkg: String, currentlyImportant: Boolean) {
        viewModelScope.launch {
            if (currentlyImportant) actionStore.unmarkImportant(pkg)
            else actionStore.markImportant(pkg)
        }
    }

    fun askMythara(r: NotificationListener.Recent) {
        val app = appLabel(r.packageName)
        val body = listOfNotNull(r.title, r.text).joinToString(" — ").ifBlank { "(no content)" }
        agentRunner.submit(
            text = "Help me handle this notification from $app: \"$body\". " +
                "Summarise what it wants and suggest or take the next action.",
            fromVoice = false,
        )
    }

    /** v7 — tap a notification card to open its source app (the same
     *  thing the system shade does on tap). */
    fun openSource(r: NotificationListener.Recent) {
        com.mythara.services.openNotificationSource(ctx, r)
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))
}

private enum class HubTab { Live, Triage }

@Composable
fun NotificationHubScreen(
    onAskNavigateChat: () -> Unit,
    vm: NotificationHubViewModel = hiltViewModel(),
) {
    var tab by remember { mutableStateOf(HubTab.Live) }
    val groups by vm.groups.collectAsState()
    val enabled by vm.listenerEnabled.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab switcher.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabChip("live", tab == HubTab.Live) { tab = HubTab.Live }
            TabChip("triaged", tab == HubTab.Triage) { tab = HubTab.Triage }
        }

        when (tab) {
            HubTab.Live -> {
                if (!enabled) {
                    EmptyState(
                        "notification access is off — grant it in Settings → Apps → " +
                            "Special access → Notification access so Mythara can surface your alerts here.",
                    )
                } else if (groups.isEmpty()) {
                    EmptyState("no active notifications — you're all caught up ${Glyph.Check}")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(groups, key = { it.packageName }) { g ->
                            GroupCard(
                                group = g,
                                onDismiss = { vm.dismiss(it) },
                                onToggleImportant = { vm.toggleImportant(g.packageName, g.important) },
                                onAsk = { r -> vm.askMythara(r); onAskNavigateChat() },
                                onOpen = { r -> vm.openSource(r) },
                            )
                        }
                    }
                }
            }
            HubTab.Triage -> {
                // Reuse the existing "what Mythara auto-dismissed" screen.
                NotificationTriageScreen(onBack = {})
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onTap: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Text(
        text = label,
        color = if (selected) MytharaColors.Bg else MytharaColors.FgMute,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(shape)
            .background(if (selected) MytharaColors.Charple else MytharaColors.SurfaceMid)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun GroupCard(
    group: NotifGroup,
    onDismiss: (String) -> Unit,
    onToggleImportant: () -> Unit,
    onAsk: (NotificationListener.Recent) -> Unit,
    onOpen: (NotificationListener.Recent) -> Unit,
) {
    val accent = if (group.important) MytharaColors.Mustard else MytharaColors.Charple
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            // Near-opaque so the body text stays legible even over the
            // bright animated backdrops (Aurora light / HUD / Rose).
            .background(MytharaColors.Surface.copy(alpha = 0.88f))
            .border(1.dp, accent.copy(alpha = 0.7f), shape)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.appLabel,
                color = accent,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
            val pinColor = if (group.important) MytharaColors.Mustard else MytharaColors.FgMute
            NotifActionChip(
                label = if (group.important) "${Glyph.Check} important" else "pin",
                color = pinColor,
                onTap = onToggleImportant,
            )
        }
        Spacer(Modifier.height(6.dp))
        group.items.take(4).forEach { r ->
            // Tap the body title/text to open the source app; the
            // explicit action chips below are bigger, padded
            // tap-targets for the same + dismiss + ask.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(r) }
                    .padding(vertical = 4.dp),
            ) {
                r.title?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                }
                r.text?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotifActionChip(label = "${Glyph.Arrow} open", color = MytharaColors.Malibu) {
                        onOpen(r)
                    }
                    NotifActionChip(label = "${Glyph.Cross} dismiss", color = MytharaColors.Sriracha) {
                        onDismiss(r.key)
                    }
                    NotifActionChip(label = "${Glyph.DiamondFilled} ask", color = MytharaColors.Bok) {
                        onAsk(r)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(msg: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = msg,
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * A real, padded chip-style tap target — the bare `Text.clickable`
 * pattern made the hit area essentially just the glyphs and was easy
 * to miss. Background fill at low alpha so the user can SEE the chip,
 * plus a generous touch padding INSIDE the clickable so a fingertip
 * always lands on it.
 */
@Composable
private fun NotifActionChip(label: String, color: androidx.compose.ui.graphics.Color, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
