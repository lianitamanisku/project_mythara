package com.mythara.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.JetBrainsMono
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.delay

/**
 * "Mythara is thinking…" indicator — Phase E redesign.
 *
 * New visual: a 16 dp mini-rose breathing in Charple ↔ Lavender at
 * 0.8 Hz (the brand heartbeat — same cadence as the watch face's
 * active-PTT pulse), followed by a rolodex phrase in JetBrainsMono
 * + a trailing ellipsis glyph. The mini-rose uses the same petal +
 * hex geometry as every other rose surface in the system
 * (watch face, splash, amulet, fold bloom) so the user instantly
 * recognises "Mythara is doing something" without a label.
 *
 * The rolodex phrase rotation is preserved from the previous
 * gradient-text implementation — it gives the indicator personality
 * beyond a static spinner, and matches the chat surface's "friend
 * keeping you in the loop" voice.
 *
 * Rendered in the chat surface between submit() and the first
 * streamed delta; suppressed once streaming text starts arriving.
 */
@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier,
) {
    // Phrase rolodex. Reads like a friend updating you on what
    // they're doing. ~1.6s per phrase — slow enough to read,
    // fast enough to feel responsive.
    var phraseIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PHRASE_INTERVAL_MS)
            phraseIdx = (phraseIdx + 1) % PHRASES.size
        }
    }
    val phrase = PHRASES[phraseIdx]

    // Pulse phase 0 → 1 → 0, 1250 ms period (0.8 Hz). Drives both
    // the petal colour blend AND a subtle scale wobble so the rose
    // visibly "breathes" through each cycle.
    val transition = rememberInfiniteTransition(label = "thinking-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-pulse-phase",
    )
    val petalColor = lerpColor(MytharaColors.Charple, RoseGeometry.Lavender, pulse)
    val scale = 1f + 0.06f * pulse

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(ROSE_SIZE_DP.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val targetRadius =
                (minOf(size.width, size.height) / 2f) * 0.9f * scale
            // RoseGeometry uses source units where the outer petal tip
            // sits at 30 units from centre — scale accordingly.
            val unitScale = targetRadius / RoseGeometry.OuterRadiusSourceUnits
            // Five big petals at the 5-fold rotation, blending Charple
            // ↔ Lavender per pulse phase.
            for (deg in RoseGeometry.BigPetalAngles) {
                drawPath(
                    path = RoseGeometry.petalPath(
                        diamond = RoseGeometry.BigPetal,
                        angleDegrees = deg.toFloat(),
                        cx = cx, cy = cy, scale = unitScale,
                    ),
                    color = petalColor.copy(alpha = 0.95f),
                )
            }
            // Five smaller lavender petals interleaved at 36° offsets.
            for (deg in RoseGeometry.SmallPetalAngles) {
                drawPath(
                    path = RoseGeometry.petalPath(
                        diamond = RoseGeometry.SmallPetal,
                        angleDegrees = deg.toFloat(),
                        cx = cx, cy = cy, scale = unitScale,
                    ),
                    color = RoseGeometry.Lavender.copy(alpha = 0.85f),
                )
            }
            // Cyan hex nucleus pulses with the same phase.
            val hexAlpha = 0.55f + 0.45f * pulse
            drawPath(
                path = RoseGeometry.hexPath(cx = cx, cy = cy, scale = unitScale),
                color = MytharaColors.Bok.copy(alpha = hexAlpha),
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = phrase,
            color = MytharaColors.FgDim,
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text = Glyph.Ellipsis,
            color = MytharaColors.Charple.copy(alpha = 0.6f + 0.4f * pulse),
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp),
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt,
    )
}

private const val PHRASE_INTERVAL_MS = 1_600L
/** 1250 ms = 0.8 Hz — the brand heartbeat. Matches the watch
 *  face's active-PTT pulse so any pulsing surface across the
 *  Mythara ecosystem reads as one rhythm. */
private const val PULSE_PERIOD_MS = 1_250
/** Mini-rose target size. Big enough to read as a rose, small
 *  enough to sit comfortably inline next to body text. */
private const val ROSE_SIZE_DP = 18

private val PHRASES = listOf(
    "Mythara is thinking…",
    "Reading the room…",
    "Composing a reply…",
    "Looking it up…",
    "Pulling things together…",
    "Just a sec…",
    "Mythara is working…",
)
