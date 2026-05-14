package com.mythara.memory.devices

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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
 * Device-to-device message log.
 *
 * Pattern: each install owns a "from-this-device" outbox (synced to
 * the memory repo) and a "to-this-device" inbox (pulled from the
 * repo). Every payload is a single JSON line; the schema is generic
 * enough to carry any request type — the [kind] field disambiguates,
 * [payloadJson] holds kind-specific content.
 *
 * Today's traffic:
 *   - location_request : "tell me where you are"
 *   - location_response: GPS fix payload back to the requester
 *   - ping             : connectivity test
 *
 * Layout in the memory repo (see [DeviceMessageSync]):
 *   device_messages/inbox/<recipient_id>.jsonl  — append-only
 *   device_messages/cursors/<reader_id>.json    — last seen IDs per peer
 *
 * Dedup: every message has a UUID; receivers track lastSeenId per
 * peer in their cursor file. Re-pulling the same inbox is idempotent.
 */
@Entity(tableName = "device_messages")
data class DeviceMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "ts_millis") val tsMillis: Long,
    @ColumnInfo(name = "from_device") val fromDevice: String,
    @ColumnInfo(name = "to_device") val toDevice: String,
    /** "location_request" | "location_response" | "ping" | future. */
    val kind: String,
    /**
     * Request id this message belongs to. For LocationRequest, equals
     * [id]. For LocationResponse, equals the original Request's id so
     * the requester can correlate. Null for fire-and-forget pings.
     */
    @ColumnInfo(name = "request_id") val requestId: String? = null,
    /** Kind-specific JSON payload. */
    @ColumnInfo(name = "payload_json") val payloadJson: String = "{}",
    /**
     * pending  — created, not yet synced out
     * sent     — pushed to repo
     * received — pulled into local inbox
     * handled  — receiver processed it (e.g. answered a location_request)
     * expired  — past the TTL with no resolution
     */
    val status: String = "pending",
    /** Human-readable note, e.g. error from handler. */
    val note: String? = null,
)

@Dao
interface DeviceMessageDao {
    @Query("SELECT * FROM device_messages ORDER BY ts_millis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<DeviceMessageEntity>>

    @Query("SELECT * FROM device_messages WHERE status = :status LIMIT :limit")
    suspend fun listByStatus(status: String, limit: Int = 200): List<DeviceMessageEntity>

    @Query("SELECT * FROM device_messages WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): DeviceMessageEntity?

    @Query("SELECT * FROM device_messages WHERE request_id = :reqId ORDER BY ts_millis DESC")
    suspend fun byRequestId(reqId: String): List<DeviceMessageEntity>

    @Query("SELECT * FROM device_messages ORDER BY ts_millis DESC LIMIT :limit")
    suspend fun listRecent(limit: Int = 500): List<DeviceMessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(msg: DeviceMessageEntity): Long

    @Query("UPDATE device_messages SET status = :status, note = :note WHERE id = :id")
    suspend fun setStatus(id: String, status: String, note: String? = null)

    @Query("DELETE FROM device_messages WHERE ts_millis < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM device_messages")
    suspend fun clear()
}

@Database(entities = [DeviceMessageEntity::class], version = 1, exportSchema = false)
abstract class DeviceMessagesDb : RoomDatabase() {
    abstract fun messages(): DeviceMessageDao
}

@Singleton
class DeviceMessageRepository @Inject constructor(@ApplicationContext ctx: Context) {
    private val db: DeviceMessagesDb = Room.databaseBuilder(
        ctx, DeviceMessagesDb::class.java, "mythara_device_messages.db",
    ).fallbackToDestructiveMigration().build()
    val dao: DeviceMessageDao = db.messages()
}

object DeviceMessageKind {
    const val LOCATION_REQUEST = "location_request"
    const val LOCATION_RESPONSE = "location_response"
    const val PING = "ping"
    /** User-initiated note / chat / idea handoff from device A → B.
     *  Payload: {"title": "...", "body": "..."} — recipient surfaces it
     *  in their chat scrollback as a FromOtherDevice card. */
    const val CHAT_NOTE = "chat_note"
    /** New task posted by device A — recipient(s) pick it up via the
     *  task scheduler. Payload: TaskExport JSON. */
    const val TASK_CREATED = "task_created"
    /** Status update on an existing task (claimed / running / done /
     *  failed). Payload: {"taskId":..., "status":..., "note":...}. */
    const val TASK_UPDATE = "task_update"
    /** Ask the recipient for a comprehensive sensor snapshot. */
    const val SENSOR_REQUEST = "sensor_request"
    /** Recipient's full sensor JSON, indexed back to the original requestId. */
    const val SENSOR_RESPONSE = "sensor_response"
}

object DeviceMessageStatus {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val RECEIVED = "received"
    const val HANDLED = "handled"
    const val FAILED = "failed"
    const val EXPIRED = "expired"
}
