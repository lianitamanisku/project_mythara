package com.mythara.ui.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.mic.Tts
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Exposes the live TTS speaking state to [FaceScreen] — the only
 * thing the face avatar needs to know about. Lives in its own tiny
 * ViewModel so the face is a standalone destination, independent of
 * the chat surface.
 */
@HiltViewModel
class FaceViewModel @Inject constructor(tts: Tts) : ViewModel() {
    val speaking: StateFlow<Boolean> =
        tts.speaking.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}

/**
 * The Mythara face — an alternate, full-screen interface to the
 * agent. A wireframe-mesh human head that idly bobs and sways, blinks
 * on its own, and animates its mouth open/closed while Lumi is
 * speaking (driven by [Tts.speaking]). Pure Compose Canvas — no 3D
 * engine — styled to match the tactical/geometric Mythara aesthetic.
 */
@Composable
fun FaceScreen(onBack: () -> Unit, vm: FaceViewModel = hiltViewModel()) {
    val speaking by vm.speaking.collectAsState()

    val infinite = rememberInfiniteTransition(label = "face")
    // Slow idle head bob + sway so the face never sits perfectly still.
    val bob by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val sway by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5400, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway",
    )
    // Fast phase driving the talking mouth — only applied while speaking.
    val mouthPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(280, easing = LinearEasing), RepeatMode.Restart),
        label = "mouth",
    )
    // Self-driven blink — closes the eyes every couple of seconds.
    val eyeOpen = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2200L, 5200L))
            eyeOpen.animateTo(0.08f, tween(90))
            eyeOpen.animateTo(1f, tween(140))
        }
    }

    val mouthOpen = if (speaking) {
        abs(sin(mouthPhase.toDouble())).toFloat() * 0.85f + 0.15f
    } else {
        0.05f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawMeshFace(
                bob = bob,
                sway = sway,
                eyeOpen = eyeOpen.value,
                mouthOpen = mouthOpen,
                speaking = speaking,
            )
        }

        Text(
            text = "‹ chat",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.Charple),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .clickable(onClick = onBack),
        )
        Text(
            text = if (speaking) "● speaking" else "○ idle",
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (speaking) MytharaColors.Bok else MytharaColors.FgDim,
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )
    }
}

private fun DrawScope.drawMeshFace(
    bob: Float,
    sway: Float,
    eyeOpen: Float,
    mouthOpen: Float,
    speaking: Boolean,
) {
    val purple = MytharaColors.Charple
    val cyan = MytharaColors.Bok
    val cx = size.width / 2f + sway * 12f
    val cy = size.height / 2f + bob * 9f
    val r = minOf(size.width, size.height) * 0.30f

    // ---- wireframe head: outline + latitude + longitude rings ----
    drawCircle(
        color = purple.copy(alpha = 0.55f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = 2.5f),
    )
    // latitude lines — horizontal ellipses, narrowing toward the poles
    for (i in -2..2) {
        if (i == 0) continue
        val frac = i / 3f
        val yy = cy + r * frac
        val halfW = sqrt((1f - frac.pow(2)).coerceAtLeast(0f)) * r
        drawOval(
            color = purple.copy(alpha = 0.26f),
            topLeft = Offset(cx - halfW, yy - 7f),
            size = Size(halfW * 2f, 14f),
            style = Stroke(width = 1.4f),
        )
    }
    // longitude lines — vertical ellipses of varying width
    for (w in listOf(0.30f, 0.62f, 1.0f)) {
        val halfW = r * w
        drawOval(
            color = purple.copy(alpha = 0.22f),
            topLeft = Offset(cx - halfW, cy - r),
            size = Size(halfW * 2f, r * 2f),
            style = Stroke(width = 1.4f),
        )
    }

    // ---- eyes — glowing cyan, blink via eyeOpen ----
    val eyeY = cy - r * 0.16f
    val eyeDx = r * 0.40f
    val eyeRx = r * 0.15f
    val eyeRy = (r * 0.13f) * eyeOpen + 1f
    for (sign in listOf(-1f, 1f)) {
        val ex = cx + sign * eyeDx
        drawOval(
            color = cyan.copy(alpha = 0.22f),
            topLeft = Offset(ex - eyeRx * 1.7f, eyeY - eyeRy * 1.7f),
            size = Size(eyeRx * 3.4f, eyeRy * 3.4f),
        )
        drawOval(
            color = cyan,
            topLeft = Offset(ex - eyeRx, eyeY - eyeRy),
            size = Size(eyeRx * 2f, eyeRy * 2f),
        )
    }

    // ---- nose — a short mesh line ----
    drawLine(
        color = purple.copy(alpha = 0.4f),
        start = Offset(cx, eyeY + r * 0.06f),
        end = Offset(cx, cy + r * 0.18f),
        strokeWidth = 1.6f,
    )

    // ---- mouth — opens/closes while speaking ----
    val mouthY = cy + r * 0.44f
    val mouthW = r * 0.52f
    val mouthH = (r * 0.34f) * mouthOpen + 3f
    drawOval(
        color = (if (speaking) cyan else purple).copy(alpha = 0.20f),
        topLeft = Offset(cx - mouthW * 0.62f, mouthY - mouthH * 0.9f),
        size = Size(mouthW * 1.24f, mouthH * 1.8f),
    )
    drawOval(
        color = if (speaking) cyan else purple.copy(alpha = 0.75f),
        topLeft = Offset(cx - mouthW / 2f, mouthY - mouthH / 2f),
        size = Size(mouthW, mouthH),
        style = Stroke(width = 2.6f),
    )
}
