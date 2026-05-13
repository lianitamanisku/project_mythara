package com.mythara.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Minimal VoiceInteractionSessionService stub. Required by Android
 * to be paired with [MytharaVoiceInteractionService] — without it
 * the OS refuses to register us as a valid Digital Assistant.
 *
 * We don't actually use the session overlay UI (no in-app picture-in-
 * picture-style assistant chrome). Our session is a no-op that
 * immediately closes; the actual assistant work happens in
 * [com.mythara.MainActivity] after [MytharaVoiceInteractionService]
 * delivers an ACTION_ASSIST intent to it via the
 * onLaunchVoiceAssistFromKeyguard path.
 *
 * Why ship a stub instead of a real session: a real
 * VoiceInteractionSession draws a window over whatever app the user
 * is in (it's what Google Assistant's overlay sheet is). Mythara is
 * a full-screen chat surface; we'd rather pop the activity than
 * paint a translucent overlay. The stub keeps the registration
 * gate happy while we route the actual UX through the activity.
 */
class MytharaVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.d(TAG, "onNewSession (immediate finish)")
        return object : VoiceInteractionSession(this) {
            override fun onCreate() {
                super.onCreate()
                // Close right away. The real chat UI is reached via
                // MainActivity's ACTION_ASSIST intent path.
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "Mythara/VISSession"
    }
}
