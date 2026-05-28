package com.mythara.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactProfileRepository
import com.mythara.branding.MoodSink
import com.mythara.tasks.TaskRepository
import com.mythara.ui.face.FaceMesh
import com.mythara.ui.face.FaceViewModel
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaWordmark
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row in the Home key-notifications strip. */
data class NotifChip(
    val recent: com.mythara.services.NotificationListener.Recent,
    val appLabel: String,
    val important: Boolean,
)

@HiltViewModel
class HomeHubViewModel @Inject constructor(
    tasks: TaskRepository,
    contacts: ContactProfileRepository,
    pttController: com.mythara.voice.PttController,
    private val agentRunner: com.mythara.agent.AgentRunner,
    private val tts: com.mythara.mic.Tts,
    notifFeed: com.mythara.services.NotificationFeedRepository,
    notifActions: com.mythara.services.NotificationActionStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val ctx: Context,
) : ViewModel() {
    val pendingTasks: StateFlow<Int> =
        tasks.dao.pendingCountFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val peopleCount: StateFlow<Int> =
        contacts.dao.observeAll()
            .map { rows -> rows.count { it.kind == "person" && !it.isHidden } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val mood: StateFlow<String?> = MoodSink.moodFlow

    /** True while the rose PTT mic is open — drives the "listening…"
     *  subtitle so the user knows their hold armed voice. */
    val listening: StateFlow<Boolean> = pttController.listening

    /** True while Mythara is speaking via TTS — drives the agent
     *  caption + sunburst flow on the face. */
    val speaking: StateFlow<Boolean> = tts.speaking

    /** Live partial transcript of the user's PTT audio, updated as
     *  SpeechRecognition emits partial results. Empty when not
     *  recording. */
    val userPartial: StateFlow<String> = pttController.partialText

    private val _agentText = kotlinx.coroutines.flow.MutableStateFlow("")
    /** Live transcript of Mythara's reply — accumulates streamed
     *  Delta events from [AgentRunner.turnEvents] then settles on the
     *  Finished.finalText. Auto-clears a few seconds after TTS ends. */
    val agentText: StateFlow<String> = _agentText.asStateFlow()

    /** Top notifications strip on Home — up to 5 non-ongoing, non-self
     *  notifications, important-marked apps first, newest within group.
     *  Tappable → open the source app via [openNotification]. */
    val topNotifications: StateFlow<List<NotifChip>> =
        kotlinx.coroutines.flow.combine(notifFeed.feed, notifActions.exemptFlow()) { feed, exempt ->
            feed.asSequence()
                .filter { !it.ongoing && it.packageName.isNotEmpty() && it.packageName != ctx.packageName }
                // Important apps pinned first, then by most-recent.
                .sortedWith(
                    compareByDescending<com.mythara.services.NotificationListener.Recent> {
                        it.packageName in exempt
                    }.thenByDescending { it.postTimeMs },
                )
                // Dedup by package — one chip per app (the newest).
                .distinctBy { it.packageName }
                .take(5)
                .map { r ->
                    NotifChip(
                        recent = r,
                        appLabel = appLabel(r.packageName),
                        important = r.packageName in exempt,
                    )
                }
                .toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tap a Home notification chip → open the source app. */
    fun openNotification(r: com.mythara.services.NotificationListener.Recent) {
        com.mythara.services.openNotificationSource(ctx, r)
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    init {
        // Stream agent text as the model generates it.
        viewModelScope.launch {
            agentRunner.turnEvents.collect { turn ->
                when (turn) {
                    is com.mythara.agent.AgentLoop.Turn.Delta -> {
                        _agentText.value = _agentText.value + turn.text
                    }
                    is com.mythara.agent.AgentLoop.Turn.Finished -> {
                        if (turn.finalText.isNotBlank()) _agentText.value = turn.finalText
                    }
                    is com.mythara.agent.AgentLoop.Turn.Error -> _agentText.value = ""
                    else -> { /* tool start/end / MissingApiKey — ignore */ }
                }
            }
        }
        // Clear stale captions: when PTT starts, reset for the fresh
        // turn; after TTS finishes speaking, fade out a few seconds
        // later so the user has time to read.
        viewModelScope.launch {
            pttController.listening.collect { isListening ->
                if (isListening) _agentText.value = ""
            }
        }
        viewModelScope.launch {
            tts.speaking.collect { isSpeaking ->
                if (!isSpeaking) {
                    kotlinx.coroutines.delay(6_000L)
                    if (!tts.speaking.value) _agentText.value = ""
                }
            }
        }
    }
}

/**
 * The home hub — a LIVING landing surface, not a menu (v7).
 *
 * Top: a slim wordmark + mood line. Center: the camera-tracked
 * animated face mesh ([FaceMesh]) — it looks at whoever's in front of
 * the phone and its mouth animates while Mythara is speaking. Bottom:
 * left clear for the persistent spinning rose (mounted in MytharaRoot),
 * which is also the push-to-talk button.
 *
 * Navigation lives in the right-edge spine dock + the amulet — the old
 * 6-tile grid is gone. Tapping the face opens Chat.
 *
 * Phase 3 will add a tappable key-notifications strip above the face.
 */
@Composable
fun HomeHubScreen(
    onOpenChat: () -> Unit,
    onOpenPeople: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    onOpenAboutMe: () -> Unit = {},
    /** User-tap on a notification chip → navigate to the full Alerts
     *  hub (Routes.NotifHub) instead of jumping straight into the
     *  source app. The "open source app" path stays available via
     *  the Alerts screen's row → ↗ open chevron. */
    onOpenAlerts: () -> Unit = {},
    vm: HomeHubViewModel = hiltViewModel(),
    faceVm: FaceViewModel = hiltViewModel(),
) {
    val mood by vm.mood.collectAsState()
    val listening by vm.listening.collectAsState()
    val speaking by faceVm.speaking.collectAsState()
    val pose by faceVm.pose.collectAsState()
    val userPartial by vm.userPartial.collectAsState()
    val agentText by vm.agentText.collectAsState()
    val notifications by vm.topNotifications.collectAsState()
    val ctx = LocalContext.current

    // Front-camera permission — drives the face's head tracking.
    var hasCam by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val camLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCam = granted }
    LaunchedEffect(Unit) { if (!hasCam) camLauncher.launch(Manifest.permission.CAMERA) }

    // RECORD_AUDIO launcher — invoked the first time the PTT pill is
    // pressed without the permission. Once granted the pill works.
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted → handled lazily by next press */ }

    // Phone-pickup detector — registers TYPE_SIGNIFICANT_MOTION while
    // Home is on screen. Fires when the user picks up the phone and
    // opens an 8-second "camera allowed" window. Each face detection
    // extends the window so the camera stays alive while someone is
    // actually looking; otherwise it unbinds, saving the lens +
    // ML Kit + GPU costs that the previous always-on flow paid.
    DisposableEffect(Unit) {
        faceVm.enablePickupDetector()
        onDispose { faceVm.disablePickupDetector() }
    }
    val cameraActive by faceVm.cameraActive.collectAsState()

    // Camera runs only while Home is composed AND permission held
    // AND the pickup detector has an open window. The triple-gate
    // reduces idle camera drain to ~0 — the front sensor is bound
    // for at most 8 s after each pickup gesture (extended while a
    // face is in frame).
    DisposableEffect(hasCam, cameraActive) {
        if (hasCam && cameraActive) faceVm.bindCamera() else faceVm.unbindCamera()
        onDispose { faceVm.unbindCamera() }
    }

    // PTT controller — same singleton wired into the rose hold; the
    // dedicated PTT pill below also drives it (visible when a face is
    // detected, press-and-hold to talk, release to send).
    val pttController: com.mythara.voice.PttController = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            ctx.applicationContext,
            com.mythara.ui.MytharaRootEntryPoint::class.java,
        ).pttController()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MytharaWordmark(fontSize = 22.sp, shimmer = true)
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    listening -> "listening — release to send"
                    speaking -> "speaking…"
                    pose.present -> "i see you"
                    !hasCam -> "tap to enable camera"
                    mood != null -> "feeling $mood"
                    else -> "say hello"
                },
                color = if (listening) MytharaColors.Charple else MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
                modifier = if (!hasCam) Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            waitForUpOrCancellation()
                            camLauncher.launch(Manifest.permission.CAMERA)
                        }
                    } else Modifier,
            )

            // Key notifications strip — top app chips. v7 P7+:
            // tapping a chip now opens the full Alerts hub
            // (Routes.NotifHub) where the user can see all live
            // notifications, dismiss, ask Mythara, or use the row's
            // ↗ chevron to open the source app. Routing the Home
            // tap to Alerts (instead of straight to the source app)
            // gives the user a manageable triage surface before they
            // commit to leaving Mythara.
            if (notifications.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                NotificationsStrip(
                    items = notifications,
                    onOpen = { onOpenAlerts() },
                )
            }

            // The face — fills the middle. NO `.clickable` so tapping
            // the face does NOT navigate; the rose handles tap→Chat
            // and the PTT pill below handles voice.
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                FaceMesh(speaking = speaking, pose = pose, modifier = Modifier.fillMaxSize())
            }

            // Bottom band reserved for the persistent rose amulet
            // (mounted in MytharaRoot at BottomCenter).
            Spacer(Modifier.height(96.dp))
        }

        // Live transcription panel — both speakers' live captions,
        // sits above the PTT pill. The user's partial transcript
        // streams in as SpeechRecognition emits partials; Mythara's
        // reply streams as the model generates Delta events.
        val showTranscript = userPartial.isNotBlank() || agentText.isNotBlank()
        AnimatedVisibility(
            visible = showTranscript,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(240)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 188.dp)
                .padding(horizontal = 18.dp),
        ) {
            TranscriptPanel(userPartial = userPartial, agentText = agentText)
        }

        // Dedicated PTT pill — appears only when the camera sees a
        // face. Tap to start recording, tap again to send. Sits just
        // above the spinning rose.
        AnimatedVisibility(
            visible = pose.present || listening,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.8f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 116.dp),
        ) {
            PttPill(
                listening = listening,
                onToggle = {
                    if (listening) {
                        pttController.stop()
                    } else if (pttController.micPermissionGranted()) {
                        pttController.start()
                    } else {
                        audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        }
    }
}

/**
 * The dedicated push-to-talk pill — visible on Home when the front
 * camera has a face in frame. **Toggle** behaviour: tap once to start
 * recording, tap again to stop and send (no need to hold). The pill's
 * fill switches to a hot gradient + "● recording — tap to send" so the
 * recording state is unmistakable.
 */
@Composable
private fun PttPill(
    listening: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    // v7.4 — pill gradient pulls from the active skin's palette so it
    // always matches the current theme (rose on Living Rose, cyan on
    // HUD, violet on Aurora, etc). "Recording" uses the warm-warning
    // accents (Sriracha → Mustard → Citron) to signal hot/active.
    val gradient = Brush.horizontalGradient(
        if (listening) listOf(
            MytharaColors.Sriracha, MytharaColors.Mustard, MytharaColors.Citron,
        ) else listOf(
            MytharaColors.Charple, MytharaColors.Malibu, MytharaColors.Bok,
        ),
    )
    Box(
        modifier = Modifier
            .size(width = 240.dp, height = 56.dp)
            .clip(shape)
            .background(gradient)
            .border(1.dp, MytharaColors.Fg.copy(alpha = 0.30f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Tap = toggle (only fire on a clean up, not a
                    // drag-cancel). We wait for the up; if the pointer
                    // is still pressed (not cancelled) at that moment,
                    // fire onToggle.
                    val up = waitForUpOrCancellation()
                    if (up != null) onToggle()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (listening) "● recording — tap to send" else "◉ tap to talk",
                // Foreground adapts: near-white on dark skins, near-
                // black on light skins → readable on every palette.
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * Live captions for both speakers — the user's PTT partial and
 * Mythara's streamed reply, stacked in a soft translucent card so
 * the user can read along while talking + listening.
 */
@Composable
private fun TranscriptPanel(userPartial: String, agentText: String) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MytharaColors.Surface.copy(alpha = 0.78f))
            .border(1.dp, MytharaColors.SurfaceHigh.copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (userPartial.isNotBlank()) {
            CaptionRow(
                glyph = "●",
                glyphColor = MytharaColors.Charple,
                speaker = "you",
                text = userPartial,
            )
        }
        if (agentText.isNotBlank()) {
            CaptionRow(
                glyph = "◆",
                glyphColor = MytharaColors.Bok,
                speaker = "mythara",
                text = agentText,
            )
        }
    }
}

/**
 * Horizontal row of compact notification chips above the face. Each
 * chip = one app (most-recent notification), tap → open the source
 * app via the captured PendingIntent (fallback: launch intent).
 * Important-marked apps surface first.
 */
@Composable
private fun NotificationsStrip(
    items: List<NotifChip>,
    onOpen: (NotifChip) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.recent.packageName }) { chip ->
            NotificationChip(chip = chip, onTap = { onOpen(chip) })
        }
    }
}

@Composable
private fun NotificationChip(chip: NotifChip, onTap: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val accent = if (chip.important) MytharaColors.Mustard else MytharaColors.Charple
    val r = chip.recent
    Column(
        modifier = Modifier
            .size(width = 200.dp, height = 70.dp)
            .clip(shape)
            .background(MytharaColors.Surface.copy(alpha = 0.82f))
            .border(1.dp, accent.copy(alpha = 0.55f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null) onTap()
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = chip.appLabel.lowercase(),
            color = accent,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        r.title?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        r.text?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CaptionRow(glyph: String, glyphColor: Color, speaker: String, text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$glyph $speaker",
            color = glyphColor,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = text,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
        )
    }
}
