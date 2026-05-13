package com.mythara.ui.secret

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.secret.SecretAuthStore
import com.mythara.memory.Tier
import com.mythara.secret.observe.ObserveSession
import com.mythara.secret.observe.ObserveState
import com.mythara.secret.observe.ObserveStore
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.extract.gemma.GemmaModelStore
import com.mythara.secret.observe.extract.gemma.HuggingFaceTokenStore
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.secret.observe.vosk.Language
import com.mythara.secret.observe.vosk.VoskModelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * VM behind the Secret Settings screen. Wraps [ObserveStore] for the
 * Observe pipeline controls and surfaces RECORD_AUDIO permission state
 * — the user must grant the mic before we can flip the toggle on.
 */
@HiltViewModel
class SecretSettingsViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: ObserveStore,
    private val voskModel: VoskModelStore,
    private val embedModel: EmbeddingsModelStore,
    private val gemmaModel: GemmaModelStore,
    private val gemmaExtractor: GemmaExtractor,
    private val hfToken: HuggingFaceTokenStore,
    private val session: ObserveSession,
    private val secretAuth: SecretAuthStore,
    private val vault: LearningVault,
) : ViewModel() {

    data class State(
        val observeState: ObserveState = ObserveState.Idle,
        val micGranted: Boolean = false,
        val notifGranted: Boolean = false,
        /** True on Android < 13: notification permission is implicit, no prompt needed. */
        val notifRequired: Boolean = false,
        val confirmingForget: Boolean = false,
        val modelState: VoskModelStore.State = VoskModelStore.State.Missing,
        val embedModelState: EmbeddingsModelStore.State = EmbeddingsModelStore.State.Missing,
        val gemmaModelState: GemmaModelStore.State = GemmaModelStore.State.Missing,
        /** True when a HuggingFace access token has been saved (its value
         *  itself is never exposed to the UI). */
        val hfTokenSaved: Boolean = false,
        val transcriptCount: Int = 0,
        val recentTranscripts: List<TranscriptPreview> = emptyList(),
        /** True when the user has opted into biometric unlock for Secret mode. */
        val biometricUnlock: Boolean = false,
        val vaultCount: Int = 0,
        val recentLearnings: List<VaultPreview> = emptyList(),
        val activeLanguage: Language = Language.Default,
        val languageAvailability: Map<String, Boolean> = emptyMap(),
    ) {
        val readyToStart: Boolean
            get() = micGranted && (!notifRequired || notifGranted) && modelState is VoskModelStore.State.Ready
    }

    data class TranscriptPreview(val tsMs: Long, val text: String)

    data class VaultPreview(
        val id: String,
        val tsMs: Long,
        val tier: String,
        val src: String,
        val content: String,
        val facets: List<String>,
        val seen: Int,
        val hasEmbedding: Boolean,
    )

    private val _state = MutableStateFlow(State(observeState = store.state.value))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collect { s ->
                _state.update { it.copy(observeState = s) }
                if (s is ObserveState.Running) refreshTranscripts()
            }
        }
        viewModelScope.launch {
            voskModel.state.collect { ms ->
                _state.update { it.copy(modelState = ms) }
            }
        }
        viewModelScope.launch {
            embedModel.state.collect { ems ->
                _state.update { it.copy(embedModelState = ems) }
            }
        }
        viewModelScope.launch {
            gemmaModel.state.collect { gms ->
                _state.update { it.copy(gemmaModelState = gms) }
            }
        }
        viewModelScope.launch {
            hfToken.tokenFlow().collect { t ->
                _state.update { it.copy(hfTokenSaved = !t.isNullOrBlank()) }
            }
        }
        viewModelScope.launch {
            secretAuth.useBiometricFlow().collect { enabled ->
                _state.update { it.copy(biometricUnlock = enabled) }
            }
        }
        viewModelScope.launch {
            vault.observeCount().collect { c ->
                _state.update { it.copy(vaultCount = c) }
            }
        }
        viewModelScope.launch {
            voskModel.activeLanguageFlow().collect { lang ->
                _state.update { it.copy(activeLanguage = lang) }
            }
        }
        viewModelScope.launch {
            voskModel.availability.collect { map ->
                _state.update { it.copy(languageAvailability = map) }
            }
        }
        viewModelScope.launch {
            vault.observeRecent(MAX_PREVIEW).collect { rows ->
                val previews = rows.map { row ->
                    VaultPreview(
                        id = row.id,
                        tsMs = row.tsMillis,
                        tier = row.tier,
                        src = row.src,
                        content = row.content,
                        facets = vault.decodeFacets(row),
                        seen = row.seen,
                        hasEmbedding = row.embedding != null,
                    )
                }
                _state.update { it.copy(recentLearnings = previews) }
            }
        }
        refreshPermission()
        refreshTranscripts()
    }

    fun setBiometricUnlock(enabled: Boolean) {
        viewModelScope.launch {
            secretAuth.setUseBiometric(enabled)
        }
    }

    fun ensureModel() {
        viewModelScope.launch { voskModel.ensureReady() }
    }

    fun ensureModelFor(lang: Language) {
        viewModelScope.launch { voskModel.ensureReadyFor(lang) }
    }

    fun setActiveLanguage(lang: Language) {
        viewModelScope.launch {
            voskModel.setActiveLanguage(lang)
            // Auto-fetch the new active if it isn't on disk yet.
            if (!voskModel.isExtractedFor(lang)) voskModel.ensureReadyFor(lang)
        }
    }

    fun forgetLanguage(lang: Language) {
        viewModelScope.launch { voskModel.forgetLanguage(lang) }
    }

    fun ensureEmbedModel() {
        viewModelScope.launch { embedModel.ensureReady() }
    }

    fun ensureGemmaModel() {
        viewModelScope.launch { gemmaModel.ensureReady() }
    }

    fun importGemmaFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            gemmaExtractor.release()
            gemmaModel.importFromUri(uri)
        }
    }

    fun forgetGemmaModel() {
        viewModelScope.launch {
            gemmaExtractor.release()
            gemmaModel.forgetModel()
        }
    }

    fun saveHfToken(plain: String) {
        if (plain.isBlank()) return
        viewModelScope.launch { hfToken.setToken(plain) }
    }

    fun clearHfToken() {
        viewModelScope.launch { hfToken.clear() }
    }

    fun forgetVoskModel() {
        viewModelScope.launch { voskModel.forgetModel() }
    }

    fun forgetEmbedModel() {
        viewModelScope.launch { embedModel.forgetModel() }
    }

    fun refreshTranscripts() {
        val dir = File(ctx.filesDir, "observe/transcripts")
        val previews = if (!dir.exists()) emptyList() else {
            dir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.take(MAX_PREVIEW)
                ?.map { f ->
                    TranscriptPreview(
                        tsMs = f.lastModified(),
                        text = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault(""),
                    )
                }
                .orEmpty()
        }
        _state.update {
            it.copy(
                recentTranscripts = previews,
                transcriptCount = session.transcriptCountSnapshot(),
            )
        }
    }

    fun clearTranscripts() {
        viewModelScope.launch {
            val dir = File(ctx.filesDir, "observe/transcripts")
            if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
            refreshTranscripts()
        }
    }

    fun refreshPermission() {
        val mic = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val notifNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notif = if (notifNeeded) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        _state.update {
            it.copy(
                micGranted = mic,
                notifGranted = notif,
                notifRequired = notifNeeded,
            )
        }
    }

    fun toggleObserve() {
        when (val s = _state.value.observeState) {
            is ObserveState.Idle -> store.start()
            is ObserveState.Starting,
            is ObserveState.Running,
            is ObserveState.Paused -> store.stop()
            is ObserveState.Stopping -> {} // no-op, transitioning
            is ObserveState.Error -> {
                // Reset + try again
                store.stop(); store.start()
            }
            else -> {}
        }
    }

    fun pauseOrResume() {
        when (_state.value.observeState) {
            is ObserveState.Running -> store.pause()
            is ObserveState.Paused -> store.resume()
            else -> {}
        }
    }

    fun openForgetConfirm() = _state.update { it.copy(confirmingForget = true) }
    fun cancelForget()       = _state.update { it.copy(confirmingForget = false) }
    fun confirmForget() {
        _state.update { it.copy(confirmingForget = false) }
        store.forgetEverything()
        viewModelScope.launch {
            voskModel.forgetModel()
            embedModel.forgetModel()
            gemmaExtractor.release()
            gemmaModel.forgetModel()
            vault.clear()
            refreshTranscripts()
        }
    }

    companion object { private const val MAX_PREVIEW = 8 }
}
