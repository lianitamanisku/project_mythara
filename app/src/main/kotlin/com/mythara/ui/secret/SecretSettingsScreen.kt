package com.mythara.ui.secret

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mythara.secret.observe.ObserveState
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.vosk.VoskModelStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * The screen behind the triple-tap + password gate. M8.1a delivers the
 * real Observe service controls (start / pause / stop, status pill,
 * RECORD_AUDIO permission gate) and the "forget everything" purge.
 *
 * The audio + Vosk ASR + Gemma extractor land in M8.1b. The service
 * today is a heartbeat that journals "observe" entries every 30s so
 * lifecycle is end-to-end verifiable on a real device before we add
 * heavy native dependencies.
 */
@Composable
fun SecretSettingsScreen(
    onBack: () -> Unit,
    vm: SecretSettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> vm.refreshPermission() }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> vm.refreshPermission() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "${Glyph.DiamondFilled} observe",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(20.dp))

        Panel("status") {
            val accent = when (val s = state.observeState) {
                is ObserveState.Running -> MytharaColors.Julep
                is ObserveState.Starting -> MytharaColors.Citron
                is ObserveState.Paused -> MytharaColors.Mustard
                is ObserveState.Stopping -> MytharaColors.FgDim
                is ObserveState.Error -> MytharaColors.Sriracha
                is ObserveState.Idle -> MytharaColors.FgMute
                else -> MytharaColors.FgMute
            }
            val glyph = when (state.observeState) {
                is ObserveState.Running -> Glyph.Dot
                is ObserveState.Starting -> Glyph.Ellipsis
                is ObserveState.Paused -> Glyph.CircleOutline
                is ObserveState.Stopping -> Glyph.Ellipsis
                is ObserveState.Error -> Glyph.Cross
                else -> Glyph.CircleOutline
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(glyph, color = accent, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(end = 6.dp))
                Text(
                    text = state.observeState.displayLabel,
                    color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Panel("unlock method") {
            ToggleRow(
                label = "use biometric (face / fingerprint / device pin)",
                on = state.biometricUnlock,
                onToggle = { vm.setBiometricUnlock(it) },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (state.biometricUnlock) {
                    "${Glyph.AccentBar} triple-tap → biometric prompt. password is still the fallback (tap 'use password instead' in the dialog)."
                } else {
                    "${Glyph.AccentBar} triple-tap → password form. enable the toggle above to use device biometric instead."
                },
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("permissions") {
            // Microphone
            if (state.micGranted) {
                Text(
                    text = "${Glyph.Check} microphone — granted",
                    color = MytharaColors.Julep, style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = "${Glyph.Cross} microphone — needed for the (incoming m8.1b) ASR pipeline.",
                    color = MytharaColors.Sriracha, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                    ),
                ) { Text("${Glyph.Arrow} grant microphone") }
            }

            // Notification (Android 13+) — without this the persistent
            // "Mythara is running" notification is silently blocked and
            // the foreground service runs invisibly.
            if (state.notifRequired) {
                Spacer(Modifier.height(10.dp))
                if (state.notifGranted) {
                    Text(
                        text = "${Glyph.Check} notifications — granted",
                        color = MytharaColors.Julep, style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = "${Glyph.Cross} notifications — Android 13+ blocks the persistent service notification without this. Observe will still run but you won't see the indicator.",
                        color = MytharaColors.Sriracha, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Arrow} grant notifications") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Panel("controls") {
            val active = state.observeState.isActive

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { vm.toggleObserve() },
                    enabled = state.readyToStart && state.observeState !is ObserveState.Stopping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) MytharaColors.Sriracha else MytharaColors.Bok,
                        contentColor = MytharaColors.Bg,
                    ),
                ) {
                    Text(
                        text = when {
                            active -> "${Glyph.Cross} stop observe"
                            else   -> "${Glyph.Dot} start observe"
                        },
                    )
                }
                if (state.observeState is ObserveState.Running || state.observeState is ObserveState.Paused) {
                    Button(
                        onClick = { vm.pauseOrResume() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                        ),
                    ) {
                        Text(
                            text = if (state.observeState is ObserveState.Running) {
                                "${Glyph.Ellipsis} pause"
                            } else {
                                "${Glyph.Arrow} resume"
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "${Glyph.AccentBar} a persistent system notification appears whenever the service runs — Android requires it.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("speech model (Vosk en-us, ~40MB)") {
            when (val ms = state.modelState) {
                is VoskModelStore.State.Ready -> {
                    Text(
                        text = "${Glyph.Check} model ready", color = MytharaColors.Julep,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { vm.forgetVoskModel() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Cross} clear cache") }
                }
                is VoskModelStore.State.Missing -> {
                    Text(
                        text = "${Glyph.Cross} not downloaded — required for transcription.",
                        color = MytharaColors.Sriracha, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.ensureModel() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Arrow} download model (40MB)") }
                }
                is VoskModelStore.State.Downloading -> Text(
                    text = "${Glyph.Ellipsis} downloading ${ms.pct}%",
                    color = MytharaColors.Citron, style = MaterialTheme.typography.bodyMedium,
                )
                is VoskModelStore.State.Extracting -> Text(
                    text = "${Glyph.Ellipsis} extracting…", color = MytharaColors.Citron,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is VoskModelStore.State.Failed -> {
                    Text(
                        text = "${Glyph.Cross} failed: ${ms.message}",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.ensureModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Refresh} retry") }
                        Button(
                            onClick = { vm.forgetVoskModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Cross} clear cache") }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} the model runs entirely on-device. no audio leaves the phone.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("embeddings (Universal Sentence Encoder, ~6MB)") {
            when (val es = state.embedModelState) {
                is EmbeddingsModelStore.State.Ready -> Text(
                    text = "${Glyph.Check} embedder ready (100-dim)",
                    color = MytharaColors.Julep,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is EmbeddingsModelStore.State.Missing -> {
                    Text(
                        text = "${Glyph.Cross} not downloaded — transcripts will save without vectors.",
                        color = MytharaColors.Mustard, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.ensureEmbedModel() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Arrow} download embedder (6MB)") }
                }
                is EmbeddingsModelStore.State.Downloading -> Text(
                    text = "${Glyph.Ellipsis} downloading ${es.pct}%",
                    color = MytharaColors.Citron, style = MaterialTheme.typography.bodyMedium,
                )
                is EmbeddingsModelStore.State.Failed -> {
                    Text(
                        text = "${Glyph.Cross} failed: ${es.message}",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.ensureEmbedModel() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Refresh} retry") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} each captured transcript is embedded locally to a 100-dim vector. semantic retrieval (M8.3+) is built on these. inference runs entirely on-device.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("extractor model (Gemma 3 1B INT4, ~530MB)") {
            when (val gs = state.gemmaModelState) {
                is GemmaModelStore.State.Ready -> {
                    Text(
                        text = "${Glyph.Check} Gemma ready — facts extracted in English regardless of source language.",
                        color = MytharaColors.Julep,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { vm.forgetGemmaModel() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Cross} clear cache") }
                }
                is GemmaModelStore.State.Missing -> {
                    Text(
                        text = "${Glyph.Cross} not downloaded — semantic extraction falls back to a regex heuristic until Gemma is available.",
                        color = MytharaColors.Mustard,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.ensureGemmaModel() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Arrow} download Gemma (~530MB)") }
                }
                is GemmaModelStore.State.Downloading -> Text(
                    text = "${Glyph.Ellipsis} downloading ${gs.pct}% (${gs.bytes / 1_000_000}MB / ${gs.total / 1_000_000}MB)",
                    color = MytharaColors.Citron,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is GemmaModelStore.State.Failed -> {
                    Text(
                        text = "${Glyph.Cross} failed: ${gs.message}",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.ensureGemmaModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Refresh} retry") }
                        Button(
                            onClick = { vm.forgetGemmaModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Cross} clear cache") }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} Gemma runs entirely on-device. transcripts never leave the phone. extracted facts are always in English so the synced vault stays consistent.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("learning vault (durable)") {
            Text(
                text = "${state.vaultCount} record(s) stored locally — working-tier transcripts + heuristic-extracted semantic facts. semantic records sync to your GitHub memory repo; working stays on-device.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (state.recentLearnings.isEmpty()) {
                Text(
                    text = "${Glyph.CircleOutline} no learnings yet.",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.recentLearnings.forEach { lp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = when (lp.tier) {
                                "s" -> Glyph.DiamondFilled
                                "w" -> Glyph.Dot
                                else -> Glyph.CircleOutline
                            },
                            color = when (lp.tier) {
                                "s" -> MytharaColors.Charple
                                "w" -> MytharaColors.Bok
                                else -> MytharaColors.FgMute
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lp.content.take(120) + if (lp.content.length > 120) "…" else "",
                                color = MytharaColors.Fg,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = buildString {
                                    append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lp.tsMs)))
                                    append(" · ").append(lp.src)
                                    if (lp.seen > 1) append(" · ×${lp.seen}")
                                    if (lp.hasEmbedding) append(" · emb")
                                },
                                color = MytharaColors.FgDim,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} ${Glyph.DiamondFilled} = semantic (synced) · ${Glyph.Dot} = working (local-only)",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("recent transcripts (this device only)") {
            Text(
                text = "${state.recentTranscripts.size} stored locally · session total: ${state.transcriptCount}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (state.recentTranscripts.isEmpty()) {
                Text(
                    text = "${Glyph.CircleOutline} nothing captured yet.",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.recentTranscripts.forEach { tp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date(tp.tsMs)),
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = tp.text.take(160) + if (tp.text.length > 160) "…" else "",
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.refreshTranscripts() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Refresh} refresh") }
                    Button(
                        onClick = { vm.clearTranscripts() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Sriracha, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Cross} clear") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} transcripts auto-purge after 24h. only condensed learnings (M8.2) sync to GitHub.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("danger zone") {
            Text(
                text = "forget everything wipes Observe scratch + the learning journal. " +
                    "your chat history and settings are unaffected.",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.openForgetConfirm() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Sriracha, contentColor = MytharaColors.Fg,
                ),
            ) { Text("${Glyph.Cross} forget everything") }
        }

        Spacer(Modifier.height(40.dp))

        if (state.confirmingForget) {
            AlertDialog(
                onDismissRequest = { vm.cancelForget() },
                title = { Text("forget everything?", color = MytharaColors.Fg) },
                text = {
                    Text(
                        text = "this wipes the Observe scratch directory + the learning journal in one transaction. there is no undo.",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                containerColor = MytharaColors.Surface,
                confirmButton = {
                    Button(
                        onClick = { vm.confirmForget() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Sriracha, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Cross} forget") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.cancelForget() }) {
                        Text("cancel", color = MytharaColors.FgMute)
                    }
                },
            )
        }
    }
}

@Composable
private fun Panel(title: String, body: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} $title",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        body()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(Glyph.Dot, color = MytharaColors.Bok, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.padding(end = 6.dp))
        Text(text = text, color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!on) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (on) Glyph.CircleFilled else Glyph.CircleOutline,
            color = if (on) MytharaColors.Charple else MytharaColors.FgMute,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.padding(end = 8.dp))
        Text(
            text = label,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
