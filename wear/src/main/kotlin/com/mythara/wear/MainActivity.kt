package com.mythara.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.SwipeToDismissValue
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberSwipeToDismissBoxState
import com.google.android.gms.wearable.Wearable
import com.mythara.wear.resonance.ResonancePad
import com.mythara.wear.resonance.ResonanceStore
import com.mythara.wear.ui.ConstellationOverlay
import com.mythara.wear.ui.MytharaRose
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sqrt

private val PURPLE = Color(0xFF6B50FF)
private val BOK = Color(0xFF68FFD6)
private val CARD = Color(0xFF15151A)
private val CARD_BORDER = Color(0xFF2A2A33)
private val DIM = Color(0xFF9A9AA5)
private val MUTE = Color(0xFF777777)

// --- gesture tuning ---------------------------------------------------
// A deliberate wrist shake = [SHAKE_SPIKES_NEEDED] g-force spikes above
// [SHAKE_G_THRESHOLD] landing within [SHAKE_WINDOW_MS]. High enough that
// normal arm movement / raising the wrist doesn't trigger it.
private const val SHAKE_G_THRESHOLD = 2.3f
private const val SHAKE_SPIKES_NEEDED = 2
private const val SHAKE_WINDOW_MS = 700L
private const val SHAKE_DEBOUNCE_MS = 2_500L

/**
 * Mythara Wear OS companion. Screens switched by simple state:
 *  - Home     : push-to-talk mic (ships transcripts to the phone agent)
 *  - Tasks    : the cluster's recent tasks, mirrored from the phone
 *  - Calendar : today's events, mirrored from the phone
 *  - People   : favorite contacts; tap one for Lumi's latest insights
 *  - Audit    : the recent agent action log, most-recent-first
 *
 * Gesture-friendly throughout:
 *  - **wrist shake** on Home → starts push-to-talk to the agent, no tap
 *  - **swipe right** → goes back a screen (the standard Wear gesture,
 *    handled via SwipeToDismissBox); from Home it exits to the face
 *  - **rotary crown / bezel** → scrolls the list screens
 *  - tap targets are finger-sized; the mic is an 84dp circle
 *
 * No Hilt / DB — the wear module stays tiny. Every list is a read-only
 * mirror of phone state, cached by [ClusterDataStore] from the phone's
 * WatchClusterDataPusher over the Wearable Data Layer.
 */
class MainActivity : ComponentActivity() {

    /** Runtime permission launcher for BODY_SENSORS. Stays here for
     *  the rare case where the user has actively denied / never
     *  accepted at install — but [onResume] only fires it once per
     *  activity-lifetime to avoid re-prompting in a loop if the user
     *  dismisses. */
    private val sensorPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d("Mythara/MainActivity", "BODY_SENSORS grant result: $granted")
            if (granted) {
                HeartRateService.start(this)
            }
        }

    /** Set to true the first time onResume launches the BODY_SENSORS
     *  prompt this activity-lifetime, so a "denied" outcome doesn't
     *  re-launch the prompt on every subsequent resume — that would
     *  loop the system permission UI forever. */
    private var sensorPromptShown: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pin to portrait in code as well as the manifest — Samsung's
        // One UI Watch doesn't reliably honour the manifest attribute
        // alone, so the screen would flip on wrist movement.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContent {
            MaterialTheme {
                Scaffold {
                    AppRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Start the heart-rate foreground service from a guaranteed-
        // foreground lifecycle point — Android 12+ blocks
        // startForegroundService() from background contexts, and a
        // Compose LaunchedEffect can fire before the activity resumes.
        //
        // Only prompt for BODY_SENSORS once per activity-lifetime: if
        // the user dismisses the system prompt, onResume re-fires
        // immediately and a prompt-on-every-resume would loop the
        // permission UI forever — what was happening on the Pixel
        // Watch's freshly-installed Mythara.
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BODY_SENSORS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            HeartRateService.start(this)
        } else if (!sensorPromptShown) {
            sensorPromptShown = true
            Log.d("Mythara/MainActivity", "BODY_SENSORS not granted — prompting (one-shot)")
            sensorPermLauncher.launch(Manifest.permission.BODY_SENSORS)
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Tasks : Screen
    data object People : Screen
    data object Calendar : Screen
    data object Audit : Screen
    data class Person(val person: ClusterDataStore.WatchPerson) : Screen
}

/** Screen order for the "swipe right = back one step" gesture. */
private val MAIN_RING = listOf(Screen.Home, Screen.Tasks, Screen.Calendar, Screen.People, Screen.Audit)

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    // Request BODY_SENSORS once if missing — the actual service start
    // happens in MainActivity.onResume(), a guaranteed-foreground point
    // (Android 12+ blocks startForegroundService from the background).
    val hrPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted → MainActivity.onResume() starts the service */ }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.BODY_SENSORS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) hrPermLauncher.launch(Manifest.permission.BODY_SENSORS)
    }

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    // SwipeToDismissBox takes over Wear's system swipe-right gesture so
    // it goes back ONE screen inside the app instead of closing the
    // activity outright. From Home there's nothing to go back to, so a
    // right-swipe there exits to the watch face as usual.
    val swipeState = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipeState.currentValue) {
        if (swipeState.currentValue == SwipeToDismissValue.Dismissed) {
            when (val s = screen) {
                is Screen.Person -> screen = Screen.People
                Screen.Home -> activity?.finish()
                else -> {
                    val idx = MAIN_RING.indexOf(s)
                    screen = if (idx > 0) MAIN_RING[idx - 1] else Screen.Home
                }
            }
            swipeState.snapTo(SwipeToDismissValue.Default)
        }
    }

    SwipeToDismissBox(
        state = swipeState,
        hasBackground = screen != Screen.Home,
        backgroundScrimColor = Color.Black,
        contentScrimColor = Color.Black,
    ) { isBackground ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (!isBackground) {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        onOpenTasks = { screen = Screen.Tasks },
                        onOpenPeople = { screen = Screen.People },
                        onOpenCalendar = { screen = Screen.Calendar },
                        onOpenAudit = { screen = Screen.Audit },
                    )
                    Screen.Tasks -> TasksScreen(onBack = { screen = Screen.Home })
                    Screen.Calendar -> CalendarScreen(onBack = { screen = Screen.Home })
                    Screen.Audit -> AuditScreen(onBack = { screen = Screen.Home })
                    Screen.People -> PeopleScreen(
                        onBack = { screen = Screen.Home },
                        onOpenPerson = { screen = Screen.Person(it) },
                    )
                    is Screen.Person -> PersonDetailScreen(
                        person = s.person,
                        onBack = { screen = Screen.People },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- gestures

/**
 * Registers an accelerometer listener while [enabled] and fires
 * [onShake] on a deliberate wrist shake. Scoped to the composition it's
 * called from — leave the screen and the sensor unregisters, so it's
 * battery-safe (only listens while the user is looking at Home).
 */
@Composable
private fun ShakeToTalk(enabled: Boolean, onShake: () -> Unit) {
    val ctx = LocalContext.current
    val currentOnShake by rememberUpdatedState(onShake)
    DisposableEffect(enabled) {
        if (!enabled) {
            onDispose { }
        } else {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sm == null || accel == null) {
                onDispose { }
            } else {
                var spikeCount = 0
                var firstSpikeMs = 0L
                var lastFireMs = 0L
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        val x = e.values[0]
                        val y = e.values[1]
                        val z = e.values[2]
                        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                        if (gForce <= SHAKE_G_THRESHOLD) return
                        val now = System.currentTimeMillis()
                        if (now - firstSpikeMs > SHAKE_WINDOW_MS) {
                            firstSpikeMs = now
                            spikeCount = 1
                        } else {
                            spikeCount++
                        }
                        if (spikeCount >= SHAKE_SPIKES_NEEDED && now - lastFireMs > SHAKE_DEBOUNCE_MS) {
                            lastFireMs = now
                            spikeCount = 0
                            currentOnShake()
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
                onDispose { sm.unregisterListener(listener) }
            }
        }
    }
}

/**
 * Routes rotary-crown / bezel scroll events into [scrollState] so the
 * list screens scroll with the crown, not just touch. Returns a
 * Modifier — apply it to the scrolling Column alongside `.verticalScroll`.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.rotaryScroll(scrollState: androidx.compose.foundation.ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    return this
        .onRotaryScrollEvent { event ->
            scope.launch { scrollState.scrollBy(event.verticalScrollPixels) }
            true
        }
        .focusRequester(focusRequester)
        .focusable()
}

// ---------------------------------------------------------------- Home / PTT

/**
 * PTT-first watch home — single primary action: tap the rose to talk.
 *
 * Layout (top → bottom, all centered on the round watch face):
 *  • Tiny status row: clock (HH:MM) + HR badge. ~10sp, dim. Hidden in
 *    Resonance Mode (the eyes-free pad needs the room).
 *  • The Mythara rose — 120 dp, slowly rotating, hex nucleus pulsing.
 *    TAP            → start PTT (or stop if already listening).
 *    LONG-PRESS     → open the Constellation overflow menu.
 *    While listening: pulse speeds + a charple ring fades in.
 *  • Below the rose: live partial transcript or hint text. ~11sp.
 *  • Bottom subtle "↑ menu" hint.
 *
 * Wrist-shake on the home screen still fires startPtt — the gesture
 * is unchanged from the previous design so anyone who learned it
 * keeps it. Battery: the rose's Compose animation pauses when the
 * Constellation overlay covers it (the rose composable leaves
 * recomposition).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(
    onOpenTasks: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenAudit: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("shake wrist or tap to talk") }
    var weather by remember { mutableStateOf("…") }
    var recognizer: SpeechRecognizer? by remember { mutableStateOf(null) }
    // Resonance Mode flags. `available` = phone has enabled the feature
    // in the secret menu; `active` = the user has flipped the discreet
    // toggle dot on this watch.
    val resonanceAvailable by ResonanceStore.observeAvailable(ctx)
    val resonanceActive by ResonanceStore.observeActive(ctx)

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) "ready" else "mic denied"
    }

    val locationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        weather = if (granted) "…" else "location off"
        if (granted) scope.launch { weather = loadWeather(ctx) }
    }

    LaunchedEffect(Unit) {
        val hasLoc = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLoc) {
            weather = loadWeather(ctx)
        } else {
            locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.destroy()
            recognizer = null
        }
    }

    // Push-to-talk start. Shared by the mic tap AND the wrist-shake
    // gesture so both routes into the agent are identical.
    fun startPtt() {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (listening) {
            recognizer?.stopListening()
            return
        }
        listening = true
        partial = ""
        status = "listening…"
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx).also { recognizer = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false
                status = "error $error"
                sr.destroy()
                recognizer = null
            }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                val text = texts.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    sendToPhone(ctx, text)
                    status = "sent"
                    partial = text
                } else {
                    status = "no speech"
                }
                listening = false
                sr.destroy()
                recognizer = null
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                partial = texts.firstOrNull().orEmpty()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        sr.startListening(intent)
    }

    // Wrist shake → talk. Only registers while NOT already listening
    // and NOT in Resonance Mode — pad taps would false-trigger the
    // accelerometer, and a shake while the user is mid-combo would be
    // an unwanted interrupt.
    ShakeToTalk(enabled = !listening && !resonanceActive) { startPtt() }

    var showConstellation by remember { mutableStateOf(false) }

    // Live heart-rate badge — refreshes once a second from WatchHrStore.
    // Null when no fresh sample (>3 min stale).
    val hrBpm by androidx.compose.runtime.produceState(
        initialValue = WatchHrStore.latestBpm(ctx).takeIf { WatchHrStore.isFresh(ctx) },
        key1 = Unit,
    ) {
        while (true) {
            value = WatchHrStore.latestBpm(ctx).takeIf { WatchHrStore.isFresh(ctx) }
            kotlinx.coroutines.delay(1_000L)
        }
    }

    // Live clock — re-renders every 30 s so HH:MM stays current
    // without burning frames every second.
    val nowMs by androidx.compose.runtime.produceState(
        initialValue = System.currentTimeMillis(),
        key1 = Unit,
    ) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000L)
        }
    }
    val clockLabel = remember(nowMs) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(nowMs))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Pull-up gesture on empty area also opens the constellation
            // — gives the menu two ways in (rose long-press + swipe up)
            // so it's discoverable both ways.
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -20f && !showConstellation && !resonanceActive) {
                        showConstellation = true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (resonanceActive) {
            // Eyes-free Resonance Mode takes over the whole face —
            // the discreet pad is the entire UI. Tiny exit affordance
            // at the bottom so the user can long-press to flip back.
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("◐ resonance", color = BOK, fontSize = 10.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                ResonancePad(onStartPtt = { startPtt() })
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(BOK)
                        .clickable {
                            ResonanceStore.setActive(ctx, false)
                            HeartRateService.stopStreaming(ctx)
                            sendBytesToPhone(
                                ctx,
                                WearPaths.RESONANCE_TOGGLE,
                                "off".toByteArray(Charsets.UTF_8),
                            )
                        },
                )
            }
        } else {
            // PTT-first home — rose ANCHORED at absolute screen centre
            // via Box alignment so it never shifts when surrounding
            // text changes length. The earlier SpaceBetween Column had
            // the rose move vertically every time the status line
            // grew/shrank (a long partial transcript pushed the rose
            // upward, "tap to talk" let it drop back down — read as
            // "the rose is floating around"). With Box overlays, only
            // the text tiers reflow; the rose stays put while it spins.
            Box(
                modifier = Modifier.fillMaxSize().padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Centre layer: rose only — fixed 120 dp circle, rotates
                // around its own centre. Nothing in this layer changes
                // size with content, so the rose never moves.
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = { startPtt() },
                            onLongClick = { showConstellation = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    MytharaRose(
                        modifier = Modifier.fillMaxSize(),
                        listening = listening,
                        showRing = listening,
                    )
                }

                // Top overlay: tiny clock + HR badge. Aligned to top-
                // centre of the parent Box — doesn't affect the rose's
                // centre alignment.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    Text(clockLabel, color = DIM, fontSize = 11.sp)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "♥ ${hrBpm?.toString() ?: "--"}",
                        color = Color(0xFFEB4268),
                        fontSize = 11.sp,
                    )
                }

                // Status text overlay: sits just below the rose. Aligned
                // via offset from centre, so even when the text wraps to
                // two lines (a long partial transcript) the rose above
                // stays put.
                val statusText = when {
                    listening && partial.isNotBlank() -> partial
                    listening -> "listening…"
                    partial.isNotBlank() -> partial
                    else -> status
                }
                Text(
                    text = statusText,
                    color = if (statusText == status || statusText == "listening…") DIM else Color.White,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 38.dp, start = 18.dp, end = 18.dp),
                )

                // Bottom overlay: weather + menu hint.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Text(
                        text = weather,
                        color = BOK,
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable {
                            weather = "…"
                            scope.launch { weather = loadWeather(ctx) }
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "↑ menu",
                        color = MUTE,
                        fontSize = 8.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Constellation overlay — long-press rose OR drag-up to open.
        // Renders above the home content; tap scrim or rose centre to
        // close. Toggling Resonance via this overlay always closes
        // first so the home screen rebuilds into the eyes-free layout.
        ConstellationOverlay(
            visible = showConstellation,
            resonanceAvailable = resonanceAvailable,
            resonanceActive = resonanceActive,
            onClose = { showConstellation = false },
            onTasks = onOpenTasks,
            onCalendar = onOpenCalendar,
            onPeople = onOpenPeople,
            onAudit = onOpenAudit,
            onToggleResonance = {
                val newActive = !resonanceActive
                ResonanceStore.setActive(ctx, newActive)
                if (newActive) HeartRateService.startStreaming(ctx)
                else HeartRateService.stopStreaming(ctx)
                sendBytesToPhone(
                    ctx,
                    WearPaths.RESONANCE_TOGGLE,
                    (if (newActive) "on" else "off").toByteArray(Charsets.UTF_8),
                )
            },
        )
    }
}

// ---------------------------------------------------------------- Tasks

@Composable
private fun TasksScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tasks = remember { ClusterDataStore.tasks(ctx) }
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .rotaryScroll(scrollState)
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NavHeader("tasks", onBack)
        Spacer(Modifier.height(8.dp))
        if (tasks.isEmpty()) {
            Text("no tasks yet", color = MUTE, fontSize = 12.sp, textAlign = TextAlign.Center)
        } else {
            tasks.forEach { t ->
                TaskRow(t)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TaskRow(t: ClusterDataStore.WatchTask) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CARD)
            .border(1.dp, CARD_BORDER, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column {
            Text(t.title, color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(t.status.lowercase(), color = statusColor(t.status), fontSize = 10.sp)
            if (t.result.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(t.result, color = DIM, fontSize = 10.sp)
            }
        }
    }
}

private fun statusColor(status: String): Color = when (status.uppercase()) {
    "DONE" -> BOK
    "FAILED" -> Color(0xFFEB4268)
    "RUNNING" -> Color(0xFFF5EF34)
    else -> Color(0xFF8E8A95)
}

// ---------------------------------------------------------------- People

@Composable
private fun PeopleScreen(
    onBack: () -> Unit,
    onOpenPerson: (ClusterDataStore.WatchPerson) -> Unit,
) {
    val ctx = LocalContext.current
    val people = remember { ClusterDataStore.people(ctx) }
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .rotaryScroll(scrollState)
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NavHeader("favorites", onBack)
        Spacer(Modifier.height(8.dp))
        if (people.isEmpty()) {
            Text(
                text = "no favorites yet — add them in the phone app",
                color = MUTE,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            people.forEach { p ->
                PersonRow(p) { onOpenPerson(p) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PersonRow(p: ClusterDataStore.WatchPerson, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CARD)
            .border(1.dp, PURPLE, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text("${p.name}  ›", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun PersonDetailScreen(person: ClusterDataStore.WatchPerson, onBack: () -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .rotaryScroll(scrollState)
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NavHeader(person.name, onBack)
        Spacer(Modifier.height(8.dp))
        Text("◆ latest insights", color = BOK, fontSize = 11.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(person.insight, color = Color(0xFFD8D8DD), fontSize = 12.sp)
        if (person.points.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("◆ key points", color = BOK, fontSize = 11.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            person.points.forEach { pt ->
                Text("· $pt", color = DIM, fontSize = 11.sp)
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Calendar

@Composable
private fun CalendarScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val events = remember { ClusterDataStore.calendar(ctx) }
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .rotaryScroll(scrollState)
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NavHeader("today", onBack)
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text(
                text = "nothing on the calendar",
                color = MUTE,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            events.forEach { e ->
                CalEventRow(e)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CalEventRow(e: ClusterDataStore.WatchCalEvent) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CARD)
            .border(1.dp, CARD_BORDER, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column {
            Text(calTimeFmt(e.startMs), color = BOK, fontSize = 11.sp)
            Spacer(Modifier.height(2.dp))
            Text(e.title, color = Color.White, fontSize = 12.sp)
            if (e.location.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(e.location, color = DIM, fontSize = 10.sp)
            }
        }
    }
}

private fun calTimeFmt(ms: Long): String =
    java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

// ---------------------------------------------------------------- Audit log

@Composable
private fun AuditScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val entries = remember { ClusterDataStore.audit(ctx) }
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .rotaryScroll(scrollState)
            .verticalScroll(scrollState)
            .padding(horizontal = 14.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NavHeader("activity log", onBack)
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                text = "no activity yet — Lumi's actions show up here",
                color = MUTE,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            // Already most-recent-first from the phone push.
            entries.forEach { e ->
                AuditRow(e)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AuditRow(e: ClusterDataStore.WatchAuditEntry) {
    val accent = if (e.ok) BOK else Color(0xFFEB4268)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CARD)
            .border(1.dp, CARD_BORDER, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (e.ok) "●" else "▲", color = accent, fontSize = 9.sp)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "  ${e.title}",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${auditTimeFmt(e.tsMs)} · ${e.kind}",
                color = DIM,
                fontSize = 9.sp,
            )
            if (e.detail.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(e.detail, color = DIM, fontSize = 10.sp)
            }
        }
    }
}

private fun auditTimeFmt(ms: Long): String =
    java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

// ---------------------------------------------------------------- shared UI

@Composable
private fun NavHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onBack)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "‹  $title", color = PURPLE, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NavPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1A1F))
            .border(1.dp, PURPLE, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text = label, color = PURPLE, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------- Data Layer

/**
 * Fire-and-forget: send the transcript to the paired phone via the
 * Wearable Data Layer, under [WearPaths.PTT_SUBMIT]. Shared by the
 * in-app mic and the standalone [PttActivity] hardware-key entry point.
 */
internal fun sendToPhone(ctx: android.content.Context, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val nodeClient = Wearable.getNodeClient(ctx)
    val msgClient = Wearable.getMessageClient(ctx)
    nodeClient.connectedNodes
        .addOnSuccessListener { nodes ->
            for (node in nodes) {
                msgClient.sendMessage(node.id, WearPaths.PTT_SUBMIT, bytes)
                    .addOnSuccessListener {
                        Log.d(TAG, "PTT submitted to ${node.displayName}: \"${text.take(60)}\"")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "PTT send to ${node.displayName} failed: ${e.message}")
                    }
            }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "could not list connected nodes: ${e.message}")
        }
}

/**
 * Path-aware fan-out for any Resonance message. Same fire-and-forget
 * MessageClient pattern as [sendToPhone], but lets the caller pick the
 * path so we don't have to overload the PTT-specific helper for every
 * new wire path the watch wants to push.
 */
internal fun sendBytesToPhone(ctx: android.content.Context, path: String, bytes: ByteArray) {
    val nodeClient = Wearable.getNodeClient(ctx)
    val msgClient = Wearable.getMessageClient(ctx)
    nodeClient.connectedNodes
        .addOnSuccessListener { nodes ->
            for (node in nodes) {
                msgClient.sendMessage(node.id, path, bytes)
                    .addOnFailureListener { e ->
                        Log.w(TAG, "send $path to ${node.displayName} failed: ${e.message}")
                    }
            }
        }
        .addOnFailureListener { e ->
            Log.w(TAG, "could not list connected nodes for $path: ${e.message}")
        }
}

/**
 * Resolve the PTT screen's weather line — current-location conditions
 * via [fetchWeather], collapsed to a short wrist-sized status string.
 */
private suspend fun loadWeather(ctx: android.content.Context): String =
    when (val r = fetchWeather(ctx)) {
        is WeatherResult.Ok -> "${r.info.tempC}°C · ${r.info.label}"
        WeatherResult.NoPermission -> "location off"
        WeatherResult.NoLocation -> "no location"
        is WeatherResult.Error -> "weather n/a"
    }

private const val TAG = "Mythara/Wear"

/**
 * Message paths shared between the watch and phone modules. Kept tiny
 * so the wear module doesn't need to depend on anything from the phone
 * app. Keep these in sync with the phone-side MytharaWearListenerService
 * + WatchClusterDataPusher.
 */
object WearPaths {
    const val PTT_SUBMIT = "/mythara/ptt/submit"

    /** Phone → watch: the latest Mythara agent chat message. */
    const val INSIGHT = "/mythara/insight"

    /** Phone → watch: the paired phone's battery percent. */
    const val PHONE_BATTERY = "/mythara/phone_battery"

    /** Phone → watch: delimited snapshot of recent cluster tasks. */
    const val TASKS = "/mythara/tasks"

    /** Phone → watch: delimited snapshot of favorite people + insights. */
    const val PEOPLE = "/mythara/people"

    /** Watch → phone: a single heart-rate reading (bpm). */
    const val HEART_RATE = "/mythara/heart_rate"

    /** Phone → watch: delimited snapshot of today's calendar events. */
    const val CALENDAR = "/mythara/calendar"

    /** Phone → watch: the single next upcoming reminder (title + epoch ms). */
    const val REMINDER = "/mythara/reminder"

    /** Phone → watch: delimited snapshot of the recent agent audit log. */
    const val AUDIT = "/mythara/audit"

    // ---- Resonance Mode (discreet sound control + closed-loop self-regulation)

    /** Watch → phone: a committed 2-tap combo. Payload "<code>|<epochMs>". */
    const val RESONANCE_COMBO = "/mythara/resonance/combo"

    /** Watch → phone: live HR sample while a Resonance session is active.
     *  Payload "<bpm>|<epochMs>". */
    const val RESONANCE_HR = "/mythara/resonance/hr"

    /** Watch → phone: the discreet on/off toggle next to the mic button.
     *  Payload "on" / "off". */
    const val RESONANCE_TOGGLE = "/mythara/resonance/toggle"

    /** Phone → watch: feature enabled in the secret menu. Payload "1" / "0". */
    const val RESONANCE_AVAIL = "/mythara/resonance/avail"

    /** Phone → watch: session active state for confirm/end haptic.
     *  Payload "active" / "ended". */
    const val RESONANCE_STATE = "/mythara/resonance/state"
}
