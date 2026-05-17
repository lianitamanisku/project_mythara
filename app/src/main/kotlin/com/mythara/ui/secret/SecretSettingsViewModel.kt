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
import com.mythara.secret.observe.vosk.SpeakerModelStore
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.extract.gemma.GemmaModelStore
import com.mythara.secret.observe.extract.gemma.HuggingFaceTokenStore
import com.mythara.data.ResonanceSettings
import com.mythara.resonance.ResonanceAudioEngine
import com.mythara.resonance.ResonanceCommand
import com.mythara.resonance.ResonanceController
import com.mythara.resonance.ResonanceLoop
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.secret.observe.vosk.Language
import com.mythara.secret.observe.vosk.VoskModelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val speakerModel: SpeakerModelStore,
    private val gemmaModel: GemmaModelStore,
    private val gemmaExtractor: GemmaExtractor,
    private val hfToken: HuggingFaceTokenStore,
    private val session: ObserveSession,
    private val secretAuth: SecretAuthStore,
    private val vault: LearningVault,
    private val semanticExtractor: com.mythara.secret.observe.extract.SemanticExtractor,
    private val episodicPromoter: com.mythara.agent.EpisodicPromoter,
    private val resonanceSettings: ResonanceSettings,
    private val resonanceEngine: ResonanceAudioEngine,
    private val resonanceLoop: ResonanceLoop,
    private val resonanceController: ResonanceController,
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
        val speakerModelState: SpeakerModelStore.State = SpeakerModelStore.State.Missing,
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
        /** Result of the most recent "Try Gemma" tap; null when never tested. */
        val gemmaProbe: GemmaProbe = GemmaProbe.Idle,
        val gemmaEnabled: Boolean = false,
        val episodicReport: EpisodicReport = EpisodicReport.Idle,
        // ---- Resonance Mode (off by default; secret-menu opt-in) ----
        val resonanceEnabled: Boolean = false,
        val resonanceDefaultProtocol: String = ResonanceSettings.DEFAULT_PROTOCOL,
        val resonanceVolumeCapPercent: Int = ResonanceSettings.DEFAULT_VOLUME_CAP_PERCENT,
        val resonanceEngineState: ResonanceAudioEngine.State = ResonanceAudioEngine.State(),
        val resonanceLoopPhase: ResonanceLoop.Phase = ResonanceLoop.Phase.Idle,
        /** True while a session is open (controller has a live session). */
        val resonanceSessionActive: Boolean = false,
        /** Most recent watch-streamed HR sample (bpm). Null until the
         *  watch warms up and pushes its first sample — if this stays
         *  null while a session is active, the watch isn't streaming. */
        val resonanceLiveHrBpm: Int? = null,
        /** Mean HR across the analyzer's window. */
        val resonanceLiveHrAvgBpm: Int? = null,
        /** Baseline HR the analyzer is comparing against. */
        val resonanceBaselineBpm: Int? = null,
        /** Count of `topic:resonance` HR rows visible in the latest
         *  vault page — proof that flushes are landing in the vault. */
        val resonanceHrRowCount: Int = 0,
        // ── Phase G — Observe live-UI surfaces ─────────────────
        /** Latest partial transcript emitted by Vosk while the
         *  user is mid-utterance. Empty when no session is
         *  active or between utterances. */
        val liveTranscript: String = "",
        /** Acoustic features (mean F0 / RMS / words-per-sec /
         *  duration) of the most-recently-completed utterance.
         *  Null when no transcript has finalised yet in the
         *  current session. */
        val latestFeatures: com.mythara.secret.observe.acoustic.AcousticAnalyzer.Features? = null,
        /** Environment-context snapshot tagged onto the most-
         *  recent transcript — "are you in a meeting", "is the
         *  room loud", "what devices are nearby". */
        val latestEnv: com.mythara.secret.observe.env.EnvironmentContext.Snapshot? = null,
    ) {
        val readyToStart: Boolean
            get() = micGranted && (!notifRequired || notifGranted) && modelState is VoskModelStore.State.Ready
    }

    /** Forensic state of the most recent Gemma init probe. */
    sealed interface GemmaProbe {
        data object Idle : GemmaProbe
        data object Running : GemmaProbe
        data class Ok(val sampleOutput: String) : GemmaProbe
        data class Failed(val message: String) : GemmaProbe
    }

    /** Outcome of a manual episodic-promotion run. */
    sealed interface EpisodicReport {
        data object Idle : EpisodicReport
        data object Running : EpisodicReport
        data class Ok(
            val workingSeen: Int,
            val clustersFound: Int,
            val episodicCreated: Int,
            val skippedReason: String? = null,
        ) : EpisodicReport
        data class Failed(val message: String) : EpisodicReport
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
        // Phase G — live-UI surfaces. The session pushes the
        // freshest partial / acoustic / env data via these flows;
        // we mirror them onto the UI state so SecretSettingsScreen
        // can render the ticker + readout + env hints in real time.
        viewModelScope.launch {
            session.liveTranscript.collect { t ->
                _state.update { it.copy(liveTranscript = t) }
            }
        }
        viewModelScope.launch {
            session.latestFeatures.collect { f ->
                _state.update { it.copy(latestFeatures = f) }
            }
        }
        viewModelScope.launch {
            session.latestEnv.collect { e ->
                _state.update { it.copy(latestEnv = e) }
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
            speakerModel.state.collect { sps ->
                _state.update { it.copy(speakerModelState = sps) }
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
            semanticExtractor.gemmaEnabledFlow().collect { enabled ->
                _state.update { it.copy(gemmaEnabled = enabled) }
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
                // Count of resonance HR-stream rows visible in the
                // recent vault page — proof that ResonanceHrStore is
                // actually flushing batches every ~60 s.
                val hrRowCount = previews.count { p ->
                    p.src == "resonance:hr-stream" ||
                        ("topic:resonance" in p.facets && "kind:heart-rate" in p.facets)
                }
                _state.update {
                    it.copy(
                        recentLearnings = previews,
                        resonanceHrRowCount = hrRowCount,
                    )
                }
            }
        }
        // ---- Resonance Mode flows ----
        viewModelScope.launch {
            resonanceSettings.enabledFlow().collect { v ->
                _state.update { it.copy(resonanceEnabled = v) }
            }
        }
        viewModelScope.launch {
            resonanceSettings.defaultProtocolFlow().collect { v ->
                _state.update { it.copy(resonanceDefaultProtocol = v) }
            }
        }
        viewModelScope.launch {
            resonanceSettings.volumeCapPercentFlow().collect { v ->
                _state.update { it.copy(resonanceVolumeCapPercent = v) }
            }
        }
        viewModelScope.launch {
            resonanceEngine.state.collect { s ->
                _state.update { it.copy(resonanceEngineState = s) }
            }
        }
        viewModelScope.launch {
            resonanceLoop.phase.collect { p ->
                _state.update { it.copy(resonanceLoopPhase = p) }
            }
        }
        // Live HR readout — follows whichever ResonanceSession is
        // currently open. flatMapLatest swaps to the new session's
        // state flow when the controller opens one, and emits null
        // when the session ends so the UI clears itself.
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            resonanceController.session
                .flatMapLatest { sess -> sess?.state ?: flowOf(null) }
                .collect { snap ->
                    _state.update {
                        it.copy(
                            resonanceSessionActive = snap != null ||
                                resonanceController.session.value != null,
                            resonanceLiveHrBpm = snap?.liveHrBpm,
                            resonanceLiveHrAvgBpm = snap?.liveHrAvgBpm,
                            resonanceBaselineBpm = snap?.baselineBpm,
                        )
                    }
                }
        }
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            resonanceController.session.collect { sess ->
                _state.update { it.copy(resonanceSessionActive = sess != null) }
            }
        }
        refreshPermission()
        refreshTranscripts()
    }

    // ---- Resonance Mode setters / actions ----

    fun setResonanceEnabled(value: Boolean) {
        viewModelScope.launch { resonanceSettings.setEnabled(value) }
    }

    fun setResonanceDefaultProtocol(name: String) {
        viewModelScope.launch { resonanceSettings.setDefaultProtocol(name) }
    }

    fun setResonanceVolumeCapPercent(value: Int) {
        viewModelScope.launch {
            resonanceSettings.setVolumeCapPercent(value)
            // Apply immediately if a session is currently audible.
            resonanceEngine.setVolumeCap(value / 100f)
        }
    }

    /** Manually start a Resonance session from the secret menu (handy
     *  for testing without the watch). Uses the configured default
     *  protocol; routes through the controller so the same FGS path
     *  + safety rails apply. */
    fun startResonanceFromApp() {
        val name = state.value.resonanceDefaultProtocol
        val protocol = runCatching { ResonanceCommand.Protocol.valueOf(name) }
            .getOrDefault(ResonanceCommand.Protocol.Calm)
        resonanceController.startProtocolFromApp(protocol)
    }

    fun stopResonanceFromApp() {
        resonanceController.stopFromApp()
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

    fun ensureSpeakerModel() {
        viewModelScope.launch { speakerModel.ensureReady() }
    }

    /**
     * One-shot Gemma init probe. Runs OUTSIDE the Observe audio loop —
     * this is how we test whether the LiteRT-LM crash we saw on Gemma 4
     * E2B was due to in-Observe contention or fundamental. On success,
     * persists `gemmaEnabled = true` so SemanticExtractor stops bypassing
     * Gemma. On failure, leaves the toggle off and surfaces the error.
     */
    fun probeGemma() {
        viewModelScope.launch {
            _state.update { it.copy(gemmaProbe = GemmaProbe.Running) }
            val result = runCatching {
                // Tiny prompt — we just need to verify init + a single
                // round-trip. The output doesn't get stored anywhere.
                gemmaExtractor.extractWithMood(
                    "The user said: I had a really nice walk in the park this morning, the weather was lovely.",
                )
            }
            result.onSuccess { r ->
                semanticExtractor.setGemmaEnabled(true)
                _state.update {
                    it.copy(
                        gemmaProbe = GemmaProbe.Ok(
                            sampleOutput = "facts=${r.facts.size} mood=${r.mood ?: "n/a"}",
                        ),
                        gemmaEnabled = true,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        gemmaProbe = GemmaProbe.Failed(e.message ?: e.javaClass.simpleName),
                    )
                }
            }
        }
    }

    fun setGemmaEnabled(value: Boolean) {
        viewModelScope.launch {
            semanticExtractor.setGemmaEnabled(value)
            _state.update { it.copy(gemmaEnabled = value) }
        }
    }

    /**
     * Trigger an episodic-promotion run now, bypassing the nightly
     * WorkManager cadence. Useful for verifying the Gemma
     * summarisation path end-to-end without waiting for the next
     * tick. Stamps `episodicReport` in state for the UI to render.
     */
    fun runEpisodicPromotionNow() {
        viewModelScope.launch {
            _state.update { it.copy(episodicReport = EpisodicReport.Running) }
            val report = runCatching { episodicPromoter.promote() }.getOrElse { e ->
                _state.update { it.copy(episodicReport = EpisodicReport.Failed(e.message ?: e.javaClass.simpleName)) }
                return@launch
            }
            _state.update {
                it.copy(
                    episodicReport = EpisodicReport.Ok(
                        workingSeen = report.workingSeen,
                        clustersFound = report.clustersFound,
                        episodicCreated = report.episodicCreated,
                        skippedReason = report.skippedReason,
                    ),
                )
            }
        }
    }

    fun forgetSpeakerModel() {
        speakerModel.forget()
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
