package com.mythara.mic.supertonic

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk store + lazy downloader for the Supertonic-2 ONNX files.
 *
 * Supertonic-2 is a 66M-param multilingual on-device TTS distributed
 * as 4 ONNX models plus 2 small JSON configs:
 *
 *   filesDir/supertonic/
 *     ├── text_encoder.onnx        (~3 MB)
 *     ├── duration_predictor.onnx  (~5 MB)
 *     ├── vector_estimator.onnx    (~50 MB)
 *     ├── vocoder.onnx             (~200 MB) — biggest piece, mel→wav
 *     ├── tts.json                 (model config: sample rate, dims)
 *     └── unicode_indexer.json     (text → unicode-id lookup table)
 *
 * Plus voice styles, which we bundle as APK assets rather than
 * download — they're tiny (~5–20 KB each) and shipping them lets the
 * user start synthesising the instant the models finish downloading.
 *
 * Total network footprint: ~270 MB. Download is gated by a
 * user-explicit "install on-device voice" action in Settings — we
 * never pull it speculatively to keep first-launch cellular traffic
 * minimal. State is observable so the Settings panel can render
 * progress / readiness.
 *
 * Source: `onnx-community/Supertonic-TTS-2-ONNX` on Hugging Face,
 * a community-converted ONNX export of the official Supertone
 * weights. We use the LFS-backed raw URLs (resolve/main/...) so a
 * direct GET returns the binary, not the LFS pointer file.
 */
@Singleton
class SupertonicModelStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    enum class State { Idle, Downloading, Installed, Failed }

    private val _state = MutableStateFlow(
        if (allFilesPresent()) State.Installed else State.Idle,
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    /** 0..1 — coarse overall progress across the file set; updated
     *  per-file rather than per-byte to keep observers cheap. */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        // HF resolves `/resolve/main/<path>` with a 302 / 307 to the
        // CAS-bridge CDN (cas-bridge.xethub.hf.co). Both follow flags
        // are on by default in OkHttp, but make the intent explicit
        // — the cross-protocol case especially can be a silent
        // failure if a future config disables it.
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun isInstalled(): Boolean = allFilesPresent()

    fun modelDir(): File = File(ctx.filesDir, "supertonic").apply { mkdirs() }

    fun pathOf(name: String): File = File(modelDir(), name)

    /** Cheap presence check — every required file must exist AND
     *  be non-empty (defends against half-finished downloads from
     *  a previous crashed attempt). */
    private fun allFilesPresent(): Boolean = FILES.all { (name, _) ->
        val f = pathOf(name)
        f.exists() && f.length() > 0
    }

    /** Download every missing file, in sequence, into [modelDir]. */
    suspend fun ensureInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (allFilesPresent()) {
            _state.value = State.Installed
            return@withContext true
        }
        // If the previous attempt failed (or this is a retry after a
        // URL fix that changes which repo we pull from), wipe any
        // partially-downloaded files first. Otherwise a leftover
        // text_encoder.onnx from the wrong repo would skip the
        // download check ("exists, size > 0") and we'd end up with
        // an incompatible model file mixed in with fresh ones.
        if (_state.value == State.Failed) {
            modelDir().listFiles()?.forEach { runCatching { it.delete() } }
        }
        _state.value = State.Downloading
        _progress.value = 0f
        modelDir() // ensure dir exists
        for ((index, entry) in FILES.withIndex()) {
            val (name, url) = entry
            val target = pathOf(name)
            if (target.exists() && target.length() > 0) {
                _progress.value = (index + 1).toFloat() / FILES.size
                continue
            }
            val ok = runCatching { downloadOne(url, target) }.getOrDefault(false)
            if (!ok) {
                Log.w(TAG, "download failed for $name")
                _state.value = State.Failed
                return@withContext false
            }
            _progress.value = (index + 1).toFloat() / FILES.size
        }
        _state.value = State.Installed
        true
    }

    private fun downloadOne(url: String, target: File): Boolean {
        Log.i(TAG, "downloading ${target.name} from $url")
        val req = Request.Builder().url(url).get().build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "http ${resp.code} for ${target.name}")
                return@use false
            }
            val body = resp.body ?: return@use false
            // Stream to a .part file then rename atomically so a
            // crashed download never gets observed as "installed".
            val tmp = File(target.parentFile, "${target.name}.part")
            tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            if (tmp.length() < MIN_BYTES_PER_FILE) {
                Log.w(TAG, "${target.name} suspiciously small (${tmp.length()}B) — likely 404 page")
                tmp.delete()
                return@use false
            }
            tmp.renameTo(target).also { renamed ->
                if (!renamed) tmp.delete()
            }
        }
    }

    /** Hard reset — wipes downloaded files. Mostly for QA + the
     *  "free up space" Settings action. */
    suspend fun wipe() = withContext(Dispatchers.IO) {
        runCatching {
            modelDir().listFiles()?.forEach { it.delete() }
            _state.value = State.Idle
            _progress.value = 0f
        }
    }

    companion object {
        private const val TAG = "Mythara/SupertonicDL"

        /** Min size sanity check per file — anything below this is
         *  almost certainly an LFS pointer or an error page, not
         *  the real model. The smallest real file (unicode_indexer
         *  + tts.json) is several KB. */
        private const val MIN_BYTES_PER_FILE = 1_000L

        /**
         * File set + download URLs. Pulled from the
         * **official** `Supertone/supertonic-2` repo on Hugging Face
         * (the canonical one the Java reference targets), not the
         * `onnx-community/...` transformers.js port — that port uses
         * a different file layout (3 ONNX bundles with sidecar
         * `.onnx_data` weight files, binary voice files, tokenizer.json
         * instead of unicode_indexer.json + tts.json), which would
         * require a different inference pipeline than the one in
         * [SupertonicTtsEngine].
         *
         * URL form `resolve/main/<path>` returns the actual file
         * (302/307 → CDN → bytes) — no `?download=true` query needed
         * here since these aren't LFS-pointer-blob files on the HF
         * side; they redirect straight to the binary.
         */
        private val BASE_URL =
            "https://huggingface.co/Supertone/supertonic-2/resolve/main"

        /** Default voice style — male, neutral. The user can swap to
         *  F1/M2/etc. later by adding more downloads, but a single
         *  style is enough to start synthesising. */
        const val DEFAULT_VOICE_STYLE = "M1.json"

        private val FILES = listOf(
            "text_encoder.onnx" to "$BASE_URL/onnx/text_encoder.onnx",
            "duration_predictor.onnx" to "$BASE_URL/onnx/duration_predictor.onnx",
            "vector_estimator.onnx" to "$BASE_URL/onnx/vector_estimator.onnx",
            "vocoder.onnx" to "$BASE_URL/onnx/vocoder.onnx",
            "tts.json" to "$BASE_URL/onnx/tts.json",
            "unicode_indexer.json" to "$BASE_URL/onnx/unicode_indexer.json",
            DEFAULT_VOICE_STYLE to "$BASE_URL/voice_styles/$DEFAULT_VOICE_STYLE",
        )
    }
}
