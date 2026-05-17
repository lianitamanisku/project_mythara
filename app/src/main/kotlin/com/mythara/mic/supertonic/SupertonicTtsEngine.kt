package com.mythara.mic.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * On-device TTS via Supertonic-2 ONNX models.
 *
 * Pipeline (matches the supertone-inc/supertonic Java reference):
 *
 *   text
 *     → UnicodeProcessor.preprocess (normalise, language wrap)
 *     → code-points → indexer lookup → long[] text IDs
 *     → text_encoder.onnx           → text_emb
 *     → duration_predictor.onnx     → duration[] per token
 *     → Box-Muller noisy latent of shape [1, latent_dim, latent_len]
 *     → vector_estimator.onnx × N denoising steps → cleaned latent
 *     → vocoder.onnx                → audio waveform float[]
 *     → AudioTrack streaming playback
 *
 * Sessions live for the engine's lifetime; the inference mutex
 * serialises all calls so a second `speak()` queues behind the first
 * rather than crashing the ONNX session.
 *
 * Falls through silently when:
 *   - the model files aren't installed (caller checks
 *     [SupertonicModelStore.isInstalled] first)
 *   - any ONNX call throws (logged, returns false)
 *   - text is empty after preprocessing
 *
 * Privacy: text and audio bytes stay on-device. No network at
 * inference time; the only network footprint is the one-shot
 * model download via [SupertonicModelStore].
 */
@Singleton
class SupertonicTtsEngine @Inject constructor(
    @ApplicationContext private val ctx: android.content.Context,
    private val store: SupertonicModelStore,
) {

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var sessions: Sessions? = null
    @Volatile private var config: TtsConfig? = null
    @Volatile private var indexer: LongArray? = null
    @Volatile private var style: VoiceStyle? = null

    private val initLock = Mutex()
    /** Serialises actual ONNX inference so concurrent speak() calls
     *  queue safely; AudioTrack also gets exclusive access this way. */
    private val inferenceLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var playJob: Job? = null
    @Volatile private var currentTrack: AudioTrack? = null

    /** True iff all models + configs loaded successfully. Reflects
     *  whether speak() will actually produce audio. */
    suspend fun isReady(): Boolean {
        if (sessions != null) return true
        if (!store.isInstalled()) return false
        return initLock.withLock {
            if (sessions != null) return@withLock true
            tryLoad()
        }
    }

    /**
     * Synthesise [text] and start asynchronous playback via
     * [AudioTrack]. Suspends until playback STARTS (so the caller
     * can flip the "speaking" flag), then returns; playback
     * continues on the engine's own scope.
     *
     * Calls [onStart] when audio begins, [onDone] when it finishes
     * (or fails). Returns true if synthesis succeeded + playback
     * was scheduled; false on any failure (model not loaded, ONNX
     * error, empty audio).
     */
    suspend fun speak(
        text: String,
        lang: String = "en",
        speed: Float = 1.05f,
        totalStep: Int = 8,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
    ): Boolean {
        if (text.isBlank()) return false
        if (!isReady()) return false
        val s = sessions ?: return false
        val cfg = config ?: return false
        val idx = indexer ?: return false
        val sty = style ?: return false

        return inferenceLock.withLock {
            runCatching {
                stopInternal()
                val pcm = synthesizeWaveform(text, lang, s, cfg, idx, sty, speed, totalStep)
                    ?: return@runCatching false
                schedulePlayback(pcm, cfg.sampleRate, onStart, onDone)
                true
            }.getOrElse {
                Log.w(TAG, "speak failed: ${it.message}", it)
                onDone()
                false
            }
        }
    }

    /** Stop any in-flight playback. Safe to call from any thread. */
    fun stop() {
        playJob?.cancel()
        playJob = null
        runCatching { currentTrack?.pause(); currentTrack?.flush(); currentTrack?.release() }
        currentTrack = null
    }

    private fun stopInternal() = stop()

    fun release() {
        stop()
        runCatching { sessions?.close() }
        sessions = null
        runCatching { env?.close() }
        env = null
        scope.cancel()
    }

    // ── Model load ──────────────────────────────────────────────────

    private fun tryLoad(): Boolean = runCatching {
        val dir = store.modelDir()
        val cfgFile = File(dir, "tts.json")
        val idxFile = File(dir, "unicode_indexer.json")
        val styleFile = File(dir, SupertonicModelStore.DEFAULT_VOICE_STYLE)
        if (!cfgFile.exists() || !idxFile.exists() || !styleFile.exists()) {
            Log.w(TAG, "missing config / indexer / style file")
            return@runCatching false
        }

        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        val sess = Sessions(
            duration = e.createSession(File(dir, "duration_predictor.onnx").absolutePath, opts),
            textEnc = e.createSession(File(dir, "text_encoder.onnx").absolutePath, opts),
            vectorEst = e.createSession(File(dir, "vector_estimator.onnx").absolutePath, opts),
            vocoder = e.createSession(File(dir, "vocoder.onnx").absolutePath, opts),
        )
        config = parseConfig(cfgFile.readText())
        indexer = parseIndexer(idxFile.readText())
        style = parseVoiceStyle(styleFile.readText(), e)
        env = e
        sessions = sess
        Log.i(TAG, "Supertonic engine ready · sample_rate=${config!!.sampleRate}")
        true
    }.getOrElse {
        Log.w(TAG, "load failed: ${it.message}", it)
        false
    }

    // ── Synthesis ───────────────────────────────────────────────────

    /** Run the full TTS pipeline on a single string. Returns a
     *  16-bit-mono PCM byte array ready for AudioTrack. */
    private fun synthesizeWaveform(
        text: String,
        lang: String,
        s: Sessions,
        cfg: TtsConfig,
        idx: LongArray,
        sty: VoiceStyle,
        speed: Float,
        totalStep: Int,
    ): ByteArray? {
        val preprocessed = preprocessText(text, lang)
        val codePoints = preprocessed.codePoints().toArray()
        if (codePoints.isEmpty()) return null
        val textIds = LongArray(codePoints.size) { i ->
            val cp = codePoints[i]
            if (cp >= 0 && cp < idx.size) idx[cp] else 0L
        }
        val textMask = FloatArray(textIds.size) { 1f }

        val e = env ?: return null
        val textIdsT = OnnxTensor.createTensor(e, LongBuffer.wrap(textIds), longArrayOf(1, textIds.size.toLong()))
        val textMaskT = OnnxTensor.createTensor(e, FloatBuffer.wrap(textMask), longArrayOf(1, 1, textMask.size.toLong()))

        // 1) Duration predictor → per-token frame counts
        val dpResult = s.duration.run(
            mapOf(
                "text_ids" to textIdsT,
                "style_dp" to sty.dp,
                "text_mask" to textMaskT,
            ),
        )
        val rawDur = dpResult.get(0).value
        val duration = when (rawDur) {
            is Array<*> -> @Suppress("UNCHECKED_CAST") (rawDur as Array<FloatArray>)[0]
            is FloatArray -> rawDur
            else -> {
                Log.w(TAG, "unexpected duration shape: ${rawDur::class.java}")
                dpResult.close()
                textIdsT.close(); textMaskT.close()
                return null
            }
        }
        for (i in duration.indices) duration[i] = duration[i] / speed
        dpResult.close()

        // 2) Text encoder → embeddings (used inside the denoising loop)
        val encResult = s.textEnc.run(
            mapOf(
                "text_ids" to textIdsT,
                "style_ttl" to sty.ttl,
                "text_mask" to textMaskT,
            ),
        )
        val textEmb = encResult.get(0) as OnnxTensor

        // 3) Box-Muller noise + latent mask
        val (noisy, latentMask) = sampleNoisyLatent(duration, cfg)
        var xt = noisy
        val latentMaskT = createFloatTensor3D(latentMask, e)

        // 4) Denoising loop
        val totalStepT = OnnxTensor.createTensor(e, floatArrayOf(totalStep.toFloat()))
        for (step in 0 until totalStep) {
            val curStepT = OnnxTensor.createTensor(e, floatArrayOf(step.toFloat()))
            val xtT = createFloatTensor3D(xt, e)
            val tmT = OnnxTensor.createTensor(e, FloatBuffer.wrap(textMask), longArrayOf(1, 1, textMask.size.toLong()))
            val veResult = s.vectorEst.run(
                mapOf(
                    "noisy_latent" to xtT,
                    "text_emb" to textEmb,
                    "style_ttl" to sty.ttl,
                    "latent_mask" to latentMaskT,
                    "text_mask" to tmT,
                    "current_step" to curStepT,
                    "total_step" to totalStepT,
                ),
            )
            @Suppress("UNCHECKED_CAST")
            xt = veResult.get(0).value as Array<Array<FloatArray>>
            veResult.close()
            curStepT.close(); xtT.close(); tmT.close()
        }
        totalStepT.close()
        encResult.close()

        // 5) Vocoder → audio float[] in [-1, 1]
        val xtFinalT = createFloatTensor3D(xt, e)
        val vocResult = s.vocoder.run(mapOf("latent" to xtFinalT))
        @Suppress("UNCHECKED_CAST")
        val wavBatch = vocResult.get(0).value as Array<FloatArray>
        vocResult.close()
        xtFinalT.close()
        textIdsT.close(); textMaskT.close(); latentMaskT.close()

        // First batch element only (we always send bsz=1).
        val wav = wavBatch[0]
        // Trim to the actual predicted duration (vocoder pads to
        // the latent length, which is usually a couple frames longer).
        // duration[0] is in SECONDS — multiply by sample rate for
        // a sample-count cap. Parenthesise carefully: `?:` binds
        // tighter than `*`, so without these parens we'd compute
        // (duration[0] ?: (0f * sr)).toInt() and end up trimming
        // the wav to LITERAL seconds-count samples (e.g. 2 samples
        // for a 2.5s utterance instead of 60_000 samples). Result
        // would be silent audio. Same fix below for wavLen in
        // sampleNoisyLatent.
        val sr = cfg.sampleRate
        val realLen = ((duration.firstOrNull() ?: 0f) * sr).toInt().coerceIn(0, wav.size)
        val trimmed = if (realLen > 0 && realLen < wav.size) wav.copyOfRange(0, realLen) else wav
        Log.i(
            TAG,
            "synth ok · dur=${"%.2f".format(duration.firstOrNull() ?: 0f)}s " +
                "samples=${trimmed.size} sr=$sr (vocoder emitted ${wav.size})",
        )

        return floatPcmToShortPcmBytes(trimmed)
    }

    private fun sampleNoisyLatent(
        duration: FloatArray,
        cfg: TtsConfig,
    ): Pair<Array<Array<FloatArray>>, Array<Array<FloatArray>>> {
        val sr = cfg.sampleRate
        val bcs = cfg.baseChunkSize
        val ccf = cfg.chunkCompressFactor
        val ldim = cfg.latentDim
        val wavLenMax = (duration.maxOrNull() ?: 0f) * sr
        // Same precedence trap as in synthesizeWaveform — paren
        // the null-coalesce BEFORE the multiplication.
        val wavLen = ((duration.firstOrNull() ?: 0f) * sr).toLong()
        val chunkSize = bcs * ccf
        val latentLen = ((wavLenMax + chunkSize - 1) / chunkSize).toInt()
        val latentDim = ldim * ccf
        val rng = Random.Default
        val noisy = Array(1) {
            Array(latentDim) {
                FloatArray(latentLen) {
                    // Box-Muller: two uniforms → standard normal.
                    val u1 = max(1e-10, rng.nextDouble())
                    val u2 = rng.nextDouble()
                    (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)).toFloat()
                }
            }
        }
        val latentSize = bcs.toLong() * ccf
        val latentMaskLen = ((wavLen + latentSize - 1) / latentSize).toInt()
        val mask = Array(1) {
            Array(1) {
                FloatArray(latentLen) { idx -> if (idx < latentMaskLen) 1f else 0f }
            }
        }
        for (d in 0 until latentDim) {
            for (t in 0 until latentLen) {
                noisy[0][d][t] *= mask[0][0][t]
            }
        }
        return noisy to mask
    }

    private fun createFloatTensor3D(a: Array<Array<FloatArray>>, e: OrtEnvironment): OnnxTensor {
        val d0 = a.size; val d1 = a[0].size; val d2 = a[0][0].size
        val flat = FloatArray(d0 * d1 * d2)
        var i = 0
        for (b in 0 until d0) for (c in 0 until d1) for (t in 0 until d2) flat[i++] = a[b][c][t]
        return OnnxTensor.createTensor(e, FloatBuffer.wrap(flat), longArrayOf(d0.toLong(), d1.toLong(), d2.toLong()))
    }

    private fun floatPcmToShortPcmBytes(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[2 * i] = (s.toInt() and 0xff).toByte()
            bytes[2 * i + 1] = ((s.toInt() shr 8) and 0xff).toByte()
        }
        return bytes
    }

    // ── Playback ────────────────────────────────────────────────────

    private fun schedulePlayback(
        pcm: ByteArray,
        sampleRate: Int,
        onStart: () -> Unit,
        onDone: () -> Unit,
    ) {
        playJob?.cancel()
        playJob = scope.launch {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                max(minBuf, pcm.size.coerceAtMost(64 * 1024)),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            currentTrack = track
            track.play()
            onStart()
            try {
                var written = 0
                while (written < pcm.size && isActive()) {
                    val chunk = (pcm.size - written).coerceAtMost(8 * 1024)
                    val n = track.write(pcm, written, chunk)
                    if (n <= 0) break
                    written += n
                }
                // Wait for the buffered audio to actually finish
                // playing — AudioTrack.write returns once buffered,
                // not when played. AudioTrack has no "wait until
                // done" API in stream mode; sleep approximating the
                // remaining buffer duration is the standard pattern.
                val frames = pcm.size / 2
                val durMs = (frames.toLong() * 1000L) / sampleRate
                kotlinx.coroutines.delay(durMs.coerceAtLeast(0L))
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
                if (currentTrack === track) currentTrack = null
                onDone()
            }
        }
    }

    private fun CoroutineScope.isActive(): Boolean = coroutineContext[Job]?.isActive ?: false

    // ── Config / indexer / voice-style parsing ──────────────────────

    private fun parseConfig(raw: String): TtsConfig {
        val root = json.parseToJsonElement(raw).jsonObject
        val ae = root["ae"]!!.jsonObject
        val ttl = root["ttl"]!!.jsonObject
        return TtsConfig(
            sampleRate = ae["sample_rate"]!!.jsonPrimitive.long.toInt(),
            baseChunkSize = ae["base_chunk_size"]!!.jsonPrimitive.long.toInt(),
            chunkCompressFactor = ttl["chunk_compress_factor"]!!.jsonPrimitive.long.toInt(),
            latentDim = ttl["latent_dim"]!!.jsonPrimitive.long.toInt(),
        )
    }

    private fun parseIndexer(raw: String): LongArray {
        val arr = json.parseToJsonElement(raw).jsonArray
        return LongArray(arr.size) { i -> arr[i].jsonPrimitive.long }
    }

    private fun parseVoiceStyle(raw: String, e: OrtEnvironment): VoiceStyle {
        val root = json.parseToJsonElement(raw).jsonObject
        val ttl = parseStyleNode(root["style_ttl"]!!.jsonObject, e)
        val dp = parseStyleNode(root["style_dp"]!!.jsonObject, e)
        return VoiceStyle(ttl = ttl, dp = dp)
    }

    private fun parseStyleNode(node: JsonObject, e: OrtEnvironment): OnnxTensor {
        val dims = node["dims"]!!.jsonArray.map { it.jsonPrimitive.long }
        val data = node["data"]!!.jsonArray
        // Shape is [bsz, dim1, dim2]; we batch=1 so flatten the
        // single batch row into one FloatArray of dim1*dim2 floats.
        val dim1 = dims[1].toInt(); val dim2 = dims[2].toInt()
        val flat = FloatArray(dim1 * dim2)
        var i = 0
        val firstBatch = data[0].jsonArray
        for (row in firstBatch) {
            for (v in row.jsonArray) {
                flat[i++] = v.jsonPrimitive.contentOrNull?.toFloatOrNull() ?: 0f
            }
        }
        return OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(flat),
            longArrayOf(1L, dim1.toLong(), dim2.toLong()),
        )
    }

    // ── Text preprocessing (ported from supertonic Java reference) ──

    private fun preprocessText(text: String, lang: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
        t = removeEmojis(t)
        // Dash + quote normalisation
        t = t
            .replace("–", "-").replace("‑", "-").replace("—", "-")
            .replace("_", " ")
            .replace("“", "\"").replace("”", "\"")
            .replace("‘", "'").replace("’", "'")
            .replace("´", "'").replace("`", "'")
            .replace("[", " ").replace("]", " ")
            .replace("|", " ").replace("/", " ").replace("#", " ")
            .replace("→", " ").replace("←", " ")
        // Drop misc symbols
        t = t.replace(Regex("[♥☆♡©\\\\]"), "")
        // Common expansions
        t = t.replace("@", " at ")
            .replace("e.g.,", "for example, ")
            .replace("i.e.,", "that is, ")
        // Spacing around punctuation
        t = t.replace(Regex(" ,"), ",").replace(Regex(" \\."), ".")
            .replace(Regex(" !"), "!").replace(Regex(" \\?"), "?")
            .replace(Regex(" ;"), ";").replace(Regex(" :"), ":")
            .replace(Regex(" '"), "'")
        // Dedupe quotes
        while (t.contains("\"\"")) t = t.replace("\"\"", "\"")
        while (t.contains("''")) t = t.replace("''", "'")
        while (t.contains("``")) t = t.replace("``", "`")
        // Collapse whitespace
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.isNotEmpty() && !t.matches(Regex(".*[.!?;:,'\"\\u201C\\u201D\\u2018\\u2019)\\]}…。」』】〉》›»]$"))) {
            t += "."
        }
        return "<$lang>$t</$lang>"
    }

    private fun removeEmojis(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp: Int
            if (Character.isHighSurrogate(text[i]) && i + 1 < text.length &&
                Character.isLowSurrogate(text[i + 1])
            ) {
                cp = text.codePointAt(i)
                i += 2
            } else {
                cp = text[i].code
                i += 1
            }
            val isEmoji = (cp in 0x1F600..0x1F64F) ||
                (cp in 0x1F300..0x1F5FF) ||
                (cp in 0x1F680..0x1F6FF) ||
                (cp in 0x1F700..0x1F77F) ||
                (cp in 0x1F780..0x1F7FF) ||
                (cp in 0x1F800..0x1F8FF) ||
                (cp in 0x1F900..0x1F9FF) ||
                (cp in 0x1FA00..0x1FA6F) ||
                (cp in 0x1FA70..0x1FAFF) ||
                (cp in 0x2600..0x26FF) ||
                (cp in 0x2700..0x27BF) ||
                (cp in 0x1F1E6..0x1F1FF)
            if (!isEmoji) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    // ── Internal types ──────────────────────────────────────────────

    private class Sessions(
        val duration: OrtSession,
        val textEnc: OrtSession,
        val vectorEst: OrtSession,
        val vocoder: OrtSession,
    ) : AutoCloseable {
        override fun close() {
            runCatching { duration.close() }
            runCatching { textEnc.close() }
            runCatching { vectorEst.close() }
            runCatching { vocoder.close() }
        }
    }

    private data class TtsConfig(
        val sampleRate: Int,
        val baseChunkSize: Int,
        val chunkCompressFactor: Int,
        val latentDim: Int,
    )

    private class VoiceStyle(val ttl: OnnxTensor, val dp: OnnxTensor)

    companion object {
        private const val TAG = "Mythara/Supertonic"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
