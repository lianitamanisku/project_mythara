package com.mythara.ui.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.graphics.BitmapFactory
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import com.mythara.me.MeProfileStore
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mythara's **Dynamic Island** — a single floating pill at the
 * top of every screen that holds the brand mark AND the system
 * chrome the OS status bar would normally show (which we hide in
 * launcher mode). NOT a "status bar" in the old chrome-row sense
 * — it's a discrete island the user identifies with, that just
 * happens to also carry the status indicators.
 *
 * Layout (left → right):
 *   - 🌸 Rose mark (left edge) — tap → 360° spin + clears any
 *     active DynamicIslandSink insight
 *   - Clock (HH:mm)
 *   - 4 signal-strength dots (purple)
 *   - MiniMax API status dot (M●, blue when online)
 *   - Image API status dot (I●, yellow when online)
 *   - Me avatar (tap → AboutMe)
 *   - 🎙 PTT button (tap → fires ACTION_ASSIST → MainActivity's
 *     voice handler kicks in, same code path as Pixel Buds
 *     touch-and-hold)
 *   - Battery percent + circular battery icon
 *   - MYTHARA wordmark (right edge)
 *
 * The pill positions ITSELF below the camera cutout via the
 * safeTopDp math (safeTopDp = cutout.bottomDp + 4 when a cutout
 * is present), with 16dp side margins so the wallpaper shows
 * through on either side — reinforcing the "discrete island"
 * read rather than "edge-to-edge system bar".
 *
 * Permission posture: requires only ACCESS_NETWORK_STATE (already
 * declared). Cellular signal strength would need READ_PHONE_STATE
 * which we don't ask for — so the dots map to "is the device
 * connected to a network at all" rather than true tower bars.
 * That's the high-signal info anyway: "do my API calls have a
 * pipe to fly through?".
 */
@Composable
fun MytharaStatusBar(
    modifier: Modifier = Modifier,
    onOpenAboutMe: () -> Unit = {},
    /**
     * Tap-on-rose action. Per user spec: "clicking on the star
     * in that pill must open the Mythara chat screen from
     * wherever it is clicked, even if on another app." The
     * overlay variant routes this through MainActivity + the
     * EXTRA_OPEN_ROUTE deep-link; the in-app variant navigates
     * the local NavController to Routes.Chat.
     */
    onRoseTap: () -> Unit = {},
    /**
     * Tap on the M● / I● API health dots opens the Usage
     * screen so the user can see the underlying call stats /
     * MiniMax sign-in.
     */
    onOpenUsage: () -> Unit = {},
    /**
     * When > 0, the pill is wrapped in a SOLID BLACK rectangle
     * of this dp height at the very top of the screen — turns
     * the entire upper portion black per user spec ("move it
     * up and turn the complete upper portion black"). The
     * camera cutout sits BEHIND this rectangle and disappears
     * into the black; the pill is centred vertically inside.
     * 0 (default) keeps the in-app pill on transparent bg with
     * the wallpaper visible behind it.
     */
    blackZoneHeightDp: Int = 0,
) {
    val ctx = LocalContext.current

    val nowFmt by produceState(initialValue = formatNow(), key1 = Unit) {
        while (true) {
            value = formatNow()
            delay(30_000L)
        }
    }

    var battery by remember { mutableStateOf(readBattery(ctx)) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                battery = readBattery(ctx, intent)
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    // Network state — fed from a ConnectivityManager.NetworkCallback
    // so the dots update the moment connectivity flips. Also
    // re-evaluates the API health snapshot on each connectivity
    // change — losing wifi flips both API dots to grey instantly.
    var network by remember { mutableStateOf(readNetwork(ctx)) }
    DisposableEffect(ctx) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) { network = readNetwork(ctx) }
            override fun onLost(net: Network) { network = readNetwork(ctx) }
            override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) {
                network = readNetwork(ctx)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }

    // API health dots — re-poll every 2 s. The store is a volatile
    // singleton so reads are cheap; we don't wire a flow because
    // the renderer's already on a UI thread and the freshness
    // doesn't need to be sub-second.
    val minimaxHealth by produceState(initialValue = ApiStatusStore.minimax(), key1 = network) {
        while (true) {
            // Network gone → mark offline regardless of credential.
            // Network back → leave to whatever the last call set
            // (or Online if no call has happened yet).
            if (!network.connected && ApiStatusStore.minimax() != ApiStatusStore.ApiHealth.Error) {
                ApiStatusStore.markMinimaxOffline()
            }
            value = ApiStatusStore.minimax()
            delay(2_000L)
        }
    }
    val imageHealth by produceState(initialValue = ApiStatusStore.image(), key1 = network) {
        while (true) {
            if (!network.connected && ApiStatusStore.image() != ApiStatusStore.ApiHealth.Error) {
                ApiStatusStore.markImageOffline()
            }
            value = ApiStatusStore.image()
            delay(2_000L)
        }
    }

    // Resolve the actual cutout RECT (not just the inset) so the
    // Dynamic Island can wrap dock-bar style around the pinhole.
    // Returns null on devices without a cutout (foldable inner
    // display, tablets, emulators) — the island then renders as
    // a single centred pill.
    val cutout = rememberCutoutRect()

    // Top padding strategy — strip sits BELOW the cutout. Earlier
    // versions tried to wrap the pill AROUND the cutout, but the
    // absolute-offset positioning was off (wrap pills were
    // rendered way past the actual cutout because their offsets
    // were relative to a center-aligned Box, not to the screen
    // origin) which made the island feel like it was floating in
    // the wrong place AND dominating the screen.
    //
    // Simpler model: pad the strip's top to clear the cutout, then
    // let the strip + island + clock + battery all live on a
    // single horizontal line BENEATH the cutout. The clock and
    // battery clusters are visible at the strip's edges and the
    // island is just centered between them.
    // Top padding hardcoded to TOP_PADDING_DP per user spec —
    // earlier cutout-based math was overshooting (the overlay
    // window's `cutout.bottomDp` was inflating to include the
    // system-status-bar inset, giving ~84 dp instead of the
    // expected ~26 dp pinhole bottom). A flat value gets us
    // a deterministic gap on every device + a single place to
    // tune it.
    @Suppress("UNUSED_VARIABLE") val cutoutTopDp =
        WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    @Suppress("UNUSED_VARIABLE") val statusTopDp =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val safeTopDp = TOP_PADDING_DP.toFloat()

    // ───── Collapsed ↔ Expanded Dynamic Island state machine ─────
    //
    // Default state: SMALL pill, just rose + MYTHARA, ~35% screen
    // width. Centred at the top, below the cutout.
    //
    // Tap the pill: rose spins, pill animates to FULL width, and
    // the status indicators (clock, signal, M/I, Me, PTT, battery)
    // fade in inside the expanded pill. The expanded pill IS the
    // status bar — same chrome the user used to see in the always-
    // visible row, just gated behind a tap.
    //
    // Auto-collapse after AUTO_COLLAPSE_MS so a user who taps to
    // glance doesn't have to tap again to reclaim the space.
    // Pill always boots MINIMIZED — small rose + MYTHARA only.
    // Tap expands, auto-collapses after AUTO_COLLAPSE_MS of no
    // further interaction. Per user spec "must always stay
    // minimized view default, expand on click and then auto
    // close in 5 seconds if no action."
    var expanded by remember { mutableStateOf(false) }
    val widthFraction by animateFloatAsState(
        targetValue = if (expanded) 1f else COLLAPSED_WIDTH_FRACTION,
        animationSpec = tween(
            durationMillis = EXPAND_DURATION_MS,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "pillWidth",
    )
    // Cluster items render only once the pill is wide enough to
    // hold them comfortably (≥85% of full width). Without the
    // gate they'd flash in INSIDE a still-narrow pill mid-
    // animation, which reads as a glitch.
    val showCluster = widthFraction >= 0.85f

    // Auto-collapse timer — resets every time `expanded` is
    // toggled (LaunchedEffect cancels + relaunches on key
    // change). Tap during the expanded window resets to a fresh
    // window via the user.
    LaunchedEffect(expanded) {
        if (expanded) {
            kotlinx.coroutines.delay(AUTO_COLLAPSE_MS)
            expanded = false
        }
    }

    // Rose spin + scale-pulse animation, fired on every pill
    // tap regardless of whether the tap is expanding or
    // collapsing. SinkClear runs alongside so a fresh insight
    // gets acknowledged when the user interacts with the pill.
    val scope = rememberCoroutineScope()
    val rotation = remember { androidx.compose.animation.core.Animatable(0f) }
    val pulseScale = remember { androidx.compose.animation.core.Animatable(1f) }
    // Reusable spin + scale-pulse on rose. Triggered by both
    // pill-tap (expand/collapse) AND rose-tap (open chat) so
    // either interaction still gives the brand-mark feedback.
    val spinRose: () -> Unit = {
        DynamicIslandSink.clear()
        scope.launch {
            rotation.snapTo(0f)
            rotation.animateTo(
                360f,
                androidx.compose.animation.core.tween(
                    durationMillis = 700,
                    easing = androidx.compose.animation.core.LinearOutSlowInEasing,
                ),
            )
            rotation.snapTo(0f)
        }
        scope.launch {
            pulseScale.snapTo(1f)
            pulseScale.animateTo(1.12f, androidx.compose.animation.core.tween(140))
            pulseScale.animateTo(1f, androidx.compose.animation.core.tween(220))
        }
    }
    val onPillTap: () -> Unit = {
        expanded = !expanded
        spinRose()
    }

    // ───── Pill layout ─────
    //
    // Outer Box layering:
    //   - When blackZoneHeightDp > 0 (overlay mode), the outer
    //     Box has a SOLID BLACK background filling the top
    //     blackZoneHeightDp dp of the screen — covers the camera
    //     cutout so it visually disappears into the bar.
    //   - When blackZoneHeightDp == 0 (in-app mode default),
    //     the outer Box is transparent + heightless, the pill
    //     just floats below the cutout via safeTopDp.
    // Simple positioning — pill sits below the system status
    // bar via safeTopDp (which already accounts for the
    // cutout / system inset). The earlier "wrap in a 64dp
    // black box at the top of the screen" experiment broke the
    // overlay's touch dispatch: stacking a fixed-height Box
    // with .background() around the pill changed the way
    // Compose laid out the pointer-input nodes inside a
    // WindowManager-hosted ComposeView, and clicks stopped
    // firing entirely. The old simple structure (pill
    // positioned by safeTopDp, no surrounding fixed-size Box)
    // dispatched taps correctly, so we're back to that.
    //
    // blackZoneHeightDp is now ignored — the param stays for
    // API stability with existing callers but doesn't render
    // a wrapping background. If a "black bar at top" effect
    // is wanted in future, do it via a SEPARATE composable
    // adjacent to the pill, not as the pill's outer container.
    @Suppress("UNUSED_PARAMETER") val _bz = blackZoneHeightDp
    val pillBg = Color(0xCC000000)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = safeTopDp.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(fraction = widthFraction)
                .height(STRIP_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(STRIP_HEIGHT_DP.dp))
                .background(pillBg)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onPillTap,
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Tighter spacing when collapsed (rose + MYTHARA
            // need to fit a ~35% pill), wider when expanded
            // (matches the previous full-bar layout).
            horizontalArrangement = Arrangement.spacedBy(if (expanded) 33.dp else 12.dp),
        ) {
            // ROSE — always visible. Has its OWN clickable that
            // consumes the tap before the outer pill click sees
            // it, so a rose tap fires spin + onRoseTap (open
            // Mythara chat) WITHOUT toggling the pill's expand
            // state. Per user spec "clicking on the star in that
            // pill must open the Mythara chat screen from wherever
            // it is clicked, even if on another app."
            Box(
                modifier = Modifier
                    .scale(pulseScale.value)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            spinRose()
                            onRoseTap()
                        },
                    ),
            ) {
                RoseMarkSmallSpinning(
                    sizeDp = ROSE_DP,
                    rotationDeg = rotation.value,
                    accent = null,
                )
            }

            // MIDDLE CLUSTER —
            //   - EXPANDED: full chrome (clock + signal + M● + I●
            //     + Me + 🎙 + battery)
            //   - MINIMIZED (collapsed): condensed glance row of
            //     time + wifi + phone signal + MiniMax dot, per
            //     user spec "show time & MiniMax API status in
            //     the pill in minimized state". WiFi + phone are
            //     separate purple-neon icons so the user can
            //     tell at a glance which transport is live.
            if (showCluster) {
                // EXPANDED-state cluster (full chrome).
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text(
                        text = nowFmt,
                        color = MytharaColors.Fg,
                        // 11sp → 17sp (1.5× scale with the pill).
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    WifiIcon(active = network.isWifi, accent = SIGNAL_COLOR, sizeDp = 14)
                    PhoneSignalIcon(active = network.hasCellular, accent = SIGNAL_COLOR, sizeDp = 14)
                    // M / I health dots — wrapped in clickable
                    // boxes so tapping either one routes to the
                    // Usage screen.
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenUsage,
                        ),
                    ) {
                        HealthDot(label = "M", health = minimaxHealth, accent = MINIMAX_COLOR)
                    }
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenUsage,
                        ),
                    ) {
                        HealthDot(label = "I", health = imageHealth, accent = IMAGE_COLOR)
                    }
                    MeAvatar(onClick = onOpenAboutMe)
                    PttButton()
                    Text(
                        text = "${battery.percent}%",
                        color = MytharaColors.FgMute,
                        fontSize = 15.sp,
                    )
                    CircularBatteryIcon(percent = battery.percent, charging = battery.charging)
                }
            } else {
                // COLLAPSED-state glance row — visible in the
                // minimized pill. Skips Me / PTT / battery / I
                // (those need an expand to reach). Keeps the most
                // useful at-a-glance state: time + which networks
                // are live + MiniMax API status.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text(
                        text = nowFmt,
                        color = MytharaColors.Fg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    WifiIcon(active = network.isWifi, accent = SIGNAL_COLOR, sizeDp = 14)
                    // Two dummy black round spacers between WiFi
                    // and phone-signal icons. Visually represent
                    // the camera-cutout zone so the minimized
                    // pill reads as if it threads around the
                    // hole. Non-interactive — pure visual
                    // stand-ins.
                    CutoutSpacerDot()
                    CutoutSpacerDot()
                    PhoneSignalIcon(active = network.hasCellular, accent = SIGNAL_COLOR, sizeDp = 14)
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenUsage,
                        ),
                    ) {
                        HealthDot(label = "M", health = minimaxHealth, accent = MINIMAX_COLOR)
                    }
                }
            }

            // RIGHT: MYTHARA wordmark — always visible.
            Text(
                text = "MYTHARA",
                color = RoseGeometry.Lavender,
                // 10sp → 15sp + letter-spacing 1.5 → 2.25sp (1.5× scale).
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.25.sp,
            )
        }
    }
}

/** Small spinning rose painted inside the Dynamic Island. The
 *  spin animation state (rotation + scale-pulse) lives at the
 *  call-site in [MytharaStatusBar] so a SINGLE tap on the pill
 *  drives both the expand-collapse state machine AND the spin —
 *  if the rose owned its own tap handler too, we'd double-fire.
 *  Mirror of DynamicIsland's internal RoseMarkSpinning kept here
 *  so the pill is self-contained. */
@Composable
private fun RoseMarkSmallSpinning(
    sizeDp: Int,
    rotationDeg: Float,
    accent: Color?,
) {
    val petalPath = remember { Path() }
    val hexPath = remember { Path() }
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = (minOf(size.width, size.height) * 0.5f) /
            RoseGeometry.OuterRadiusSourceUnits
        rotate(degrees = rotationDeg, pivot = Offset(cx, cy)) {
            for (deg in RoseGeometry.BigPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.BigPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx, cy = cy, scale = scale,
                    out = petalPath,
                )
                drawPath(petalPath, color = accent ?: RoseGeometry.Purple)
            }
            for (deg in RoseGeometry.SmallPetalAngles) {
                RoseGeometry.petalPath(
                    diamond = RoseGeometry.SmallPetal,
                    angleDegrees = deg.toFloat(),
                    cx = cx, cy = cy, scale = scale,
                    out = petalPath,
                )
                drawPath(petalPath, color = RoseGeometry.Lavender)
            }
            RoseGeometry.hexPath(cx, cy, scale, hexPath)
            drawPath(hexPath, color = RoseGeometry.Cyan)
        }
    }
}

/** PTT (push-to-talk) mic button. Tap → broadcasts an ACTION_ASSIST
 *  intent which Mythara's MainActivity catches and routes into the
 *  voice path (same entry-point Pixel Buds touch-and-hold + the
 *  squeeze gesture use). Small filled circle with a 🎙 glyph so it
 *  reads as a mic at status-bar scale without needing the full
 *  MicButton's recording-state UX (that lives in the chat composer). */
@Composable
private fun PttButton() {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .size(PTT_BUTTON_DP.dp)
            .clip(CircleShape)
            .background(MytharaColors.Charple.copy(alpha = 0.85f))
            .clickable {
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_ASSIST).apply {
                        `package` = ctx.packageName
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🎙",
            // 10sp → 15sp (1.5× scale).
            fontSize = 15.sp,
        )
    }
}

/**
 * Tiny circular avatar of the user, sourced from
 * [MeProfileStore.Profile.photoPath]. Tapping it navigates to
 * AboutMe (where the user can change the photo, set a display name,
 * link cross-app aliases). When no photo is set, falls back to the
 * initial letter of the user's display name in lavender on a dim
 * circle — same approach as the people-list initial-letter avatar.
 *
 * The bitmap is decoded once and cached against the file's
 * `updatedAtMs` so a photo change repaints, but recompositions
 * during scrolling don't re-decode.
 */
@Composable
private fun MeAvatar(onClick: () -> Unit) {
    val ctx = LocalContext.current
    // Pull the MeProfileStore via Hilt's EntryPointAccessors —
    // status bar is a plain composable, not a HiltViewModel, so we
    // grab the singleton directly.
    val store = remember {
        EntryPointAccessors.fromApplication(
            ctx.applicationContext,
            MytharaStatusBarEntryPoint::class.java,
        ).meProfileStore()
    }
    val profile by produceState(initialValue = MeProfileStore.Profile(), key1 = store) {
        store.observe().collect { value = it }
    }
    val bmp by produceState(
        initialValue = null as androidx.compose.ui.graphics.ImageBitmap?,
        key1 = profile.photoPath, key2 = profile.updatedAtMs,
    ) {
        value = withContext(Dispatchers.IO) {
            val path = profile.photoPath
            if (path.isBlank()) return@withContext null
            runCatching {
                BitmapFactory.decodeFile(File(path).absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(ME_AVATAR_DP.dp)
            .clip(CircleShape)
            .background(MytharaColors.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "open About Me",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(ME_AVATAR_DP.dp).clip(CircleShape),
            )
        } else {
            val initial = profile.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "Me"
            Text(
                text = initial,
                color = RoseGeometry.Lavender,
                // 9sp → 13.5sp (1.5× scale).
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Hilt entry point for plain composables (no ViewModel) — lets us
 * pull the [MeProfileStore] singleton without breaking out of the
 * Hilt graph.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface MytharaStatusBarEntryPoint {
    fun meProfileStore(): MeProfileStore
}

/**
 * Tiny rendition of the Mythara rose for the status bar centre.
 * Renders the same 10-petal geometry [RoseGeometry] uses for the
 * popup amulet + watch face + wallpaper, just at status-bar scale
 * (~14dp). No animation — the wallpaper + amulet already carry
 * the brand's living motion; this is a static badge so the strip
 * stays visually quiet.
 */
@Composable
private fun RoseMarkSmall(sizeDp: Int) {
    val petalPath = remember { Path() }
    val hexPath = remember { Path() }
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Rose source viewport is 108×108, outermost petal tips at
        // |30| source units. Fit the chosen size into the canvas.
        val scale = (minOf(size.width, size.height) * 0.5f) /
            RoseGeometry.OuterRadiusSourceUnits
        for (deg in RoseGeometry.BigPetalAngles) {
            RoseGeometry.petalPath(
                diamond = RoseGeometry.BigPetal,
                angleDegrees = deg.toFloat(),
                cx = cx, cy = cy, scale = scale,
                out = petalPath,
            )
            drawPath(petalPath, color = RoseGeometry.Purple)
        }
        for (deg in RoseGeometry.SmallPetalAngles) {
            RoseGeometry.petalPath(
                diamond = RoseGeometry.SmallPetal,
                angleDegrees = deg.toFloat(),
                cx = cx, cy = cy, scale = scale,
                out = petalPath,
            )
            drawPath(petalPath, color = RoseGeometry.Lavender)
        }
        RoseGeometry.hexPath(cx, cy, scale, hexPath)
        drawPath(hexPath, color = RoseGeometry.Cyan)
    }
}

/**
 * Circular battery icon — replaces the rectangular glyph.
 *   - Background ring: SurfaceHigh, full circle
 *   - Foreground arc: fills clockwise from 12 o'clock proportional
 *     to battery percent, coloured by state (green/charging,
 *     muted/normal, charple/critical)
 *   - Centre: ⚡ when charging, otherwise empty (the percent
 *     number lives next to the icon already)
 */
@Composable
private fun CircularBatteryIcon(percent: Int, charging: Boolean) {
    val accent = when {
        percent <= 15 -> MytharaColors.Charple
        charging -> MytharaColors.Bok
        else -> MytharaColors.FgMute
    }
    Box(
        modifier = Modifier.size(BATTERY_ICON_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(BATTERY_ICON_DP.dp)) {
            val stroke = 1.5.dp.toPx()
            val padding = stroke / 2f
            val rect = androidx.compose.ui.geometry.Rect(
                left = padding,
                top = padding,
                right = size.width - padding,
                bottom = size.height - padding,
            )
            // Background ring — full circle, dim.
            drawArc(
                color = MytharaColors.SurfaceHigh,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = stroke),
            )
            // Foreground arc — proportional, accent-coloured.
            val sweep = (percent.coerceIn(0, 100) / 100f) * 360f
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = stroke),
            )
        }
        if (charging) {
            Text(
                text = "⚡",
                color = accent,
                // 9sp → 13.5sp (1.5× scale with the rest of the pill).
                fontSize = 13.5.sp,
            )
        }
    }
}

/**
 * Four cell-bar-style dots, lit from the left up to [litCount].
 * Lit dots glow in [accent]; unlit dots are dim grey. A subtle
 * shadow gives the lit ones the "neon glow" the user asked for
 * without needing a real blur pass.
 */
/**
 * Solid-black round spacer placed between the WiFi and phone-
 * signal icons in the minimized pill. Visually represents the
 * camera pinhole's spot — the pill reads as if it threads
 * AROUND the cutout, even though it actually sits BELOW the
 * cutout. Pure visual stand-in: not clickable, no glow, no
 * state. Size matches the other minimized-state icons so it
 * lines up cleanly.
 */
@Composable
private fun CutoutSpacerDot(sizeDp: Int = 14) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color.Black),
    )
}

/**
 * WiFi-style fan icon — three nested arcs + a dot at the base.
 * Glows in purple [accent] when connected, fades to grey
 * otherwise. Used in the minimized pill alongside
 * [PhoneSignalIcon] so the user can tell at a glance "do I
 * have WiFi" vs "do I have cellular" — replaces the older
 * generic 4-dot SignalDots cluster which conflated both.
 */
@Composable
private fun WifiIcon(active: Boolean, accent: Color, sizeDp: Int = 16) {
    val color = if (active) accent else GREY
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .then(if (active) Modifier.shadow(elevation = 5.dp, shape = CircleShape, ambientColor = accent, spotColor = accent) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            // Anchor the arc center near the BOTTOM so the fan
            // opens upward like a standard WiFi glyph.
            val cy = h * 0.85f
            val stroke = w * 0.085f
            val s = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            // Three concentric arcs, 90° wide opening upward.
            for ((i, r) in listOf(w * 0.85f, w * 0.55f, w * 0.28f).withIndex()) {
                drawArc(
                    color = color,
                    startAngle = 225f,        // top-left
                    sweepAngle = 90f,         // span across to top-right
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(cx - r, cy - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = s,
                )
                // Suppress unused i — purely structural.
                @Suppress("UNUSED_VARIABLE") val _i = i
            }
            // Solid dot at the apex (bottom of the fan).
            drawCircle(
                color = color,
                radius = w * 0.075f,
                center = androidx.compose.ui.geometry.Offset(cx, cy),
            )
        }
    }
}

/**
 * Phone-signal icon — 4 ascending bars. Glows purple when the
 * device has a cellular link (SIM live, any cellular network
 * available); fades to grey otherwise. Since we don't ask for
 * READ_PHONE_STATE, we can't show true tower-bar strength —
 * all four bars are filled when the connection exists,
 * dimmed when not.
 */
@Composable
private fun PhoneSignalIcon(active: Boolean, accent: Color, sizeDp: Int = 16) {
    val color = if (active) accent else GREY
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .then(if (active) Modifier.shadow(elevation = 5.dp, shape = CircleShape, ambientColor = accent, spotColor = accent) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val w = size.width
            val h = size.height
            val barWidth = w * 0.15f
            val gap = w * 0.07f
            val heights = listOf(0.30f, 0.50f, 0.72f, 0.92f).map { it * h }
            for ((i, hgt) in heights.withIndex()) {
                val left = i * (barWidth + gap) + (w - 4 * barWidth - 3 * gap) / 2f
                val top = h - hgt
                drawRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(barWidth, hgt),
                )
            }
        }
    }
}

@Composable
private fun SignalDots(litCount: Int, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 2dp → 3dp + 5dp → 8dp (1.5× scale with the pill).
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(SIGNAL_BAR_COUNT) { i ->
            val lit = i < litCount
            Dot(
                accent = if (lit) accent else GREY,
                glow = lit,
                sizeDp = 8,
            )
        }
    }
}

/**
 * Single API-health dot with a glyph tag (e.g. "M" for MiniMax,
 * "I" for Image) and a coloured circle that glows when Online,
 * goes grey when Offline, and goes red when there's been a recent
 * Error.
 */
@Composable
private fun HealthDot(label: String, health: ApiStatusStore.ApiHealth, accent: Color) {
    val (color, glow) = when (health) {
        ApiStatusStore.ApiHealth.Online -> accent to true
        ApiStatusStore.ApiHealth.Offline -> GREY to false
        ApiStatusStore.ApiHealth.Error -> ERROR_COLOR to true
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = label,
            color = color,
            // 9sp → 13.5sp + dot 7 → 10 (1.5× scale).
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
        )
        Dot(accent = color, glow = glow, sizeDp = 10)
    }
}

@Composable
private fun Dot(accent: Color, glow: Boolean, sizeDp: Int) {
    Box(
        modifier = Modifier
            .then(if (glow) Modifier.shadow(elevation = 4.dp, shape = androidx.compose.foundation.shape.CircleShape, ambientColor = accent, spotColor = accent) else Modifier)
            .size(sizeDp.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(accent),
    )
}

/** Pill height per user spec — 45 dp (1.5× the original 30 dp).
 *  Bigger touch target + better fits the expanded cluster
 *  without crowding the glyphs. Inner content sizes scale by
 *  the same 1.5× factor so the visual proportions stay right. */
internal const val STRIP_HEIGHT_DP = 45

/** Rose icon size inside the pill. 22 dp → 33 dp (1.5×). */
private const val ROSE_DP = 33

/** PTT mic button diameter. 18 dp → 27 dp (1.5×). Same scale
 *  as the API health dots + Me avatar so the cluster reads
 *  uniformly. */
private const val PTT_BUTTON_DP = 27

/** Top padding from the screen top to the pill's top edge —
 *  user-specified flat value. 54 → 60 per user request. */
private const val TOP_PADDING_DP = 60

/** Width fraction of the pill when collapsed (rose + MYTHARA
 *  only, no status cluster). 0.32 ≈ 1/3 of the screen — wide
 *  enough to fit the rose + the MYTHARA wordmark with the
 *  small inter-element padding, narrow enough that the pill
 *  reads as a discrete floating island. */
/** Collapsed-state pill width. Bumped 0.32 → 0.62 because the
 *  minimized layout now shows time + wifi + phone + M● API
 *  status alongside rose + MYTHARA (per user spec "show time
 *  & MiniMax API status in the pill in minimized state").
 *  The cluster doesn't fit at the old 32% width. */
private const val COLLAPSED_WIDTH_FRACTION = 0.62f

/** Expand-collapse animation duration. 350ms is the sweet spot
 *  — slow enough that the user perceives the growth as
 *  intentional, fast enough that it doesn't feel sluggish. */
private const val EXPAND_DURATION_MS = 350

/** Auto-collapse delay. The user taps to glance at the status
 *  chrome; after this many ms with no further interaction we
 *  shrink back to the small pill on our own. 5s per user spec
 *  ("auto close in 5 seconds if no action"). */
private const val AUTO_COLLAPSE_MS = 5_000L

/** Black-zone height (dp) used by the overlay variant.
 *
 *  Why 64 and not the 40 we had before: the OS's own system
 *  status bar (clock/signal/battery in white text, rendered
 *  by SystemUI) occupies roughly y=0 to y=24dp on most
 *  modern Android devices. SystemUI's window sits ABOVE our
 *  TYPE_APPLICATION_OVERLAY in input-dispatch order, so any
 *  touch in y=0-24dp is consumed by SystemUI before it can
 *  reach our overlay's ComposeView — which is exactly why
 *  the previous version's overlay taps appeared dead while
 *  the in-app pill (which doesn't share that zone) worked
 *  fine.
 *
 *  Layout now (64dp tall):
 *    - y=0-24dp:   black bg only, covers the system status
 *                   bar zone + the camera cutout (which
 *                   sits inside y=12-26dp on Pixel 10 Pro).
 *                   Non-interactive — anything here gets
 *                   eaten by SystemUI anyway.
 *    - y=24-58dp:  the pill itself (~34dp band, contains
 *                   the 30dp pill with a few dp of breathing
 *                   room) — BELOW the system status bar,
 *                   so taps land on Compose.
 *    - y=58-64dp:  small bottom padding so the pill doesn't
 *                   kiss the black-zone edge.
 *
 *  In mm: 64 dp at 160 dpi base = 10.16 mm.
 *  In px on Pixel 10 Pro (408 dpi): 163 px. */
const val OVERLAY_BLACK_ZONE_HEIGHT_DP = 64

/** Top padding inside the black zone — pushes the pill BELOW
 *  the system status bar zone so SystemUI doesn't intercept
 *  the touch. Matches WindowInsets.statusBars.top on most
 *  devices (24dp), with 4dp of extra clearance. */
const val OVERLAY_PILL_TOP_INSET_DP = 28
private const val SIGNAL_BAR_COUNT = 4
// Both scaled 1.5× alongside ROSE_DP / PTT_BUTTON_DP so the
// cluster proportions stay consistent inside the 45 dp pill.
private const val BATTERY_ICON_DP = 24       // was 16
private const val ME_AVATAR_DP = 27          // was 18

/** Last-resort top padding when neither displayCutout nor
 *  statusBars insets report anything (launcher mode + non-cutout
 *  device). Lowered from 40dp → 24dp so non-cutout devices don't
 *  get pushed down unnecessarily; on Pixel-class devices the real
 *  cutout rect drives the top now (see [rememberCutoutRect]). */
private const val PIXEL_PINHOLE_FLOOR_DP = 24

// Palette for the new status indicators. Purple for signal so it
// reads as a Mythara accent (matches the rose petal palette).
// Blue for MiniMax = its brand colour. Yellow = Mythara's mustard
// accent, used for the secondary AI service.
private val SIGNAL_COLOR = Color(0xFF9B86FF)   // lavender / Mythara purple
private val MINIMAX_COLOR = Color(0xFF3D9CFF)  // bright cyan-blue
private val IMAGE_COLOR = Color(0xFFFFB000)    // mustard-yellow
private val GREY = Color(0xFF555555)
private val ERROR_COLOR = Color(0xFFEB4268)    // red-pink, pops against dark bg

private fun formatNow(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private data class BatterySnapshot(val percent: Int, val charging: Boolean)

private fun readBattery(ctx: Context, intent: Intent? = null): BatterySnapshot {
    val sticky = intent ?: ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
    val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        ?: BatteryManager.BATTERY_STATUS_UNKNOWN
    val charging = plugged != 0 ||
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    return BatterySnapshot(percent = pct, charging = charging)
}

/** Snapshot of the device's network state for the status-bar dots.
 *  `bars` is in [0..4]:
 *     0 = no internet
 *     2 = cellular validated (we don't check signal strength
 *         without READ_PHONE_STATE so cellular always reports 2)
 *     4 = wifi validated
 *     3 = ethernet / other validated
 *  Distinct from raw `connected` so the renderer can fade the
 *  unlit dots while still acknowledging "you're online". */
private data class NetworkSnapshot(
    val connected: Boolean,
    val bars: Int,
    /** True when the currently-active validated transport is
     *  WiFi. Drives the minimized-pill's WifiIcon glow. */
    val isWifi: Boolean = false,
    /** True when the device has a working cellular link (either
     *  primary or secondary). We can't read true tower-bars
     *  without READ_PHONE_STATE; this is a binary connected/
     *  not signal that drives the PhoneSignalIcon's glow. */
    val hasCellular: Boolean = false,
)

private fun readNetwork(ctx: Context): NetworkSnapshot {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return NetworkSnapshot(connected = false, bars = 0)
    val active = cm.activeNetwork ?: return NetworkSnapshot(connected = false, bars = 0)
    val caps = cm.getNetworkCapabilities(active) ?: return NetworkSnapshot(connected = false, bars = 0)
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    if (!validated) return NetworkSnapshot(connected = false, bars = 0)
    val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val isCellularActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    // Also check whether ANY underlying network has cellular —
    // a Pixel on WiFi typically still has cellular available as a
    // backup, and the user expects the phone-signal icon to glow
    // when the SIM is live, not only when cellular is the active
    // transport. Walk allNetworks looking for a validated cellular.
    val hasCellularAnywhere = isCellularActive || cm.allNetworks.any { net ->
        val c = cm.getNetworkCapabilities(net) ?: return@any false
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    val bars = when {
        isWifi -> 4
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
        isCellularActive -> 2
        else -> 1
    }
    return NetworkSnapshot(
        connected = true,
        bars = bars,
        isWifi = isWifi,
        hasCellular = hasCellularAnywhere,
    )
}
