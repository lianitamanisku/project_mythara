package com.mythara.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mythara.agent.AgentRunner
import com.mythara.mic.MicBroker
import com.mythara.mic.SpeechRecognition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide push-to-talk controller — the "hold the rose to talk,
 * release to send" walkie-talkie path (v7).
 *
 * Unlike the chat-screen voice collector (which only listens while
 * ChatScreen is composed and drops text into the composer draft),
 * this runs regardless of which screen is showing: hold the rose on
 * Home → [start] opens the recognizer; release → [stop] finalises and
 * submits the captured transcript straight to [AgentRunner]. The reply
 * is spoken via TTS, which the Home face mesh animates to (it observes
 * `Tts.speaking`).
 *
 * Mechanics: [SpeechRecognition.listen] is a cancelable callbackFlow.
 * While held we keep the latest Partial/Final text; on release we
 * cancel the flow (its `awaitClose` calls `stopListening`) and submit
 * the best text we have — true release-to-send rather than waiting for
 * the recognizer's silence endpointer.
 *
 * The recognizer must be driven on a Looper thread, so the scope is
 * the main dispatcher. Mic arbitration goes through [MicBroker] so PTT
 * never fights the wake-word / observe / continuous-chat clients, and
 * respects the global STT mute.
 */
@Singleton
class PttController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val micBroker: MicBroker,
    private val agentRunner: AgentRunner,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _listening = MutableStateFlow(false)
    /** True while the mic is open for a PTT utterance. The Home face /
     *  rose observe this to show a "listening" state. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    /** Live partial transcript — updated as SpeechRecognition emits
     *  Partial events while the user is recording. Cleared on stop.
     *  The Home screen reads this to show live captions. */
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private var job: Job? = null
    @Volatile private var lastText: String = ""

    /** True only if RECORD_AUDIO is granted — callers can decide to
     *  request the permission before arming PTT. */
    fun micPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Arm PTT — begin listening. No-op if already listening, mic
     *  permission missing, or the mic is busy / muted. */
    fun start() {
        if (_listening.value) return
        if (!micPermissionGranted()) {
            Log.w(TAG, "PTT start refused — RECORD_AUDIO not granted")
            return
        }
        if (!micBroker.acquire(MicBroker.Client.CONTINUOUS_CHAT)) {
            Log.w(TAG, "PTT start refused — mic busy or muted")
            return
        }
        lastText = ""
        _partialText.value = ""
        _listening.value = true
        Log.d(TAG, "PTT armed — listening")
        job = scope.launch {
            try {
                SpeechRecognition.listen(ctx).collect { ev ->
                    when (ev) {
                        is SpeechRecognition.Event.Partial -> {
                            if (ev.text.isNotBlank()) {
                                lastText = ev.text
                                _partialText.value = ev.text
                            }
                        }
                        is SpeechRecognition.Event.Final -> {
                            if (ev.text.isNotBlank()) {
                                lastText = ev.text
                                _partialText.value = ev.text
                            }
                            finishAndSubmit()
                        }
                        is SpeechRecognition.Event.Error -> {
                            Log.w(TAG, "PTT recognizer error: ${ev.message}")
                            finishAndSubmit()
                        }
                        else -> { /* Ready / Partial-empty / EndOfSpeech */ }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            }
        }
    }

    /** Release PTT — finalise + submit whatever we captured. */
    fun stop() {
        if (!_listening.value) return
        scope.launch {
            // Cancel the listen flow first (awaitClose → stopListening),
            // then submit the latest transcript we held.
            runCatching { job?.cancelAndJoin() }
            finishAndSubmit()
        }
    }

    private fun finishAndSubmit() {
        if (!_listening.value) return // already finished (re-entry guard)
        val text = lastText.trim()
        _listening.value = false
        _partialText.value = ""
        micBroker.release(MicBroker.Client.CONTINUOUS_CHAT)
        job = null
        if (text.isNotEmpty()) {
            Log.d(TAG, "PTT release → submit: \"$text\"")
            agentRunner.submit(text, fromVoice = true)
        } else {
            Log.d(TAG, "PTT release → nothing captured")
        }
    }

    companion object {
        private const val TAG = "Mythara/Ptt"
    }
}
