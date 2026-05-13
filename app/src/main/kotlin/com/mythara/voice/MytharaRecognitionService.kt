package com.mythara.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Stub [RecognitionService] required by Android for Mythara to be
 * accepted as a Digital Assistant.
 *
 * The OS validates `voice_interaction_service.xml` against this
 * service when the user picks Mythara — if no `recognitionService`
 * attribute is set, the system rejects the assistant with
 * "Bad voice interaction service: No recognitionService specified"
 * and reverts the selection to None. We hit exactly that on Pixel.
 *
 * We don't actually want to handle recognition through this service —
 * Mythara's STT path uses [android.speech.SpeechRecognizer] directly
 * (specifically `createOnDeviceSpeechRecognizer`), which picks
 * whichever RecognitionService is the device default (Google's on a
 * Pixel). All this stub does is exist so the manifest registration
 * gate is satisfied. Every onStartListening call immediately fires
 * an error so callers fall back to the system default.
 */
class MytharaRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.d(TAG, "onStartListening — stub fires NETWORK error so callers retry against system default")
        // Use the legacy listener API (onError(int)) — Callback exposes
        // it as `error(int)`. Pick a non-fatal code so the caller isn't
        // misled into thinking the hardware is broken.
        runCatching { listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
    }

    override fun onCancel(listener: Callback?) {
        // No-op — we never started anything.
    }

    override fun onStopListening(listener: Callback?) {
        // No-op — we never started anything.
    }

    companion object {
        private const val TAG = "Mythara/RecogStub"
    }
}
