package com.mythara.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaWordmarkInline

/**
 * About screen — version, brand, the small print, and the hidden
 * triple-tap entry to Secret mode.
 *
 * The triple-tap target is the small inline MYTHARA wordmark at the
 * top of the screen. Three taps within [TRIPLE_TAP_WINDOW_MS] (1.5s)
 * invokes [onSecretRequest]. We deliberately don't telegraph the
 * affordance — that's the whole point.
 *
 * Other surfaces here are visible: tagline, brief credits, a link to
 * the privacy policy and the memory-repo URL.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onSecretRequest: () -> Unit,
) {
    var tapCount by remember { mutableIntStateOf(0) }
    var firstTapMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(tapCount) {
        if (tapCount >= TRIPLE_TAP_REQUIRED) {
            tapCount = 0
            onSecretRequest()
        }
    }

    // Phase C — MytharaScaffold provides the top inset + 44 dp
    // header (← back / ◇ about). The body now only pads the
    // navigation bar at the bottom (the scaffold already handled
    // the status bar at the top — double-padding pushed content
    // too far down).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {

        // Triple-tap target — the inline wordmark.
        // windowInsetsPadding(displayCutout) dodges horizontally
        // centered cameras (Pixel 10 Pro punch-hole, foldable
        // inner-display cutout) so the brand mark doesn't get
        // partially eaten by the lens. Plus an explicit top
        // spacer so the wordmark sits visibly below the cutout
        // band — without this, the scaffold's status-bar inset
        // alone isn't enough on devices whose cutout extends
        // deeper than the status bar height.
        Spacer(Modifier.height(WORDMARK_TOP_GAP_DP.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            val now = System.currentTimeMillis()
                            if (tapCount == 0 || (now - firstTapMs) > TRIPLE_TAP_WINDOW_MS) {
                                firstTapMs = now
                                tapCount = 1
                            } else {
                                tapCount += 1
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            MytharaWordmarkInline(fontSize = 28.sp)
        }

        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "${Glyph.AccentBar} field intelligence in your pocket.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MytharaColors.FgDim, letterSpacing = 1.sp,
                ),
            )
        }

        Spacer(Modifier.height(28.dp))

        Panel("version") {
            Text("0.0.1-debug · MiniMax-M2 family", color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))

        Panel("privacy") {
            Text(
                "Mythara has no backend, no telemetry, no analytics. The only network calls are to the MiniMax endpoint you configured and (if enabled) your GitHub memory repo.",
                color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "API keys and the device-secret password stay on the phone, encrypted at rest. Chat history, learnings, and non-secret settings can sync to a private GitHub repo you control.",
                color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))

        Panel("created by") {
            Text(
                "Mythara is your personal field intelligence agent.",
                color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Built by Ankur (Creator) using Lumi — the powerful mother-ship AI platform Ankur built at CES.",
                color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))

        Panel("credits") {
            Text(
                "MiniMax · Charmbracelet (Crush aesthetic) · JetBrains Mono · AndroidX · Shizuku",
                color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Panel(title: String, body: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} $title",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        body()
    }
}

private const val TRIPLE_TAP_WINDOW_MS = 1500L
private const val TRIPLE_TAP_REQUIRED = 3

/** Vertical gap above the wordmark, on top of the scaffold's
 *  status-bar inset, to clear a centred camera punch-hole on
 *  modern Pixels. Large enough to push the wordmark visibly
 *  below the cutout band; small enough that the brand mark
 *  still reads as "page title-ish" rather than floating. */
private const val WORDMARK_TOP_GAP_DP = 28
