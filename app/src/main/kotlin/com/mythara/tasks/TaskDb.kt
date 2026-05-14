package com.mythara.tasks

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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-device task queue. One row per task; tasks sync to the memory
 * repo (lifeline-style, partitioned by YYYY-MM), and any Mythara
 * install signed into the same repo picks them up on its 5-minute
 * heartbeat sync.
 *
 * Lifecycle:
 *
 *   PENDING ──▶ CLAIMED ──▶ RUNNING ──▶ DONE / FAILED
 *
 * Targeting:
 *  - targetDeviceId = null → "any device" (first that claims wins).
 *  - targetDeviceId = "<id>" → only that device may claim.
 *
 * Handoff safety:
 *  The user can request `targetDeviceId = X` explicitly via the
 *  agent's `handoff_task` tool or the TasksScreen composer. The
 *  scheduler refuses to auto-route tasks across devices on its own —
 *  null-targeted ones are picked up cooperatively by whichever device
 *  happens to be alive when the heartbeat fires.
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["target_device_id"]),
        Index(value = ["created_ms"]),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "body") val body: String,
    /** Device that created the task (the "requester"). */
    @ColumnInfo(name = "requester_device_id") val requesterDeviceId: String,
    /** Null = any device may claim. Non-null = only that device. */
    @ColumnInfo(name = "target_device_id") val targetDeviceId: String?,
    /** [TaskStatus.name] */
    @ColumnInfo(name = "status") val status: String = TaskStatus.PENDING.name,
    /** Device that claimed it (null while PENDING). */
    @ColumnInfo(name = "claimed_by_device_id") val claimedByDeviceId: String? = null,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    @ColumnInfo(name = "claimed_ms") val claimedMs: Long? = null,
    @ColumnInfo(name = "completed_ms") val completedMs: Long? = null,
    /** When DONE: free text result. When FAILED: error message. */
    @ColumnInfo(name = "result_text") val resultText: String? = null,
    /** Optional schedule (epoch ms). Null = run-asap. */
    @ColumnInfo(name = "scheduled_for_ms") val scheduledForMs: Long? = null,
    /**
     * Optional recurrence spec ([com.mythara.reminders.Recurrence]
     * encoded form, e.g. "DAILY:09:00"). Null = one-shot. When set,
     * the reminder receiver re-arms [scheduledForMs] to the next
     * occurrence each time it fires instead of going terminal.
     */
    @ColumnInfo(name = "recurrence") val recurrence: String? = null,
    /**
     * Local marker — true if we've already shipped this row's
     * latest state to the memory repo on a previous sync. Cleared
     * whenever the row changes state so the next sync re-uploads.
     */
    @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,
)

enum class TaskStatus {
    PENDING,   // unclaimed
    CLAIMED,   // someone said "I'll do this" but hasn't started
    RUNNING,   // actively executing
    DONE,      // terminal
    FAILED,    // terminal
    CANCELED,  // terminal — user pulled the plug
}

@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TaskEntity): Long

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY created_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY created_ms DESC LIMIT :limit")
    suspend fun listRecent(limit: Int = 200): List<TaskEntity>

    /**
     * Tasks claimable by [deviceId] right now — PENDING, scheduled for
     * the past or unscheduled, targeted at this device or null.
     */
    @Query(
        """
        SELECT * FROM tasks
        WHERE status = :pending
          AND (target_device_id IS NULL OR target_device_id = :deviceId)
          AND (scheduled_for_ms IS NULL OR scheduled_for_ms <= :nowMs)
        ORDER BY created_ms ASC
        LIMIT :limit
        """,
    )
    suspend fun listClaimable(
        deviceId: String,
        nowMs: Long,
        limit: Int = 5,
        pending: String = TaskStatus.PENDING.name,
    ): List<TaskEntity>

    @Query(
        """
        UPDATE tasks
        SET status = :claimed, claimed_by_device_id = :deviceId,
            claimed_ms = :nowMs, synced_at_ms = NULL
        WHERE id = :id AND status = :pending
        """,
    )
    suspend fun tryClaim(
        id: String, deviceId: String, nowMs: Long,
        claimed: String = TaskStatus.CLAIMED.name,
        pending: String = TaskStatus.PENDING.name,
    ): Int

    @Query(
        """
        UPDATE tasks
        SET status = :newStatus, result_text = :result,
            completed_ms = :nowMs, synced_at_ms = NULL
        WHERE id = :id
        """,
    )
    suspend fun markTerminal(
        id: String, newStatus: String, result: String?, nowMs: Long,
    )

    @Query("UPDATE tasks SET status = :running, synced_at_ms = NULL WHERE id = :id")
    suspend fun markRunning(id: String, running: String = TaskStatus.RUNNING.name)

    /** Rows whose local state hasn't been synced to the repo yet. */
    @Query(
        """
        SELECT * FROM tasks
        WHERE synced_at_ms IS NULL
        ORDER BY created_ms ASC
        """,
    )
    suspend fun listUnsynced(): List<TaskEntity>

    /**
     * Every row this device is responsible for publishing to the repo —
     * tasks it created (requester) or claimed/ran (claimer). In the
     * per-device task-file layout each device writes ONLY its own
     * `tasks/<deviceId>/...` subtree from this set, so there's a single
     * writer per file and cross-device task writes never conflict.
     */
    @Query(
        """
        SELECT * FROM tasks
        WHERE requester_device_id = :deviceId OR claimed_by_device_id = :deviceId
        ORDER BY created_ms ASC
        """,
    )
    suspend fun listOwnedBy(deviceId: String): List<TaskEntity>

    @Query("UPDATE tasks SET synced_at_ms = :nowMs WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, nowMs: Long)

    @Query("SELECT COUNT(*) FROM tasks WHERE status IN (:pending, :claimed, :running)")
    suspend fun pendingCount(
        pending: String = TaskStatus.PENDING.name,
        claimed: String = TaskStatus.CLAIMED.name,
        running: String = TaskStatus.RUNNING.name,
    ): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE status IN (:pending, :claimed, :running)")
    fun pendingCountFlow(
        pending: String = TaskStatus.PENDING.name,
        claimed: String = TaskStatus.CLAIMED.name,
        running: String = TaskStatus.RUNNING.name,
    ): Flow<Int>
}

@Database(entities = [TaskEntity::class], version = 2, exportSchema = false)
abstract class TaskDb : RoomDatabase() {
    abstract fun dao(): TaskDao
}

/** v1 → v2: adds the nullable `recurrence` column for repeating tasks. */
internal val MIGRATION_TASKS_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN recurrence TEXT")
    }
}

@Singleton
class TaskRepository @Inject constructor(@ApplicationContext ctx: Context) {
    private val db: TaskDb = Room.databaseBuilder(
        ctx, TaskDb::class.java, "mythara_tasks.db",
    ).addMigrations(MIGRATION_TASKS_1_2).build()
    val dao: TaskDao = db.dao()
}
