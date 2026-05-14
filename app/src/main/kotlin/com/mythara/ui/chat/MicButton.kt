package com.mythara.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mythara.mic.SpeechRecognition
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Push-to-talk mic button. Tap-to-toggle (Android's SpeechRecognizer
 * doesn't model "hold to talk" cleanly — `stopListening` is async and
 * `startListening` doesn't resume; safer to make each press a new
 * listening session and let the recognizer auto-stop on silence).
 *
 * - First tap → request RECORD_AUDIO if not granted, then start
 *   on-device SR. Live partials surface via [onPartial].
 * - Second tap → cancel the in-flight session.
 * - End of speech / final result → fires [onFinal] and the button
 *   returns to idle.
 *
 * The composer uses [onPartial] to populate the text field as the user
 * speaks and [onFinal] to optionally auto-submit. Surface a press-to-cancel
 * affordance via the visible "● recording" state.
 */
@Composable
fun MicButton(
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (String) -> Unit,
    muted: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var listening by remember { mutableStateOf(false) }
    var listenJob by remember { mutableStateOf<Job?>(null) }
    var pendingStart by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingStart) {
            pendingStart = false
            startListening(
                onStart = { listening = true },
                ctx = ctx, scope = scope, onPartial = onPartial, onFinal = onFinal, onError = onError,
                onEnd = { listening = false; listenJob = null },
                bindJob = { listenJob = it },
            )
        }
    }

    LaunchedEffect(Unit) { /* placeholder for engine warmup hooks */ }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    muted -> MytharaColors.SurfaceMid
                    listening -> MytharaColors.Bok
                    else -> MytharaColors.Surface
                },
            )
            .border(
                width = 2.dp,
                color = when {
                    muted -> MytharaColors.FgDim
                    listening -> MytharaColors.Bok
                    else -> MytharaColors.Charple
                },
                shape = CircleShape,
            )
            .clickable(enabled = !muted) {
                if (listening) {
                    listenJob?.cancel()
                    listening = false
                    listenJob = null
                    return@clickable
                }
                val granted = ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    pendingStart = true
                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    startListening(
                        onStart = { listening = true },
                        ctx = ctx, scope = scope,
                        onPartial = onPartial, onFinal = onFinal, onError = onError,
                        onEnd = { listening = false; listenJob = null },
                        bindJob = { listenJob = it },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (listening) Glyph.Dot else Glyph.CircleFilled,
            color = when {
                muted -> MytharaColors.FgDim
                listening -> MytharaColors.Bg
                else -> MytharaColors.Charple
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun startListening(
    onStart: () -> Unit,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (String) -> Unit,
    onEnd: () -> Unit,
    bindJob: (Job) -> Unit,
) {
    onStart()
    val job = scope.launch {
        try {
            SpeechRecognition.listen(ctx).collect { ev ->
                when (ev) {
                    is SpeechRecognition.Event.Partial -> onPartial(ev.text)
                    is SpeechRecognition.Event.Final   -> onFinal(ev.text)
                    is SpeechRecognition.Event.Error   -> onError(ev.message)
                    SpeechRecognition.Event.EndOfSpeech -> {}
                    SpeechRecognition.Event.Ready -> {}
                }
            }
        } finally { onEnd() }
    }
    bindJob(job)
}
