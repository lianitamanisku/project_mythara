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
import androidx.compose.ui.graphics.toArgb
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
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
    onOpenPeople: () -> Unit = {},
    /**
     * When null (compact / single-pane), the chat-header drawer pill
     * opens an in-screen ModalBottomSheet. When non-null (two-pane
     * layout) the parent provides this callback to route to the
     * right pane instead — so the drawer renders inline next to the
     * chat, matching how People / Settings / About appear in that
     * mode.
     */
    onOpenAppDrawer: (() -> Unit)? = null,
    /** Same pattern for the lifeline timeline grid. */
    onOpenTimeline: (() -> Unit)? = null,
    /** Same pattern for the cross-device tasks screen. */
    onOpenTasks: (() -> Unit)? = null,
    /** Opens the animated-face interface. Null hides the menu entry. */
    onOpenFace: (() -> Unit)? = null,
    /** Opens the self-profile "About Me" screen. Null hides the menu entry. */
    onOpenAboutMe: (() -> Unit)? = null,
    /** Opens the relationship-graph Insights screen. Null hides the menu entry. */
    onOpenInsights: (() -> Unit)? = null,
    vm: ChatViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val insets = WindowInsets.systemBars.asPaddingValues()
    // ASSIST-intent dictation → composer draft. When the user hits
    // PTT on Pixel Buds / watch / assist gesture, the resulting
    // transcript lands here (not in the agent's submit queue) so
    // they can review + edit + tap send. Cleared by the Composer
    // after consumption.
    var dictation by remember { mutableStateOf<String?>(null) }

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
            // Start a parallel raw-PCM recorder so AcousticAnalyzer can
            // extract pitch / energy / speaking-rate from the same
            // utterance the SpeechRecognizer is transcribing. On
            // devices that refuse concurrent capture, start() returns
            // false and we proceed with text-only mood scoring.
            val pcmRecorder = com.mythara.mic.VoicePcmRecorder(ctx)
            val pcmStarted = pcmRecorder.start()
            try {
                // One-shot listen. Wait for the first Final or Error.
                val terminal: SpeechRecognition.Event? = SpeechRecognition
                    .listen(ctx)
                    .firstOrNull { ev ->
                        ev is SpeechRecognition.Event.Final ||
                            ev is SpeechRecognition.Event.Error
                    }
                val captured = if (pcmStarted) pcmRecorder.stop() else null
                when (terminal) {
                    is SpeechRecognition.Event.Final -> {
                        val text = terminal.text.trim()
                        if (text.isNotEmpty()) {
                            // Drop the transcript into the composer
                            // draft instead of auto-submitting — the
                            // user gets a chance to review + edit
                            // before sending, which matters most for
                            // dictation off Pixel Buds / the watch
                            // where speech-recognition errors are
                            // common and there's no good way to
                            // recover from a wrong auto-submit.
                            dictation = text
                            // captured PCM is discarded on dictation
                            // path — mood-tracking happens on submit
                            // in the Composer's onSubmit, not here.
                        }
                    }
                    is SpeechRecognition.Event.Error ->
                        android.util.Log.w("Mythara/Chat", "voice trigger SR error: ${terminal.message}")
                    else -> { /* upstream cancelled before terminal — no-op */ }
                }
            } finally {
                pcmRecorder.release()
                vm.micBroker.release(MicBroker.Client.CONTINUOUS_CHAT)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg),
    ) {
        var appDrawerOpen by remember { mutableStateOf(false) }
        var timelineOpen by remember { mutableStateOf(false) }
        var tasksOpen by remember { mutableStateOf(false) }
        // Two-pane mode hands us [onOpenAppDrawer] / [onOpenTimeline]
        // / [onOpenTasks] so those surfaces land in the right pane.
        // Single-pane leaves them null and we toggle local bottom sheets.
        val openDrawer: () -> Unit = onOpenAppDrawer ?: { appDrawerOpen = true }
        val openTimeline: () -> Unit = onOpenTimeline ?: { timelineOpen = true }
        val openTasks: () -> Unit = onOpenTasks ?: { tasksOpen = true }

        // Chat content fills the screen; the nav menu floats over the
        // top-right corner (see ChatMenuFab below) instead of the old
        // six-pill header eating a fixed band of vertical space.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {

        if (appDrawerOpen && onOpenAppDrawer == null) {
            com.mythara.ui.launcher.AppDrawerSheet(onDismiss = { appDrawerOpen = false })
        }
        if (timelineOpen && onOpenTimeline == null) {
            com.mythara.ui.lifeline.TimelineGridSheet(onDismiss = { timelineOpen = false })
        }
        if (tasksOpen && onOpenTasks == null) {
            com.mythara.ui.tasks.TasksScreenSheet(onDismiss = { tasksOpen = false })
        }

        // Lifeline filter chip strip. Visible only when there ARE
        // foreign-device photos in the timeline — no point offering
        // a filter that wouldn't do anything on a single-device feed.
        if (ui.hasRemoteLifeline) {
            LifelineFilterChips(
                current = ui.lifelineFilter,
                onSelect = { vm.setLifelineFilter(it) },
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            if (ui.items.isEmpty() && ui.streaming.isNullOrEmpty()) {
                EmptyStateHero(thinking = ui.thinking)
            } else {
                Transcript(
                    items = ui.items,
                    streaming = ui.streaming,
                    // Show the gradient-rolodex thinking indicator at
                    // the bottom of the timeline while Lumi is working
                    // but the first streamed token hasn't landed yet.
                    // Once streaming text shows, the indicator hides
                    // so the user reads the actual reply.
                    thinkingVisible = ui.thinking && ui.streaming.isNullOrEmpty(),
                    musicMode = ui.musicMode,
                    onReplayMusic = { text -> vm.replayMusic(text) },
                    onReinforce = { text, gotIt -> vm.musicReinforceReply(text, gotIt) },
                )
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

        val speechMuted by vm.micBroker.muted.collectAsState()
        Composer(
            // The Composer distinguishes mic-driven vs typed input
            // — both call this callback but with different
            // `fromVoice` flags so the agent loop can produce a
            // voice-friendly short reply when the user spoke.
            onSubmit = { text, fromVoice -> vm.submit(text, fromVoice) },
            enabled = !ui.thinking,
            incomingDictation = dictation,
            onDictationConsumed = { dictation = null },
            speechMuted = speechMuted,
            onToggleSpeechMute = { vm.micBroker.setMuted(!speechMuted) },
            musicMode = ui.musicMode,
            onToggleMusicMode = { vm.setMusicMode(!ui.musicMode) },
        )
        }

        // Floating nav menu — replaces the old top pill row, reclaiming
        // the vertical band it used to occupy.
        ChatMenuFab(
            thinking = ui.thinking,
            continuousMode = ui.continuousMode,
            onToggleContinuous = { vm.setContinuousMode(!ui.continuousMode) },
            onOpenPeople = onOpenPeople,
            onOpenTimeline = openTimeline,
            onOpenAppDrawer = openDrawer,
            onOpenTasks = openTasks,
            onOpenSettings = onOpenSettings,
            onOpenFace = onOpenFace,
            onOpenAboutMe = onOpenAboutMe,
            onOpenInsights = onOpenInsights,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(insets)
                .padding(top = 6.dp, end = 12.dp),
        )
    }

    // ConfirmationGate dialog overlay. Renders the topmost pending
    // request from the gate; subsequent requests queue and pop after
    // the current one resolves. We collect from a SharedFlow but
    // also read currentRequest as a snapshot fallback so the dialog
    // survives a recompose (e.g. theme change mid-prompt).
    val pendingRequest by vm.confirmationGate.pending.collectAsState(initial = null)
    val request = pendingRequest ?: vm.confirmationGate.currentRequest
    if (request != null) {
        ConfirmationDialog(
            request = request,
            onResolve = { decision, always ->
                vm.resolveConfirmation(request, decision, always)
            },
        )
    }
}

/**
 * Floating nav menu — replaces the old always-visible six-pill header
 * row. Collapsed it's a single 44dp Mythara-diamond button in the
 * top-right corner (showing the thinking ellipsis while the agent
 * runs). Tapped, it drops down a vertical menu of the same
 * destinations — voice toggle, people, memory, apps, tasks, settings
 * — overlaying the chat instead of permanently occupying a band of
 * vertical space.
 */
@Composable
private fun ChatMenuFab(
    thinking: Boolean,
    continuousMode: Boolean,
    onToggleContinuous: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenAppDrawer: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFace: (() -> Unit)? = null,
    onOpenAboutMe: (() -> Unit)? = null,
    onOpenInsights: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MytharaColors.Surface)
                .border(1.5.dp, MytharaColors.Charple, CircleShape)
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    expanded -> Glyph.Cross
                    thinking -> Glyph.Ellipsis
                    else -> Glyph.DiamondFilled
                },
                style = MaterialTheme.typography.titleMedium.copy(
                    color = MytharaColors.Charple, fontWeight = FontWeight.Bold,
                ),
            )
        }
        if (expanded) {
            // Voice toggle stays open so the user can flip it and keep
            // scanning; the nav items close the menu on tap.
            ChatMenuItem(
                label = if (continuousMode) "${Glyph.Dot} voice on" else "${Glyph.CircleOutline} voice off",
                accent = if (continuousMode) MytharaColors.Bok else MytharaColors.SurfaceHigh,
                textColor = if (continuousMode) MytharaColors.Bok else MytharaColors.FgMute,
                onClick = onToggleContinuous,
            )
            if (onOpenFace != null) {
                ChatMenuItem("${Glyph.DiamondFilled} face", MytharaColors.Bok, MytharaColors.Bok) {
                    expanded = false
                    onOpenFace()
                }
            }
            if (onOpenAboutMe != null) {
                ChatMenuItem("${Glyph.DiamondFilled} about me", MytharaColors.Malibu, MytharaColors.Malibu) {
                    expanded = false
                    onOpenAboutMe()
                }
            }
            if (onOpenInsights != null) {
                ChatMenuItem("${Glyph.DiamondFilled} insights", MytharaColors.Bok, MytharaColors.Bok) {
                    expanded = false
                    onOpenInsights()
                }
            }
            ChatMenuItem("${Glyph.DiamondFilled} people", MytharaColors.Charple, MytharaColors.Charple) {
                expanded = false; onOpenPeople()
            }
            ChatMenuItem("${Glyph.DiamondFilled} memory", MytharaColors.Bok, MytharaColors.Bok) {
                expanded = false; onOpenTimeline()
            }
            ChatMenuItem("${Glyph.DiamondFilled} apps", MytharaColors.Mustard, MytharaColors.Mustard) {
                expanded = false; onOpenAppDrawer()
            }
            TasksPill(onClick = { expanded = false; onOpenTasks() })
            ChatMenuItem("⚙ settings", MytharaColors.SurfaceHigh, MytharaColors.FgMute) {
                expanded = false; onOpenSettings()
            }
        }
    }
}

@Composable
private fun ChatMenuItem(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MytharaColors.Surface)
            .border(1.dp, accent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(color = textColor),
        )
    }
}

/**
 * Compact timeline card for a logged interaction with a contact —
 * who Lumi called / messaged / looked up, and when. Interleaved into
 * the scrollback by the interaction's timestamp.
 */
@Composable
private fun PersonInteractionCard(item: ChatViewModel.ChatItem.PersonInteraction) {
    val time = remember(item.tsMillis) {
        java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(item.tsMillis))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Glyph.DiamondFilled,
            color = if (item.ok) MytharaColors.Charple else MytharaColors.Sriracha,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.action} ${item.contactName}",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = time,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
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
private fun Transcript(
    items: List<ChatViewModel.ChatItem>,
    streaming: String?,
    thinkingVisible: Boolean = false,
    musicMode: Boolean = false,
    onReplayMusic: (String) -> Unit = {},
    onReinforce: (String, Boolean) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val streamingActive = !streaming.isNullOrEmpty()
    LaunchedEffect(items.size, streaming, thinkingVisible) {
        val extra = (if (streamingActive) 1 else 0) + (if (thinkingVisible && !streamingActive) 1 else 0)
        val target = items.size + extra
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
                is ChatViewModel.ChatItem.UserText -> TextBubble(text = item.text, kind = item.kind)
                is ChatViewModel.ChatItem.AssistantText -> TextBubble(
                    text = item.text,
                    kind = item.kind,
                    musicMode = musicMode && item.kind == ChatViewModel.TextKind.Reply,
                    onReplayMusic = { onReplayMusic(item.text) },
                    onReinforce = { gotIt -> onReinforce(item.text, gotIt) },
                )
                is ChatViewModel.ChatItem.Thought -> ThoughtBubble(item)
                is ChatViewModel.ChatItem.Tool -> ToolCallBubble(item)
                is ChatViewModel.ChatItem.FromOtherDevice -> FromOtherDeviceCard(item)
                is ChatViewModel.ChatItem.LifelinePhoto -> LifelineCard(item)
                is ChatViewModel.ChatItem.ReminderCard -> ReminderCard(item)
                is ChatViewModel.ChatItem.PersonInteraction -> PersonInteractionCard(item)
            }
        }
        if (streamingActive) {
            item("streaming") {
                TextBubble(text = streaming + Glyph.AccentBar, kind = ChatViewModel.TextKind.Reply)
            }
        } else if (thinkingVisible) {
            // Rolodex thinking indicator with the Charple→Bok brand
            // gradient — only when nothing is streaming yet. Hides
            // the instant the first token lands so we don't double
            // up with the actual reply.
            item("thinking") {
                ThinkingIndicator()
            }
        }
    }
}

/**
 * Plain text bubble, color-coded by [ChatViewModel.TextKind]:
 *  - User         → Charple frame, right-aligned
 *  - Notification → Mustard frame + label, body de-emphasised
 *                   (the `[notif]` prefix is stripped — the frame
 *                   already says "this is a notification")
 *  - Reply        → calm SurfaceHigh frame, Bok label (the common case)
 *  - Update       → Malibu frame + label (agent reacting to a notification)
 */
@Composable
private fun TextBubble(
    text: String,
    kind: ChatViewModel.TextKind,
    /** When true and [kind] is Reply, the body text is rendered with
     *  each content word coloured by its motif's OM-harmonic hue,
     *  with a small ▶ replay chip below. The hide-then-reveal
     *  decode-tap flow has been retired — passive visual association
     *  is the learning surface. */
    musicMode: Boolean = false,
    onReplayMusic: () -> Unit = {},
    onReinforce: (Boolean) -> Unit = {},
) {
    val isUser = kind == ChatViewModel.TextKind.User || kind == ChatViewModel.TextKind.Notification
    val accent = when (kind) {
        ChatViewModel.TextKind.User -> MytharaColors.Charple
        ChatViewModel.TextKind.Notification -> MytharaColors.Mustard
        ChatViewModel.TextKind.Reply -> MytharaColors.Bok
        ChatViewModel.TextKind.Update -> MytharaColors.Malibu
    }
    val label = when (kind) {
        ChatViewModel.TextKind.User -> "you"
        ChatViewModel.TextKind.Notification -> "notification"
        ChatViewModel.TextKind.Reply -> "mythara"
        ChatViewModel.TextKind.Update -> "mythara · update"
    }
    val border = when (kind) {
        ChatViewModel.TextKind.User -> MytharaColors.Charple
        ChatViewModel.TextKind.Notification -> MytharaColors.Mustard
        ChatViewModel.TextKind.Update -> MytharaColors.Malibu
        ChatViewModel.TextKind.Reply -> MytharaColors.SurfaceHigh
    }
    val bg = if (kind == ChatViewModel.TextKind.User) MytharaColors.SurfaceMid else MytharaColors.Surface
    val bodyColor = if (kind == ChatViewModel.TextKind.Notification) MytharaColors.FgMute else MytharaColors.Fg
    val align = if (isUser) Alignment.End else Alignment.Start
    val displayText = if (kind == ChatViewModel.TextKind.Notification) {
        text.removePrefix(com.mythara.agent.AgentLoop.NOTIF_PREFIX).trim()
    } else {
        text
    }

    // Music Mode renders the body as a coloured AnnotatedString —
    // each non-stopword word gets the colour of its motif (the
    // circular-mean of its 3 OM-harmonic hues). Computed once per
    // (text, musicMode) pair and cached via produceState so we
    // don't re-encode on every recomposition.
    val composedAnnotated = if (musicMode && kind == ChatViewModel.TextKind.Reply) {
        produceMusicAnnotated(displayText, bodyColor)
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            text = "${Glyph.DiamondFilled} $label",
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
            Column(modifier = Modifier.fillMaxWidth()) {
                if (composedAnnotated != null) {
                    Text(
                        text = composedAnnotated,
                        color = bodyColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BubbleChip(
                            label = "▶ replay tones",
                            color = MytharaColors.SurfaceHigh,
                        ) { onReplayMusic() }
                    }
                } else {
                    Text(text = displayText, color = bodyColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Encode the reply text into a coloured [AnnotatedString] in Music
 *  Mode. Subscribes to the View Model's `musicNowPlaying` so the
 *  word matching the currently-playing motif gets a translucent
 *  background glow — visual sync between the tone the user hears
 *  and the word they see, key to the learning loop.
 *
 *  Recomputes whenever the highlight index changes, so the glow
 *  travels word-by-word across the bubble in lockstep with audio. */
@Composable
private fun produceMusicAnnotated(text: String, defaultColor: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString? {
    val vm: ChatViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val nowPlaying by vm.musicNowPlaying.collectAsState()
    val highlight = nowPlaying?.takeIf { it.sourceKey == text }?.motifIndex
    val state = androidx.compose.runtime.produceState<androidx.compose.ui.text.AnnotatedString?>(
        initialValue = null,
        text,
        highlight,
    ) {
        value = vm.composeMusicAnnotated(text, defaultColor.toArgb(), highlightMotifIndex = highlight)
    }
    return state.value
}

@Composable
private fun BubbleChip(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = label, color = MytharaColors.Bg, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Composer(
    onSubmit: (String, Boolean) -> Unit,
    enabled: Boolean,
    /**
     * When non-null, this text is dropped into the composer's draft
     * field for the user to review + edit before tapping send. Used by
     * the ASSIST-intent voice path (Pixel Buds tap, watch long-press)
     * — the user wanted dictation-to-composer rather than auto-submit
     * so they get a chance to fix the transcription before it lands
     * with the agent.
     */
    incomingDictation: String? = null,
    onDictationConsumed: () -> Unit = {},
    /** Global speech-to-text mute — disables the mic button entirely. */
    speechMuted: Boolean = false,
    onToggleSpeechMute: () -> Unit = {},
    /** Music Mode toggle. When on, agent replies are tone-encoded. */
    musicMode: Boolean = false,
    onToggleMusicMode: () -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    // Apply incoming dictation exactly once per (string, identity).
    // Append to whatever the user has already typed so a half-typed
    // thought + a dictated tail merge naturally.
    LaunchedEffect(incomingDictation) {
        val text = incomingDictation
        if (!text.isNullOrBlank()) {
            draft = if (draft.isBlank()) text else "${draft.trim()} ${text.trim()}"
            onDictationConsumed()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // STT mute toggle — hard kill-switch for every speech-to-text
        // path (wake-word, Observe, continuous chat, push-to-talk).
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (speechMuted) MytharaColors.Sriracha else MytharaColors.Surface)
                .border(
                    2.dp,
                    if (speechMuted) MytharaColors.Sriracha else MytharaColors.SurfaceHigh,
                    RoundedCornerShape(24.dp),
                )
                .clickable { onToggleSpeechMute() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (speechMuted) "MUTE" else "STT",
                color = if (speechMuted) MytharaColors.Bg else MytharaColors.FgMute,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // Mic button — push-to-talk via SpeechRecognizer. Partials stream into
        // the draft field; final fires submit() with fromVoice=true so the
        // agent loop produces a voice-friendly short reply. Disabled while
        // speech-to-text is muted.
        MicButton(
            onPartial = { draft = it },
            onFinal = {
                draft = it
                if (enabled && draft.isNotBlank()) {
                    onSubmit(draft, /* fromVoice = */ true); draft = ""
                }
            },
            onError = { /* surface later via VM event channel */ },
            muted = speechMuted,
        )

        // Music Mode toggle — when on, every agent reply is also
        // encoded as a sequence of tone motifs and played alongside
        // the text. The same colours and 48dp footprint as the STT
        // mute toggle so the composer row stays balanced.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (musicMode) MytharaColors.Charple else MytharaColors.Surface)
                .border(
                    2.dp,
                    if (musicMode) MytharaColors.Charple else MytharaColors.SurfaceHigh,
                    RoundedCornerShape(24.dp),
                )
                .clickable { onToggleMusicMode() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "♪",
                color = if (musicMode) MytharaColors.Bg else MytharaColors.FgMute,
                style = MaterialTheme.typography.titleMedium,
            )
        }

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

/**
 * Three-chip filter strip for lifeline photos in the chat scrollback.
 * Shown only when there's at least one foreign-device photo in the
 * timeline (controlled by UiState.hasRemoteLifeline).
 */
@Composable
private fun LifelineFilterChips(
    current: ChatViewModel.LifelineFilter,
    onSelect: (ChatViewModel.LifelineFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${Glyph.AccentBar} photos:",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
        FilterChip(label = "all", active = current == ChatViewModel.LifelineFilter.ALL) {
            onSelect(ChatViewModel.LifelineFilter.ALL)
        }
        FilterChip(label = "this device", active = current == ChatViewModel.LifelineFilter.LOCAL) {
            onSelect(ChatViewModel.LifelineFilter.LOCAL)
        }
        FilterChip(label = "other devices", active = current == ChatViewModel.LifelineFilter.REMOTE) {
            onSelect(ChatViewModel.LifelineFilter.REMOTE)
        }
    }
}

/**
 * Tiny standalone ViewModel — just exposes the live pending-task
 * count so the chat-header pill can show a badge without ChatViewModel
 * needing to know about the tasks subsystem.
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class TasksPillViewModel @javax.inject.Inject constructor(
    repo: com.mythara.tasks.TaskRepository,
) : androidx.lifecycle.ViewModel() {
    val pendingCount: StateFlow<Int> = repo.dao.pendingCountFlow()
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            0,
        )
}

@Composable
private fun TasksPill(onClick: () -> Unit) {
    val vm: TasksPillViewModel = hiltViewModel()
    val pending by vm.pendingCount.collectAsState()
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.Malibu, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (pending > 0) "${Glyph.DiamondFilled} tasks · $pending"
            else "${Glyph.DiamondFilled} tasks",
            style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.Malibu),
        )
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    val border = if (active) MytharaColors.Mustard else MytharaColors.SurfaceHigh
    val txt = if (active) MytharaColors.Mustard else MytharaColors.FgMute
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MytharaColors.Surface)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text = label, color = txt, style = MaterialTheme.typography.labelSmall)
    }
}
