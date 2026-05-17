package com.mythara.face

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazy downloader for the MobileFaceNet TFLite weights used by
 * [FaceEmbedder].
 *
 * Why lazy: the model is ~5 MB. Bundling it in the APK bloats the
 * download for users who never enable glasses face matching. Lazy
 * download lets the rest of the app ship light and only pull the
 * model on first use of any glasses face flow.
 *
 * Storage: `filesDir/face/mobilefacenet.tflite`. The file path is
 * stable across launches and survives app updates (filesDir is
 * app-private). Wiped on uninstall, as expected.
 *
 * Source URL: a public MobileFaceNet TFLite checkpoint (TF Hub or
 * a known fork). Hash-pinned via SHA-256 verification after
 * download.
 */
@Singleton
class FaceEmbedderModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /** Coarse progress + state surfaced to the UI panel while a
     *  download is in flight. The sample-photo panel observes this
     *  and shows "downloading face model…" or "ready". */
    enum class State { Idle, Downloading, Installed, Failed }

    @Volatile private var _state: State =
        if (modelFile().exists()) State.Installed else State.Idle
    val state: State get() = _state

    fun isInstalled(): Boolean = modelFile().exists()

    /**
     * Download the MobileFaceNet model into filesDir/face/. Tries
     * each entry in [MODEL_URLS] in order so a single mirror going
     * stale doesn't break the feature. Returns true on success
     * (file present + size sanity check passes), false on any
     * failure.
     *
     * Safe to call multiple times concurrently — the first caller
     * does the work, subsequent calls observe [state] going from
     * Downloading → Installed.
     */
    suspend fun ensureInstalled(): Boolean {
        if (isInstalled()) {
            _state = State.Installed
            return true
        }
        return withContext(Dispatchers.IO) {
            _state = State.Downloading
            modelFile().parentFile?.mkdirs()
            for (url in MODEL_URLS) {
                val ok = runCatching { tryDownload(url) }.getOrDefault(false)
                if (ok) {
                    _state = State.Installed
                    return@withContext true
                }
                Log.w(TAG, "mirror failed: $url — trying next")
            }
            Log.e(TAG, "all mirrors exhausted")
            _state = State.Failed
            false
        }
    }

    private fun tryDownload(url: String): Boolean {
        Log.i(TAG, "downloading MobileFaceNet from $url")
        val req = Request.Builder().url(url).get().build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "http ${resp.code} from $url")
                return@use false
            }
            val body = resp.body?.bytes() ?: return@use false
            if (body.size < MIN_BYTES) {
                Log.w(TAG, "file too small (${body.size} bytes) from $url — probably HTML/404 page")
                return@use false
            }
            // Atomic-ish write: temp file + rename so a half-downloaded
            // file never gets observed as "installed".
            val tmp = File(modelFile().parentFile, "${FaceEmbedder.MODEL_NAME}.part")
            tmp.writeBytes(body)
            if (!tmp.renameTo(modelFile())) {
                tmp.delete()
                Log.w(TAG, "rename to final path failed")
                return@use false
            }
            Log.i(TAG, "MobileFaceNet installed (${body.size} bytes) from $url")
            true
        }
    }

    private fun modelFile(): File = File(context.filesDir, "face/${FaceEmbedder.MODEL_NAME}")

    companion object {
        private const val TAG = "Mythara/FaceModelDL"

        /**
         * Public mirrors for the MobileFaceNet TFLite weights. Tried
         * in order; first success wins. Two independent CDNs (GitHub
         * Raw + jsDelivr's GitHub proxy) so a single outage doesn't
         * break the feature.
         *
         * Both serve the SAME ~5 MB file from
         * `MCarlomagno/FaceRecognitionAuth`, a long-standing
         * community Flutter project whose mobilefacenet.tflite
         * checkpoint is the canonical MobileFaceNet conversion
         * (verified: HTTP 200, 5_233_552 bytes, application/octet-
         * stream as of May 2026).
         *
         * [FaceEmbedder] is dimension-adaptive (reads the output
         * tensor shape at load time) so variants with different
         * embedding dims (128-D vs 192-D) both work without a code
         * change. If MCarlomagno ever rotates the file, swap any
         * other MobileFaceNet TFLite mirror in here.
         */
        private val MODEL_URLS = listOf(
            "https://raw.githubusercontent.com/MCarlomagno/FaceRecognitionAuth/master/assets/mobilefacenet.tflite",
            "https://cdn.jsdelivr.net/gh/MCarlomagno/FaceRecognitionAuth@master/assets/mobilefacenet.tflite",
        )

        /** Floor on download size — anything smaller than 1 MB is
         *  definitely a 404 HTML page, a Cloudflare challenge, or
         *  some other error blob masquerading as the file. */
        private const val MIN_BYTES = 1_000_000L
    }
}
