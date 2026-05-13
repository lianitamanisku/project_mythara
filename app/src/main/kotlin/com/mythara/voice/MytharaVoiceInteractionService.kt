package com.mythara.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Implementing [VoiceInteractionService] is the piece that makes
 * Android route assistant-class gestures (home long-press, corner
 * swipe-up assist, Pixel Buds touch-and-hold) to Mythara when the
 * user picks it in Settings → Apps → Default apps → Digital
 * assistant app.
 *
 * Why the bare ACTION_ASSIST activity filter on its own wasn't enough:
 * Android 12+ lists any app with that filter as a *candidate*, but
 * the SystemUI gestures (long-press, corner swipe) only fire on apps
 * that ALSO implement a real VoiceInteractionService — Google
 * tightened this in 2022 specifically to stop random apps grabbing
 * the assist hot-path. Microsoft Copilot, Bixby, and every other
 * third-party assistant do exactly the same dance.
 *
 * Our implementation is minimal: we don't actually run a
 * VoiceInteractionSession (no overlay UI, no system audio focus
 * negotiation, no follow-up callbacks). Instead the service just
 * exists so Android lists us as an Assistant, AND on
 * `onLaunchVoiceAssistFromKeyguard` / the gesture path it fires
 * MainActivity with ACTION_ASSIST — same intent we already handle.
 *
 * Result: the user's gesture lands MainActivity, MainActivity fires
 * VoiceActionStore, ChatScreen starts the one-shot STT — the exact
 * same flow that already worked for explicit `am start -a ASSIST`.
 */
class MytharaVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "VoiceInteractionService onReady — Mythara is the active assistant")
    }

    /**
     * Fired when the user invokes the assistant from the lock screen
     * (some OEMs route the hardware assist button here too). We
     * launch MainActivity with ACTION_ASSIST so the existing path
     * in [com.mythara.MainActivity.handleVoiceIntent] handles it.
     */
    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        Log.d(TAG, "onLaunchVoiceAssistFromKeyguard")
        launchAssistActivity()
    }

    private fun launchAssistActivity() {
        val intent = Intent(Intent.ACTION_ASSIST).apply {
            component = ComponentName(this@MytharaVoiceInteractionService, "com.mythara.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "launchAssistActivity failed", it) }
    }

    companion object {
        private const val TAG = "Mythara/VIS"

        /**
         * Helper for the Settings panel: is Mythara currently the
         * device's active VoiceInteractionService? Pulled by
         * checking the secure setting Android writes when the user
         * picks an assistant; component-name match wins.
         */
        fun isCurrentAssistant(ctx: Context): Boolean {
            val value = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                "voice_interaction_service",
            ).orEmpty()
            if (value.isBlank()) return false
            val ours = ComponentName(
                ctx.packageName,
                "com.mythara.voice.MytharaVoiceInteractionService",
            ).flattenToString()
            return value.equals(ours, ignoreCase = true)
        }

        /**
         * Looser check: is *any* of Mythara's components set as the
         * current assistant? Used as a fallback indicator when a
         * device routes ACTION_ASSIST via the legacy
         * `assistant` secure setting rather than the
         * `voice_interaction_service` one. Older Pixels with the
         * Activity-only path land here.
         */
        fun isAssistantPackage(ctx: Context): Boolean {
            val candidates = listOf("voice_interaction_service", "assistant")
            for (key in candidates) {
                val raw = android.provider.Settings.Secure.getString(ctx.contentResolver, key)
                    ?: continue
                if (raw.contains(ctx.packageName, ignoreCase = true)) return true
            }
            return false
        }
    }
}
