package com.mythara.ui.secret

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
// rememberLauncherForActivityResult + ActivityResultContracts re-used by
// the Gemma panel for SAF .task imports.
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mythara.secret.observe.ObserveState
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.vosk.SpeakerModelStore
import com.mythara.ui.secret.speaker.SpeakerEnrollmentDialog
import com.mythara.ui.secret.speaker.SpeakerEnrollmentViewModel
import com.mythara.secret.observe.extract.gemma.GemmaModelStore
import com.mythara.secret.observe.vosk.VoskModelStore
import androidx.compose.ui.text.font.FontWeight
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
    /** Opens the Notes capture surface. Null hides the entry (e.g. on
     *  layouts that don't yet route it). */
    onOpenNotes: (() -> Unit)? = null,
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
        // Phase D — MytharaScaffold provides header (← back / ◉
        // secret); body owns content from here down.

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

        if (onOpenNotes != null) {
            Spacer(Modifier.height(14.dp))
            Panel("notes") {
                Text(
                    text = "Capture any copied or typed text — file it as a general memory Mythara recalls, " +
                        "a note about a specific person (feeds their relationship analysis), or a quick jotting.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenNotes,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                    ),
                ) { Text("${Glyph.DiamondFilled} open notes") }
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

        // Phase G.1 — prominent banner when the Vosk speech model
        // isn't installed yet. Observe.start() returns "Vosk model
        // not ready" silently otherwise; the user just sees a red
        // error pill with no actionable path. This banner gives
        // them a one-tap install + makes the gate obvious.
        if (state.modelState !is VoskModelStore.State.Ready) {
            ObserveModelMissingBanner(
                state = state.modelState,
                onInstall = { vm.ensureModel() },
            )
            Spacer(Modifier.height(14.dp))
        }

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

        // Phase G.2 + G.3 + G.4 — live Observe readout. Only
        // rendered while a session is active. Shows the rolling
        // partial transcript (G.2), the most-recent utterance's
        // pitch / energy / rate / duration (G.3), and the env
        // context (G.4 — in-meeting flag, ambient bucket, nearby
        // devices). Empties out automatically when the session
        // stops.
        if (state.observeState is ObserveState.Running ||
            state.observeState is ObserveState.Paused
        ) {
            Spacer(Modifier.height(14.dp))
            ObserveLivePanel(
                liveTranscript = state.liveTranscript,
                features = state.latestFeatures,
                env = state.latestEnv,
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

        Panel("speaker model (Vosk x-vector, ~13MB)") {
            when (val ss = state.speakerModelState) {
                is SpeakerModelStore.State.Ready -> {
                    Text(
                        text = "${Glyph.Check} speaker model ready",
                        color = MytharaColors.Julep,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { vm.forgetSpeakerModel() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Cross} clear cache") }
                }
                is SpeakerModelStore.State.Missing -> {
                    Text(
                        text = "${Glyph.Cross} not downloaded — speaker tagging stays off until this lands.",
                        color = MytharaColors.Mustard, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.ensureSpeakerModel() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Arrow} download speaker model (13MB)") }
                }
                is SpeakerModelStore.State.Downloading -> Text(
                    text = if (ss.total > 0) "${Glyph.Ellipsis} downloading ${ss.pct}% (${ss.bytes / 1_000_000}MB / ${ss.total / 1_000_000}MB)"
                    else "${Glyph.Ellipsis} downloading ${ss.bytes / 1_000_000}MB",
                    color = MytharaColors.Citron, style = MaterialTheme.typography.bodyMedium,
                )
                is SpeakerModelStore.State.Extracting -> Text(
                    text = "${Glyph.Ellipsis} extracting…",
                    color = MytharaColors.Citron, style = MaterialTheme.typography.bodyMedium,
                )
                is SpeakerModelStore.State.Failed -> {
                    Text(
                        text = "${Glyph.Cross} failed: ${ss.message}",
                        color = MytharaColors.Sriracha,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.ensureSpeakerModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Refresh} retry") }
                        Button(
                            onClick = { vm.forgetSpeakerModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Cross} clear cache") }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} once downloaded, every Observe utterance also gets a 128-dim speaker vector. Enrol speakers below to start tagging transcripts with `speaker:<name>` facets.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Speakers list + enrollment dialog.
        val enrollVm: SpeakerEnrollmentViewModel = hiltViewModel()
        val enrolledSpeakers by enrollVm.enrolled.collectAsState()
        Panel("speakers") {
            if (enrolledSpeakers.isEmpty()) {
                Text(
                    text = "${Glyph.CircleOutline} no speakers enrolled yet.",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                enrolledSpeakers.forEach { sp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${Glyph.DiamondFilled} ${sp.name}",
                                color = MytharaColors.Fg,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = buildString {
                                    append("${sp.enrollmentSampleCount} sample(s)")
                                    if (sp.matchCount > 0) {
                                        append(" · matched ${sp.matchCount}×")
                                        if (sp.lastMatchedAtMs > 0) {
                                            val ago = (System.currentTimeMillis() - sp.lastMatchedAtMs) / 60_000L
                                            when {
                                                ago < 1 -> append(" · just now")
                                                ago < 60 -> append(" · ${ago}m ago")
                                                ago < 1440 -> append(" · ${ago / 60}h ago")
                                                else -> append(" · ${ago / 1440}d ago")
                                            }
                                        }
                                    }
                                },
                                color = MytharaColors.FgDim,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { enrollVm.delete(sp.id) }) {
                            Text(Glyph.Cross, color = MytharaColors.Sriracha)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { enrollVm.openDialog() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                ),
            ) { Text("${Glyph.Arrow} enrol a speaker") }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} every Observe transcript that matches an enrolled voice (cosine ≥ 0.5) gets tagged with `speaker:<name>`. Enrol the same name again to refine the reference — samples are weighted-averaged with what's already stored.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SpeakerEnrollmentDialog(vm = enrollVm)

        Spacer(Modifier.height(14.dp))

        Panel("extractor model (Gemma 4 E2B via LiteRT-LM, ~2.6GB)") {
            // SAF picker — user selects a .litertlm file they've already
            // downloaded into Files / Drive / Downloads. Backup path in
            // case the in-app fetch is interrupted on the 2.6GB stream.
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) vm.importGemmaFromUri(uri)
            }

            when (val gs = state.gemmaModelState) {
                is GemmaModelStore.State.Ready -> {
                    Text(
                        text = "${Glyph.Check} Gemma 4 E2B on disk — extraction stays on heuristic until you probe init successfully.",
                        color = MytharaColors.Julep,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Init probe status pill
                    val (probeGlyph, probeColor, probeText) = when (val p = state.gemmaProbe) {
                        is SecretSettingsViewModel.GemmaProbe.Idle -> Triple(
                            Glyph.CircleOutline, MytharaColors.FgMute, "not tested yet",
                        )
                        is SecretSettingsViewModel.GemmaProbe.Running -> Triple(
                            Glyph.Ellipsis, MytharaColors.Citron, "running init probe…",
                        )
                        is SecretSettingsViewModel.GemmaProbe.Ok -> Triple(
                            Glyph.Check, MytharaColors.Julep, "init ok · ${p.sampleOutput}",
                        )
                        is SecretSettingsViewModel.GemmaProbe.Failed -> Triple(
                            Glyph.Cross, MytharaColors.Sriracha, "init failed: ${p.message}",
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(probeGlyph, color = probeColor, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.padding(end = 6.dp))
                        Text(probeText, color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    // Mood-enabled status
                    Text(
                        text = if (state.gemmaEnabled) "${Glyph.Dot} Gemma extraction ENABLED — mood facets will fire on next Observe transcript"
                        else "${Glyph.CircleOutline} Gemma extraction is DISABLED — Observe uses heuristic only",
                        color = if (state.gemmaEnabled) MytharaColors.Bok else MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.probeGemma() },
                            enabled = state.gemmaProbe !is SecretSettingsViewModel.GemmaProbe.Running,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Arrow} probe Gemma init") }
                        if (state.gemmaEnabled) {
                            Button(
                                onClick = { vm.setGemmaEnabled(false) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                                ),
                            ) { Text("${Glyph.Cross} disable") }
                        }
                        Button(
                            onClick = { vm.forgetGemmaModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Cross} clear cache") }
                    }
                }
                is GemmaModelStore.State.Missing -> {
                    Text(
                        text = "${Glyph.Cross} not loaded. semantic extraction falls back to a regex heuristic until Gemma is available.",
                        color = MytharaColors.Mustard,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.ensureGemmaModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Arrow} download (2.6GB)") }
                        Button(
                            onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.DiamondFilled} import .litertlm") }
                    }
                }
                is GemmaModelStore.State.Downloading -> Text(
                    text = if (gs.total > 0)
                        "${Glyph.Ellipsis} loading ${gs.pct}% (${gs.bytes / 1_000_000}MB / ${gs.total / 1_000_000}MB)"
                    else
                        "${Glyph.Ellipsis} loading ${gs.bytes / 1_000_000}MB",
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
                            onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.DiamondFilled} import .litertlm") }
                        Button(
                            onClick = { vm.forgetGemmaModel() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Cross} clear cache") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${Glyph.AccentBar} Apache 2.0 — anonymous download works, no Hugging Face license to accept. Alt path: grab `gemma-4-E2B-it.litertlm` from huggingface.co/litert-community/gemma-4-E2B-it-litert-lm and import via the button above. Inference runs entirely on-device; transcripts never leave the phone.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(14.dp))

        Panel("episodic promotion (Gemma)") {
            val (epGlyph, epColor, epText) = when (val r = state.episodicReport) {
                is SecretSettingsViewModel.EpisodicReport.Idle -> Triple(
                    Glyph.CircleOutline, MytharaColors.FgMute, "not run yet",
                )
                is SecretSettingsViewModel.EpisodicReport.Running -> Triple(
                    Glyph.Ellipsis, MytharaColors.Citron, "running…",
                )
                is SecretSettingsViewModel.EpisodicReport.Ok -> Triple(
                    Glyph.Check, MytharaColors.Julep,
                    "ok · ${r.workingSeen} working · ${r.clustersFound} cluster(s) · ${r.episodicCreated} episodic created" +
                        (r.skippedReason?.let { " · skipped: $it" } ?: ""),
                )
                is SecretSettingsViewModel.EpisodicReport.Failed -> Triple(
                    Glyph.Cross, MytharaColors.Sriracha, "failed: ${r.message}",
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(epGlyph, color = epColor, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(end = 6.dp))
                Text(epText, color = MytharaColors.Fg, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.runEpisodicPromotionNow() },
                enabled = state.episodicReport !is SecretSettingsViewModel.EpisodicReport.Running &&
                    state.gemmaEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                ),
            ) { Text("${Glyph.Arrow} promote now") }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} clusters recent working-tier transcripts by embedding similarity, then Gemma summarises each cluster into a single episodic-tier record. Runs nightly via WorkManager too; this button bypasses the cadence for testing. Needs Gemma probed + enabled.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
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

        Panel("resonance mode") {
            Text(
                text = "${Glyph.AccentBar} discreet sound control + closed-loop auditory self-regulation. " +
                    "off by default. when on, the watch shows a tiny toggle by the mic; tap it (or send a " +
                    "calm/focus/wind-down combo) to start a session — tones play to YOUR ears, gated by your " +
                    "own heart rate. wellness aid, not a medical treatment. session caps + safety clamps " +
                    "are baked into the audio engine.",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                label = "enable resonance mode",
                on = state.resonanceEnabled,
                onToggle = { vm.setResonanceEnabled(it) },
            )
            if (state.resonanceEnabled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "default protocol",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Calm", "Focus", "WindDown").forEach { name ->
                        val selected = state.resonanceDefaultProtocol == name
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MytharaColors.Charple
                                    else MytharaColors.Surface,
                                )
                                .border(
                                    1.dp,
                                    if (selected) MytharaColors.Charple else MytharaColors.SurfaceHigh,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { vm.setResonanceDefaultProtocol(name) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = name.lowercase(),
                                color = if (selected) MytharaColors.Fg else MytharaColors.FgMute,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "volume cap: ${state.resonanceVolumeCapPercent}%",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            vm.setResonanceVolumeCapPercent(state.resonanceVolumeCapPercent - 5)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("−") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            vm.setResonanceVolumeCapPercent(state.resonanceVolumeCapPercent + 5)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("+") }
                }
                Spacer(Modifier.height(10.dp))
                // Live status — useful for verifying the engine is doing
                // what we think it's doing without staring at logcat.
                val es = state.resonanceEngineState
                val statusLine = "engine: ${es.phase.name.lowercase()} · " +
                    "${es.mode.name.lowercase()} · " +
                    "carrier ${es.carrierHz.toInt()}Hz · " +
                    "beat ${"%.1f".format(es.currentBeatHz)}/${ "%.1f".format(es.targetBeatHz)}Hz · " +
                    "headphones=${es.routeIsHeadphones}"
                Text(
                    text = statusLine,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "loop: ${state.resonanceLoopPhase.name.lowercase()}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                // Live HR readout — the question "is the watch actually
                // streaming HR" is the single most common diagnostic,
                // so it gets its own line. Shows live bpm + recent avg
                // + baseline; "live: --" while the watch warms up or
                // when streaming hasn't started.
                val live = state.resonanceLiveHrBpm?.toString() ?: "--"
                val avg = state.resonanceLiveHrAvgBpm?.toString() ?: "--"
                val base = state.resonanceBaselineBpm?.toString() ?: "--"
                val sessionTag = if (state.resonanceSessionActive) "session: open" else "session: idle"
                Text(
                    text = "$sessionTag · HR live $live bpm · avg $avg · baseline $base",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "vault HR rows (recent): ${state.resonanceHrRowCount}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.startResonanceFromApp() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Check} start") }
                    Button(
                        onClick = { vm.stopResonanceFromApp() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface, contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Cross} stop") }
                }
                Spacer(Modifier.height(14.dp))
                ComboCheatsheet()
            }
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

// ---- Resonance combo reference -------------------------------------
// Mirrors the watch pad colours so the user can map the cheatsheet
// they read on the phone to the buttons they tap on the watch. Order
// matches the watch pad's grid (TL/TR/BL/BR = R/A/G/B).
private val PAD_RED = Color(0xFFEB4268)
private val PAD_AMBER = Color(0xFFF5B033)
private val PAD_GREEN = Color(0xFF68FFD6)
private val PAD_BLUE = Color(0xFF00A4FF)

/**
 * Single row in the combo cheatsheet: two colour swatches → label +
 * one-line description. The first two arguments are the pad button
 * colours of the 2-tap combo, in tap order.
 */
@Composable
private fun ComboRow(c1: Color, c2: Color, name: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(c1)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "+",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(c2)
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = name,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = desc,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ComboCheatsheet() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "combos · tap two buttons in sequence",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "PROTOCOLS",
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.bodySmall,
        )
        ComboRow(PAD_RED, PAD_RED, "Calm", "de-escalate · target 6–10 Hz alpha/theta")
        ComboRow(PAD_AMBER, PAD_AMBER, "Focus", "alert · low-beta band, isochronic ≤14 Hz")
        ComboRow(PAD_GREEN, PAD_GREEN, "Wind-down", "sleep onset · 3–6 Hz theta/delta")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "COMMANDS",
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.bodySmall,
        )
        ComboRow(PAD_RED, PAD_BLUE, "Check-in", "silent note to your favourite person")
        ComboRow(PAD_GREEN, PAD_AMBER, "Mark moment", "vault row + latest HR")
        ComboRow(PAD_BLUE, PAD_BLUE, "Start PTT", "watch push-to-talk")
        ComboRow(PAD_RED, PAD_GREEN, "End session", "hard stop")
    }
}

/**
 * Phase G.1 — prominent banner shown at the top of the Observe
 * panel when the Vosk speech model isn't installed (or is
 * downloading / failed). Replaces the silent "Vosk model not
 * ready" error path with a one-tap install + progress bar.
 *
 * Renders nothing when the model is Ready (banner is suppressed
 * by the caller in that case).
 */
@Composable
private fun ObserveModelMissingBanner(
    state: VoskModelStore.State,
    onInstall: () -> Unit,
) {
    val (label, body, actionText, actionColor) = when (state) {
        is VoskModelStore.State.Missing -> Quad(
            "${Glyph.CircleOutline} speech model required",
            "Observe mode needs the Vosk speech recogniser (~40 MB) before it can transcribe. One-tap install — runs offline once downloaded; no audio leaves the device.",
            "${Glyph.Arrow} install speech model",
            MytharaColors.Charple,
        )
        is VoskModelStore.State.Downloading -> Quad(
            "${Glyph.Ellipsis} downloading ${state.pct}%",
            "Vosk model ${state.bytes / 1_000_000} of ${state.total / 1_000_000} MB. Once finished Observe can start instantly; the file persists across reinstalls of the app.",
            "${Glyph.Ellipsis} please wait",
            MytharaColors.Citron,
        )
        is VoskModelStore.State.Extracting -> Quad(
            "${Glyph.Ellipsis} unpacking model",
            "Final step before Observe can start.",
            "${Glyph.Ellipsis} please wait",
            MytharaColors.Citron,
        )
        is VoskModelStore.State.Failed -> Quad(
            "${Glyph.Cross} download failed",
            state.message,
            "${Glyph.Refresh} try again",
            MytharaColors.Sriracha,
        )
        is VoskModelStore.State.Ready -> return  // suppressed
    }
    val borderColor = when (state) {
        is VoskModelStore.State.Failed -> MytharaColors.Sriracha
        is VoskModelStore.State.Downloading, is VoskModelStore.State.Extracting -> MytharaColors.Citron
        else -> MytharaColors.Charple
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MytharaColors.Surface)
            .border(1.5.dp, borderColor.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            text = label,
            color = borderColor,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onInstall,
            enabled = state is VoskModelStore.State.Missing || state is VoskModelStore.State.Failed,
            colors = ButtonDefaults.buttonColors(
                containerColor = actionColor,
                contentColor = MytharaColors.Bg,
            ),
        ) {
            Text(actionText)
        }
    }
}

/** Tiny tuple for the banner content packing (used because
 *  Triple has only 3 slots and we need 4). */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/**
 * Phase G.2 + G.3 + G.4 — live readout while an Observe session
 * is active. Three stacked sections:
 *
 *   • Live transcript ticker — the rolling partial Vosk emits as
 *     the user speaks. Goes blank between utterances (which is
 *     when an utterance completed + landed in the vault).
 *   • Acoustic readout — pitch / energy / words-per-sec /
 *     duration of the most-recently-completed utterance.
 *   • Environment line — env:meeting / env:loud / proximity:*
 *     facets the EnvironmentContext computed at the same
 *     utterance-final boundary.
 *
 * Nothing rendered when no session is active — caller's `if`
 * gate elides the panel entirely in that case.
 */
@Composable
private fun ObserveLivePanel(
    liveTranscript: String,
    features: com.mythara.secret.observe.acoustic.AcousticAnalyzer.Features?,
    env: com.mythara.secret.observe.env.EnvironmentContext.Snapshot?,
) {
    Panel("live") {
        // Live transcript ticker.
        Text(
            text = "${Glyph.AccentBar} live transcript",
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(4.dp))
        if (liveTranscript.isBlank()) {
            Text(
                text = "${Glyph.CircleOutline} listening… (speak to see the partial transcript here)",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
            )
        } else {
            Text(
                text = "“$liveTranscript”",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Acoustic readout from the most-recently-completed utterance.
        Text(
            text = "${Glyph.AccentBar} last utterance",
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(4.dp))
        if (features == null) {
            Text(
                text = "${Glyph.CircleOutline} no utterance finalised yet this session",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
            )
        } else {
            Text(
                text = buildString {
                    append("◇ pitch ").append("%.0f".format(features.meanF0Hz)).append(" Hz")
                    append("   ◇ energy ").append("%.3f".format(features.meanRms))
                    append("   ◇ rate ").append("%.1f".format(features.wordsPerSec)).append(" wps")
                    append("   ◇ ").append("%.1f".format(features.durationSec)).append(" s")
                },
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Environment context — calendar / ambient / proximity.
        if (env != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${Glyph.AccentBar} environment",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(4.dp))
            val tags = buildList {
                if (env.inMeeting) add("◆ in meeting${env.meetingTitle?.let { " · $it" } ?: ""}")
                when (env.ambient) {
                    com.mythara.secret.observe.env.EnvironmentContext.AmbientLevel.Loud -> add("◆ loud room")
                    com.mythara.secret.observe.env.EnvironmentContext.AmbientLevel.Quiet -> add("◇ quiet room")
                    else -> { /* Normal — don't tag */ }
                }
                for (label in env.nearbyDeviceLabels) {
                    add("◇ nearby · $label")
                }
            }
            if (tags.isEmpty()) {
                Text(
                    text = "${Glyph.CircleOutline} nothing notable in the surroundings",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                )
            } else {
                Text(
                    text = tags.joinToString(" · "),
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
