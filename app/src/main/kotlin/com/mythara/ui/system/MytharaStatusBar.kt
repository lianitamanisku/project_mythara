package com.mythara.ui.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.delay
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
fun MytharaStatusBar(modifier: Modifier = Modifier) {
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

    // Pixel-tuned safe-area padding. The system reports BOTH the
    // statusBars inset (height of the system status bar zone, 0 in
    // launcher mode where we hide it) AND the displayCutout inset
    // (height of the camera hole-punch area). On Pixel 10 Pro the
    // pinhole centre sits ~36-40dp from the top edge — we take the
    // MAX of (displayCutout.top, statusBars.top, PIXEL_FLOOR) so
    // any non-cutout-reporting Pixel class still gets enough room.
    val cutoutTopDp = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val statusTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val safeTopDp = maxOf(cutoutTopDp.value, statusTopDp.value, PIXEL_PINHOLE_FLOOR_DP.toFloat())

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Top padding = max of system insets + Pixel-class
            // floor. This puts the strip cleanly below the camera
            // pinhole on every Pixel device shipped 2023+ (10 Pro,
            // 9 Pro, 9 Pro Fold outer + inner) without needing
            // per-device hardcodes.
            .padding(top = safeTopDp.dp)
            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .background(MytharaColors.Bg)
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .height(STRIP_HEIGHT_DP.dp),
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

        // Centre cluster: small rose mark + "MYTHARA" text. The
        // strip itself is positioned BELOW the pinhole vertical
        // line via the 22dp top push above, so this content sits
        // CLEAR of the camera cutout rather than under it.
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoseMarkSmall(sizeDp = 14)
            Text(
                text = "MYTHARA",
                color = RoseGeometry.Lavender,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
        }

        // Right cluster: API health dots + battery percent + circular
        // battery icon. Tight cluster so it stays inside the strip.
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
    }
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

internal const val STRIP_HEIGHT_DP = 32
private const val SIGNAL_BAR_COUNT = 4
private const val BATTERY_ICON_DP = 16

/** Minimum top padding for Pixel-class devices when the system
 *  doesn't report a useful inset (launcher mode hides statusBars
 *  to 0; some devices under-report displayCutout). 40dp clears
 *  the centred pinhole on Pixel 10 Pro / 9 Pro / 9 Pro Fold
 *  comfortably with a few dp of breathing room. */
private const val PIXEL_PINHOLE_FLOOR_DP = 40

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
