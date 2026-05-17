package com.mythara.secret.observe

import android.content.Context
import android.util.Log
import com.mythara.growth.LearningJournal
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.mic.MicBroker
import com.mythara.secret.observe.acoustic.AcousticAnalyzer
import com.mythara.secret.observe.extract.QuickNoteDetector
import com.mythara.secret.observe.extract.SemanticExtractor
import com.mythara.secret.observe.speaker.SpeakerVault
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.secret.observe.vosk.VoskAsr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Observe pipeline at run time.
 *
 *   AudioRecorder (16 kHz mono PCM)
 *     ↓ ShortArray frames
 *   Vosk Recognizer.acceptWaveForm(...)
 *     ↓ "is this the end of an utterance?"
 *   YES → final transcript json → write to disk under
 *         filesDir/observe/transcripts/<isoTs>.txt
 *         + append a metadata-only journal entry (no transcript text
 *         in the journal — that's the M8.2 extractor's job)
 *   NO  → partial transcript (ignored today; reserved for live-UI in M8.2)
 *
 * Privacy invariants enforced here:
 *  - Audio never leaves the device. We don't even hold raw PCM in
 *    memory past the recogniser's internal buffers; nothing is
 *    persisted to disk on the audio path.
 *  - Transcripts live in `filesDir/observe/transcripts/` and are
 *    auto-purged by [RawDataPurger] after 24h.
 *  - The journal entry only logs lifecycle ("transcript captured,
 *    N words") — never the transcript content. M8.2's Gemma
 *    extractor will lift durable learnings out of these transcripts
 *    before they purge, and only those condensed learnings make it
 *    into the synced repo.
 */
@Singleton
class ObserveSession @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val ctx: Context,
    private val asr: VoskAsr,
    private val embedder: LocalEmbedder,
    private val vault: LearningVault,
    private val extractor: SemanticExtractor,
    private val journal: LearningJournal,
    private val speakerVault: SpeakerVault,
    private val micBroker: MicBroker,
    private val acousticAnalyzer: AcousticAnalyzer,
    private val environment: com.mythara.secret.observe.env.EnvironmentContext,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var paused: Boolean = false
    @Volatile private var transcriptCount: Int = 0

    val isRunning: Boolean get() = job?.isActive == true
    fun transcriptCountSnapshot(): Int = transcriptCount

    // ── Phase G — live-UI surfaces ────────────────────────────────
    // The session captures partials + final acoustic features
    // already; previously they were discarded (`ignored today;
    // reserved for live-UI in M8.2` per the file header). Now they
    // flow out via StateFlows so the SecretSettingsScreen Observe
    // panel can render a live transcript ticker + an acoustic
    // readout while a session is running.

    private val _liveTranscript = kotlinx.coroutines.flow.MutableStateFlow("")
    /** Latest partial transcript from Vosk's `recognizer.partialResult`.
     *  Updated several times per second while the user is speaking;
     *  cleared on utterance final + on session stop. UI ticker
     *  observes this Flow directly. */
    val liveTranscript: kotlinx.coroutines.flow.StateFlow<String> = _liveTranscript

    private val _latestFeatures =
        kotlinx.coroutines.flow.MutableStateFlow<AcousticAnalyzer.Features?>(null)
    /** Latest acoustic features (pitch / energy / rate / duration)
     *  from the most-recently-completed utterance. Stays set until
     *  the next utterance overwrites it; cleared on session stop. */
    val latestFeatures: kotlinx.coroutines.flow.StateFlow<AcousticAnalyzer.Features?> =
        _latestFeatures

    private val _latestEnv =
        kotlinx.coroutines.flow.MutableStateFlow<com.mythara.secret.observe.env.EnvironmentContext.Snapshot?>(null)
    /** Latest environment-context snapshot tagged onto the most-
     *  recent transcript. Surfaced so the UI can show "in a
     *  meeting · loud · with the Watch nearby" hints next to the
     *  live transcript ticker. */
    val latestEnv: kotlinx.coroutines.flow.StateFlow<com.mythara.secret.observe.env.EnvironmentContext.Snapshot?> =
        _latestEnv

    fun start(): Result<Unit> {
        if (isRunning) return Result.success(Unit)
        if (!asr.isReady()) return Result.failure(IllegalStateException("Vosk model not ready"))

        // Acquire the mic via the broker FIRST. If another mode (Mythara
        // wake-word listener / continuous voice chat) is currently
        // holding it, refuse cleanly with a clear error — UI surfaces
        // this so the user can toggle the other mode off.
        if (!micBroker.acquire(MicBroker.Client.OBSERVE)) {
            val current = micBroker.owner.value?.let { micBroker.describe(it) } ?: "another client"
            return Result.failure(IllegalStateException("Microphone busy — $current is using it"))
        }

        val recorder = AudioRecorder()
        if (!recorder.start()) {
            micBroker.release(MicBroker.Client.OBSERVE)
            return Result.failure(IllegalStateException("AudioRecord init failed"))
        }
        val recognizer = runCatching { asr.newRecognizer() }.getOrElse {
            recorder.release()
            return Result.failure(it)
        }

        val transcriptsDir = ctx.filesDir.resolve("observe/transcripts").apply { mkdirs() }
        val buf = ShortArray(recorder.readFrameSamples)

        // Per-utterance PCM buffer for acoustic analysis (M8.5 phase 2).
        // Sized for ~30s @ 16kHz mono int16 → ~960KB resident, freed on
        // every utterance final. The clipping behaviour (`min` on copy)
        // means a very long utterance just gets analysed up to 30s and
        // the rest is fed to Vosk normally; we don't grow the buffer
        // because that would invite OOM on rambly speakers.
        val utteranceBuf = ShortArray(MAX_UTTERANCE_SAMPLES)
        var utteranceSize = 0

        job = scope.launch {
            try {
                while (isActive) {
                    if (paused) {
                        // Cheap idle while paused; mic released only on stop().
                        kotlinx.coroutines.delay(200)
                        continue
                    }
                    val n = recorder.read(buf)
                    if (n <= 0) continue
                    // Append to per-utterance buffer up to the cap.
                    val toCopy = minOf(n, utteranceBuf.size - utteranceSize)
                    if (toCopy > 0) {
                        System.arraycopy(buf, 0, utteranceBuf, utteranceSize, toCopy)
                        utteranceSize += toCopy
                    }
                    val isFinal = recognizer.acceptWaveForm(buf, n)
                    if (isFinal) {
                        val resultJson = recognizer.result
                        val text = asr.parseText(resultJson)
                        if (text.isNotBlank()) {
                            val spkVec = asr.parseSpk(resultJson)
                            val acoustic = analyseUtterance(
                                utteranceBuf, utteranceSize, text,
                            )
                            // Phase G — push the freshly-completed
                            // utterance + its acoustic features +
                            // a snapshot of the environment context
                            // out to the UI panel before the write
                            // happens, so the user sees the live
                            // readout the moment they finish a
                            // sentence.
                            val envSnap = runCatching { environment.snapshot() }.getOrNull()
                            _latestFeatures.value = acoustic
                            _latestEnv.value = envSnap
                            _liveTranscript.value = ""
                            writeTranscript(transcriptsDir, text, spkVec, acoustic, envSnap)
                            transcriptCount += 1
                        }
                        utteranceSize = 0
                    } else {
                        // Phase G — emit the partial transcript so
                        // the UI ticker can show what's being heard
                        // in real time. The previous "ignored today"
                        // comment is now stale.
                        val partialJson = recognizer.partialResult
                        val partial = asr.parseText(partialJson)
                        if (partial.isNotBlank()) {
                            _liveTranscript.value = partial
                        }
                    }
                }
                // Drain final result on graceful stop.
                val tailJson = recognizer.finalResult
                val tail = asr.parseText(tailJson)
                if (tail.isNotBlank()) {
                    val tailSpk = asr.parseSpk(tailJson)
                    val tailAcoustic = analyseUtterance(utteranceBuf, utteranceSize, tail)
                    writeTranscript(transcriptsDir, tail, tailSpk, tailAcoustic)
                    transcriptCount += 1
                }
            } catch (t: Throwable) {
                Log.e(TAG, "session loop crashed: ${t.message}", t)
            } finally {
                runCatching { recognizer.close() }
                recorder.stop()
                recorder.release()
                micBroker.release(MicBroker.Client.OBSERVE)
                // Phase G — clear the live-UI surfaces on session
                // end so a stale transcript / acoustic readout
                // doesn't linger after stop.
                _liveTranscript.value = ""
                _latestFeatures.value = null
                _latestEnv.value = null
                Log.d(TAG, "session ended; transcripts=$transcriptCount")
            }
        }
        Log.d(TAG, "session started")
        return Result.success(Unit)
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    fun stop() {
        paused = false
        job?.cancel()
        job = null
    }

    /**
     * Compute acoustic features for the just-captured utterance.
     * Word count comes from the Vosk transcript so [AcousticAnalyzer]
     * doesn't have to re-tokenise. Falls back to an empty Features
     * struct on any error so the recording loop never crashes from
     * a DSP issue.
     */
    private fun analyseUtterance(
        pcm: ShortArray,
        validSamples: Int,
        text: String,
    ): AcousticAnalyzer.Features {
        if (validSamples <= 0) return AcousticAnalyzer.Features(0f, 0f, 0f, 0f)
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        return runCatching {
            acousticAnalyzer.analyze(
                pcm = pcm,
                validSamples = validSamples,
                sampleRate = AudioRecorder.SAMPLE_RATE,
                wordCount = wordCount,
            )
        }.getOrElse { e ->
            Log.w(TAG, "acoustic analysis failed: ${e.message}")
            AcousticAnalyzer.Features(0f, 0f, 0f, 0f)
        }
    }

    /**
     * Heuristic gate on whether the captured utterance is "clean
     * enough" for speaker-vector matching to be reliable. Vosk's
     * spk-0.4 model produces unstable x-vectors on:
     *   - very brief utterances (< ~3s of voiced audio)
     *   - mostly-silent buffers (PitchDetector returns f0=0)
     *   - very quiet audio (low RMS — usually background hum)
     *
     * Field data captured 2026-05-12 showed 0/3 → 1/3 match rate
     * after the threshold drop, with the two remaining failures
     * both being degenerate audio: one f0=0 (unvoiced), one
     * 2.9s/190Hz (too short, anomalous pitch). Gating on these
     * removes those false-negative log lines and avoids tagging
     * a transcript as `speaker:unknown` when the real issue was
     * that the audio wasn't recognisable as speech at all.
     */
    private fun isCleanForSpeakerMatch(features: AcousticAnalyzer.Features): Boolean {
        if (features.durationSec < MIN_SPK_DURATION_SEC) return false
        if (features.meanF0Hz <= 0f) return false // unvoiced
        if (features.meanRms < MIN_SPK_RMS) return false
        return true
    }

    private suspend fun writeTranscript(
        dir: File,
        text: String,
        spkVec: FloatArray? = null,
        acoustic: AcousticAnalyzer.Features = AcousticAnalyzer.Features(0f, 0f, 0f, 0f),
        env: com.mythara.secret.observe.env.EnvironmentContext.Snapshot? = null,
    ) {
        val now = System.currentTimeMillis()

        // Speaker ID: if Vosk emitted an x-vector for this utterance AND
        // the acoustic gate passes (utterance long enough + voiced +
        // not too quiet), match it against enrolled speakers. The gate
        // suppresses noisy comparisons on degenerate audio (silence,
        // very brief utterances) where Vosk's spk-0.4 x-vector is too
        // unstable for cosine matching to be trustworthy. Matched
        // facet propagates to every record derived from this
        // transcript so memory recall queries can filter by speaker.
        val gateClean = isCleanForSpeakerMatch(acoustic)
        val matched = if (spkVec != null && gateClean) {
            runCatching { speakerVault.matchBest(spkVec) }.getOrNull()
        } else null
        if (spkVec != null && !gateClean) {
            Log.d(
                TAG,
                "speaker match skipped — audio too short/quiet/unvoiced " +
                    "(dur=${"%.1f".format(acoustic.durationSec)}s " +
                    "f0=${"%.0f".format(acoustic.meanF0Hz)}Hz " +
                    "rms=${"%.3f".format(acoustic.meanRms)})",
            )
        }
        val speakerFacet = matched?.let { "speaker:${it.speaker.name}" }
        if (matched != null) {
            speakerVault.recordMatch(matched.speaker.id, now)
            Log.d(
                TAG,
                "tagged transcript as speaker:${matched.speaker.name} (sim=${matched.similarity})",
            )
        }
        val base = ISO_FMT.format(Date(now))
        val txtFile = File(dir, "$base.txt")
        runCatching { txtFile.writeText(text, Charsets.UTF_8) }

        // Embedding sidecar: 100-dim float32 vector (little-endian, ~400B).
        // Best-effort — if the embedder isn't ready yet, the transcript
        // is still captured. M8.3 SelfOrganizer will back-fill missing
        // embeddings on its nightly pass.
        var transcriptEmbedding: FloatArray? = null
        var embModelId: String? = null
        if (embedder.isReady()) {
            runCatching {
                val vec = embedder.embed(text)
                val vecFile = File(dir, "$base.vec")
                vecFile.writeBytes(LocalEmbedder.encode(vec))
                transcriptEmbedding = vec
                embModelId = EmbeddingsModelStore.MODEL_ID
            }.onFailure { e ->
                android.util.Log.w(TAG, "embed failed: ${e.message}")
            }
        }

        // Acoustic-feature facets (M8.5 phase 2). Bucketed ordinal
        // labels so they ride through the same facet pipeline as
        // mood/speaker/topic. Future calibration work can use the
        // raw Features object to refine the Gemma text-only mood.
        val acousticFacets: List<String> = if (acoustic.durationSec > 0f) {
            Log.d(
                TAG,
                "acoustic: f0=${"%.0f".format(acoustic.meanF0Hz)}Hz " +
                    "rms=${"%.3f".format(acoustic.meanRms)} " +
                    "wps=${"%.2f".format(acoustic.wordsPerSec)} " +
                    "dur=${"%.1f".format(acoustic.durationSec)}s",
            )
            acousticAnalyzer.bucket(acoustic)
        } else emptyList()

        // ---- Vault writes ----
        // 1. Working-tier record holding the raw transcript text + its
        //    embedding. Stays local; never synced (see MemorySync filter).
        // Phase G — append environment facets (env:meeting / env:loud /
        // env:quiet / proximity:* / etc.) so future learning queries
        // can filter "what was I saying while in a meeting" without
        // re-parsing the timestamp against calendar etc. The facets
        // come from EnvironmentContext.snapshot(), captured at the
        // utterance-final boundary above.
        val envFacets: List<String> = env?.toFacets().orEmpty()
        val refId = "transcript:$base"
        val transcriptFacets = buildList {
            add("kind:transcript")
            if (speakerFacet != null) add(speakerFacet)
            addAll(acousticFacets)
            addAll(envFacets)
        }
        vault.add(
            content = text,
            tier = Tier.Working,
            src = "observe:vosk",
            facets = transcriptFacets,
            embedding = transcriptEmbedding,
            embModel = embModelId,
            ref = refId,
            conf = 0.9,
            now = now,
        )

        // 2a. Explicit "Hey Mythara, ..." notes. Always conf=1.0 because the
        //     user literally addressed the assistant; no probabilistic
        //     extraction needed. We still let the Gemma/heuristic
        //     extractor run below in case the same utterance carries
        //     implicit facts as well — e.g. "Mythara, note that I prefer
        //     dark roast" both records the deliberate note AND
        //     reinforces the preference.
        var explicitNoteCount = 0
        QuickNoteDetector.detect(text)?.let { noteText ->
            val noteEmbedding = if (embedder.isReady()) {
                runCatching { embedder.embed(noteText) }.getOrNull()
            } else null
            val noteFacets = buildList {
                add("kind:explicit-note")
                add("addressed:lumi")
                if (speakerFacet != null) add(speakerFacet)
                addAll(acousticFacets)
            }
            val added = vault.add(
                content = noteText,
                tier = Tier.Semantic,
                src = "observe:note-to-lumi",
                facets = noteFacets,
                embedding = noteEmbedding,
                embModel = if (noteEmbedding != null) EmbeddingsModelStore.MODEL_ID else null,
                ref = refId,
                conf = 1.0,
                now = now,
            )
            if (added) explicitNoteCount += 1
            Log.d(TAG, "Lumi note captured: ${noteText.take(80)}")
        }

        // 2b. Heuristic / Gemma-extracted semantic facts. These DO sync —
        //     they're durable observations about the user, not raw audio
        //     content. SemanticExtractor picks Gemma when its model is
        //     loaded, falls back to heuristic regex otherwise. Gemma
        //     also returns a single mood label for the whole transcript
        //     which lands as `mood:<label>` on every record produced
        //     here AND on the working-tier transcript record above
        //     (back-patched into the transcript's facets via the
        //     existing seen-bump path; for v1 we leave the working
        //     record's mood facet absent — mood is a Gemma-only signal
        //     attached to derived semantic facts).
        val extractionResult = runCatching { extractor.extract(text) }.getOrNull()
        val moodFacet = extractionResult?.mood?.let { "mood:$it" }
        var semanticCount = 0
        if (extractionResult != null) {
            val srcTag = "extract:${extractionResult.source}"
            for (fact in extractionResult.facts) {
                val factEmbedding = if (embedder.isReady()) {
                    runCatching { embedder.embed(fact.content) }.getOrNull()
                } else null
                val factFacets = buildList {
                    addAll(fact.facets)
                    if (speakerFacet != null && fact.facets.none { it.startsWith("speaker:") }) {
                        add(speakerFacet)
                    }
                    if (moodFacet != null && fact.facets.none { it.startsWith("mood:") }) {
                        add(moodFacet)
                    }
                    for (af in acousticFacets) {
                        val key = af.substringBefore(':') + ":"
                        if (fact.facets.none { it.startsWith(key) }) add(af)
                    }
                }
                val added = vault.add(
                    content = fact.content,
                    tier = Tier.Semantic,
                    src = srcTag,
                    facets = factFacets,
                    embedding = factEmbedding,
                    embModel = if (factEmbedding != null) EmbeddingsModelStore.MODEL_ID else null,
                    ref = refId,
                    conf = fact.conf,
                    now = now,
                )
                if (added) semanticCount++
            }
            if (extractionResult.mood != null) {
                Log.d(TAG, "transcript mood: ${extractionResult.mood} (via ${extractionResult.source})")
            }
        }

        // Metadata-only journal entry — never the transcript text.
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val embedNote = transcriptEmbedding?.let { "${it.size}-dim emb" } ?: "no emb"
        val noteSummary = buildString {
            append("captured transcript ($wordCount words, $embedNote, ")
            append("$semanticCount semantic facts")
            if (explicitNoteCount > 0) append(", $explicitNoteCount Lumi note")
            append(")")
        }
        journal.append(
            LearningJournal.Entry(
                tsMillis = now,
                kind = "observe",
                note = noteSummary,
            ),
        )
    }

    companion object {
        private const val TAG = "Mythara/Observe"

        /** Max samples buffered per utterance for acoustic analysis.
         *  At 16 kHz, 30s × 16000 = 480_000 — about 960 KB resident.
         *  Beyond this the utterance is still transcribed; only the
         *  acoustic features are computed from the first 30 seconds. */
        private const val MAX_UTTERANCE_SAMPLES = 480_000

        /** Below this duration, Vosk's speaker x-vector isn't stable
         *  enough for reliable matching. Tuned empirically — at 2.9s
         *  with anomalous-pitch audio we saw a 0.13 similarity to the
         *  enrolled reference (wildly off); at 5.2s with normal audio
         *  it landed at 0.44 (clean match). 3s feels like the right
         *  conservative cutoff. */
        private const val MIN_SPK_DURATION_SEC = 3.0f

        /** Mean RMS below this is essentially noise floor for the
         *  VOICE_RECOGNITION mic source on a Pixel — typically means
         *  background hum or a very distant speaker. The x-vector
         *  computed off such audio drifts. */
        private const val MIN_SPK_RMS = 0.005f

        private val ISO_FMT = SimpleDateFormat("yyyyMMdd'T'HHmmss'_'SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
}
