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
import androidx.compose.foundation.layout.fillMaxHeight
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
    // header (← back / ◇ about). The body is a Box so we can
    // anchor the wordmark + tagline at viewport CENTER (per
    // user request — the brand mark + secret-unlock target
    // should sit in the middle of the screen, not hugged to
    // the top) while the panels live in a scrollable bottom
    // strip beneath.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.navigationBars.asPaddingValues()),
    ) {

        // ── Wordmark + tagline — vertically centered ────────────
        // Alignment.Center anchors this Column to the geometric
        // middle of the body. windowInsetsPadding(displayCutout)
        // ensures it dodges any device cutout that intersects
        // its bounds (foldable inner displays etc.) — though at
        // viewport center the wordmark is well clear of any
        // punch-hole on Pixel hardware.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Triple-tap target — the inline wordmark.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
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
            Text(
                text = "${Glyph.AccentBar} field intelligence in your pocket.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MytharaColors.FgDim, letterSpacing = 1.sp,
                ),
            )
        }

        // ── Panels — bottom 45% of the body, scrollable ─────────
        // Anchored to BottomCenter so they fill the lower portion
        // of the screen without overlapping the centered wordmark
        // above. fillMaxHeight(0.45f) gives a fixed slot; long
        // panel content scrolls inside.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(BOTTOM_PANELS_FRACTION)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Panel("version") {
                Text(
                    "0.0.1-debug · MiniMax-M2 family",
                    color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium,
                )
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

            Spacer(Modifier.height(24.dp))
        }
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

/** Fraction of the body height reserved for the scrollable
 *  panels strip at the bottom. The remaining ~55% above is
 *  empty space surrounding the centered wordmark, anchoring
 *  the brand mark visually at the screen's middle. */
private const val BOTTOM_PANELS_FRACTION = 0.45f
