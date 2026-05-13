package com.mythara.ui.secret.speaker

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Modal dialog the user opens to enrol or re-enrol a speaker. Owns
 * a single instance of [SpeakerEnrollmentViewModel]'s state through
 * [vm] and renders the four phases inline:
 *
 *   1. Name input + permission gate (no recording yet)
 *   2. Recording — live partial transcript + sample counter
 *   3. Stopped with samples — "save" affordance
 *   4. Saving / error
 *
 * Mic conflict messaging: if AudioRecord init fails (because Observe
 * / Lumi-listen / continuous-chat is holding it), the VM surfaces a
 * clear error and we route the user to close those modes first.
 */
@Composable
fun SpeakerEnrollmentDialog(vm: SpeakerEnrollmentViewModel) {
    val state by vm.state.collectAsState()
    if (!state.open) return

    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startRecording()
    }

    AlertDialog(
        onDismissRequest = { if (!state.saving) vm.closeDialog() },
        containerColor = MytharaColors.Surface,
        title = {
            Text(
                text = "${Glyph.DiamondFilled} enrol a speaker",
                color = MytharaColors.Fg,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = vm::setName,
                    singleLine = true,
                    enabled = !state.recording,
                    placeholder = { Text("speaker name (e.g. ankur)", color = MytharaColors.FgDim) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MytharaColors.SurfaceMid,
                        unfocusedContainerColor = MytharaColors.SurfaceMid,
                        focusedTextColor = MytharaColors.Fg,
                        unfocusedTextColor = MytharaColors.Fg,
                        cursorColor = MytharaColors.Charple,
                        focusedIndicatorColor = MytharaColors.Charple,
                        unfocusedIndicatorColor = MytharaColors.SurfaceHigh,
                    ),
                )

                Spacer(Modifier.height(12.dp))

                // Status pill: idle / recording / stopped-with-samples / error
                val (glyph, color, text) = when {
                    state.error != null -> Triple(
                        Glyph.Cross, MytharaColors.Sriracha, state.error.orEmpty(),
                    )
                    state.saving -> Triple(
                        Glyph.Ellipsis, MytharaColors.Citron, "saving…",
                    )
                    state.recording -> Triple(
                        Glyph.Dot, MytharaColors.Bok,
                        "recording — ${state.samplesCollected} sample(s)" +
                            if (state.partial.isNotBlank()) " · \"${state.partial}…\"" else "",
                    )
                    state.samplesCollected > 0 -> Triple(
                        Glyph.Check, MytharaColors.Julep,
                        "${state.samplesCollected} sample(s) captured — tap save to finish",
                    )
                    else -> Triple(
                        Glyph.CircleOutline, MytharaColors.FgMute,
                        "tap record + say a few sentences in your normal voice",
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.padding(end = 6.dp))
                    Text(text, color = MytharaColors.FgDim, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.recording) {
                        Button(
                            onClick = vm::stopRecording,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Sriracha,
                                contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Cross} stop") }
                    } else {
                        Button(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    ctx, Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) vm.startRecording()
                                else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            enabled = state.name.isNotBlank() && !state.saving,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Fg,
                            ),
                        ) { Text("${Glyph.Dot} record") }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = vm::save,
                enabled = state.samplesCollected > 0 && !state.recording && !state.saving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Bok,
                    contentColor = MytharaColors.Bg,
                ),
            ) { Text("${Glyph.Check} save") }
        },
        dismissButton = {
            TextButton(onClick = vm::closeDialog, enabled = !state.saving) {
                Text("cancel", color = MytharaColors.FgMute)
            }
        },
    )
}
