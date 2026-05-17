package com.mythara.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.SettingsStore
import com.mythara.mic.ElevenLabsTtsService
import com.mythara.minimax.ApiException
import com.mythara.minimax.GeminiVisionService
import com.mythara.minimax.MiniMaxClient
import com.mythara.minimax.Region
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val gemini: GeminiVisionService,
    private val elevenLabs: ElevenLabsTtsService,
    private val supertonicStore: com.mythara.mic.supertonic.SupertonicModelStore,
    private val tts: com.mythara.mic.Tts,
) : ViewModel() {

    /** One-shot result of the "test voice" button so the UI can
     *  surface a green "✓ synthesised" or a red error toast. */
    data class TestVoiceResult(val ok: Boolean, val message: String)
    private val _testVoiceResult = MutableStateFlow<TestVoiceResult?>(null)
    val testVoiceResult: StateFlow<TestVoiceResult?> = _testVoiceResult.asStateFlow()

    fun testSupertonicVoice() {
        viewModelScope.launch {
            _testVoiceResult.value = TestVoiceResult(true, "${com.mythara.ui.theme.Glyph.Ellipsis} synthesising…")
            val ok = runCatching { tts.testSupertonic() }.getOrDefault(false)
            _testVoiceResult.value = TestVoiceResult(
                ok = ok,
                message = if (ok) {
                    "${com.mythara.ui.theme.Glyph.Check} synthesised — you should hear audio"
                } else {
                    "${com.mythara.ui.theme.Glyph.Cross} synthesis failed — check logcat 'Mythara/Supertonic'"
                },
            )
        }
    }

    /** Mirrors [SupertonicModelStore.state] for the UI panel.
     *  Collected eagerly so the SettingsScreen can render the
     *  current state without explicitly fetching. */
    val supertonicState: StateFlow<com.mythara.mic.supertonic.SupertonicModelStore.State> =
        supertonicStore.state
    val supertonicProgress: StateFlow<Float> = supertonicStore.progress

    fun installSupertonicVoice() {
        viewModelScope.launch {
            runCatching { supertonicStore.ensureInstalled() }
        }
    }

    fun removeSupertonicVoice() {
        viewModelScope.launch {
            runCatching { supertonicStore.wipe() }
        }
    }

    data class State(
        val region: Region = Region.Default,
        val apiKey: String? = null,
        val model: String = SettingsStore.DEFAULT_MODEL,
        val validating: Boolean = false,
        val validation: ValidationResult? = null,
        val supportedModels: List<String> = SettingsStore.SUPPORTED_MODELS,
        // Gemini vision key (optional, separate from MiniMax key).
        val geminiKey: String? = null,
        val geminiValidating: Boolean = false,
        val geminiValidation: ValidationResult? = null,
        // ElevenLabs TTS key + toggle + voice id.
        val elevenLabsKey: String? = null,
        val elevenLabsVoiceId: String = SettingsStore.DEFAULT_ELEVEN_LABS_VOICE_ID,
        val useElevenLabs: Boolean = false,
        val elevenLabsValidating: Boolean = false,
        val elevenLabsValidation: ValidationResult? = null,
        /** Voices fetched from /v1/voices for the dropdown picker. */
        val elevenLabsVoices: List<com.mythara.mic.ElevenLabsTtsService.Voice> = emptyList(),
        val elevenLabsVoicesLoading: Boolean = false,
        /** Vision routing preference. false = Gemma on-device first
         *  (default; privacy + zero-cost). true = cloud Gemini first
         *  when the key is configured (higher accuracy per call). */
        val preferCloudVision: Boolean = false,
        /** Currently-selected Supertonic-2 voice (F1..F5, M1..M5). */
        val supertonicVoice: String = SettingsStore.DEFAULT_SUPERTONIC_VOICE,
    )

    data class ValidationResult(val ok: Boolean, val message: String)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val snap = store.snapshot()
            _state.update {
                it.copy(
                    region = snap.region,
                    apiKey = snap.apiKey,
                    model = snap.model,
                    geminiKey = snap.geminiKey,
                    elevenLabsKey = snap.elevenLabsKey,
                    elevenLabsVoiceId = snap.elevenLabsVoiceId,
                    useElevenLabs = snap.useElevenLabs,
                    preferCloudVision = snap.preferCloudVision,
                    supertonicVoice = snap.supertonicVoice,
                )
            }
            // Pre-fetch the voice library on cold start so the
            // dropdown is populated without the user needing to tap
            // anything. No-op if the key is empty / network is down.
            if (!snap.elevenLabsKey.isNullOrBlank()) {
                loadElevenLabsVoices(snap.elevenLabsKey)
            }
        }
    }

    /**
     * Fetch the user's ElevenLabs voice library and stash it in
     * state for the dropdown. Safe to call repeatedly; the inner
     * HTTP call is short.
     */
    private suspend fun loadElevenLabsVoices(apiKey: String) {
        if (apiKey.isBlank()) return
        _state.update { it.copy(elevenLabsVoicesLoading = true) }
        val out = runCatching { elevenLabs.fetchVoices(apiKey) }.getOrElse {
            com.mythara.mic.ElevenLabsTtsService.VoicesOutcome(
                ok = false, detail = it.message, code = "threw",
            )
        }
        _state.update {
            it.copy(
                elevenLabsVoicesLoading = false,
                elevenLabsVoices = if (out.ok) out.voices else it.elevenLabsVoices,
            )
        }
    }

    suspend fun setRegion(r: Region) {
        store.setRegion(r)
        _state.update { it.copy(region = r, validation = null) }
    }

    suspend fun setModel(m: String) {
        store.setModel(m)
        _state.update { it.copy(model = m) }
    }

    suspend fun saveAndValidate(plainKey: String) {
        if (plainKey.isBlank()) return
        store.setApiKey(plainKey)
        _state.update { it.copy(apiKey = plainKey, validating = true, validation = null) }
        val region = _state.value.region
        val client = MiniMaxClient(apiKey = plainKey, region = region)
        val res = runCatching { client.validateKey().getOrThrow() }
        val v = when {
            res.isSuccess -> ValidationResult(ok = true, message = "key OK · ${res.getOrNull()?.size ?: 0} models visible")
            res.exceptionOrNull() is ApiException ->
                ValidationResult(ok = false, message = (res.exceptionOrNull() as ApiException).mapped.message)
            else ->
                ValidationResult(ok = false, message = res.exceptionOrNull()?.message ?: "validation failed")
        }
        _state.update { it.copy(validating = false, validation = v) }
    }

    /**
     * Save the Gemini vision key and probe it with a tiny one-shot
     * `:generateContent` call to confirm the key is valid AND the
     * default vision model is reachable for this account.
     */
    suspend fun saveAndValidateGemini(plainKey: String) {
        val trimmed = plainKey.trim()
        if (trimmed.isBlank()) return
        store.setGeminiKey(trimmed)
        _state.update { it.copy(geminiKey = trimmed, geminiValidating = true, geminiValidation = null) }
        val outcome = runCatching { gemini.validate(trimmed) }.getOrElse {
            com.mythara.minimax.GeminiVisionService.Outcome(false, it.message ?: "validation failed", "threw")
        }
        _state.update {
            it.copy(
                geminiValidating = false,
                geminiValidation = ValidationResult(ok = outcome.ok, message = outcome.text),
            )
        }
    }

    suspend fun clearGeminiKey() {
        store.clearGeminiKey()
        _state.update { it.copy(geminiKey = null, geminiValidation = null) }
    }

    // ---------- ElevenLabs ----------

    suspend fun saveAndValidateElevenLabs(plainKey: String) {
        val trimmed = plainKey.trim()
        if (trimmed.isBlank()) return
        store.setElevenLabsKey(trimmed)
        _state.update { it.copy(elevenLabsKey = trimmed, elevenLabsValidating = true, elevenLabsValidation = null) }
        val outcome = runCatching { elevenLabs.validate(trimmed) }.getOrElse {
            com.mythara.mic.ElevenLabsTtsService.Outcome(false, it.message ?: "validation failed", "threw")
        }
        _state.update {
            it.copy(
                elevenLabsValidating = false,
                elevenLabsValidation = ValidationResult(
                    ok = outcome.ok,
                    message = outcome.detail ?: if (outcome.ok) "key OK" else "validation failed",
                ),
            )
        }
        // On a successful validate, also refresh the voice list so the
        // dropdown is populated immediately. Don't reuse the existing
        // list — the new key might belong to a different account.
        if (outcome.ok) {
            loadElevenLabsVoices(trimmed)
        }
    }

    suspend fun clearElevenLabsKey() {
        store.clearElevenLabsKey()
        store.setUseElevenLabs(false)
        _state.update {
            it.copy(
                elevenLabsKey = null,
                elevenLabsValidation = null,
                useElevenLabs = false,
                elevenLabsVoices = emptyList(),
            )
        }
    }

    suspend fun setElevenLabsVoiceId(voiceId: String) {
        store.setElevenLabsVoiceId(voiceId)
        _state.update {
            it.copy(
                elevenLabsVoiceId = voiceId.ifBlank { SettingsStore.DEFAULT_ELEVEN_LABS_VOICE_ID },
            )
        }
    }

    suspend fun setUseElevenLabs(value: Boolean) {
        store.setUseElevenLabs(value)
        _state.update { it.copy(useElevenLabs = value) }
    }

    suspend fun setPreferCloudVision(value: Boolean) {
        store.setPreferCloudVision(value)
        _state.update { it.copy(preferCloudVision = value) }
    }

    /** Returns the catalog of installable Supertonic voices so the
     *  Settings picker can render the dropdown. Surfaced as a
     *  ViewModel method rather than direct const access so future
     *  voice subsets / per-user availability gating slots in here. */
    fun availableSupertonicVoices(): List<String> =
        com.mythara.mic.supertonic.SupertonicModelStore.AVAILABLE_VOICES

    suspend fun setSupertonicVoice(name: String) {
        store.setSupertonicVoice(name)
        _state.update { it.copy(supertonicVoice = name) }
    }
}
