package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.audit.AuditEntry
import com.mythara.audit.AuditLogger
import com.mythara.audit.AuditRepository
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class AuditLogViewModel @Inject constructor(
    repo: AuditRepository,
    private val logger: AuditLogger,
    private val deviceIdStore: com.mythara.memory.DeviceIdStore,
) : ViewModel() {
    val entries: StateFlow<List<AuditEntry>> =
        repo.dao.observeRecent(limit = 200)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _localDeviceId = MutableStateFlow<String?>(null)
    val localDeviceId: StateFlow<String?> = _localDeviceId.asStateFlow()

    init {
        viewModelScope.launch {
            _localDeviceId.value = runCatching { deviceIdStore.id() }.getOrNull()
        }
    }

    fun clear() {
        viewModelScope.launch { logger.clear() }
    }
}

/**
 * Settings panel showing the agent's audit log — every tool call, every
 * redirect, every user-cancelled confirmation. Append-only, scrollable.
 *
 * Why surface this prominently rather than burying it in a debug menu:
 *   the user explicitly asked for it. The agent has broad permissions
 *   (SMS, calls, accessibility automation); a visible log of "here's
 *   exactly what Mythara did on your behalf" is a basic trust primitive.
 *   No surprises, no untraceable side effects.
 */
@Composable
fun AuditLogPanel(
    onOpenFull: (() -> Unit)? = null,
    vm: AuditLogViewModel = hiltViewModel(),
) {
    val entries by vm.entries.collectAsState()
    val localDeviceId by vm.localDeviceId.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondOutline} audit log",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
            )
            Text(
                text = "${entries.size} entries",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} every tool call Mythara makes on your behalf — what it did, when, with what arguments, and how long it took. Read-only, append-only, lives on-device.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )

        Spacer(Modifier.height(10.dp))

        if (entries.isEmpty()) {
            Text(
                text = "${Glyph.CircleOutline} no actions logged yet. Mythara hasn't called any tools — start a conversation and check back.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            // LazyColumn inside a vertically-scrolling parent gets sized
            // by its content height when capped — give it a sensible
            // visible range so it doesn't take over the screen.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { it.id }) { e ->
                    AuditRow(e, localDeviceId = localDeviceId)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "view full" link — opens the dedicated AuditScreen
            // with search + filters + stats + expandable rows.
            // Elided when the host doesn't pass a navigator.
            if (onOpenFull != null) {
                TextButton(onClick = onOpenFull) {
                    Text(
                        text = "${Glyph.DiamondOutline} view full audit  ${Glyph.Arrow}",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            TextButton(
                onClick = { confirmClear = true },
                enabled = entries.isNotEmpty(),
            ) {
                Text(
                    text = "${Glyph.Cross} clear log",
                    color = if (entries.isNotEmpty()) MytharaColors.Sriracha else MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("clear audit log?", color = MytharaColors.Fg) },
            text = {
                Text(
                    "wipes every recorded action — this can't be undone. " +
                        "Chat history and learnings are not affected.",
                    color = MytharaColors.FgDim,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.clear()
                        confirmClear = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Sriracha,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text("${Glyph.Cross} clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("cancel", color = MytharaColors.FgMute)
                }
            },
            containerColor = MytharaColors.Surface,
        )
    }
}

@Composable
private fun AuditRow(e: AuditEntry, localDeviceId: String?) {
    val statusGlyph = when {
        e.kind == "tool_redirect" -> Glyph.Refresh
        e.kind == "user_canceled" -> Glyph.Cross
        e.kind == "system" -> Glyph.AccentBar
        e.resultOk -> Glyph.Check
        else -> Glyph.Cross
    }
    val statusColor = when {
        e.kind == "tool_redirect" -> MytharaColors.FgMute
        e.kind == "user_canceled" -> MytharaColors.Sriracha
        e.kind == "system" -> MytharaColors.FgMute
        e.resultOk -> MytharaColors.Julep
        else -> MytharaColors.Sriracha
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MytharaColors.Bg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tool name + resolved contact when present. The contact
            // tag (in Charple/brand colour) makes "send to Mom" stand
            // out so a quick scroll-through is enough to confirm a
            // message went where the user expected.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$statusGlyph ${e.toolName ?: e.kind}",
                    color = statusColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                e.contactName?.let { name ->
                    Text(
                        text = "  · to ",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = name,
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            // Device tag — only shown when this entry was authored on
            // ANOTHER device (cross-device sync). For local entries,
            // the device id is redundant noise. "dev:abc123" with the
            // short hash suffix is enough to scan-distinguish.
            val deviceTag = e.deviceId
                ?.takeIf { it.isNotBlank() && it != localDeviceId }
                ?.let { "dev:${it.takeLast(6)}" }
            Text(
                text = (deviceTag?.let { "$it  ·  " } ?: "") +
                    formatTs(e.tsMillis) +
                    if (e.latencyMs > 0) "  ·  ${e.latencyMs}ms" else "",
                color = if (deviceTag != null) MytharaColors.Bok else MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        e.argsPreview?.let { args ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${Glyph.Arrow} $args",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        e.resultPreview?.let { res ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${Glyph.DescendingArrow} $res",
                color = if (e.resultOk) MytharaColors.FgDim else MytharaColors.Sriracha,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        e.note?.let { n ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${Glyph.ThinDivider} $n",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

private fun formatTs(ts: Long): String = TIME_FORMAT.format(Date(ts))
