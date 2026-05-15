package com.mythara.wear.resonance

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.mythara.wear.WearPaths
import com.mythara.wear.sendBytesToPhone
import kotlinx.coroutines.delay

private val RED = Color(0xFFEB4268)
private val AMBER = Color(0xFFF5B033)
private val GREEN = Color(0xFF68FFD6)
private val BLUE = Color(0xFF00A4FF)
private val BUTTON_BORDER = Color.White.copy(alpha = 0.20f)
private val DOT_OFF = Color.White.copy(alpha = 0.18f)
private val DOT_ON = Color.White.copy(alpha = 0.85f)

/** Per-tap auto-commit window — a lone tap older than this resets. */
private const val PARTIAL_TIMEOUT_MS = 1200L

/** How long the per-tap flash lingers (ms). */
private const val FLASH_MS = 140L

/**
 * The Resonance discreet input pad. Renders as a 2×2 grid of four 64 dp
 * coloured circle buttons in fixed corners (eyes-free positions): TL
 * red, TR amber, BL green, BR blue. The user taps two buttons in
 * sequence; the pair auto-commits as a combo and ships to the phone
 * over [WearPaths.RESONANCE_COMBO]. Each tap pulses a short haptic and
 * a brief colour flash. A 1.2 s timeout discards a lone tap with a
 * cancel buzz; an unknown combo buzzes an error and resets.
 *
 * Designed to overlay [com.mythara.wear.MainActivity]'s `PttScreen`
 * when [ResonanceStore.isActive] is true — the mic button stays
 * accessible above so the user can still talk.
 */
@Composable
fun ResonancePad(
    modifier: Modifier = Modifier,
    /**
     * Called locally when the user commits the Start-PTT combo (B,B).
     * Fires the host PttScreen's existing speech-recognizer flow without
     * needing a phone round-trip. The combo is still shipped to the
     * phone for the audit trail.
     */
    onStartPtt: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val taps = remember { mutableStateListOf<ComboCodec.Color>() }
    var partialStartedAt by remember { mutableStateOf(0L) }

    // Per-button "I just got tapped" timestamp for the flash effect.
    val flashAt = remember { mutableStateListOf(0L, 0L, 0L, 0L) }

    // Lone-tap timeout: if a 1-tap state lingers past PARTIAL_TIMEOUT_MS
    // we cancel it (double-buzz). Re-keyed on partialStartedAt so a
    // fresh first tap restarts the clock cleanly.
    LaunchedEffect(partialStartedAt) {
        if (partialStartedAt == 0L) return@LaunchedEffect
        delay(PARTIAL_TIMEOUT_MS)
        if (taps.size == 1 && partialStartedAt != 0L) {
            taps.clear()
            partialStartedAt = 0L
            cancelBuzz(ctx)
        }
    }

    fun onTap(color: ComboCodec.Color) {
        // First tap → start the 1.2s window.
        if (taps.isEmpty()) {
            partialStartedAt = System.currentTimeMillis()
        }
        flashAt[color.id] = System.currentTimeMillis()
        tapBuzz(ctx)
        taps.add(color)
        // Auto-commit at 2 taps.
        if (taps.size >= 2) {
            val c1 = taps[0]
            val c2 = taps[1]
            taps.clear()
            partialStartedAt = 0L
            commitCombo(ctx, c1, c2, onStartPtt)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PadButton(RED, isFlashing(flashAt[0])) { onTap(ComboCodec.Color.Red) }
            PadButton(AMBER, isFlashing(flashAt[1])) { onTap(ComboCodec.Color.Amber) }
        }
        Spacer(Modifier.height(6.dp))
        // 2-dot progress strip — fills as taps accumulate.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressDot(taps.size >= 1)
            ProgressDot(taps.size >= 2)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PadButton(GREEN, isFlashing(flashAt[2])) { onTap(ComboCodec.Color.Green) }
            PadButton(BLUE, isFlashing(flashAt[3])) { onTap(ComboCodec.Color.Blue) }
        }
    }
}

@Composable
private fun PadButton(color: Color, flashing: Boolean, onClick: () -> Unit) {
    val alpha by animateFloatAsState(
        if (flashing) 1f else 0.78f,
        tween(if (flashing) 50 else 220),
        label = "padButtonAlpha",
    )
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
            .border(1.5.dp, BUTTON_BORDER, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ProgressDot(filled: Boolean) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (filled) DOT_ON else DOT_OFF),
    )
}

@Composable
private fun isFlashing(at: Long): Boolean {
    val now = remember { System.currentTimeMillis() }
    return at != 0L && now - at < FLASH_MS
}

/**
 * Compact "you tapped a button" pulse — short and tight so an
 * accidental brush is still felt clearly but doesn't read as a confirm.
 */
private fun tapBuzz(ctx: Context) {
    val v = vibrator(ctx) ?: return
    runCatching {
        v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/**
 * "You almost did something" — fired when a lone tap times out. Two
 * short pulses so the user knows the combo was abandoned (vs. a single
 * pulse which would feel like a successful confirm).
 */
private fun cancelBuzz(ctx: Context) {
    val v = vibrator(ctx) ?: return
    runCatching {
        v.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 30), -1),
        )
    }
}

/**
 * Crisp double-pulse — combo accepted and on its way to the phone.
 * Distinct from cancelBuzz so the haptic vocabulary is unambiguous
 * eyes-free.
 */
private fun confirmBuzz(ctx: Context) {
    val v = vibrator(ctx) ?: return
    runCatching {
        v.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 45, 60, 45), -1),
        )
    }
}

/** Long single buzz — unknown combo, nothing sent. */
private fun errorBuzz(ctx: Context) {
    val v = vibrator(ctx) ?: return
    runCatching {
        v.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

private fun vibrator(ctx: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

private fun commitCombo(
    ctx: Context,
    c1: ComboCodec.Color,
    c2: ComboCodec.Color,
    onStartPtt: (() -> Unit)? = null,
) {
    val code = ComboCodec.encode(c1, c2)
    if (!ComboCodec.isValid(code)) {
        errorBuzz(ctx)
        return
    }
    confirmBuzz(ctx)
    // Start-PTT is the one combo handled locally on the watch — fire
    // the host PttScreen's recognizer flow immediately so the user
    // doesn't pay a watch->phone->watch round-trip just to start
    // listening. We still ship the combo to the phone so the audit log
    // / future analytics see it.
    if (code == ComboCodec.encode(ComboCodec.Color.Blue, ComboCodec.Color.Blue)) {
        onStartPtt?.invoke()
    }
    val payload = "$code|${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
    sendBytesToPhone(ctx, WearPaths.RESONANCE_COMBO, payload)
}
