package com.mythara.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.agent.tools.TermuxExecTool
import com.mythara.services.TermuxAvailability
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Settings panel for the Termux bridge — the second shell path
 * Mythara can take after `run_shell`. Surfaces:
 *   - Current install state (not installed / needs verification /
 *     ready missing API companion / ready)
 *   - One-time setup steps when not ready
 *   - "Verify" button that fires `/system/bin/echo mythara-ready` via
 *     `termux_exec` and confirms the round-trip works.
 *   - Last-verified timestamp once green.
 *
 * Why both packages: Termux itself ships the userland + shell. The
 * separate `com.termux.api` APK installs the `termux-*` binaries
 * (clipboard / camera / TTS / sensors / location / etc.) that the
 * `termux_api` agent tool wraps. The exec path works without the API
 * companion; only `termux_api` returns command-not-found in that
 * case.
 */
@HiltViewModel
class TermuxSetupPanelViewModel @Inject constructor(
    val availability: TermuxAvailability,
    private val exec: TermuxExecTool,
) : ViewModel() {

    /** Result of the latest verify ping. Cleared on next refresh. */
    sealed interface VerifyState {
        object Idle : VerifyState
        object Running : VerifyState
        data class Ok(val ms: Long) : VerifyState
        data class Failed(val reason: String) : VerifyState
    }

    private val _verifyState = MutableStateFlow<VerifyState>(VerifyState.Idle)
    val verifyState: StateFlow<VerifyState> = _verifyState.asStateFlow()

    /** Fire a benign echo through Termux:RUN_COMMAND. On success, mark
     *  verified + flip the panel to the green Ready state. */
    fun verify() {
        viewModelScope.launch {
            _verifyState.value = VerifyState.Running
            val start = System.currentTimeMillis()
            val args = buildJsonObject {
                put("command", "/system/bin/echo")
                put("args", JsonArray(listOf(JsonPrimitive(VERIFY_TOKEN))))
                put("background", true)
                put("timeout_ms", 8_000L)
            }
            val result = runCatching { exec.execute(args) }.getOrNull()
            val ms = System.currentTimeMillis() - start
            val out = result?.output.orEmpty()
            _verifyState.value = when {
                result == null -> VerifyState.Failed("exec threw")
                !result.ok -> VerifyState.Failed(out.take(160))
                out.contains(VERIFY_TOKEN) -> {
                    availability.markVerified()
                    VerifyState.Ok(ms)
                }
                out.contains("\"status\":\"not_installed\"") ->
                    VerifyState.Failed("Termux not installed — install from F-Droid.")
                out.contains("\"status\":\"play_store_variant\"") ->
                    VerifyState.Failed("Play Store Termux is incompatible — install the F-Droid build instead.")
                out.contains("ERR_SERVICE_NOT_FOUND") || out.contains("\"errCode\":-1001") ->
                    VerifyState.Failed("RunCommandService not found — install the F-Droid Termux build (not the Play Store version).")
                out.contains("\"status\":\"timeout\"") ->
                    VerifyState.Failed("Timed out. Did you set allow-external-apps=true in ~/.termux/termux.properties?")
                else -> VerifyState.Failed("Unexpected response: ${out.take(160)}")
            }
        }
    }

    fun clearVerification() {
        availability.clearVerified()
        _verifyState.value = VerifyState.Idle
    }

    companion object {
        /** Token the verify command echoes — has to be improbable
         *  enough that random shell output never contains it. */
        const val VERIFY_TOKEN = "mythara-termux-ready-7f3a"
    }
}

@Composable
fun TermuxSetupPanel(vm: TermuxSetupPanelViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(vm.availability.state()) }
    val verify by vm.verifyState.collectAsState()

    LaunchedEffect(lifecycleOwner, verify) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state = vm.availability.state()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        // Also re-read after a verify attempt resolves so the badge
        // flips to Ready / ReadyMissingApi without waiting for a
        // window resume event.
        state = vm.availability.state()
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
            text = "${Glyph.DiamondOutline} termux — full GNU userland + Android platform tools",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(6.dp))

        val statusColor = when (state) {
            TermuxAvailability.State.Ready -> MytharaColors.Bok
            TermuxAvailability.State.ReadyMissingApi -> MytharaColors.Mustard
            TermuxAvailability.State.NeedsVerification -> MytharaColors.Charple
            TermuxAvailability.State.NotInstalled -> MytharaColors.Sriracha
        }
        Text(
            text = "${Glyph.Dot} state: ${state.name}",
            color = statusColor,
            style = MaterialTheme.typography.bodyMedium,
        )

        vm.availability.lastVerifiedAt()?.let { ts ->
            Text(
                text = "${Glyph.AccentBar} last verified ${formatRelative(ts)}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Play Store variant takes priority over every other state —
        // the badge can say "NeedsVerification" but the underlying
        // issue is the wrong APK, and clicking Verify will never
        // succeed until that's fixed. Show the specific call-to-action
        // first so the user doesn't waste time troubleshooting
        // termux.properties.
        if (state != TermuxAvailability.State.NotInstalled && vm.availability.isPlayStoreVariant()) {
            PlayStoreVariantBlock(
                onOpenFDroid = { openFDroid(ctx, TermuxAvailability.TERMUX_PKG) },
            )
            return@Column
        }

        when (state) {
            TermuxAvailability.State.NotInstalled -> NotInstalledBlock(
                onOpenFDroid = { openFDroid(ctx, TermuxAvailability.TERMUX_PKG) },
            )
            TermuxAvailability.State.NeedsVerification -> NeedsVerificationBlock(
                onVerify = { scope.launch { vm.verify() } },
                running = verify is TermuxSetupPanelViewModel.VerifyState.Running,
            )
            TermuxAvailability.State.ReadyMissingApi -> ReadyMissingApiBlock(
                onOpenFDroid = { openFDroid(ctx, TermuxAvailability.TERMUX_API_PKG) },
                onReverify = { vm.clearVerification() },
            )
            TermuxAvailability.State.Ready -> ReadyBlock(
                onReverify = { vm.clearVerification() },
            )
        }

        when (val v = verify) {
            is TermuxSetupPanelViewModel.VerifyState.Ok -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${Glyph.Check} verified in ${v.ms} ms — Termux bridge is live",
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            is TermuxSetupPanelViewModel.VerifyState.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${Glyph.Cross} ${v.reason}",
                    color = MytharaColors.Sriracha,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            else -> { /* idle / running — nothing extra */ }
        }
    }
}

@Composable
private fun PlayStoreVariantBlock(onOpenFDroid: () -> Unit) {
    Text(
        text = "${Glyph.Cross} You have the Google Play Store edition of Termux installed. " +
            "The Play Store build is STRIPPED DOWN — it doesn't ship RunCommandService, so " +
            "the agent's termux_exec / termux_api tools can't reach it no matter how you " +
            "configure termux.properties.\n\n" +
            "Fix:\n" +
            "  1. Uninstall the current Termux (Settings → Apps → Termux → Uninstall).\n" +
            "  2. Install Termux from F-Droid — the maintained release.\n" +
            "  3. Also install Termux:API from F-Droid for the platform tools.\n" +
            "  4. Enable allow-external-apps=true in ~/.termux/termux.properties.\n" +
            "  5. Re-open this panel + tap verify.",
        color = MytharaColors.Sriracha,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onOpenFDroid,
        colors = ButtonDefaults.buttonColors(
            containerColor = MytharaColors.Charple,
            contentColor = MytharaColors.Bg,
        ),
    ) { Text("open F-Droid (com.termux)") }
}

@Composable
private fun NotInstalledBlock(onOpenFDroid: () -> Unit) {
    Text(
        text = "${Glyph.AccentBar} Install Termux from F-Droid (recommended — the Play Store " +
            "version is stale + incompatible). Then also install the Termux:API companion APK " +
            "so the platform tools (clipboard, camera, location, etc.) light up.",
        color = MytharaColors.FgDim,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onOpenFDroid,
        colors = ButtonDefaults.buttonColors(
            containerColor = MytharaColors.Charple,
            contentColor = MytharaColors.Bg,
        ),
    ) { Text("open F-Droid (com.termux)") }
}

@Composable
private fun NeedsVerificationBlock(onVerify: () -> Unit, running: Boolean) {
    Text(
        text = "${Glyph.AccentBar} Termux installed. One-time setup:\n" +
            "  1. Open the Termux app once.\n" +
            "  2. Run:  echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n" +
            "  3. Run:  termux-reload-settings\n" +
            "  4. Tap Verify below — Mythara will fire a benign echo through RUN_COMMAND " +
            "to prove the round-trip works.",
        color = MytharaColors.FgDim,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onVerify,
        enabled = !running,
        colors = ButtonDefaults.buttonColors(
            containerColor = MytharaColors.Charple,
            contentColor = MytharaColors.Bg,
        ),
    ) { Text(if (running) "${Glyph.Ellipsis} verifying…" else "verify") }
}

@Composable
private fun ReadyMissingApiBlock(onOpenFDroid: () -> Unit, onReverify: () -> Unit) {
    Text(
        text = "${Glyph.AccentBar} termux_exec is live — Mythara can run arbitrary shell " +
            "commands inside Termux. Termux:API companion is missing though, so termux_api " +
            "(clipboard / battery / location / camera / sensors / TTS / vibrate / etc.) won't " +
            "work until you install it.",
        color = MytharaColors.FgDim,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onOpenFDroid,
            colors = ButtonDefaults.buttonColors(
                containerColor = MytharaColors.Charple,
                contentColor = MytharaColors.Bg,
            ),
        ) { Text("open F-Droid (com.termux.api)") }
        TextButton(onClick = onReverify) {
            Text("re-verify", color = MytharaColors.FgMute)
        }
    }
}

@Composable
private fun ReadyBlock(onReverify: () -> Unit) {
    Text(
        text = "${Glyph.AccentBar} Termux ready. Mythara's `termux_exec` runs anything in the " +
            "Debian-grade userland (apt, git, python, ssh, …); `termux_api` reaches the " +
            "Android platform features (clipboard / battery / location / camera / TTS / sensors). " +
            "Both honour Mythara's hook middleware (dangerous-shell denials still apply).",
        color = MytharaColors.Bok,
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onReverify) {
            Text("re-verify", color = MytharaColors.FgMute)
        }
    }
}

/** Open the package's F-Droid page in a browser. Falls back to a
 *  no-op when no browser is installed (vanishingly rare on Android). */
private fun openFDroid(ctx: android.content.Context, pkg: String) {
    val uri = Uri.parse("https://f-droid.org/packages/$pkg/")
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun formatRelative(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
    }
}

