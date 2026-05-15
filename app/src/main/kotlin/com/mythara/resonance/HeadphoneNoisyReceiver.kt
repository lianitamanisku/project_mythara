package com.mythara.resonance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log

/**
 * Hard-stop trigger for the headphone-removal case. Android fires
 * [AudioManager.ACTION_AUDIO_BECOMING_NOISY] right before audio routes
 * to the speaker — when the user pulls headphones, BT headphones
 * disconnect, etc. — and Resonance sessions must NEVER auto-route
 * binaural / entrainment tones onto a public speaker without explicit
 * user opt-in.
 *
 * Important: this broadcast can ONLY be received via a dynamically-
 * registered receiver. A manifest entry is silently ignored by the
 * framework. [ResonanceForegroundService] registers an instance in
 * `onCreate` and unregisters in `onDestroy`.
 */
class HeadphoneNoisyReceiver(
    private val onBecomingNoisy: () -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
        Log.d(TAG, "ACTION_AUDIO_BECOMING_NOISY → resonance hard stop")
        onBecomingNoisy()
    }

    companion object {
        private const val TAG = "Mythara/ResNoisy"
    }
}
