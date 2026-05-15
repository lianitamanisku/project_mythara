package com.mythara.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaTheme

/**
 * Health Connect permissions-rationale screen.
 *
 * Health Connect only lists an app in its "app permissions" screen —
 * and only lets the grant flow complete — when the app declares an
 * activity that handles the rationale intent. Mythara had every health
 * read-permission in the manifest but no rationale activity, so it
 * never appeared in the Health Connect app list at all.
 *
 * This activity is the target of two manifest entries:
 *  - the `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` filter
 *    (Health Connect on Android 13 and below — separate APK), and
 *  - the `ViewPermissionUsageActivity` alias for
 *    `android.intent.action.VIEW_PERMISSION_USAGE` +
 *    `category.HEALTH_PERMISSIONS` (Android 14+, Health Connect baked
 *    into the OS).
 *
 * It just explains, honestly, what Mythara does with health data — the
 * actual permission grant UI is Health Connect's own.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MytharaTheme {
                RationaleContent(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun RationaleContent(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "HEALTH DATA",
            style = MaterialTheme.typography.headlineSmall.copy(
                color = MytharaColors.Fg, letterSpacing = 3.sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${Glyph.AccentBar} why Mythara reads Health Connect",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )

        Spacer(Modifier.height(20.dp))
        Para(
            "Mythara (Lumi) reads your Health Connect data — steps, sleep, " +
                "heart rate, activity, weight — to build the health history " +
                "on your About Me screen and to ground its personality " +
                "insights in how you're actually doing.",
        )
        Para(
            "Everything stays on this device. Health data is never uploaded, " +
                "shared, or sent off the phone — it's read locally, summarised " +
                "by the on-device model, and stored in Mythara's own private " +
                "memory.",
        )
        Para(
            "You're in control: grant only the data types you're comfortable " +
                "with, and revoke any of them at any time from Health Connect " +
                "settings. Mythara silently skips whatever it isn't granted.",
        )

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onClose) {
            Text("${Glyph.LeftArrow} close", color = MytharaColors.FgMute)
        }
    }
}

@Composable
private fun Para(text: String) {
    Text(
        text = text,
        color = MytharaColors.Fg,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(14.dp))
}
