package com.mythara.mic

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
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
class Tts @Inject constructor(@ApplicationContext private val ctx: Context) {

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready: Boolean = false

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

    fun speak(text: String) = speak(text, locale = null)

    /**
     * Speak the text with the given [locale]. Falls back to the system
     * default if the locale isn't available on the device's TTS engine
     * (the engine returns LANG_MISSING_DATA / LANG_NOT_SUPPORTED, in
     * which case the previously-set language remains active).
     *
     * Pass `null` to retain whatever language the engine was last set
     * to — typically the system default. Use this when you don't know
     * the target language for the utterance.
     */
    fun speak(text: String, locale: Locale?) {
        if (text.isBlank()) return
        if (engine == null) init()
        if (!ready) return
        locale?.let { setLanguageIfSupported(it) }
        engine?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
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
        _speaking.value = false
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
        _speaking.value = false
    }
}
