package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.persona.PersonaBuilder
import com.mythara.persona.PersonaScheduler
import com.mythara.persona.PersonaSettings
import com.mythara.persona.UsageAccessHelper
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PersonaPanelViewModel @Inject constructor(
    private val store: PersonaSettings,
    private val accessHelper: UsageAccessHelper,
    private val scheduler: PersonaScheduler,
    private val builder: PersonaBuilder,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = store.enabledFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    private val _hasAccess = MutableStateFlow(accessHelper.isGranted())
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private val _buildStatus = MutableStateFlow<String?>(null)
    val buildStatus: StateFlow<String?> = _buildStatus.asStateFlow()

    fun refreshAccess() {
        _hasAccess.value = accessHelper.isGranted()
    }

    fun openAccessSettings() = accessHelper.openSettings()

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            store.setEnabled(value)
            if (value) scheduler.start() else scheduler.stop()
        }
    }

    /** Manual "build now" — kicks the worker logic synchronously
     *  so the user can see the persona record land without waiting
     *  the 24h cadence. */
    fun buildNow() {
        viewModelScope.launch {
            _buildStatus.value = "${Glyph.Ellipsis} building…"
            val report = runCatching { builder.buildDaily() }.getOrElse {
                _buildStatus.value = "× ${it.message ?: "failed"}"
                return@launch
            }
            _buildStatus.value = if (report.ok) {
                "${Glyph.Check} wrote ${report.recordsWritten} record(s)"
            } else {
                "× ${report.message ?: "no data"}"
            }
        }
    }
}

/**
 * Settings panel for the persona-builder pipeline. Three rows:
 *  - Access status (granted / not granted), with deep-link to
 *    system "Usage access" page when missing.
 *  - Toggle for whether daily collection runs at all.
 *  - "Build now" button so the user can sanity-check the system
 *    without waiting for the periodic worker.
 *
 * High-trust feature — heavy disclosure copy.
 */
@Composable
fun PersonaPanel(vm: PersonaPanelViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsState()
    val hasAccess by vm.hasAccess.collectAsState()
    val buildStatus by vm.buildStatus.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Recompute access on resume — the user comes back from the
    // system settings page where they may have just flipped us on.
    LaunchedEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshAccess()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} user persona from usage",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        // Status row.
        val (glyph, color, label) = when {
            !hasAccess -> Triple(
                Glyph.Cross, MytharaColors.Sriracha,
                "usage access not granted — Mythara can't see what apps you use",
            )
            !enabled -> Triple(
                Glyph.CircleOutline, MytharaColors.Mustard,
                "access granted but the daily collector is off",
            )
            else -> Triple(
                Glyph.Dot, MytharaColors.Julep,
                "active — building persona from your phone usage daily",
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(end = 6.dp))
            Text(label, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(10.dp))

        // Access grant button (only shown when missing).
        if (!hasAccess) {
            Button(
                onClick = { vm.openAccessSettings() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple,
                    contentColor = MytharaColors.Fg,
                ),
            ) {
                Text("${Glyph.Arrow} open usage-access settings")
            }
            Spacer(Modifier.height(6.dp))
        }

        // Enable toggle row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasAccess) { vm.setEnabled(!enabled) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (enabled) Glyph.CircleFilled else Glyph.CircleOutline,
                color = when {
                    !hasAccess -> MytharaColors.FgDim
                    enabled -> MytharaColors.Charple
                    else -> MytharaColors.FgMute
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "build user persona daily from app usage",
                color = if (hasAccess) MytharaColors.Fg else MytharaColors.FgDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Build-now row.
        if (hasAccess && enabled) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.buildNow() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Surface,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text("${Glyph.Refresh} build now")
                }
                buildStatus?.let { status ->
                    Spacer(Modifier.padding(start = 10.dp))
                    Text(
                        text = status,
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} when on, every 24 hours Mythara reads your phone's usage statistics (which apps were foregrounded, for how long, when) and writes summary traits to Lumi's memory — 'top apps today', 'screen time bucket', 'morning-active vs night-owl', 'compulsive-checker vs deep-focus'. These traits sync to your GitHub backup like every other vault record and are surfaced via semantic recall on relevant chat turns so Lumi knows you. Granular per-app history stays on the system level; we only persist the daily summaries.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
