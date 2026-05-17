package com.mythara.face

import android.content.Context
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores precomputed face embeddings for every contact who has a
 * photo override (or a system-contacts photo Mythara can read).
 *
 * One row per (nameKey, sourcePhotoPath) — a single contact with
 * multiple avatars over time accumulates multiple rows; the
 * matcher considers them all and uses the lowest cosine distance
 * across the contact's embeddings.
 *
 * Privacy + sync (Phase F update): embeddings + their source crop
 * PNGs now DO sync via [com.mythara.memory.MemorySync] as
 * `analytics/contact_face_samples.jsonl`. Previously this DB was
 * deliberately excluded for privacy; the user explicitly reversed
 * that decision so a fresh install / `pm clear` on any peer device
 * restores every "this is what Sam looks like" sample they had
 * uploaded. The repo + chat history are still scrubbed of any
 * other raw face exposure (no live camera frames, no third-party
 * faces) — only the user-curated samples + their derived
 * embeddings travel. The user can wipe everything via the Settings
 * panel, which also overwrites the synced JSONL on next push.
 */
@Entity(
    tableName = "contact_face_embeddings",
    indices = [Index(value = ["name_key"]), Index(value = ["source_photo_path"], unique = true)],
)
data class ContactFaceEmbedding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "source_photo_path") val sourcePhotoPath: String,
    /** L2-normalised 128-D embedding stored as a length-prefixed
     *  ByteArray (4-byte little-endian floats). */
    @ColumnInfo(name = "embedding") val embedding: ByteArray,
    @ColumnInfo(name = "computed_at_ms") val computedAtMs: Long,
    /** [FaceEmbedder.EMBEDDING_DIM] at time of compute — protects
     *  against model swaps invalidating older embeddings silently. */
    @ColumnInfo(name = "model_version") val modelVersion: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactFaceEmbedding) return false
        return id == other.id && nameKey == other.nameKey &&
            sourcePhotoPath == other.sourcePhotoPath &&
            computedAtMs == other.computedAtMs && modelVersion == other.modelVersion &&
            embedding.contentEquals(other.embedding)
    }
    override fun hashCode(): Int = id.hashCode()
}

@Dao
interface ContactFaceDao {
    @Query("SELECT * FROM contact_face_embeddings")
    suspend fun listAll(): List<ContactFaceEmbedding>

    @Query("SELECT * FROM contact_face_embeddings WHERE name_key = :nameKey")
    suspend fun listForContact(nameKey: String): List<ContactFaceEmbedding>

    /** Live observation of every embedding row for one contact —
     *  used by the People detail's "sample photos for face
     *  recognition" panel so the thumbnail grid updates the moment
     *  the user adds or removes a sample. */
    @Query("SELECT * FROM contact_face_embeddings WHERE name_key = :nameKey ORDER BY computed_at_ms DESC")
    fun observeForContact(nameKey: String): kotlinx.coroutines.flow.Flow<List<ContactFaceEmbedding>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ContactFaceEmbedding): Long

    @Query("DELETE FROM contact_face_embeddings WHERE name_key = :nameKey")
    suspend fun deleteForContact(nameKey: String): Int

    @Query("DELETE FROM contact_face_embeddings WHERE source_photo_path = :path")
    suspend fun deleteByPath(path: String): Int

    @Query("DELETE FROM contact_face_embeddings")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM contact_face_embeddings")
    suspend fun count(): Int

    @Query("SELECT COUNT(DISTINCT name_key) FROM contact_face_embeddings")
    suspend fun distinctContactCount(): Int
}

@Database(entities = [ContactFaceEmbedding::class], version = 1, exportSchema = false)
abstract class ContactFaceDb : RoomDatabase() {
    abstract fun dao(): ContactFaceDao
}

@Singleton
class ContactFaceIndex @Inject constructor(@ApplicationContext ctx: Context) {
    // Destructive on schema bump — embeddings can always be rebuilt
    // from the contact photos themselves on demand.
    private val db: ContactFaceDb = Room.databaseBuilder(
        ctx, ContactFaceDb::class.java, "mythara_contact_faces.db",
    ).fallbackToDestructiveMigration().build()
    val dao: ContactFaceDao = db.dao()
}

/** Helpers for converting between FloatArray embeddings + the
 *  ByteArray BLOB stored in Room. Length-prefixed so we can roundtrip
 *  safely even if EMBEDDING_DIM changes between model versions. */
internal fun FloatArray.toEmbeddingBlob(): ByteArray {
    val buf = java.nio.ByteBuffer.allocate(4 + this.size * 4)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.putInt(this.size)
    for (f in this) buf.putFloat(f)
    return buf.array()
}

internal fun ByteArray.toEmbeddingFloats(): FloatArray {
    val buf = java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val n = buf.int
    if (n < 0 || n > 4096) return FloatArray(0) // sanity
    val out = FloatArray(n)
    for (i in 0 until n) out[i] = buf.float
    return out
}
