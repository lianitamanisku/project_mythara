package com.mythara.ui.chat

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mythara.mic.ContinuousSpeechRecognition
import com.mythara.mic.MicBroker
import com.mythara.mic.SpeechRecognition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.JetBrainsMono
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaWordmark

/**
 * How long the continuous-voice mode waits after the user stops
 * talking before sending the accumulated utterance to the agent.
 * Set per user request: lets multi-clause thoughts ("Hey Lumi,
 * what's the weather, actually also tell me ...") concatenate
 * before the agent fires.
 */
private const val SILENCE_TIMEOUT_MS = 5_000L

/**
 * Main chat surface. Pulls state from [ChatViewModel] and renders the
 * Crush-styled timeline + composer + (when the assistant is streaming)
 * a live bubble that grows token-by-token.
 *
 * Push-to-talk + TTS land in M3; for M2 we use a plain text composer so
 * MiniMax integration is testable end-to-end before adding voice on top.
 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit = {},
    vm: ChatViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val insets = WindowInsets.systemBars.asPaddingValues()

    // Continuous-mode driver. Two behavioural notes the original v1
    // didn't get right:
    //   1. Submit-on-pause, not submit-on-Final. Soda's idea of
    //      end-of-utterance fires after ~1-2s of silence; the user
    //      wanted a 5s window so multi-sentence thoughts ("Hey Lumi,
    //      ... actually, can you also ...") concatenate into one
    //      query before the agent kicks in.
    //   2. Mute the mic while Lumi is speaking out loud. Otherwise
    //      the on-device recogniser transcribes the assistant's own
    //      TTS reply and the loop runs away from itself. We key the
    //      LaunchedEffect on `!ui.speaking` so the recogniser is torn
    //      down when TTS starts and recreated when it ends — Soda
    //      bring-up is fast enough (~100ms) that this is invisible
    //      to a conversational cadence.
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) vm.setContinuousMode(false)
    }
    LaunchedEffect(ui.continuousMode, ui.speaking) {
        if (!ui.continuousMode) return@LaunchedEffect
        if (ui.speaking) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        // Coordinate with the mic broker so Observe / Lumi-listen can't
        // steal the mic mid-utterance. If acquire fails, flip the toggle
        // back off — UI will then show the conflict via the same flow.
        if (!vm.micBroker.acquire(MicBroker.Client.CONTINUOUS_CHAT)) {
            vm.setContinuousMode(false)
            return@LaunchedEffect
        }

        // Pending-utterance buffer. Each Soda Final appends to it;
        // a 5-second silence (no Partial since the last word) triggers
        // a submit. Partials reset the timer. This lets the user pause
        // mid-thought without the agent firing prematurely.
        val pending = StringBuilder()
        var commitJob: kotlinx.coroutines.Job? = null
        fun resetCommitTimer() {
            commitJob?.cancel()
            commitJob = launch {
                kotlinx.coroutines.delay(SILENCE_TIMEOUT_MS)
                val text = pending.toString().trim()
                if (text.isNotEmpty()) {
                    pending.clear()
                    if (!ui.thinking && !ui.speaking) {
                        // Continuous-mode utterances are voice input —
                        // flag for short conversational reply.
                        vm.submit(text, fromVoice = true)
                    }
                }
            }
        }

        try {
            ContinuousSpeechRecognition.listenContinuously(ctx).collect { ev ->
                when (ev) {
                    is SpeechRecognition.Event.Partial -> {
                        // User is still speaking — push the silence-timer
                        // out. The Final will land in a moment.
                        commitJob?.cancel()
                    }
                    is SpeechRecognition.Event.Final -> {
                        val text = ev.text.trim()
                        if (text.isNotBlank()) {
                            if (pending.isNotEmpty()) pending.append(' ')
                            pending.append(text)
                            resetCommitTimer()
                        }
                    }
                    is SpeechRecognition.Event.Error -> {
                        if (!ContinuousSpeechRecognition.isTransient(ev.code)) {
                            android.util.Log.w("Mythara/Chat", "continuous SR error: ${ev.message}")
                        }
                    }
                    else -> { /* Ready / EndOfSpeech — no-op */ }
                }
            }
        } finally {
            // Always release the mic when the collector unwinds, whether
            // from toggling off, screen exit, or TTS-paused recompose.
            vm.micBroker.release(MicBroker.Client.CONTINUOUS_CHAT)
        }
    }

    // ----------------------------------------------------------------
    // Pixel Buds / Digital-Assistant-tap path. When MainActivity fires
    // VoiceActionStore.fire(...) we kick off a one-shot
    // SpeechRecognition listen, gather the user's utterance, and
    // submit() it the same way the mic button does. Works whether
    // Mythara is brought to foreground from cold start (ACTION_ASSIST
    // launches the activity) or already in foreground (onNewIntent).
    LaunchedEffect(Unit) {
        vm.voiceActions.triggers.collect { trigger ->
            // Same permission + mic-broker handshake as the continuous
            // mode collector above. We only acquire when we have the
            // mic permission AND no other client (Observe / wake /
            // continuous chat) holds the mic.
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@collect
            }
            if (ui.thinking || ui.speaking) {
                // Lumi is mid-reply; ignore the tap. Dropping > queuing
                // because the user would be confused if a tap from 10
                // seconds ago suddenly opened the mic right after
                // hearing the previous answer.
                android.util.Log.d("Mythara/Chat", "voice trigger ignored: thinking=${ui.thinking} speaking=${ui.speaking}")
                return@collect
            }
            if (!vm.micBroker.acquire(MicBroker.Client.CONTINUOUS_CHAT)) {
                android.util.Log.w("Mythara/Chat", "voice trigger: mic busy")
                return@collect
            }
            android.util.Log.d("Mythara/Chat", "voice trigger from ${trigger.source}")
            try {
                // One-shot listen. Wait for the first Final or Error
                // (firstOrNull suspends collection until the predicate
                // matches and then cancels the upstream flow cleanly,
                // releasing the SpeechRecognizer). This matches the
                // "tap-to-talk" shape of Pixel Buds touch-and-hold:
                // speak once, get answered.
                val terminal: SpeechRecognition.Event? = SpeechRecognition
                    .listen(ctx)
                    .firstOrNull { ev ->
                        ev is SpeechRecognition.Event.Final ||
                            ev is SpeechRecognition.Event.Error
                    }
                when (terminal) {
                    is SpeechRecognition.Event.Final -> {
                        val text = terminal.text.trim()
                        if (text.isNotEmpty()) vm.submit(text, fromVoice = true)
                    }
                    is SpeechRecognition.Event.Error ->
                        android.util.Log.w("Mythara/Chat", "voice trigger SR error: ${terminal.message}")
                    else -> { /* upstream cancelled before terminal — no-op */ }
                }
            } finally {
                vm.micBroker.release(MicBroker.Client.CONTINUOUS_CHAT)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(insets),
    ) {
        ChatHeader(
            onOpenSettings = onOpenSettings,
            thinking = ui.thinking,
            continuousMode = ui.continuousMode,
            onToggleContinuous = { vm.setContinuousMode(!ui.continuousMode) },
        )

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            if (ui.items.isEmpty() && ui.streaming.isNullOrEmpty()) {
                EmptyStateHero(thinking = ui.thinking)
            } else {
                Transcript(items = ui.items, streaming = ui.streaming)
            }

            ui.errorBanner?.let { msg ->
                Banner(text = msg, color = MytharaColors.Sriracha, onDismiss = vm::dismissError,
                    align = Alignment.TopCenter)
            }
            if (ui.needsApiKey) {
                Banner(
                    text = "${Glyph.AccentBar} paste your MiniMax API key in Settings to start chatting.",
                    color = MytharaColors.Mustard, onDismiss = vm::dismissMissingKey,
                    align = Alignment.TopCenter,
                )
            }
        }

        Composer(
            // The Composer distinguishes mic-driven vs typed input
            // — both call this callback but with different
            // `fromVoice` flags so the agent loop can produce a
            // voice-friendly short reply when the user spoke.
            onSubmit = { text, fromVoice -> vm.submit(text, fromVoice) },
            enabled = !ui.thinking,
        )
    }
}

@Composable
private fun ChatHeader(
    onOpenSettings: () -> Unit,
    thinking: Boolean,
    continuousMode: Boolean,
    onToggleContinuous: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${if (thinking) Glyph.Ellipsis else Glyph.DiamondFilled} mythara",
            style = MaterialTheme.typography.labelLarge.copy(
                color = MytharaColors.Charple, fontWeight = FontWeight.Bold,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Voice-chat toggle. ◇ when off, ● in Bok when on — same
            // motif as the wake-word panel's "listening" indicator so
            // the user reads "live mic" consistently across surfaces.
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (continuousMode) MytharaColors.Bok else MytharaColors.Surface)
                    .border(
                        1.dp,
                        if (continuousMode) MytharaColors.Bok else MytharaColors.SurfaceHigh,
                        CircleShape,
                    )
                    .clickable(onClick = onToggleContinuous)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (continuousMode) "${Glyph.Dot} voice on" else "${Glyph.CircleOutline} voice off",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (continuousMode) MytharaColors.Bg else MytharaColors.FgMute,
                    ),
                )
            }
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MytharaColors.Surface)
                    .border(1.dp, MytharaColors.SurfaceHigh, CircleShape)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${Glyph.DiamondOutline} settings",
                    style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.FgMute),
                )
            }
        }
    }
}

@Composable
private fun EmptyStateHero(thinking: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MytharaWordmark(shimmer = thinking || true, fontSize = 44.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "${Glyph.AccentBar} field intelligence in your pocket.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MytharaColors.FgDim, letterSpacing = 1.sp,
            ),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "type a message ${Glyph.Arrow}",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgMute),
        )
    }
}

@Composable
private fun Transcript(items: List<ChatViewModel.ChatItem>, streaming: String?) {
    val listState = rememberLazyListState()
    LaunchedEffect(items.size, streaming) {
        val target = items.size + if (!streaming.isNullOrEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ChatViewModel.ChatItem.UserText -> TextBubble(role = "you", text = item.text, isUser = true)
                is ChatViewModel.ChatItem.AssistantText -> TextBubble(role = "mythara", text = item.text, isUser = false)
                is ChatViewModel.ChatItem.Thought -> ThoughtBubble(item)
                is ChatViewModel.ChatItem.Tool -> ToolCallBubble(item)
            }
        }
        if (!streaming.isNullOrEmpty()) {
            item("streaming") {
                TextBubble(role = "mythara", text = streaming + Glyph.AccentBar, isUser = false)
            }
        }
    }
}

@Composable
private fun TextBubble(role: String, text: String, isUser: Boolean) {
    val bg = if (isUser) MytharaColors.SurfaceMid else MytharaColors.Surface
    val border = if (isUser) MytharaColors.Charple else MytharaColors.SurfaceHigh
    val align = if (isUser) Alignment.End else Alignment.Start
    val accent = if (isUser) MytharaColors.Charple else MytharaColors.Bok

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            text = "${Glyph.DiamondFilled} $role",
            style = MaterialTheme.typography.labelMedium.copy(color = accent),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = text, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Composer(onSubmit: (String, Boolean) -> Unit, enabled: Boolean) {
    var draft by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Mic button — push-to-talk via SpeechRecognizer. Partials stream into
        // the draft field; final fires submit() with fromVoice=true so the
        // agent loop produces a voice-friendly short reply.
        MicButton(
            onPartial = { draft = it },
            onFinal = {
                draft = it
                if (enabled && draft.isNotBlank()) {
                    onSubmit(draft, /* fromVoice = */ true); draft = ""
                }
            },
            onError = { /* surface later via VM event channel */ },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(MytharaColors.Surface)
                .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = enabled,
                singleLine = false,
                cursorBrush = SolidColor(MytharaColors.Charple),
                textStyle = TextStyle(
                    color = MytharaColors.Fg,
                    fontFamily = JetBrainsMono,
                    fontSize = 14.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                "tap mic or type…",
                                color = MytharaColors.FgDim,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled && draft.isNotBlank()) MytharaColors.Charple else MytharaColors.Surface)
                .border(1.dp, if (enabled && draft.isNotBlank()) MytharaColors.Charple else MytharaColors.SurfaceHigh, CircleShape)
                .clickable(enabled = enabled && draft.isNotBlank()) {
                    // Typed input — fromVoice=false. Long answers with
                    // markdown are fine on the chat surface, so we
                    // skip the brevity system prompt.
                    onSubmit(draft, /* fromVoice = */ false)
                    draft = ""
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (enabled) Glyph.Arrow else Glyph.Ellipsis,
                color = if (enabled && draft.isNotBlank()) MytharaColors.Fg else MytharaColors.FgDim,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun Banner(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    align: Alignment,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}
