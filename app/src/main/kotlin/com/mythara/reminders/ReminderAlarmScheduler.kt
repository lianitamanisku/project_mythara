package com.mythara.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mythara.tasks.TaskEntity
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers AlarmManager exact-wake intents for every task that has a
 * scheduled_for_ms in the future and isn't already terminal.
 *
 * Why AlarmManager (vs WorkManager / the 5-min heartbeat):
 *  - The heartbeat ticks every 5 min — fine for cooperative claiming
 *    of "any-device" tasks, but a 5-min slop is unacceptable for
 *    user-facing reminders ("remind me at 14:30").
 *  - WorkManager periodic floors at 15 min and doesn't fire at exact
 *    times.
 *  - AlarmManager.setExactAndAllowWhileIdle fires within the second
 *    of the requested wall-clock time even in Doze, which is the
 *    contract a user expects from a reminder.
 *
 * Lifecycle:
 *  - [start] subscribes to TaskDao.observeRecent and re-syncs alarms
 *    on every change.
 *  - On any task that's PENDING/CLAIMED with scheduled_for_ms in the
 *    future, register an exact alarm (or update the existing one).
 *  - On terminal-state tasks, cancel any pending alarm.
 *  - Boot path ([rescheduleAll]) called from BootReceiver re-registers
 *    everything from a clean state because AlarmManager doesn't
 *    survive a reboot.
 *
 * Local time always used: the user thinks in their wall-clock; we
 * pass scheduled_for_ms as RTC_WAKEUP which honours the device's
 * configured time zone automatically.
 */
@Singleton
class ReminderAlarmScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val taskRepo: TaskRepository,
    private val deviceIdStore: com.mythara.memory.DeviceIdStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Initial pass on boot — recover from a process restart
            // where alarms were lost but the DB rows are intact.
            runCatching { rescheduleAll() }
                .onFailure { Log.w(TAG, "initial reschedule failed: ${it.message}") }
            taskRepo.dao.observeRecent(limit = 500).collect { rows ->
                reconcile(rows)
            }
        }
        Log.d(TAG, "ReminderAlarmScheduler started")
    }

    /** Re-register every still-pending scheduled task. Used by BootReceiver. */
    suspend fun rescheduleAll() {
        val rows = runCatching { taskRepo.dao.listRecent(limit = 500) }.getOrDefault(emptyList())
        reconcile(rows)
    }

    private suspend fun reconcile(rows: List<TaskEntity>) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        val now = System.currentTimeMillis()
        val localId = runCatching { deviceIdStore.id() }.getOrNull()
        for (task in rows) {
            val sched = task.scheduledForMs ?: continue
            // Only the target device arms + fires a pinned reminder —
            // otherwise every synced device would notify in parallel.
            val target = task.targetDeviceId
            if (target != null && localId != null && target != localId) continue
            val pi = pendingIntentFor(task.id, mutable = false) ?: continue
            val isLive = task.status in LIVE_STATUSES
            val isFuture = sched > now
            if (isLive && isFuture) {
                runCatching {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sched, pi)
                }.onFailure {
                    // SCHEDULE_EXACT_ALARM revocable on Android 12 — fall
                    // back to inexact (~10 min slop) rather than crash.
                    Log.w(TAG, "exact alarm denied for ${task.id} (${it.message}); falling back to inexact")
                    runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, sched, pi) }
                }
            } else {
                // Terminal or past-due — cancel any pending alarm.
                runCatching { am.cancel(pi) }
            }
        }
    }

    private fun pendingIntentFor(taskId: String, mutable: Boolean): PendingIntent? {
        val intent = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return runCatching {
            PendingIntent.getBroadcast(ctx, taskId.hashCode(), intent, flags)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Mythara/ReminderAlm"
        const val ACTION_FIRE = "com.mythara.action.REMINDER_FIRE"
        const val ACTION_ACTION = "com.mythara.action.REMINDER_ACTION"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_ACTION_KIND = "action_kind" // "done" | "snooze_15m" | "snooze_1h"

        private val LIVE_STATUSES = setOf(
            TaskStatus.PENDING.name,
            TaskStatus.CLAIMED.name,
            TaskStatus.RUNNING.name,
        )
    }
}
