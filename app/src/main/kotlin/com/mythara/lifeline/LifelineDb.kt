package com.mythara.lifeline

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
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One row per camera-captured photo in the user's life timeline.
 *
 * Storage rules:
 *  - We persist METADATA + the captioned text only. The actual photo
 *    bytes stay in MediaStore (the user's gallery); we just keep a
 *    URI / mediaStoreId pointer so the UI can re-decode the image
 *    on demand.
 *  - Cross-device sync ships rows of THIS table (caption + ts + lat/
 *    lng + device id) to the memory repo so the user's timeline
 *    follows them. Raw pixels NEVER leave the device they were taken
 *    on — when device A scrolls back to a photo taken on device B,
 *    it sees the caption + date + device label but the image
 *    placeholder reads "photo on phone-B (not on this device)".
 *
 * Dedup:
 *  - Within one device: unique on (deviceId, mediaStoreId). MediaStore
 *    IDs are stable per install but get reassigned across factory
 *    resets — that's fine, a wipe loses the local row, the synced row
 *    survives.
 *  - Across devices: a contentHash field (sha-256 of the file's first
 *    256KB) lets us recognise the same photo if it ended up on two
 *    devices via cloud backup. NULLable because hashing every image
 *    on import is expensive; computed lazily on first sync.
 */
@Entity(
    tableName = "lifeline_entries",
    indices = [
        Index(value = ["device_id", "media_store_id"], unique = true),
        Index(value = ["taken_ms"]),
        Index(value = ["caption_status"]),
        Index(value = ["content_hash"]),
    ],
)
data class LifelineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "bucket") val bucket: String,
    @ColumnInfo(name = "taken_ms") val takenMs: Long,
    @ColumnInfo(name = "added_ms") val addedMs: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "width") val width: Int = 0,
    @ColumnInfo(name = "height") val height: Int = 0,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
    @ColumnInfo(name = "lat") val lat: Double? = null,
    @ColumnInfo(name = "lng") val lng: Double? = null,
    @ColumnInfo(name = "place_label") val placeLabel: String? = null,
    /** [LifelineCaptionStatus.name] */
    @ColumnInfo(name = "caption_status") val captionStatus: String = LifelineCaptionStatus.PENDING.name,
    @ColumnInfo(name = "caption_text") val captionText: String? = null,
    @ColumnInfo(name = "caption_model") val captionModel: String? = null,
    @ColumnInfo(name = "captioned_at_ms") val captionedAtMs: Long? = null,
    @ColumnInfo(name = "caption_attempts") val captionAttempts: Int = 0,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,
    /** True if this row was hydrated from a cross-device sync rather than scanned locally. */
    @ColumnInfo(name = "is_remote") val isRemote: Boolean = false,
    /**
     * Tombstone marker. Set true when [PhotoScanner] notices the
     * underlying MediaStore id is gone (user deleted the photo from
     * gallery). The row stays in the DB so the deletion synchronises
     * to other devices on the next memory sync, but the UI hides it
     * everywhere except the per-device tombstone log.
     */
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo(name = "deleted_at_ms") val deletedAtMs: Long? = null,
)

enum class LifelineCaptionStatus {
    PENDING,    // newly scanned, waiting for caption
    CAPTIONED,  // caption_text set
    FAILED,     // caption attempt failed, attempts < MAX → will retry
    SKIPPED,    // permanently skipped (out of retries, or user disabled captioning)
}

@Dao
interface LifelineDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: LifelineEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LifelineEntity): Long

    @Query("SELECT * FROM lifeline_entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): LifelineEntity?

    @Query("SELECT * FROM lifeline_entries WHERE device_id = :deviceId AND media_store_id = :mediaStoreId LIMIT 1")
    suspend fun byLocalRef(deviceId: String, mediaStoreId: Long): LifelineEntity?

    @Query(
        """
        SELECT * FROM lifeline_entries
        WHERE is_deleted = 0
        ORDER BY taken_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecent(limit: Int = 200): List<LifelineEntity>

    /**
     * Photos taken between (exclusive, inclusive) — used by the chat
     * scrollback to interleave timeline cards with messages. UI calls
     * with a window matching the chat history's loaded slice.
     */
    @Query(
        """
        SELECT * FROM lifeline_entries
        WHERE taken_ms > :fromMs AND taken_ms <= :toMs AND is_deleted = 0
        ORDER BY taken_ms ASC
        """,
    )
    suspend fun listBetween(fromMs: Long, toMs: Long): List<LifelineEntity>

    @Query(
        """
        SELECT * FROM lifeline_entries
        WHERE caption_status = :pending AND caption_attempts < :maxAttempts
            AND is_deleted = 0 AND is_remote = 0
        ORDER BY taken_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listPending(
        limit: Int = 20,
        pending: String = LifelineCaptionStatus.PENDING.name,
        maxAttempts: Int = 4,
    ): List<LifelineEntity>

    /** Rows whose caption changed since the last sync; used by MemorySync to ship new + edited. */
    @Query(
        """
        SELECT * FROM lifeline_entries
        WHERE is_remote = 0 AND (synced_at_ms IS NULL OR captioned_at_ms > synced_at_ms)
        ORDER BY taken_ms ASC
        """,
    )
    suspend fun listUnsynced(): List<LifelineEntity>

    @Query("UPDATE lifeline_entries SET synced_at_ms = :nowMs WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>, nowMs: Long)

    @Query(
        """
        UPDATE lifeline_entries
        SET caption_status = :captioned, caption_text = :text,
            caption_model = :model, captioned_at_ms = :nowMs
        WHERE id = :id
        """,
    )
    suspend fun markCaptioned(
        id: Long, text: String, model: String, nowMs: Long,
        captioned: String = LifelineCaptionStatus.CAPTIONED.name,
    )

    @Query(
        """
        UPDATE lifeline_entries
        SET caption_status = :status, caption_attempts = caption_attempts + 1,
            caption_text = COALESCE(caption_text, :failureNote)
        WHERE id = :id
        """,
    )
    suspend fun markFailed(
        id: Long, failureNote: String,
        status: String = LifelineCaptionStatus.FAILED.name,
    )

    @Query(
        """
        UPDATE lifeline_entries
        SET caption_status = :skipped, caption_text = COALESCE(caption_text, :note)
        WHERE id = :id
        """,
    )
    suspend fun markSkipped(
        id: Long, note: String,
        skipped: String = LifelineCaptionStatus.SKIPPED.name,
    )

    @Query("SELECT COUNT(*) FROM lifeline_entries")
    suspend fun total(): Int

    @Query("SELECT MAX(added_ms) FROM lifeline_entries WHERE is_remote = 0")
    suspend fun lastScannedAddedMs(): Long?

    /** Live observation for the chat surface's interleaved timeline.
     *  Skips deleted rows so they vanish from the timeline the moment
     *  the scanner tombstones them. */
    @Query("SELECT * FROM lifeline_entries WHERE is_deleted = 0 ORDER BY taken_ms ASC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<LifelineEntity>>

    /** Local rows only (deviceId = this device, is_remote = false). The
     *  scanner uses this to cross-reference what's still on MediaStore
     *  vs what's been deleted. */
    @Query("SELECT * FROM lifeline_entries WHERE device_id = :deviceId AND is_remote = 0 AND is_deleted = 0")
    suspend fun listLocalLive(deviceId: String): List<LifelineEntity>

    /** Tombstone a row that's been deleted from the gallery. */
    @Query(
        """
        UPDATE lifeline_entries
        SET is_deleted = 1, deleted_at_ms = :nowMs, synced_at_ms = NULL
        WHERE id = :id
        """,
    )
    suspend fun markDeleted(id: Long, nowMs: Long)
}

/**
 * One row per calendar day — a digest of that day's interactions,
 * meaningful memories, learnings, and photos. Built nightly by
 * [DaySummaryBuilder] and interleaved into the timeline grid so the
 * "memory" surface reads as a life log, not just a photo wall.
 */
@Entity(tableName = "day_summaries")
data class DaySummaryEntity(
    /** Days since the Unix epoch (LocalDate.toEpochDay) — stable PK. */
    @PrimaryKey @ColumnInfo(name = "day_epoch") val dayEpoch: Long,
    /** Start-of-day epoch millis (local TZ) — for sorting + display. */
    @ColumnInfo(name = "date_ms") val dateMs: Long,
    val summary: String,
    @ColumnInfo(name = "interaction_count") val interactionCount: Int,
    @ColumnInfo(name = "learning_count") val learningCount: Int,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
    /** Comma-joined top contact names interacted with that day. */
    val contacts: String,
    @ColumnInfo(name = "built_ms") val builtMs: Long,
)

@Dao
interface DaySummaryDao {
    @Query("SELECT * FROM day_summaries ORDER BY date_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int = 180): Flow<List<DaySummaryEntity>>

    @Query("SELECT * FROM day_summaries WHERE day_epoch = :dayEpoch LIMIT 1")
    suspend fun byDay(dayEpoch: Long): DaySummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DaySummaryEntity)
}

@Database(
    entities = [LifelineEntity::class, DaySummaryEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class LifelineDb : RoomDatabase() {
    abstract fun dao(): LifelineDao
    abstract fun daySummaryDao(): DaySummaryDao
}

/**
 * v1 → v2 adds is_deleted + deleted_at_ms for the photo-deletion-sync
 * feature. Defaults give existing rows is_deleted=0 (i.e. live) so the
 * timeline doesn't suddenly empty after upgrade.
 */
private val MIGRATION_LIFELINE_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE lifeline_entries ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE lifeline_entries ADD COLUMN deleted_at_ms INTEGER")
    }
}

/** v2 → v3 adds the day_summaries table for the timeline life-log cards. */
private val MIGRATION_LIFELINE_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `day_summaries` (" +
                "`day_epoch` INTEGER NOT NULL, " +
                "`date_ms` INTEGER NOT NULL, " +
                "`summary` TEXT NOT NULL, " +
                "`interaction_count` INTEGER NOT NULL, " +
                "`learning_count` INTEGER NOT NULL, " +
                "`photo_count` INTEGER NOT NULL, " +
                "`contacts` TEXT NOT NULL, " +
                "`built_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`day_epoch`))",
        )
    }
}

@Singleton
class LifelineRepository @Inject constructor(@ApplicationContext ctx: Context) {
    private val db: LifelineDb = Room.databaseBuilder(
        ctx, LifelineDb::class.java, "mythara_lifeline.db",
    ).addMigrations(MIGRATION_LIFELINE_1_2, MIGRATION_LIFELINE_2_3).build()
    val dao: LifelineDao = db.dao()
    val daySummaryDao: DaySummaryDao = db.daySummaryDao()
}
