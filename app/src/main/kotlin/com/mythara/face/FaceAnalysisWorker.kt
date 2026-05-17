package com.mythara.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.interactions.ContactInteractionRepository
import com.mythara.analytics.interactions.ContactInteractionRow
import com.mythara.glasses.GlassesScreen
import com.mythara.glasses.GlassesScreenStore
import com.mythara.lifeline.LifelineRepository
import com.mythara.ui.analytics.ContactPhoto
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Background worker that runs face detection + identity matching on
 * a single LifelineEntity (typically a glasses photo). Updates the
 * row's `detected_contacts_json` field, writes physical-meet
 * interactions for each matched contact, optionally sets a contact's
 * avatar override (Phase 6 policy: silent overwrite only when no
 * override exists), and pushes a ProfileCard onto the glasses if
 * the request was a "recognise person" tap.
 *
 * Enqueued by [GlassesPhotoCapture] after every glasses-source
 * photo. Constrained to expedited (single-shot, near-immediate) so
 * the user sees their ProfileCard within a couple of seconds of the
 * neural-band tap.
 */
@HiltWorker
class FaceAnalysisWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val lifeline: LifelineRepository,
    private val faceDetector: FaceDetector,
    private val faceEmbedder: FaceEmbedder,
    private val matcher: ContactFaceMatcher,
    private val contactRepo: ContactProfileRepository,
    private val interactionRepo: ContactInteractionRepository,
    private val screenStore: GlassesScreenStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        runDetection()
    } catch (oom: OutOfMemoryError) {
        // ML Kit's NV21 conversion can spike RAM hard on a big image
        // even after our downsample gate. Catch it here so the worker
        // returns a clean failure instead of taking the whole app
        // process down — the lifeline row stays untagged and the
        // nightly worker will retry from a fresh process.
        Log.e(TAG, "OOM analysing photo — releasing and failing softly", oom)
        runCatching { System.gc() }
        Result.failure()
    } catch (t: Throwable) {
        Log.e(TAG, "unexpected error in face analysis", t)
        Result.failure()
    }

    private suspend fun runDetection(): Result {
        val lifelineId = inputData.getLong(KEY_LIFELINE_ID, -1L)
        val recognise = inputData.getBoolean(KEY_RECOGNISE, false)
        if (lifelineId <= 0L) {
            Log.w(TAG, "missing lifelineId")
            return Result.failure()
        }
        val row = lifeline.dao.byId(lifelineId) ?: run {
            Log.w(TAG, "lifeline row $lifelineId not found")
            return Result.failure()
        }
        val bmp = loadBitmap(Uri.parse(row.uri)) ?: run {
            Log.w(TAG, "could not decode bitmap for $lifelineId")
            return Result.failure()
        }
        val faces = faceDetector.detect(bmp)
        if (faces.isEmpty()) {
            lifeline.dao.updateDetectedContacts(lifelineId, "[]")
            if (recognise) {
                screenStore.push(GlassesScreen.Error("no faces in frame"))
            }
            return Result.success()
        }
        if (!faceEmbedder.isReady()) {
            Log.w(TAG, "face embedder model not installed — skipping match for $lifelineId")
            lifeline.dao.updateDetectedContacts(lifelineId, "[]")
            return Result.success()
        }

        val matchedKeys = mutableListOf<String>()
        var bestForGlasses: ContactFaceMatcher.MatchCandidate? = null
        for (face in faces) {
            val emb = faceEmbedder.embed(bmp, face.box) ?: continue
            val matches = matcher.match(emb, topK = 1)
            val top = matches.firstOrNull() ?: continue
            matchedKeys += top.nameKey
            // Pick the highest-confidence match across all faces for
            // the "recognise person" → glasses ProfileCard render.
            if (bestForGlasses == null || top.distance < bestForGlasses!!.distance) {
                bestForGlasses = top
            }
            // Phase 6: silent photo-override when (a) the match is
            // above the STRICT threshold (sharper than tagging) AND
            // (b) the contact has no existing override.
            if (top.distance <= ContactFaceMatcher.STRICT_THRESHOLD) {
                maybeSetContactAvatar(top.nameKey, bmp, face.box)
            }
            // Always log a physical-meet interaction.
            interactionRepo.dao.insert(
                ContactInteractionRow(
                    nameKey = top.nameKey,
                    tsMs = row.takenMs,
                    kind = "physical_meet",
                    source = "glasses",
                    lat = row.lat,
                    lng = row.lng,
                    placeLabel = row.placeLabel,
                    note = null,
                    refLifelineId = lifelineId,
                    refAuditId = null,
                ),
            )
        }
        // Distinct + persist as JSON array.
        val json = matchedKeys.distinct().joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        lifeline.dao.updateDetectedContacts(lifelineId, json)

        if (recognise) {
            val match = bestForGlasses
            if (match != null) {
                val profile = runCatching { contactRepo.dao.byKey(match.nameKey) }.getOrNull()
                if (profile != null) {
                    screenStore.push(
                        GlassesScreen.ProfileCard(
                            nameKey = profile.nameKey,
                            displayName = profile.displayName,
                            avatarUri = profile.photoUri,
                            toneLabel = profile.toneLabel,
                            keyPoints = parseFirstThree(profile.notableTraitsJson),
                            lastInteractionMs = profile.lastInteractionMs.takeIf { it > 0 },
                        ),
                    )
                }
            } else {
                screenStore.push(GlassesScreen.Error("no contact match"))
            }
        }
        return Result.success()
    }

    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        // Downsample BEFORE the bitmap lands in RAM. ML Kit face
        // detection internally allocates an NV21 ByteBuffer of size
        // ~1.5 × W × H bytes — a raw 12MP phone photo would request
        // ~75 MB, which OOMs the worker thread. Phone cameras now
        // route through here (not just the original 1.6 MP glasses
        // path), so we have to bound the input size ourselves.
        //
        // 1280 px on the long edge is the sweet spot: comfortably
        // above MobileFaceNet's effective resolution (112 × 112 crops
        // after detection) and below MLKit's allocation cliff, while
        // still preserving enough detail that small / distant faces
        // are caught.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcLong = maxOf(bounds.outWidth, bounds.outHeight)
        if (srcLong <= 0) return@runCatching null
        var sample = 1
        while (srcLong / sample > MAX_LONG_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // ARGB_8888 is what ML Kit wants — RGB_565 saves memory
            // but loses precision the face detector relies on for
            // landmark stability.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    private fun openStream(uri: Uri): java.io.InputStream? = runCatching {
        when (uri.scheme) {
            "file", null -> uri.path?.let { java.io.FileInputStream(it) }
            else -> context.contentResolver.openInputStream(uri)
        }
    }.getOrNull()

    private suspend fun maybeSetContactAvatar(nameKey: String, source: Bitmap, box: android.graphics.Rect) {
        val existing = runCatching { contactRepo.dao.byKey(nameKey) }.getOrNull() ?: return
        if (!existing.photoUri.isNullOrBlank()) return
        // Crop the face out + pad a bit for visual context.
        val pad = (maxOf(box.width(), box.height()) * 0.25f).toInt()
        val crop = runCatching {
            val left = (box.left - pad).coerceAtLeast(0)
            val top = (box.top - pad).coerceAtLeast(0)
            val right = (box.right + pad).coerceAtMost(source.width)
            val bottom = (box.bottom + pad).coerceAtMost(source.height)
            Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        }.getOrNull() ?: return
        // Write to a temp file so ContactPhoto.importOverride can
        // ingest it as a content/file Uri.
        val tmp = File(context.cacheDir, "face_avatar_${UUID.randomUUID()}.png")
        runCatching {
            FileOutputStream(tmp).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ContactPhoto.importOverride(context, nameKey, Uri.fromFile(tmp))?.let { savedPath ->
                runCatching {
                    contactRepo.dao.updatePhotoUri(nameKey, savedPath)
                }.onSuccess {
                    Log.i(TAG, "silent avatar override set for $nameKey")
                }
            }
        }
        runCatching { tmp.delete() }
    }

    private fun parseFirstThree(json: String?): List<String> = runCatching {
        json?.removePrefix("[")?.removeSuffix("]")
            ?.split(",")
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotBlank() }
            ?.take(3)
            .orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "Mythara/FaceAnalyse"
        const val KEY_LIFELINE_ID = "lifeline_id"
        const val KEY_RECOGNISE = "recognise"
        /** Max length of the longer image edge after downsample.
         *  Bounds the NV21 buffer ML Kit allocates per detect() call
         *  to ~3 MB (1280 × 960 × 1.5) — safe inside the worker's
         *  256 MB heap even with other work running concurrently. */
        private const val MAX_LONG_EDGE_PX = 1280

        fun enqueue(context: Context, lifelineId: Long, recognise: Boolean) {
            val req = OneTimeWorkRequestBuilder<FaceAnalysisWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_LIFELINE_ID, lifelineId)
                        .putBoolean(KEY_RECOGNISE, recognise)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
