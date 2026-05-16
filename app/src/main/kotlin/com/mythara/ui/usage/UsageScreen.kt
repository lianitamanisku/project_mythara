package com.mythara.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.minimax.MiniMaxUsageClient
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * MiniMax API usage / quota screen.
 *
 * Surfaces what the platform's `coding_plan/remains` endpoint
 * reports — caps + usage per model, with both 4h-interval and
 * weekly buckets. Important caveat surfaced explicitly in the
 * header: this is the **Coding Plan** quota endpoint specifically;
 * it doesn't necessarily match the token-plan dashboard at
 * platform.minimax.io/user-center/payment/token-plan, which
 * aggregates a different (token-based pay-as-you-go) usage
 * surface even though the same Bearer key authenticates both.
 *
 * Diagnostic tools to help the user sanity-check vs Postman / the
 * web dashboard:
 *   - "fetched X ago" timestamp under the refresh button
 *   - "raw json" button that opens the literal API response
 *     body in a scrollable dialog so it's directly comparable
 *     with what Postman returns
 *   - cards lead with raw used/total counts; the % is secondary
 */
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val client: MiniMaxUsageClient,
) : ViewModel() {

    sealed interface Ui {
        data object Loading : Ui
        data class Loaded(
            val rows: List<MiniMaxUsageClient.ModelRemaining>,
            val rawBody: String,
            val fetchedAtMs: Long,
            val authPath: MiniMaxUsageClient.AuthPath,
        ) : Ui
        data class Error(val message: String) : Ui
        data object NeedsApiKey : Ui
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Loading)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun refresh() {
        _ui.update { Ui.Loading }
        viewModelScope.launch {
            val res = client.fetch()
            _ui.update {
                res.fold(
                    onSuccess = { fetched ->
                        Ui.Loaded(
                            rows = fetched.rows.sortedBy { r -> r.modelName },
                            rawBody = fetched.rawBody,
                            fetchedAtMs = fetched.fetchedAtMs,
                            authPath = fetched.authPath,
                        )
                    },
                    onFailure = { e ->
                        if (e is MiniMaxUsageClient.MissingApiKey) Ui.NeedsApiKey
                        else Ui.Error(e.message ?: "request failed")
                    },
                )
            }
        }
    }
}

@Composable
fun UsageScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit = {},
    vm: UsageViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    var rawDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui is UsageViewModel.Ui.Loaded) {
                    TextButton(onClick = { rawDialogOpen = true }) {
                        Text("raw json", color = MytharaColors.Charple)
                    }
                }
                TextButton(onClick = { vm.refresh() }) {
                    Text("refresh", color = MytharaColors.Bok)
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "USAGE",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = MytharaColors.Fg, letterSpacing = 3.sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${Glyph.AccentBar} MiniMax Coding Plan quota — interval (4h " +
                    "refill) + weekly buckets per model. Different surface from the " +
                    "token-plan dashboard, even though both use the same API key.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
            )
            (ui as? UsageViewModel.Ui.Loaded)?.let { state ->
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val authLabel = when (state.authPath) {
                        MiniMaxUsageClient.AuthPath.WebSession -> "web session"
                        MiniMaxUsageClient.AuthPath.Bearer -> "Bearer key"
                    }
                    val authColor = when (state.authPath) {
                        MiniMaxUsageClient.AuthPath.WebSession -> MytharaColors.Bok
                        MiniMaxUsageClient.AuthPath.Bearer -> MytharaColors.Mustard
                    }
                    Text(
                        text = "auth: $authLabel",
                        color = authColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "·",
                        color = MytharaColors.FgMute,
                    )
                    Text(
                        text = "fetched ${formatAge(state.fetchedAtMs)} ago",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (state.authPath == MiniMaxUsageClient.AuthPath.Bearer) {
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = onSignIn,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = "${Glyph.DiamondOutline} sign in to web account → token-plan view",
                            color = MytharaColors.Charple,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = ui) {
                is UsageViewModel.Ui.Loading -> {
                    Text(
                        text = "loading…",
                        color = MytharaColors.FgMute,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                    )
                }
                is UsageViewModel.Ui.NeedsApiKey -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${Glyph.DiamondOutline} no API key",
                            color = MytharaColors.Charple,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Set your MiniMax API key in Settings — usage figures " +
                                "share that same credential.",
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is UsageViewModel.Ui.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${Glyph.Cross} request failed",
                            color = MytharaColors.Charple,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.message,
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is UsageViewModel.Ui.Loaded -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.rows, key = { it.modelName }) { row ->
                            UsageCard(row = row)
                        }
                    }
                }
            }
        }
    }

    if (rawDialogOpen) {
        val state = ui as? UsageViewModel.Ui.Loaded
        AlertDialog(
            onDismissRequest = { rawDialogOpen = false },
            title = { Text("raw API response", color = MytharaColors.Fg) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "GET ${MiniMaxUsageClient.USAGE_ENDPOINT}",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state?.rawBody ?: "(no response)",
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { rawDialogOpen = false }) {
                    Text("close", color = MytharaColors.FgMute)
                }
            },
        )
    }
}

@Composable
private fun UsageCard(row: MiniMaxUsageClient.ModelRemaining) {
    val intervalPct = pct(row.currentIntervalUsage, row.currentIntervalTotal)
    val weeklyPct = pct(row.currentWeeklyUsage, row.currentWeeklyTotal)
    val accent = colorForPct(maxOf(intervalPct, weeklyPct))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.5.dp, accent, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        // Title row — model name + the two raw fractions side-by-
        // side. % is shown but small so the actual numbers (which
        // the user can compare directly with Postman) read first.
        Text(
            text = "${Glyph.DiamondFilled} ${row.modelName}",
            color = accent,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(8.dp))
        UsageBar(
            label = "interval",
            sub = "refills in ${formatDuration(row.remainsTime)}",
            used = row.currentIntervalUsage,
            total = row.currentIntervalTotal,
        )
        Spacer(Modifier.height(8.dp))
        UsageBar(
            label = "weekly",
            sub = "refills in ${formatDuration(row.weeklyRemainsTime)}",
            used = row.currentWeeklyUsage,
            total = row.currentWeeklyTotal,
        )
    }
}

@Composable
private fun UsageBar(label: String, sub: String, used: Long, total: Long) {
    val frac = pct(used, total)
    val accent = colorForPct(frac)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = label,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = sub,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                // Lead with the raw counts since the user wants to
                // diagnose against Postman. % is supplementary.
                Text(
                    text = "$used / $total",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "${(frac * 100).toInt()}%",
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MytharaColors.SurfaceHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(accent),
            )
        }
    }
}

// ─── helpers ─────────────────────────────────────────────────────────

private fun pct(used: Long, total: Long): Float {
    if (total <= 0) return 0f
    return (used.toFloat() / total.toFloat()).coerceAtLeast(0f)
}

/** <60% green, 60-90% mustard, ≥90% charple-red. Highest of
 *  interval+weekly drives the card border so the user catches the
 *  more-pressing constraint at a glance. */
private fun colorForPct(p: Float): Color = when {
    p >= 0.90f -> MytharaColors.Charple
    p >= 0.60f -> MytharaColors.Mustard
    else -> MytharaColors.Bok
}

/** Format the `remains_time` value (seconds) as "Xh Ym" / "Xd Yh". */
private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "now"
    val s = seconds
    val m = s / 60
    val h = m / 60
    val d = h / 24
    return when {
        d > 0 -> "${d}d ${h % 24}h"
        h > 0 -> "${h}h ${m % 60}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

/** "fetched X ago" — tells the user how stale the displayed cap
 *  numbers are. The remote endpoint is request-driven (not push),
 *  so a 5-minute-old fetch can show a different cap than what
 *  Postman returns right now. */
private fun formatAge(tsMs: Long): String {
    val ageMs = System.currentTimeMillis() - tsMs
    if (ageMs < 0) return "just now"
    val s = ageMs / 1000
    val m = s / 60
    val h = m / 60
    return when {
        h > 0 -> "${h}h ${m % 60}m"
        m > 0 -> "${m}m"
        s > 5 -> "${s}s"
        else -> "just now"
    }
}
