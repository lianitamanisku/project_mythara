package com.mythara.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Data
import com.mythara.lifeline.LifelineRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ingests user-supplied SAMPLE photos for a contact's face index —
 * the basis the matcher uses to auto-tag that contact in every
 * future (and past) photo.
 *
 * Pipeline per supplied URI:
 *   1. Decode the image (downsampled to ≤ 1280 px on the long edge,
 *      same gate FaceAnalysisWorker uses to avoid OOM).
 *   2. Run [FaceDetector] — for each detected face:
 *      a. Crop with padding into a private PNG under
 *         filesDir/contact_face_samples/<nameKey>/<uuid>.png
 *      b. Embed via [FaceEmbedder] → 128-D vector
 *      c. Upsert into [ContactFaceIndex] with sourcePhotoPath = the
 *         crop path so future detections match this contact.
 *   3. After all URIs are processed, enqueue
 *      [com.mythara.face.FaceAnalysisWorker] for the most recent
 *      lifeline rows that DON'T already have this contact tagged —
 *      so the contact's "photos of <name>" grid back-fills with
 *      existing photos retroactively.
 *
 * Faces that don't pass detection are skipped silently; the caller
 * sees the count returned by [addSamples].
 */
@Singleton
class ContactFaceSampler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val index: ContactFaceIndex,
    private val lifeline: LifelineRepository,
    private val modelDownloader: FaceEmbedderModelDownloader,
) {
    data class IngestResult(
        val urisProcessed: Int,
        val facesFound: Int,
        val embeddingsAdded: Int,
        val embedderReady: Boolean,
        val modelDownloaded: Boolean = false,
        val modelDownloadFailed: Boolean = false,
    )

    /** Cheap check the UI panel uses to decide whether to show
     *  "install face model" vs the "add samples" CTA. Doesn't
     *  initialise the interpreter — just checks file presence. */
    fun modelInstalled(): Boolean = modelDownloader.isInstalled()

    /** Human-readable name of the TFLite delegate the face embedder
     *  picked (NPU / GPU / CPU). Triggers init if needed so the
     *  caller sees the actual backend, not "uninitialised". */
    fun backendLabel(): String {
        embedder.isReady() // warm the interpreter so backend resolves
        return embedder.backendName()
    }

    /** Triggered explicitly from the UI ("download face model" button)
     *  or implicitly from [addSamples] when the model isn't installed.
     *  Returns true if the model is ready after the call. */
    suspend fun ensureModelInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (embedder.isReady()) return@withContext true
        val ok = runCatching { modelDownloader.ensureInstalled() }.getOrDefault(false)
        if (!ok) return@withContext false
        // Reset the embedder so it picks up the freshly downloaded
        // file on its next isReady() call.
        embedder.isReady()
    }

    /** Process a batch of user-picked sample photos for [nameKey].
     *  Returns counts so the UI can show "added N face samples from
     *  M photos". If the face model isn't installed yet, this call
     *  blocks on a download attempt first — the user just sees one
     *  longer "processing…" tick instead of an opaque failure. */
    suspend fun addSamples(nameKey: String, uris: List<Uri>): IngestResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext IngestResult(0, 0, 0, embedder.isReady())
        var downloaded = false
        var downloadFailed = false
        if (!embedder.isReady()) {
            // First call → block on the download so the user's tap
            // resolves to either "samples added" or "download failed",
            // not a silent skip.
            Log.i(TAG, "face model not present — fetching before ingest…")
            val ok = runCatching { modelDownloader.ensureInstalled() }.getOrDefault(false)
            if (!ok) {
                Log.w(TAG, "model download failed — samples NOT added for $nameKey")
                return@withContext IngestResult(
                    urisProcessed = uris.size,
                    facesFound = 0,
                    embeddingsAdded = 0,
                    embedderReady = false,
                    modelDownloadFailed = true,
                )
            }
            downloaded = true
            // Reset embedder state so the next isReady() picks up the
            // freshly downloaded weights.
            if (!embedder.isReady()) {
                Log.w(TAG, "model downloaded but embedder still not ready")
                return@withContext IngestResult(
                    urisProcessed = uris.size,
                    facesFound = 0,
                    embeddingsAdded = 0,
                    embedderReady = false,
                    modelDownloaded = true,
                    modelDownloadFailed = true,
                )
            }
        }
        val outDir = File(ctx.filesDir, "contact_face_samples/$nameKey").apply { mkdirs() }
        var totalFaces = 0
        var totalAdded = 0
        val now = System.currentTimeMillis()
        for (uri in uris) {
            val bmp = runCatching { decode(uri) }.getOrNull() ?: continue
            val faces = runCatching { detector.detect(bmp) }.getOrDefault(emptyList())
            totalFaces += faces.size
            if (faces.isEmpty()) continue
            // If multiple faces in one photo, take the LARGEST — the
            // user picked this photo because it's a clear shot of
            // the contact, the biggest face is almost always them
            // (vs a background blur or someone else in frame).
            val primary = faces.maxBy { it.box.width() * it.box.height() }
            val crop = cropFace(bmp, primary.box, padFraction = 0.25f) ?: continue
            val emb = embedder.embed(bmp, primary.box) ?: continue
            val cropPath = saveCrop(outDir, crop)
            runCatching {
                index.dao.upsert(
                    ContactFaceEmbedding(
                        nameKey = nameKey,
                        sourcePhotoPath = cropPath,
                        embedding = emb.toEmbeddingBlob(),
                        computedAtMs = now,
                        modelVersion = FaceEmbedder.EMBEDDING_DIM,
                    ),
                )
                totalAdded++
            }
        }
        IngestResult(
            urisProcessed = uris.size,
            facesFound = totalFaces,
            embeddingsAdded = totalAdded,
            embedderReady = true,
            modelDownloaded = downloaded,
            modelDownloadFailed = downloadFailed,
        )
    }

    /** Delete a single sample by its crop path. Removes both the
     *  ContactFaceIndex row and the PNG file. */
    suspend fun removeSample(sourcePhotoPath: String) = withContext(Dispatchers.IO) {
        runCatching { index.dao.deleteByPath(sourcePhotoPath) }
        runCatching { File(sourcePhotoPath).delete() }
    }

    /**
     * After samples for [nameKey] were added, kick a re-analysis on
     * recent lifeline photos that don't already have this contact
     * tagged. Each kick is a single-shot FaceAnalysisWorker request
     * — WorkManager throttles concurrency so we don't fan out an
     * unbounded burst.
     *
     * Capped at [RESCAN_CAP] rows so adding samples for someone the
     * user knows well doesn't try to re-process their entire
     * lifetime camera roll in one go.
     */
    suspend fun retroactiveRescan(nameKey: String, capRows: Int = RESCAN_CAP): Int =
        withContext(Dispatchers.IO) {
            val candidates = runCatching {
                lifeline.dao.listMissingContact(nameKey, limit = capRows)
            }.getOrDefault(emptyList())
            for (row in candidates) {
                val req = OneTimeWorkRequestBuilder<FaceAnalysisWorker>()
                    .setInputData(
                        Data.Builder()
                            .putLong(FaceAnalysisWorker.KEY_LIFELINE_ID, row.id)
                            .putBoolean(FaceAnalysisWorker.KEY_RECOGNISE, false)
                            .build(),
                    )
                    .build()
                runCatching { WorkManager.getInstance(ctx).enqueue(req) }
            }
            candidates.size
        }

    private fun decode(uri: Uri): Bitmap? {
        // Same downsample logic as FaceAnalysisWorker.loadBitmap — keep
        // ML Kit's NV21 conversion well below the OOM cliff.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcLong = maxOf(bounds.outWidth, bounds.outHeight)
        if (srcLong <= 0) return null
        var sample = 1
        while (srcLong / sample > MAX_LONG_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun openStream(uri: Uri): java.io.InputStream? = runCatching {
        when (uri.scheme) {
            "file", null -> uri.path?.let { java.io.FileInputStream(it) }
            else -> ctx.contentResolver.openInputStream(uri)
        }
    }.getOrNull()

    private fun cropFace(
        source: Bitmap,
        box: android.graphics.Rect,
        padFraction: Float,
    ): Bitmap? {
        val pad = (maxOf(box.width(), box.height()) * padFraction).toInt()
        return runCatching {
            val left = (box.left - pad).coerceAtLeast(0)
            val top = (box.top - pad).coerceAtLeast(0)
            val right = (box.right + pad).coerceAtMost(source.width)
            val bottom = (box.bottom + pad).coerceAtMost(source.height)
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return@runCatching null
            Bitmap.createBitmap(source, left, top, w, h)
        }.getOrNull()
    }

    private fun saveCrop(dir: File, bmp: Bitmap): String {
        val out = File(dir, "${UUID.randomUUID()}.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out.absolutePath
    }

    companion object {
        private const val TAG = "Mythara/FaceSampler"
        /** Match FaceAnalysisWorker.MAX_LONG_EDGE_PX so the same OOM
         *  gate applies here. */
        private const val MAX_LONG_EDGE_PX = 1280
        /** Hard cap on retroactive rescan fan-out per add-samples
         *  call so a single action doesn't queue 5k WorkManager
         *  jobs. */
        const val RESCAN_CAP = 200
    }
}
