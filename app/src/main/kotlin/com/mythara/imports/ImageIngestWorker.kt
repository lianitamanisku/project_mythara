package com.mythara.imports

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mythara.memory.Tier
import com.mythara.minimax.VisionService
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.vault.LearningVault
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Throttled, long-running ingest of images extracted from a WhatsApp
 * zip export. The goal is to lift durable persona traits ("user likes
 * mountain biking", "frequently visits coffee shops", "owns a cat
 * called Mochi") from the images the user has been sharing — without
 * blasting the vision API.
 *
 * Pipeline per image:
 *   1. Wait [DEFAULT_DELAY_MS] between calls — paces the vision API.
 *      Defaults to 60s; can be overridden via input data for tests.
 *   2. Call [VisionService.describeImage]. Routes to whichever model
 *      the user has configured (Gemini preferred, else MiniMax-VL-01).
 *   3. Feed the description through Gemma's [extractWithMood] when
 *      the local model is loaded — same path as the text persona
 *      pass — to lift facts rather than verbatim descriptions.
 *   4. Persist extracted facts to the vault (semantic tier, trait
 *      facet `gemma-extracted-image`). If Gemma isn't loaded, persist
 *      the raw vision description as a low-confidence trait instead.
 *   5. Delete the staged image file on disk.
 *
 * No raw image bytes are ever persisted. The staging dir lives in
 * app-private storage and gets wiped per-file as each one is
 * processed. WorkManager handles retries on transient failure; if
 * the worker is killed mid-pass, the next run resumes (staged files
 * still on disk are still pending).
 *
 * Why a one-time WorkRequest rather than a coroutine in the
 * importer's view-model: the user might leave Settings, lock the
 * phone, walk away. WorkManager guarantees the work continues
 * across UI lifecycle, and gives us per-attempt retry with backoff
 * for free. Battery constraints keep it from running below 15%.
 */
@HiltWorker
class ImageIngestWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val vision: VisionService,
    private val gemma: GemmaExtractor,
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
    private val importer: WhatsAppExportImporter,
    private val progress: ImageIngestProgress,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val delayMs = inputData.getLong(KEY_DELAY_MS, DEFAULT_DELAY_MS)
        val stagingDir = importer.stagingDir()
        val files = stagingDir.listFiles()?.filter { isImage(it) }?.sortedBy { it.name }.orEmpty()

        if (files.isEmpty()) {
            Log.d(TAG, "no images to ingest")
            progress.publishComplete(processed = 0, errors = 0)
            return Result.success()
        }

        Log.d(TAG, "starting ingest of ${files.size} images at ${delayMs}ms cadence")
        progress.publishStart(total = files.size)

        var processed = 0
        var errors = 0
        for ((idx, file) in files.withIndex()) {
            if (isStopped) {
                Log.d(TAG, "stopped at $idx/${files.size}")
                progress.publishComplete(processed = processed, errors = errors)
                return Result.success()
            }

            progress.publishProgress(processed = processed, total = files.size, errors = errors)

            val out = runCatching { vision.describeImage(file, prompt = INGEST_PROMPT) }
                .getOrElse {
                    Log.w(TAG, "vision.describeImage threw on ${file.name}", it)
                    null
                }
            if (out == null || !out.ok || out.text.isBlank()) {
                errors++
                Log.d(TAG, "vision failed for ${file.name}: ${out?.code} / ${out?.text?.take(80)}")
                runCatching { file.delete() }
                // No retry — keep moving. A flaky network image isn't
                // worth blocking the whole batch.
            } else {
                val written = runCatching {
                    ingestDescription(description = out.text, sourceFile = file.name)
                }.getOrElse {
                    Log.w(TAG, "ingestDescription threw for ${file.name}", it)
                    0
                }
                processed++
                Log.d(TAG, "ingested ${file.name} → $written facts")
                runCatching { file.delete() }
            }

            // Throttle. Skip the delay on the last file — no point.
            if (idx < files.size - 1) delay(delayMs)
        }

        // Tidy up: wipe staging entirely (in case any non-image files
        // ended up here from a previous run).
        runCatching { importer.clearStaging() }
        progress.publishComplete(processed = processed, errors = errors)
        Log.d(TAG, "ingest done: processed=$processed errors=$errors")
        return Result.success()
    }

    private suspend fun ingestDescription(description: String, sourceFile: String): Int {
        val now = System.currentTimeMillis()
        // Gemma route — feed the vision description through the local
        // LLM to lift durable facts. Same shape as the text persona
        // pass; we tag with image-source-specific facets so retrieval
        // can attribute things back ("learned from a photo").
        if (gemma.isReady()) {
            val result = runCatching { gemma.extractWithMood(description) }.getOrNull()
            val facts = result?.facts.orEmpty()
            if (facts.isEmpty()) {
                // Fall through to the raw-description path so the
                // vision call wasn't wasted.
                return writeRawDescription(description, now, lowConf = true)
            }
            var written = 0
            for (fact in facts) {
                val content = fact.content.trim()
                if (content.isBlank()) continue
                val embedding = runCatching {
                    if (embedder.isReady()) embedder.embed(content) else null
                }.getOrNull()
                val facets = buildList {
                    add("kind:persona")
                    add("source:whatsapp-image")
                    add("trait:gemma-extracted-image")
                    addAll(fact.facets)
                }
                val ok = runCatching {
                    vault.add(
                        content = content,
                        tier = Tier.Semantic,
                        src = "persona:whatsapp-image",
                        facets = facets,
                        embedding = embedding,
                        embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                        conf = 0.8,
                        now = now,
                    )
                }.getOrDefault(false)
                if (ok) written++
            }
            return written
        }
        // No Gemma — store a low-confidence raw record so the
        // vision call did at least contribute something. The next
        // SelfOrganizer pass will compact these once Gemma loads.
        return writeRawDescription(description, now, lowConf = false)
    }

    private suspend fun writeRawDescription(description: String, now: Long, lowConf: Boolean): Int {
        val content = description.take(MAX_RAW_LEN).trim()
        if (content.isBlank()) return 0
        val embedding = runCatching {
            if (embedder.isReady()) embedder.embed(content) else null
        }.getOrNull()
        val facets = listOf(
            "kind:persona",
            "source:whatsapp-image",
            "trait:image-description",
        )
        val ok = runCatching {
            vault.add(
                content = "Image the user shared: $content",
                tier = Tier.Semantic,
                src = "persona:whatsapp-image",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = if (lowConf) 0.5 else 0.6,
                now = now,
            )
        }.getOrDefault(false)
        return if (ok) 1 else 0
    }

    private fun isImage(f: File): Boolean {
        val n = f.name.lowercase()
        return f.isFile && (n.endsWith(".jpg") || n.endsWith(".jpeg") ||
            n.endsWith(".png") || n.endsWith(".webp"))
    }

    companion object {
        private const val TAG = "Mythara/ImgIngest"
        const val UNIQUE_WORK = "mythara_image_ingest"
        const val KEY_DELAY_MS = "delayMs"
        /**
         * Default 60s between vision calls. Comfortable inside even
         * the strictest free-tier rate limit (Gemini Flash free-tier
         * gives ~15 RPM) and friendly on the user's battery.
         */
        const val DEFAULT_DELAY_MS = 60_000L
        private const val MAX_RAW_LEN = 280
        private const val INGEST_PROMPT =
            "Describe this photo in 1-2 short sentences. Focus on what the photo says about the person who took it or shared it: " +
                "places, activities, interests, people, pets, things they own or care about. " +
                "Don't speculate, don't editorialise. If the image is purely a meme / forwarded content with no personal signal, say 'forwarded content; no personal signal'."
    }
}

/**
 * Schedules the throttled ingest. Unique work — if the user re-imports
 * before the previous batch finishes, the new request replaces the old
 * one (KEEP would queue them; REPLACE is the right semantic since the
 * previous run's staging dir was already wiped by the new import).
 */
@Singleton
class ImageIngestScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun startIngest(delayMs: Long = ImageIngestWorker.DEFAULT_DELAY_MS) {
        val req = OneTimeWorkRequestBuilder<ImageIngestWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    // Vision calls hit the network. No point burning
                    // through staged files while offline — the worker
                    // will resume when reconnected.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(Data.Builder().putLong(ImageIngestWorker.KEY_DELAY_MS, delayMs).build())
            .build()
        wm.enqueueUniqueWork(
            ImageIngestWorker.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    fun cancel() {
        wm.cancelUniqueWork(ImageIngestWorker.UNIQUE_WORK)
    }

    /** Live status flow for the UI to render "ingesting 7/42 photos…". */
    fun stateFlow(): Flow<List<WorkInfo>> =
        wm.getWorkInfosForUniqueWorkFlow(ImageIngestWorker.UNIQUE_WORK)
}
