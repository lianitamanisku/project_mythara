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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mythara's own status strip rendered at the top of every screen
 * since the system status bar is hidden in launcher mode.
 *
 * Layout (left → right):
 *   - Clock (HH:mm, monospace-ish)
 *   - 4 signal-strength dots (purple, glowing) — lit count tracks
 *     network connectivity (offline → all grey; cellular without
 *     wifi → 2-3 lit; full wifi → all 4 lit)
 *   - MiniMax API status dot (blue glowing when online, grey when
 *     offline / no key, red on recent error)
 *   - Image API status dot (yellow glowing same semantics)
 *   - Battery percent + glyph
 *
 * Respects the system status-bar inset via windowInsetsPadding so
 * the strip never clips the camera notch / hole-punch on devices
 * that have one. The whole strip is clipped with a rounded bottom
 * to avoid a hard edge against the chat content beneath.
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

    // Top padding strategy — strip pinned to the very TOP of the
    // screen per user request ("move it all the way up"). The
    // previous version centered the strip's vertical midpoint on
    // the cutout, which still felt low because the strip's box
    // had to extend ABOVE that midpoint by STRIP_HEIGHT/2 — so
    // when the cutout's center was near 20dp the strip's top edge
    // landed at 0dp anyway, giving the illusion of "below" because
    // the bg fill stopped at strip bottom (~88dp on the 3x pill).
    //
    // New rule: safeTopDp is just 0 when a cutout is present (the
    // pill itself wraps around the hole). On non-cutout devices
    // we still respect the system inset so we don't tuck under
    // the system status bar zone.
    val cutoutTopDp = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val statusTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val safeTopDp = when {
        cutout != null -> 0f
        cutoutTopDp.value > 0f -> cutoutTopDp.value
        statusTopDp.value > 0f -> statusTopDp.value
        else -> PIXEL_PINHOLE_FLOOR_DP.toFloat()
    }

    // Two-layer Box so the wrapping pills (much larger than the
    // strip's own visible bg height) can extend below it without
    // being clipped by the rounded-corner mask:
    //   - Outer Box: positioning + size, NO clip
    //   - Inner Box: rounded background, clipped, sized to
    //     STRIP_HEIGHT
    //   - Foreground content (clusters + DI): rendered on top of
    //     the bg layer, free to overflow visually
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = safeTopDp.dp),
    ) {
        // Background pad — clipped + filled. This is what the
        // user perceives as "the status bar shape".
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                .background(MytharaColors.Bg),
        )
        // Foreground content layer — sized to the strip's bg
        // height + horizontal padding for the clusters, but does
        // NOT clip its children, so the 3x DynamicIsland pill is
        // free to render above + below the strip's visible bg.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT_DP.dp)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
        // Left cluster: clock + signal-strength dots.
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = nowFmt,
                color = MytharaColors.Fg,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            SignalDots(litCount = network.bars, accent = SIGNAL_COLOR)
        }

        // Centre: iPhone Dynamic Island-style pill — wraps around
        // the camera cutout dock-bar style when one is present.
        // Idles as the rose + MYTHARA wordmark and morphs to
        // surface momentary insights pushed into [DynamicIslandSink]
        // (agent thinking, fresh insight from phone, next reminder
        // countdown, HR alert). Tap → brief animation + clears
        // the active insight. Bouncing-dock entrance plays on
        // mount and on every fold-posture flip.
        DynamicIsland(
            modifier = Modifier.align(Alignment.Center),
            cutout = cutout,
        )

        // Right cluster: Me avatar (taps → AboutMe) + API health
        // dots + battery percent + circular battery icon.
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MeAvatar(onClick = onOpenAboutMe)
            HealthDot(label = "M", health = minimaxHealth, accent = MINIMAX_COLOR)
            HealthDot(label = "I", health = imageHealth, accent = IMAGE_COLOR)
            Spacer(Modifier.width(2.dp))
            Text(
                text = "${battery.percent}%",
                color = MytharaColors.FgMute,
                fontSize = 11.sp,
            )
            CircularBatteryIcon(percent = battery.percent, charging = battery.charging)
        }
        } // end inner foreground Box
    } // end outer positioning Box
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
                fontSize = 9.sp,
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
                fontSize = 9.sp,
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
@Composable
private fun SignalDots(litCount: Int, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(SIGNAL_BAR_COUNT) { i ->
            val lit = i < litCount
            Dot(
                accent = if (lit) accent else GREY,
                glow = lit,
                sizeDp = 5,
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        Dot(accent = color, glow = glow, sizeDp = 7)
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

/** Status-strip height. Sized to comfortably contain the
 *  Dynamic Island pill (36dp tall per Pixel cutout dimensions
 *  documented in DynamicIsland.kt) plus a few dp of vertical
 *  breathing room so the pill doesn't kiss the strip's clipping
 *  edges. */
internal const val STRIP_HEIGHT_DP = 44
private const val SIGNAL_BAR_COUNT = 4
private const val BATTERY_ICON_DP = 16
private const val ME_AVATAR_DP = 18

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
private data class NetworkSnapshot(val connected: Boolean, val bars: Int)

private fun readNetwork(ctx: Context): NetworkSnapshot {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return NetworkSnapshot(connected = false, bars = 0)
    val active = cm.activeNetwork ?: return NetworkSnapshot(connected = false, bars = 0)
    val caps = cm.getNetworkCapabilities(active) ?: return NetworkSnapshot(connected = false, bars = 0)
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    if (!validated) return NetworkSnapshot(connected = false, bars = 0)
    val bars = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 4
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        else -> 1
    }
    return NetworkSnapshot(connected = true, bars = bars)
}
