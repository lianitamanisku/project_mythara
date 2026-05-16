package com.mythara.ui.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mythara.audit.AuditEntry
import com.mythara.ui.settings.AuditLogViewModel
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Full-screen audit-log viewer with search, status filtering, stats,
 * and per-row expansion for full args + result inspection.
 *
 * Companion to the bounded [com.mythara.ui.settings.AuditLogPanel] —
 * Settings keeps a quick-glance preview; this screen is the deep dive
 * for when something looks off.
 *
 * Reuses AuditLogViewModel so the live `entries` flow is shared and
 * doesn't double-subscribe Room.
 */
@Composable
fun AuditScreen(
    onBack: () -> Unit,
    vm: AuditLogViewModel = hiltViewModel(),
) {
    val entries by vm.entries.collectAsState()
    val localDeviceId by vm.localDeviceId.collectAsState()
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(StatusFilter.All) }
    var toolFilter by remember { mutableStateOf<String?>(null) }
    var windowFilter by remember { mutableStateOf(WindowFilter.AllTime) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // Filter pipeline — applied in-memory over the StateFlow. The
    // observed list is capped at 200 by the Room query, which is
    // plenty for interactive use.
    val now = remember { System.currentTimeMillis() }
    val filtered = remember(entries, query, statusFilter, toolFilter, windowFilter) {
        val cutoff = windowFilter.cutoffMs(now)
        entries.asSequence()
            .filter { it.tsMillis >= cutoff }
            .filter { row ->
                when (statusFilter) {
                    StatusFilter.All -> true
                    StatusFilter.Success -> row.resultOk && row.kind == "tool_call"
                    StatusFilter.Failure -> !row.resultOk && row.kind == "tool_call"
                    StatusFilter.Canceled -> row.kind == "user_canceled"
                    StatusFilter.Redirect -> row.kind == "tool_redirect"
                    StatusFilter.System -> row.kind == "system"
                }
            }
            .filter { row -> toolFilter == null || row.toolName == toolFilter }
            .filter { row ->
                val q = query.trim().lowercase()
                if (q.isEmpty()) return@filter true
                row.toolName?.lowercase()?.contains(q) == true ||
                    row.argsPreview?.lowercase()?.contains(q) == true ||
                    row.resultPreview?.lowercase()?.contains(q) == true ||
                    row.note?.lowercase()?.contains(q) == true ||
                    row.contactName?.lowercase()?.contains(q) == true
            }
            .toList()
    }

    val stats = remember(filtered) { computeStats(filtered) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            Text(
                text = "${Glyph.DiamondFilled} audit log",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Text(
                    "${Glyph.Cross} clear",
                    color = if (entries.isNotEmpty()) MytharaColors.Sriracha else MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Search bar
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("search tool name, args, result, contact…", color = MytharaColors.FgDim) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MytharaColors.Fg,
                unfocusedTextColor = MytharaColors.Fg,
                focusedBorderColor = MytharaColors.Charple,
                unfocusedBorderColor = MytharaColors.SurfaceHigh,
                cursorColor = MytharaColors.Charple,
            ),
        )
        Spacer(Modifier.height(10.dp))

        // Status filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusFilter.values().forEach { sf ->
                FilterChip(
                    label = sf.label,
                    selected = statusFilter == sf,
                    onClick = { statusFilter = sf },
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // Time window chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WindowFilter.values().forEach { wf ->
                FilterChip(
                    label = wf.label,
                    selected = windowFilter == wf,
                    onClick = { windowFilter = wf },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Tool-name chips (top-K by frequency from current filtered set,
        // plus an "all" reset chip).
        if (stats.toolHistogram.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    label = "all tools",
                    selected = toolFilter == null,
                    onClick = { toolFilter = null },
                )
                stats.toolHistogram.take(10).forEach { (name, count) ->
                    FilterChip(
                        label = "$name · $count",
                        selected = toolFilter == name,
                        onClick = { toolFilter = if (toolFilter == name) null else name },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Stats strip
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MytharaColors.Surface)
                .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatPair("calls", "${stats.total}")
                StatPair("success", "${stats.successRatePct}%")
                StatPair("failed", "${stats.failed}")
                StatPair("avg latency", "${stats.avgLatencyMs}ms")
            }
            if (stats.slowestTool != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${Glyph.AccentBar} slowest: ${stats.slowestTool} (${stats.slowestLatencyMs}ms)",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // Row list
        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${Glyph.CircleOutline} no matches",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entries.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Mythara hasn't called any tools yet — chat for a turn and refresh.",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filtered, key = { it.id }) { e ->
                    AuditDetailRow(
                        e = e,
                        localDeviceId = localDeviceId,
                        expanded = expandedId == e.id,
                        onToggle = { expandedId = if (expandedId == e.id) null else e.id },
                    )
                }
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
                    onClick = { vm.clear(); confirmClear = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Sriracha,
                        contentColor = MytharaColors.Fg,
                    ),
                ) { Text("${Glyph.Cross} clear") }
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
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MytharaColors.Charple else MytharaColors.Surface
    val fg = if (selected) MytharaColors.Bg else MytharaColors.Fg
    val border = if (selected) MytharaColors.Charple else MytharaColors.SurfaceHigh
    Text(
        text = label,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun StatPair(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = MytharaColors.Fg, style = MaterialTheme.typography.titleMedium)
        Text(label, color = MytharaColors.FgDim, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AuditDetailRow(
    e: AuditEntry,
    localDeviceId: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
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
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(
                    "$statusGlyph ${e.toolName ?: e.kind}",
                    color = statusColor,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                e.contactName?.let { name ->
                    Text("  · to ", color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
                    Text(name, color = MytharaColors.Charple, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val deviceTag = e.deviceId?.takeIf { it.isNotBlank() && it != localDeviceId }
                ?.let { "dev:${it.takeLast(6)}" }
            Text(
                text = (deviceTag?.let { "$it  ·  " } ?: "") +
                    formatTs(e.tsMillis) +
                    if (e.latencyMs > 0) "  ·  ${e.latencyMs}ms" else "",
                color = if (deviceTag != null) MytharaColors.Bok else MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        e.argsPreview?.let { args ->
            Spacer(Modifier.height(2.dp))
            Text(
                "${Glyph.Arrow} $args",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) 99 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        e.resultPreview?.let { res ->
            Spacer(Modifier.height(2.dp))
            Text(
                "${Glyph.DescendingArrow} $res",
                color = if (e.resultOk) MytharaColors.FgDim else MytharaColors.Sriracha,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) 99 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        e.note?.let { n ->
            Spacer(Modifier.height(2.dp))
            Text(
                "${Glyph.ThinDivider} $n",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) 99 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private enum class StatusFilter(val label: String) {
    All("all"),
    Success("success"),
    Failure("failed"),
    Canceled("canceled"),
    Redirect("redirected"),
    System("system"),
}

private enum class WindowFilter(val label: String, private val windowMs: Long?) {
    AllTime("all time", null),
    LastHour("last hr", TimeUnit.HOURS.toMillis(1)),
    Last24h("24 hr", TimeUnit.HOURS.toMillis(24)),
    Last7d("7 days", TimeUnit.DAYS.toMillis(7)),
    Last30d("30 days", TimeUnit.DAYS.toMillis(30));

    fun cutoffMs(now: Long): Long =
        if (windowMs == null) 0L else now - windowMs
}

private data class AuditStats(
    val total: Int,
    val success: Int,
    val failed: Int,
    val successRatePct: Int,
    val avgLatencyMs: Long,
    val toolHistogram: List<Pair<String, Int>>,
    val slowestTool: String?,
    val slowestLatencyMs: Long,
)

private fun computeStats(rows: List<AuditEntry>): AuditStats {
    if (rows.isEmpty()) {
        return AuditStats(0, 0, 0, 0, 0, emptyList(), null, 0)
    }
    val toolCalls = rows.filter { it.kind == "tool_call" }
    val success = toolCalls.count { it.resultOk }
    val failed = toolCalls.size - success
    val successPct = if (toolCalls.isEmpty()) 0 else (success * 100) / toolCalls.size
    val avgLatency = if (toolCalls.isEmpty()) 0L else
        toolCalls.sumOf { it.latencyMs } / toolCalls.size
    val histogram = rows
        .mapNotNull { it.toolName }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }
    val slowest = toolCalls.maxByOrNull { it.latencyMs }
    return AuditStats(
        total = rows.size,
        success = success,
        failed = failed,
        successRatePct = successPct,
        avgLatencyMs = avgLatency,
        toolHistogram = histogram,
        slowestTool = slowest?.toolName,
        slowestLatencyMs = slowest?.latencyMs ?: 0L,
    )
}

private val TIME_FORMAT = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
private fun formatTs(ts: Long): String = TIME_FORMAT.format(Date(ts))
