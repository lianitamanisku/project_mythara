package com.mythara.mic

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mythara.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Locale + UUID already imported above; kept here for clarity.

/**
 * Lightweight Android TTS wrapper. One [TextToSpeech] instance for the
 * app lifetime, lazy-initialised on first [speak]. We do not surface
 * progress events to callers in M3 — they just call `speak()` and the
 * assistant voice plays. TTS engine selection + voice cloning land
 * later when we add the optional MiniMax T2A path.
 */
@Singleton
class Tts @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsStore,
    private val elevenLabs: ElevenLabsTtsService,
) {

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready: Boolean = false

    // Tts is a process-lifetime singleton; we own a SupervisorJob-scoped
    // scope for the ElevenLabs side path (which is suspending HTTP +
    // MediaPlayer playback). Use IO so the suspend chain doesn't block
    // the main thread; speaking-state callbacks marshal back via the
    // MutableStateFlow which is thread-safe.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Live "TTS is producing audio right now" flag. The chat surface
     * consumes this to pause its continuous SpeechRecognizer loop —
     * without that pause, the mic would pick up Lumi's own voice
     * playing through the speaker and try to transcribe it, looping
     * the assistant back on itself.
     */
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    fun init() {
        if (engine != null) return
        engine = TextToSpeech(ctx) { status ->
            ready = (status == TextToSpeech.SUCCESS)
            if (ready) {
                engine?.language = Locale.getDefault()
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _speaking.value = true
                    }
                    override fun onDone(utteranceId: String?) {
                        _speaking.value = false
                    }
                    @Deprecated("kept for API < 21") override fun onError(utteranceId: String?) {
                        _speaking.value = false
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _speaking.value = false
                    }
                })
            }
        }
    }

    fun speak(text: String) = speak(text, locale = null, userMoodTrend = null)
    fun speak(text: String, locale: Locale?) = speak(text, locale, userMoodTrend = null)

    /**
     * Speak the text with the given [locale]. Falls back to the system
     * default if the locale isn't available on the device's TTS engine
     * (the engine returns LANG_MISSING_DATA / LANG_NOT_SUPPORTED, in
     * which case the previously-set language remains active).
     *
     * [userMoodTrend] is a hint from M8.5 phase 3: when the user's
     * recent emotional state is known (e.g. "anxious", "excited",
     * "sad", "frustrated"), the speech rate + pitch are tweaked
     * subtly to make Lumi's voice feel appropriate — softer and
     * slower when the user is stressed; slightly more upbeat when
     * the user is excited. Defaults to the engine's normal rate/pitch
     * when no trend is detected. Settings are restored to defaults
     * after the utterance starts playing so subsequent speak() calls
     * with a different mood don't compound.
     *
     * Pass `null` for [locale] to retain whatever language the engine
     * was last set to — typically the system default. Use this when
     * you don't know the target language for the utterance.
     */
    fun speak(text: String, locale: Locale?, userMoodTrend: String?) {
        if (text.isBlank()) return
        // ElevenLabs is async (HTTP + MediaPlayer prep); decide once
        // at speak()-time whether to route there, then either fire off
        // the suspending coroutine or fall through to the synchronous
        // Android engine. Fallback path triggers on any of:
        //   - toggle off
        //   - key not set
        //   - empty text after trimming
        // We don't try the Android engine as a backup if ElevenLabs
        // fails mid-call — the failure modes are async so reverting
        // would mean the user hears the same line twice (one half
        // failed, one half from Android) once the network catches up.
        scope.launch {
            val snap = runCatching { settings.snapshot() }.getOrNull()
            val useEleven = snap?.useElevenLabs == true && !snap.elevenLabsKey.isNullOrBlank()
            if (useEleven) {
                speakViaElevenLabs(text, snap!!.elevenLabsKey!!, snap.elevenLabsVoiceId, userMoodTrend)
            } else {
                speakViaAndroid(text, locale, userMoodTrend)
            }
        }
    }

    private fun speakViaAndroid(text: String, locale: Locale?, userMoodTrend: String?) {
        if (engine == null) init()
        if (!ready) return
        // Strip any ElevenLabs audio tags ([laugh], [sigh], etc.) — the
        // model includes them when the EL route is enabled, but on the
        // Android fallback path the engine would read them literally
        // ("open bracket laugh close bracket"). EL → keep, Android → strip.
        val cleaned = com.mythara.agent.SpokenText.forSpeech(text, keepAudioTags = false)
        if (cleaned.isBlank()) return
        locale?.let { setLanguageIfSupported(it) }
        applyProsody(userMoodTrend)
        engine?.speak(cleaned, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    private suspend fun speakViaElevenLabs(
        text: String,
        apiKey: String,
        voiceId: String,
        userMoodTrend: String?,
    ) {
        // Mirror the speaking flag so the chat surface's continuous-mic
        // loop pauses for the duration of an ElevenLabs utterance the
        // same way it does for Android TTS. The callbacks fire from
        // MediaPlayer's main-thread listeners; this thread-safe Flow
        // update is fine to call from either side.
        val outcome = elevenLabs.speak(
            text = text,
            apiKey = apiKey,
            voiceId = voiceId,
            userMoodTrend = userMoodTrend,
            onStart = { _speaking.value = true },
            onDone = { _speaking.value = false },
        )
        if (!outcome.ok) {
            // Fallback: synthesize via Android TTS so the user still
            // hears Lumi even when ElevenLabs is down / over quota.
            android.util.Log.w("Mythara/Tts", "ElevenLabs failed (${outcome.code}: ${outcome.detail}); falling back to Android TTS")
            speakViaAndroid(text, locale = null, userMoodTrend = userMoodTrend)
        }
    }

    /**
     * Map a mood trend to a (pitch, rate) pair on Android's typical
     * 0.5–2.0 scale, defaults at 1.0. Values are subtle — too much
     * pitch shift makes the voice cartoonish; too little is a
     * no-op. Empirically a 5–10% nudge is the sweet spot.
     */
    private fun applyProsody(userMoodTrend: String?) {
        val e = engine ?: return
        val (pitch, rate) = when (userMoodTrend) {
            "anxious", "sad", "frustrated" -> 0.92f to 0.9f   // warmer + slower
            "excited", "happy" -> 1.05f to 1.05f              // slightly upbeat
            // Calm / neutral / unknown / null all use defaults.
            else -> 1.0f to 1.0f
        }
        runCatching { e.setPitch(pitch) }
        runCatching { e.setSpeechRate(rate) }
    }

    private fun setLanguageIfSupported(locale: Locale) {
        val e = engine ?: return
        val result = runCatching { e.setLanguage(locale) }.getOrNull() ?: return
        // setLanguage returns:
        //   LANG_AVAILABLE / LANG_COUNTRY_AVAILABLE / LANG_COUNTRY_VAR_AVAILABLE → ok
        //   LANG_MISSING_DATA / LANG_NOT_SUPPORTED                              → no-op (engine keeps current)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Engine couldn't switch; not fatal — TextToSpeech keeps its
            // existing language. The user just hears the wrong-language
            // voice. That's still a usable degradation.
        }
    }

    fun stop() {
        engine?.stop()
        elevenLabs.stop()
        _speaking.value = false
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
        elevenLabs.stop()
        _speaking.value = false
    }
}
