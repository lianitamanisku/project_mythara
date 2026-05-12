package com.mythara.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaWordmark

/**
 * Outer-app lock screen. Renders the wordmark + a single big button that
 * triggers the system BiometricPrompt. If the device has no screen lock
 * set up, we surface a clear message instead of letting the user in.
 *
 * The Activity owns the actual prompt invocation (it needs a FragmentActivity
 * handle) — this composable just calls [onUnlock] when the user taps.
 */
@Composable
fun AuthGate(
    onUnlock: () -> Unit,
    errorMessage: String? = null,
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MytharaWordmark(shimmer = true, fontSize = 36.sp)

            Spacer(Modifier.height(14.dp))
            Text(
                text = "${Glyph.AccentBar} locked",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MytharaColors.FgDim, letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.height(40.dp))

            // Lock icon (geometric, on-brand)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MytharaColors.Surface)
                    .border(2.dp, MytharaColors.Charple, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = Glyph.DiamondFilled,
                    color = MytharaColors.Charple,
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Spacer(Modifier.height(40.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (pressed) MytharaColors.Charple
                        else MytharaColors.Surface
                    )
                    .border(
                        1.dp,
                        MytharaColors.Charple,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable {
                        pressed = true
                        onUnlock()
                    }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${Glyph.DiamondFilled}  unlock  ${Glyph.Arrow}",
                    color = if (pressed) MytharaColors.Fg else MytharaColors.Charple,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "${Glyph.Cross} $errorMessage",
                    color = MytharaColors.Sriracha,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "face · fingerprint · pin",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 2.sp),
                )
            }
        }
    }
}
