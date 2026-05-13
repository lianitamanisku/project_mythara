package com.mythara.secret.observe.extract.gemma

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazy fetcher for the Gemma .task bundle that powers M8.2.1's
 * on-device fact extractor. Same self-healing pattern as
 * [com.mythara.secret.observe.vosk.VoskModelStore]: download to a
 * known path, sidecar marker records the verified Content-Length,
 * partial / truncated files don't fool subsequent retries.
 *
 * On-disk path:
 *   filesDir/gemma/gemma3-1b-it-int4.task   (~530MB)
 *   filesDir/gemma/gemma3-1b-it-int4.task.size  (Content-Length marker)
 *
 * The download URL points at the litert-community mirror on Hugging
 * Face, which serves pre-bundled MediaPipe .task files without auth.
 * If Hugging Face ever moves the file, swap [MODEL_URL] for whatever
 * mirror is currently authoritative — no other code change needed.
 */
@Singleton
class GemmaModelStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val hfTokenStore: HuggingFaceTokenStore,
) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long) : State {
            val pct: Int get() = if (total > 0) ((bytes * 100) / total).toInt() else 0
        }
        data class Ready(val path: String) : State
        data class Failed(val message: String) : State
    }

    val modelDir: File get() = ctx.filesDir.resolve("gemma").apply { mkdirs() }
    val modelFile: File get() = modelDir.resolve(MODEL_NAME)
    private val sizeMarker: File get() = modelDir.resolve("$MODEL_NAME.size")

    private val _state = MutableStateFlow<State>(
        if (isAvailable()) State.Ready(modelFile.absolutePath) else State.Missing,
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Generous read timeout — Hugging Face occasionally pauses mid-stream
        // on big transfers. A 5-minute window is comfortable for a 530MB file
        // even on slower connections.
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun isAvailable(): Boolean {
        if (!modelFile.exists()) return false
        if (modelFile.length() < MIN_VALID_BYTES) return false
        if (!sizeMarker.exists()) return modelFile.length() >= MIN_VALID_BYTES
        val expected = sizeMarker.readText().trim().toLongOrNull() ?: return false
        return expected == modelFile.length()
    }

    fun pathOrNull(): String? = if (isAvailable()) modelFile.absolutePath else null

    suspend fun ensureReady(): State {
        if (isAvailable()) {
            _state.value = State.Ready(modelFile.absolutePath)
            return _state.value
        }
        return withContext(Dispatchers.IO) {
            val attempt = runCatching {
                download()
                State.Ready(modelFile.absolutePath)
            }
            attempt.getOrElse { e ->
                Log.e(TAG, "Gemma fetch failed: ${e.message}", e)
                runCatching { if (modelFile.exists()) modelFile.delete() }
                runCatching { if (sizeMarker.exists()) sizeMarker.delete() }
                val msg = e.message ?: e.javaClass.simpleName
                // Soften the 401 message with a hint about the token path —
                // most failures with HF-hosted Gemma will land here.
                val friendlier = if (msg.contains("401")) {
                    "$msg — add an HF token in the panel below, or import a .task manually."
                } else msg
                State.Failed(friendlier)
            }.also { _state.value = it }
        }
    }

    fun forgetModel() {
        runCatching {
            if (modelFile.exists()) modelFile.delete()
            if (sizeMarker.exists()) sizeMarker.delete()
        }
        _state.value = State.Missing
    }

    /**
     * Import a `.task` file the user has already downloaded (typically
     * from Hugging Face or Kaggle, after accepting Google's Gemma
     * license). This is the no-auth path for users who don't want to
     * paste an HF token — they grab the file once via their browser
     * and we stream it into [modelFile].
     *
     * Reuses the same on-disk layout + size-marker contract as the
     * direct-download path, so subsequent `isAvailable()` checks pass
     * and `GemmaExtractor` can load it normally.
     */
    suspend fun importFromUri(uri: Uri): State = withContext(Dispatchers.IO) {
        runCatching {
            if (modelFile.exists()) modelFile.delete()
            if (sizeMarker.exists()) sizeMarker.delete()
            modelDir.mkdirs()

            // ContentResolver may or may not provide a Content-Length up
            // front depending on the document provider — Drive/Downloads
            // usually do, generic providers may not. We render -1 for the
            // total in that case; the UI still gets the byte count.
            val approxSize = runCatching {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            _state.value = State.Downloading(0, approxSize)

            val input = ctx.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected file")
            input.use { src ->
                FileOutputStream(modelFile).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var read = 0L
                    var lastReportMs = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs > PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            _state.update { State.Downloading(read, approxSize) }
                        }
                    }
                }
            }

            val size = modelFile.length()
            if (size < MIN_VALID_BYTES) {
                modelFile.delete()
                error("File too small ($size bytes) — does not look like a valid .task model")
            }
            sizeMarker.writeText(size.toString())
            Log.d(TAG, "imported Gemma .task ($size bytes) → ${modelFile.absolutePath}")
            State.Ready(modelFile.absolutePath).also { _state.value = it }
        }.getOrElse { e ->
            Log.e(TAG, "import failed: ${e.message}", e)
            runCatching { if (modelFile.exists()) modelFile.delete() }
            runCatching { if (sizeMarker.exists()) sizeMarker.delete() }
            State.Failed(e.message ?: e.javaClass.simpleName).also { _state.value = it }
        }
    }

    private suspend fun download() {
        if (modelFile.exists()) modelFile.delete()
        if (sizeMarker.exists()) sizeMarker.delete()

        // Pick up the user's HF token at request time so a freshly-saved
        // token is honoured without restarting the app. Token is never
        // logged; OkHttp's logger redacts the Authorization header per
        // GitHubClient's pattern, but we use a fresh client here without
        // logging anyway.
        val token = hfTokenStore.token()
        val reqBuilder = Request.Builder()
            .url(MODEL_URL)
            .header("User-Agent", "Mythara/0.0.1 (Android)")
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }
        val req = reqBuilder.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching Gemma model")
            val body = resp.body ?: error("empty body fetching Gemma model")
            val total = body.contentLength().coerceAtLeast(0L)
            _state.value = State.Downloading(0, total)
            body.byteStream().use { input ->
                FileOutputStream(modelFile).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var read = 0L
                    var lastReportMs = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs > PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            _state.update { State.Downloading(read, total) }
                        }
                    }
                }
            }
            val downloaded = modelFile.length()
            if (total > 0 && downloaded != total) {
                modelFile.delete()
                error("download truncated: $downloaded / $total")
            }
            if (downloaded < MIN_VALID_BYTES) {
                modelFile.delete()
                error("download too small ($downloaded bytes) — possibly an HTML error page")
            }
            sizeMarker.writeText(downloaded.toString())
            Log.d(TAG, "Gemma ready: $downloaded bytes at ${modelFile.absolutePath}")
        }
    }

    companion object {
        private const val TAG = "Mythara/Gemma"
        // Gemma 3 1B IT INT4 — Google-built, INT4-quantised for mobile.
        // The litert-community on Hugging Face mirrors MediaPipe .task
        // bundles without requiring auth.
        const val MODEL_NAME = "gemma3-1b-it-int4.task"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true"
        // 530MB is the canonical size; require at least 200MB to clear the
        // "did we just download an HTML error page?" floor while leaving
        // wiggle room for future Gemma-3 small models.
        private const val MIN_VALID_BYTES = 200L * 1024 * 1024
        private const val PROGRESS_REPORT_MS = 500L

        /** Identifier embedded in extracted-record provenance / facets. */
        const val MODEL_ID = "gemma3-1b-it-int4"
    }
}
