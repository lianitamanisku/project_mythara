package com.mythara.face

import android.content.Context
import android.graphics.Bitmap
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Faces detected in photos that DIDN'T match any existing contact in
 * [ContactFaceIndex]. Captured by [FaceAnalysisWorker] so the user
 * can promote them via the People screen's "Untagged faces" section
 * — assign to an existing contact, create a new (Mythara + device)
 * contact, or dismiss as "not a person".
 *
 * Clustering: repeated detections of the SAME unfamiliar person all
 * collapse into ONE row. When a new unmatched face arrives, we scan
 * existing untagged rows; if the cosine distance to any of them is
 * within [UnknownFaceRepository.CLUSTER_THRESHOLD], we bump that
 * row's `seenCount` + `lastSeenMs` instead of inserting a new row.
 * Single-tap promote then covers every photo that person appears in.
 *
 * Dismissed rows STAY in the table (with `dismissed = 1`) so future
 * detections of the same false-positive face cluster TO the dismissed
 * row and get silently dropped, instead of resurfacing as a new
 * untagged entry on every photo.
 *
 * Privacy: same posture as [ContactFaceIndex] — embeddings + crops
 * stay on-device, never sync via MemorySync.
 */
@Entity(
    tableName = "unknown_faces",
    indices = [
        Index(value = ["dismissed"]),
        Index(value = ["promoted"]),
        Index(value = ["last_seen_ms"]),
    ],
)
data class UnknownFaceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** L2-normalised 128-D embedding, length-prefixed bytes (same
     *  encoding as [ContactFaceEmbedding.embedding]). */
    @ColumnInfo(name = "embedding") val embedding: ByteArray,
    /** Absolute path to the face crop PNG under
     *  filesDir/unknown_faces/<uuid>.png — shown as the thumbnail
     *  in the People screen + used as the avatar source when the
     *  user promotes this face to a contact. */
    @ColumnInfo(name = "crop_path") val cropPath: String,
    /** The lifeline_entries.id of the photo the face was FIRST seen
     *  in. Useful for jumping the user back to that frame from the
     *  People screen. */
    @ColumnInfo(name = "first_lifeline_id") val firstLifelineId: Long,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
    /** How many distinct face detections have clustered to this row.
     *  Surfaced in UI ("seen 7 times") so the user knows which
     *  unknowns are recurring vs one-shots. */
    @ColumnInfo(name = "seen_count") val seenCount: Int = 1,
    /** Comma-joined list of recent lifeline_entries.id values where
     *  this face appeared. Capped to [UnknownFaceRepository.MAX_TRACE_IDS]
     *  so an active row doesn't grow unboundedly. */
    @ColumnInfo(name = "trace_ids") val traceIds: String = "",
    /** True if the user marked this face "not a person" / dismissed.
     *  Dismissed rows are kept (not deleted) so future detections of
     *  the same face cluster to this row + get suppressed. */
    @ColumnInfo(name = "dismissed") val dismissed: Boolean = false,
    /** True once the user has promoted this face to a real contact.
     *  We keep the row briefly so any in-flight workers don't re-
     *  surface it; a periodic cleanup can purge promoted rows older
     *  than N days. */
    @ColumnInfo(name = "promoted") val promoted: Boolean = false,
    /** [FaceEmbedder.EMBEDDING_DIM] at compute time — defensive
     *  against model swaps. */
    @ColumnInfo(name = "model_version") val modelVersion: Int = 1,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnknownFaceRow) return false
        return id == other.id &&
            cropPath == other.cropPath &&
            firstLifelineId == other.firstLifelineId &&
            firstSeenMs == other.firstSeenMs &&
            lastSeenMs == other.lastSeenMs &&
            seenCount == other.seenCount &&
            traceIds == other.traceIds &&
            dismissed == other.dismissed &&
            promoted == other.promoted &&
            modelVersion == other.modelVersion &&
            embedding.contentEquals(other.embedding)
    }
    override fun hashCode(): Int = id.hashCode()
}

@Dao
interface UnknownFaceDao {
    /** Active (not dismissed, not promoted) rows for the People UI's
     *  Untagged section. Sort by seen_count DESC so the most-frequent
     *  unknowns float to the top — those are the ones the user
     *  actually cares about labelling. */
    @Query(
        """
        SELECT * FROM unknown_faces
        WHERE dismissed = 0 AND promoted = 0
        ORDER BY seen_count DESC, last_seen_ms DESC
        """,
    )
    fun observeActive(): Flow<List<UnknownFaceRow>>

    @Query("SELECT * FROM unknown_faces WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): UnknownFaceRow?

    /** Every row including dismissed — used by the clustering pass
     *  when a new detection arrives so we can also catch "previously
     *  dismissed face = drop silently". */
    @Query("SELECT * FROM unknown_faces WHERE promoted = 0")
    suspend fun listForClustering(): List<UnknownFaceRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: UnknownFaceRow): Long

    @Query(
        """
        UPDATE unknown_faces
        SET seen_count = seen_count + 1,
            last_seen_ms = :nowMs,
            trace_ids = :traceIds
        WHERE id = :id
        """,
    )
    suspend fun incrementSeen(id: Long, nowMs: Long, traceIds: String)

    @Query("UPDATE unknown_faces SET dismissed = 1 WHERE id = :id")
    suspend fun markDismissed(id: Long)

    @Query("UPDATE unknown_faces SET promoted = 1 WHERE id = :id")
    suspend fun markPromoted(id: Long)

    @Query("DELETE FROM unknown_faces WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM unknown_faces WHERE promoted = 1 AND last_seen_ms < :cutoffMs")
    suspend fun purgePromotedBefore(cutoffMs: Long): Int

    @Query("DELETE FROM unknown_faces")
    suspend fun clear()
}

@Database(entities = [UnknownFaceRow::class], version = 1, exportSchema = false)
abstract class UnknownFaceDb : RoomDatabase() {
    abstract fun dao(): UnknownFaceDao
}

/**
 * Repository wrapping [UnknownFaceDb] with the operations
 * [FaceAnalysisWorker] and the People screen need:
 *
 *  - [ingest]: a new face was detected that didn't match any
 *    existing contact. Cluster it against existing untagged rows;
 *    increment a near-match or insert a new row.
 *
 *  - [assignToContact]: the user picked an existing contact for an
 *    untagged face. Copy the embedding into [ContactFaceIndex] so
 *    future detections of the same face match that contact directly,
 *    then mark the unknown row promoted.
 *
 *  - [dismiss]: the user marked a face "not a person" (false
 *    positive, group photo background blur, etc.). The row stays so
 *    future detections cluster to it and get silently dropped.
 *
 *  - [observeActive]: Flow of all currently-untagged rows for the
 *    People screen Untagged section.
 */
@Singleton
class UnknownFaceRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val contactFaceIndex: ContactFaceIndex,
) {
    private val db: UnknownFaceDb = Room.databaseBuilder(
        ctx, UnknownFaceDb::class.java, "mythara_unknown_faces.db",
    ).fallbackToDestructiveMigration().build()
    val dao: UnknownFaceDao = db.dao()

    fun observeActive(): Flow<List<UnknownFaceRow>> = dao.observeActive()

    /**
     * Persist (or merge) a new unmatched face. If a near-match
     * cluster already exists, the existing row's seenCount +
     * lastSeenMs are bumped + the new lifelineId is appended to its
     * trace (FIFO cap at MAX_TRACE_IDS). Otherwise a brand-new row is
     * inserted with the crop saved to disk.
     *
     * Returns the row id of the (new or merged) cluster, or -1 on
     * any IO failure (caller treats -1 as "skip but don't fail the
     * whole worker").
     */
    suspend fun ingest(
        embedding: FloatArray,
        cropBitmap: Bitmap,
        lifelineId: Long,
        nowMs: Long,
    ): Long {
        if (embedding.isEmpty()) return -1L
        // Cluster pass: look for an existing row within threshold,
        // INCLUDING dismissed rows (we want to suppress repeats of
        // dismissed faces too).
        val existing = runCatching { dao.listForClustering() }.getOrDefault(emptyList())
        var best: UnknownFaceRow? = null
        var bestDist = Float.MAX_VALUE
        for (row in existing) {
            val emb = row.embedding.toEmbeddingFloats()
            if (emb.size != embedding.size) continue
            val d = FaceEmbedder.cosineDistance(embedding, emb)
            if (d < bestDist) {
                bestDist = d
                best = row
            }
        }
        if (best != null && bestDist <= CLUSTER_THRESHOLD) {
            // Cluster hit — update the existing row (works for both
            // active AND dismissed; the dismissed flag is preserved).
            val updatedTrace = appendTraceId(best.traceIds, lifelineId)
            runCatching { dao.incrementSeen(best.id, nowMs, updatedTrace) }
            return best.id
        }
        // New cluster — save the crop to disk so the People UI has
        // something to render. We use the SAVED PNG as the
        // representative for this person; subsequent detections
        // don't overwrite (the first capture is usually as good
        // as later ones for an unknown face).
        val cropPath = runCatching { saveCrop(cropBitmap) }.getOrNull() ?: return -1L
        val row = UnknownFaceRow(
            embedding = embedding.toEmbeddingBlob(),
            cropPath = cropPath,
            firstLifelineId = lifelineId,
            firstSeenMs = nowMs,
            lastSeenMs = nowMs,
            seenCount = 1,
            traceIds = lifelineId.toString(),
            dismissed = false,
            promoted = false,
            modelVersion = 1,
        )
        return runCatching { dao.upsert(row) }.getOrDefault(-1L)
    }

    /**
     * Promote an untagged face onto an existing contact. Copies the
     * embedding into [ContactFaceIndex] so future detections of the
     * same face match the contact directly, then marks the unknown
     * row promoted. Returns the cropPath so the caller (UI sheet)
     * can decide whether to also set the contact's avatar.
     */
    suspend fun assignToContact(unknownId: Long, nameKey: String): String? {
        val row = dao.byId(unknownId) ?: return null
        runCatching {
            contactFaceIndex.dao.upsert(
                ContactFaceEmbedding(
                    nameKey = nameKey,
                    sourcePhotoPath = row.cropPath,
                    embedding = row.embedding,
                    computedAtMs = System.currentTimeMillis(),
                    modelVersion = row.modelVersion,
                ),
            )
        }
        runCatching { dao.markPromoted(unknownId) }
        return row.cropPath
    }

    suspend fun dismiss(unknownId: Long) {
        runCatching { dao.markDismissed(unknownId) }
    }

    suspend fun delete(unknownId: Long) {
        val row = dao.byId(unknownId)
        runCatching { dao.deleteById(unknownId) }
        // Clean up the crop file — no other row uses it (one PNG
        // per cluster).
        row?.cropPath?.let { runCatching { File(it).delete() } }
    }

    private fun saveCrop(bitmap: Bitmap): String {
        val dir = File(ctx.filesDir, "unknown_faces").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out.absolutePath
    }

    private fun appendTraceId(existing: String, newId: Long): String {
        val ids = if (existing.isBlank()) emptyList() else existing.split(",")
        val updated = (ids + newId.toString())
            .distinct()
            .takeLast(MAX_TRACE_IDS)
        return updated.joinToString(",")
    }

    companion object {
        /** Cosine-distance ceiling for grouping new detections to an
         *  existing untagged cluster. Slightly tighter than the
         *  contact-match threshold (0.65) — unknowns need stronger
         *  evidence before we collapse them into one identity. */
        const val CLUSTER_THRESHOLD = 0.55f

        /** Cap on lifeline ids tracked per cluster so an actively
         *  seen face doesn't grow its trace_ids unboundedly. */
        const val MAX_TRACE_IDS = 50
    }
}
