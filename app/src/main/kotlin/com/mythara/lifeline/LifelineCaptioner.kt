package com.mythara.lifeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mythara.minimax.VisionService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks the PENDING [LifelineEntity] rows and asks the configured
 * vision model (Gemini preferred — see [VisionService]) for a short
 * captionsuitable for inline rendering in the chat scrollback.
 *
 * Caption prompt is context-aware:
 *  - Time-of-day ("Wednesday 14:32") — Gemini will use this to lean
 *    "lunch", "evening", etc. without being told
 *  - Location lat/lng when EXIF GPS exists — Gemini knows real places
 *    from coordinates (within ~100m); we leave the actual reverse
 *    geocoding to it so we don't need a separate Geocoder roundtrip
 *  - Device label (model) — the user sometimes asks "where did I
 *    take this?" and the answer is "your old phone"
 *
 * Cost discipline:
 *  - One image at a time; sleep [MIN_GAP_MS] between calls
 *  - Caption length capped at ~120 chars
 *  - On failure we retry via the row's [LifelineEntity.captionAttempts]
 *    counter, capped at MAX_ATTEMPTS — beyond that the row is
 *    permanently SKIPPED
 *  - Image bytes get downscaled to ≤1024px long-edge before sending
 *    (matches VisionService's existing budget) to keep the request
 *    JSON small
 */
@Singleton
class LifelineCaptioner @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: LifelineRepository,
    private val vision: VisionService,
) {
    /**
     * Re-caption EVERY locally-captured photo from scratch. Resets
     * every row back to PENDING (clearing the previous caption +
     * attempt counter) and runs the captioning loop end-to-end.
     *
     * Use cases:
     *  - User just configured a Gemini key and wants captions for
     *    photos that previously fell through the cascade.
     *  - User has been adding `user_context` to old rows and wants
     *    those folded into refreshed captions.
     *  - Vision backend changed (e.g. Gemma multimodal init started
     *    succeeding after a model swap) and the user wants every
     *    archive caption re-rolled with the better backend.
     *
     * Rate-limited the same way as [captionPending] ([MIN_GAP_MS]
     * between calls) so a 200-photo archive doesn't slam the API in
     * one second.
     *
     * @param onProgress  Reports `(captioned, attempted, total)` after
     *                    each row. Suitable for surfacing a progress
     *                    bar in the calling UI.
     */
    suspend fun recaptionAll(
        onProgress: suspend (captioned: Int, attempted: Int, total: Int) -> Unit = { _, _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val total = runCatching { repo.dao.countAllLocal() }.getOrDefault(0)
        if (total == 0) {
            onProgress(0, 0, 0)
            return@withContext 0
        }
        // Reset all local rows first so subsequent inserts / scans
        // don't race with our walk — every row we touch comes from
        // a stable PENDING starting state.
        runCatching { repo.dao.markAllLocalPending() }
            .onFailure { Log.w(TAG, "markAllLocalPending failed: ${it.message}") }

        val rows = runCatching { repo.dao.listAllLocal() }.getOrDefault(emptyList())
        var captioned = 0
        var lastMs = 0L
        for ((index, row) in rows.withIndex()) {
            val now = System.currentTimeMillis()
            if (lastMs > 0 && (now - lastMs) < MIN_GAP_MS) {
                delay(MIN_GAP_MS - (now - lastMs))
            }
            // Re-read after the reset so we have the fresh PENDING
            // state + any user_context that was preserved.
            val fresh = runCatching { repo.dao.byId(row.id) }.getOrNull() ?: row
            val ok = runCatching { captionOne(fresh) }.getOrElse { e ->
                Log.w(TAG, "recaptionAll row=${row.id} threw: ${e.message}")
                false
            }
            if (ok) captioned++
            lastMs = System.currentTimeMillis()
            onProgress(captioned, index + 1, total)
        }
        Log.d(TAG, "recaptionAll: $total scanned, $captioned new captions")
        captioned
    }

    /**
     * Caption every pending row, one at a time. Returns the count of
     * rows successfully captioned in this pass.
     */
    suspend fun captionPending(maxRows: Int = 20): Int = withContext(Dispatchers.IO) {
        val rows = runCatching { repo.dao.listPending(limit = maxRows) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return@withContext 0
        var captioned = 0
        var lastMs = 0L
        for (row in rows) {
            val now = System.currentTimeMillis()
            if (lastMs > 0 && (now - lastMs) < MIN_GAP_MS) {
                delay(MIN_GAP_MS - (now - lastMs))
            }
            val ok = captionOne(row)
            if (ok) captioned++
            lastMs = System.currentTimeMillis()
        }
        Log.d(TAG, "captionPending: ${rows.size} attempted, $captioned new captions")
        captioned
    }

    /**
     * Best-effort caption of a single row. Returns true on success.
     * Marks the row either CAPTIONED, FAILED (will retry), or SKIPPED
     * (out of retries).
     */
    suspend fun captionOne(
        row: LifelineEntity,
        /** Capability Expansion v3 — user-added context folded into
         *  the prompt. When non-null, takes precedence over the
         *  row's persisted `userContext`. Lets a chat-side "regenerate
         *  with new context" flow inject text without persisting it
         *  first (the LifelineCard "add context" sheet writes BOTH
         *  the column AND passes the string here in the same op). */
        additionalContext: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val tempFile = runCatching { copyToTemp(Uri.parse(row.uri)) }.getOrElse {
            repo.dao.markFailed(row.id, "uri copy failed: ${it.message}")
            return@withContext false
        }
        try {
            val prompt = buildPrompt(row, additionalContext ?: row.userContext)
            val outcome = runCatching { vision.describeImage(tempFile, prompt = prompt) }
                .getOrElse {
                    VisionService.Outcome(false, it.message ?: "threw", code = "exception")
                }
            if (!outcome.ok || outcome.text.isBlank()) {
                val attempts = row.captionAttempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    repo.dao.markSkipped(
                        row.id,
                        "permanently skipped after $attempts attempts (${outcome.code ?: "unknown"})",
                    )
                } else {
                    repo.dao.markFailed(row.id, outcome.code ?: outcome.text.take(120))
                }
                return@withContext false
            }
            val caption = outcome.text.trim()
                .removeSurrounding("\"")
                .take(MAX_CAPTION_CHARS)
            repo.dao.markCaptioned(
                id = row.id,
                text = caption,
                model = outcome.backend ?: "vision",
                nowMs = System.currentTimeMillis(),
            )
            true
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun buildPrompt(row: LifelineEntity, userContext: String? = null): String {
        val taken = Date(row.takenMs)
        val dayLabel = SimpleDateFormat("EEEE", Locale.getDefault()).format(taken)
        val clockLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(taken)
        val locLabel = if (row.lat != null && row.lng != null) {
            "GPS ${"%.4f".format(row.lat)}, ${"%.4f".format(row.lng)}"
        } else "no GPS"
        // v3 — provenance + user-added context flow into the prompt so
        // the model knows when a photo came from POV glasses (first-
        // person framing) vs the phone camera, and so user-added
        // context ("with Sam at the cafe") gets folded into the
        // caption verbatim.
        val sourceLabel = when (row.sourceDeviceType) {
            "glasses" -> "my smart glasses (first-person POV)"
            "watch" -> "my watch"
            else -> "my phone camera"
        }
        val userContextSection = if (!userContext.isNullOrBlank()) {
            "\n              - User added context: \"$userContext\" — fold this into the caption naturally."
        } else ""
        return """
            Caption this casual photo I just took, for my personal
            timeline feed.

            Context (use it but don't quote it back to me verbatim):
              - When: $dayLabel $clockLabel
              - Where: $locLabel
              - From: $sourceLabel$userContextSection

            Rules:
              - One sentence, ≤ ${MAX_CAPTION_CHARS} characters.
              - Concrete and human — describe what's actually in the
                frame: people, food, places, light, mood.
              - If you can plausibly identify a real place from the GPS
                coordinates above, name it ("at Whole Foods near home")
                — but never invent landmarks you can't verify.
              - Skip "this image shows" / "a photo of". Just say what's
                there.
              - If the photo is genuinely uninteresting (blurry, blank,
                accidental shot), respond with the single word: SKIP.
        """.trimIndent()
    }

    /**
     * Pipe the MediaStore URI into a private file the vision service
     * can read. VisionService takes a File, not a Uri — and we want
     * the temp gone after the call regardless of success.
     */
    private fun copyToTemp(uri: Uri): File {
        val out = File(ctx.cacheDir, "lifeline_${System.nanoTime()}.tmp")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { os ->
                input.copyTo(os)
            }
        } ?: error("openInputStream returned null for $uri")
        if (out.length() == 0L) error("temp file ended up empty")
        return out
    }

    companion object {
        private const val TAG = "Mythara/Caption"
        private const val MIN_GAP_MS = 4_000L
        private const val MAX_ATTEMPTS = 4
        private const val MAX_CAPTION_CHARS = 140
    }
}
