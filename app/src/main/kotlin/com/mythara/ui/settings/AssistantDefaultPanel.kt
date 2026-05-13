package com.mythara.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.voice.MytharaVoiceInteractionService

/**
 * Settings panel that explains the "Pixel Buds tap → Lumi" path and
 * deep-links to the system surface where the user makes Mythara the
 * default Digital Assistant app.
 *
 * Why a separate panel rather than rolled into the api-key block: the
 * default-assistant choice is a system-level handoff (Settings → Apps →
 * Default apps → Digital assistant app), not an in-app preference, and
 * the deep-link is the only thing we can offer.
 *
 * Once Mythara is the default assistant, any "open the assistant"
 * gesture (Pixel Buds touch-and-hold, hardware assist button, system
 * assist gesture / corner-swipe) delivers MainActivity an
 * `ACTION_ASSIST` intent. [com.mythara.voice.VoiceActionStore] picks
 * that up and [com.mythara.ui.chat.ChatScreen] starts a one-shot
 * SpeechRecognition listen that submits to the agent.
 */
@Composable
fun AssistantDefaultPanel() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Recompute on every resume so flipping the system setting (in
    // Default apps → Digital assistant) immediately reflects when the
    // user comes back into Mythara without us having to re-mount.
    var isDefault by remember { mutableStateOf(MytharaVoiceInteractionService.isAssistantPackage(ctx)) }
    LaunchedEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefault = MytharaVoiceInteractionService.isAssistantPackage(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} pixel buds & default assistant",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        val (glyph, color, label) = if (isDefault) {
            Triple(Glyph.Dot, MytharaColors.Julep, "active — Mythara is your default assistant")
        } else {
            Triple(Glyph.Cross, MytharaColors.Sriracha, "not set — long-press / corner-swipe / Pixel Buds tap still goes to Google")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(end = 6.dp))
            Text(label, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { openDigitalAssistantSettings(ctx) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDefault) MytharaColors.Surface else MytharaColors.Charple,
                contentColor = MytharaColors.Fg,
            ),
        ) {
            Text(
                text = if (isDefault) "${Glyph.Refresh} re-open assistant settings"
                else "${Glyph.Arrow} open default-assistant settings",
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} on Android 12+, picking Mythara in 'Digital assistant app' is what routes the long-press home / corner-swipe / Pixel Buds touch-and-hold to Lumi. If the gesture still opens Google after picking Mythara, scroll for 'Use default assistant' inside Pixel Buds → Touch controls → Touch & hold → Assistant. As a fallback, the chat surface mic button always works.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun openDigitalAssistantSettings(ctx: Context) {
    // ACTION_VOICE_INPUT_SETTINGS lands on the "Choose assistant app"
    // surface on most Android 11+ devices (this is where Pixel
    // exposes the Digital assistant picker — Settings → Apps →
    // Default apps → Digital assistant app). On a few OEM skins it
    // lands one level up at "Voice and input"; the user takes one
    // more tap from there. If even that fails (very old shells we
    // don't target) we fall back to the catch-all top-level Settings.
    val targets = listOf(
        Intent("android.settings.VOICE_INPUT_SETTINGS"),
        Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in targets) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) return
    }
}
