package com.mythara.secret.observe

import android.content.Context
import android.util.Log
import com.mythara.growth.LearningJournal
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.extract.LumiNoteDetector
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var paused: Boolean = false
    @Volatile private var transcriptCount: Int = 0

    val isRunning: Boolean get() = job?.isActive == true
    fun transcriptCountSnapshot(): Int = transcriptCount

    fun start(): Result<Unit> {
        if (isRunning) return Result.success(Unit)
        if (!asr.isReady()) return Result.failure(IllegalStateException("Vosk model not ready"))

        val recorder = AudioRecorder()
        if (!recorder.start()) {
            return Result.failure(IllegalStateException("AudioRecord init failed"))
        }
        val recognizer = runCatching { asr.newRecognizer() }.getOrElse {
            recorder.release()
            return Result.failure(it)
        }

        val transcriptsDir = ctx.filesDir.resolve("observe/transcripts").apply { mkdirs() }
        val buf = ShortArray(recorder.readFrameSamples)

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
                    val isFinal = recognizer.acceptWaveForm(buf, n)
                    if (isFinal) {
                        val resultJson = recognizer.result
                        val text = asr.parseText(resultJson)
                        if (text.isNotBlank()) {
                            val spkVec = asr.parseSpk(resultJson)
                            writeTranscript(transcriptsDir, text, spkVec)
                            transcriptCount += 1
                        }
                    }
                }
                // Drain final result on graceful stop.
                val tailJson = recognizer.finalResult
                val tail = asr.parseText(tailJson)
                if (tail.isNotBlank()) {
                    val tailSpk = asr.parseSpk(tailJson)
                    writeTranscript(transcriptsDir, tail, tailSpk)
                    transcriptCount += 1
                }
            } catch (t: Throwable) {
                Log.e(TAG, "session loop crashed: ${t.message}", t)
            } finally {
                runCatching { recognizer.close() }
                recorder.stop()
                recorder.release()
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

    private suspend fun writeTranscript(dir: File, text: String, spkVec: FloatArray? = null) {
        val now = System.currentTimeMillis()

        // Speaker ID: if Vosk emitted an x-vector for this utterance AND
        // we have any enrolled speakers, find the best match. The
        // resulting facet (when present) gets attached to every record
        // we create from this transcript — the working-tier transcript
        // itself, all explicit-note records, and all Gemma/heuristic
        // semantic extractions. That way memory recall queries like
        // "what did Sarah say about her trip" can filter by speaker.
        val matched = if (spkVec != null) {
            runCatching { speakerVault.matchBest(spkVec) }.getOrNull()
        } else null
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

        // ---- Vault writes ----
        // 1. Working-tier record holding the raw transcript text + its
        //    embedding. Stays local; never synced (see MemorySync filter).
        val refId = "transcript:$base"
        val transcriptFacets = buildList {
            add("kind:transcript")
            if (speakerFacet != null) add(speakerFacet)
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

        // 2a. Explicit "Hey Lumi, ..." notes. Always conf=1.0 because the
        //     user literally addressed the assistant; no probabilistic
        //     extraction needed. We still let the Gemma/heuristic
        //     extractor run below in case the same utterance carries
        //     implicit facts as well — e.g. "Lumi, note that I prefer
        //     dark roast" both records the deliberate note AND
        //     reinforces the preference.
        var explicitNoteCount = 0
        LumiNoteDetector.detect(text)?.let { noteText ->
            val noteEmbedding = if (embedder.isReady()) {
                runCatching { embedder.embed(noteText) }.getOrNull()
            } else null
            val noteFacets = buildList {
                add("kind:explicit-note")
                add("addressed:lumi")
                if (speakerFacet != null) add(speakerFacet)
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
        private val ISO_FMT = SimpleDateFormat("yyyyMMdd'T'HHmmss'_'SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
}
