package com.mythara.ui.secret

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

        Panel("microphone access") {
            if (state.micGranted) {
                Text(
                    text = "${Glyph.Check} granted",
                    color = MytharaColors.Julep, style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = "${Glyph.Cross} not granted — Observe can't start without it.",
                    color = MytharaColors.Sriracha, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple, contentColor = MytharaColors.Fg,
                    ),
                ) { Text("${Glyph.Arrow} grant microphone") }
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
                    enabled = state.micGranted && state.observeState !is ObserveState.Stopping,
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

        Panel("today (m8.1a)") {
            Bullet("foreground service skeleton with the right FGS types")
            Bullet("persistent notification + tap-to-open")
            Bullet("heartbeat journal entry every 30s while running")
            Bullet("RawDataPurger ready — sweeps observe/ scratch on each tick")
            Bullet("'forget everything' wipes scratch + journal in one shot")
        }

        Spacer(Modifier.height(14.dp))

        Panel("coming in m8.1b") {
            Bullet("AudioRecord pipeline + VAD silence gating")
            Bullet("offline ASR via Vosk small-model")
            Bullet("raw PCM auto-purged 60s after transcribe")
            Bullet("transcripts purged 24h after extraction")
            Bullet("learning extraction via on-device MediaPipe Gemma (m8.2)")
            Bullet("encrypted vault browser (m8.2)")
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} audio + transcripts never leave the device.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp),
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
