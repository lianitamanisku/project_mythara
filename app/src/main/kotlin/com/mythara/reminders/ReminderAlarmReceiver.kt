package com.mythara.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mythara.R
import com.mythara.MainActivity
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives:
 *
 *   1. `REMINDER_FIRE`   — AlarmManager wake at the task's scheduled
 *                          time. Path: announce via TTS + post the
 *                          notification + add the in-chat reminder
 *                          card via ChatItem.ReminderCard.
 *
 *   2. `REMINDER_ACTION` — user tapped Done / Snooze 15m / Snooze 1h
 *                          on either the chat card or the notification.
 *                          Updates the underlying TaskEntity; the
 *                          alarm-scheduler observer picks the state
 *                          change up + re-registers / cancels the
 *                          alarm accordingly.
 *
 * @AndroidEntryPoint so Hilt injects the singletons we need (task
 * repo, announcer, scheduler). goAsync() to keep the broadcast alive
 * past the receiver's 10s window while we do DB writes + TTS.
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var taskRepo: TaskRepository
    @Inject lateinit var announcer: ReminderAnnouncer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        val taskId = intent.getStringExtra(ReminderAlarmScheduler.EXTRA_TASK_ID) ?: return
        val pendingResult = goAsync()
        scope.launch {
            try {
                when (action) {
                    ReminderAlarmScheduler.ACTION_FIRE -> handleFire(ctx, taskId)
                    ReminderAlarmScheduler.ACTION_ACTION -> {
                        val kind = intent.getStringExtra(ReminderAlarmScheduler.EXTRA_ACTION_KIND).orEmpty()
                        handleUserAction(ctx, taskId, kind)
                    }
                    else -> Log.w(TAG, "unknown action $action")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "receiver threw: ${t.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleFire(ctx: Context, taskId: String) {
        val task = taskRepo.dao.byId(taskId) ?: return
        Log.d(TAG, "fire: $taskId '${task.title.take(40)}'")
        // Announce: personalized TTS + notification post + ensure the
        // chat-side ReminderCard appears via the TaskDb already
        // surfacing this row (UI reads is_due via scheduled_for_ms
        // vs now).
        announcer.announce(task)
        postNotification(ctx, taskId, task.title, task.body)

        val recurrence = Recurrence.parse(task.recurrence)
        if (recurrence != null) {
            // Recurring — re-arm for the next occurrence instead of
            // going terminal. The ReminderAlarmScheduler observer picks
            // up the rewritten row and registers a fresh exact alarm.
            val next = recurrence.nextAfter(System.currentTimeMillis())
            runCatching {
                taskRepo.dao.upsert(
                    task.copy(
                        status = TaskStatus.PENDING.name,
                        scheduledForMs = next,
                        syncedAtMs = null,
                    ),
                )
            }
            Log.d(TAG, "$taskId recurring (${task.recurrence}) → re-armed for ${java.util.Date(next)}")
        } else {
            // One-shot — mark RUNNING so the chat card shows "now
            // firing". Status resolves to DONE / SNOOZED on user action.
            runCatching { taskRepo.dao.markRunning(taskId) }
        }
    }

    private suspend fun handleUserAction(ctx: Context, taskId: String, kind: String) {
        val now = System.currentTimeMillis()
        val task = taskRepo.dao.byId(taskId) ?: return
        when (kind) {
            "done" -> {
                if (Recurrence.parse(task.recurrence) != null) {
                    // Recurring — "Done" just dismisses THIS occurrence's
                    // notification. handleFire already re-armed the row
                    // for the next fire, so the series continues.
                    cancelNotif(ctx, taskId)
                    Log.d(TAG, "$taskId recurring occurrence dismissed; series continues")
                } else {
                    taskRepo.dao.markTerminal(taskId, TaskStatus.DONE.name, "user marked done", now)
                    cancelNotif(ctx, taskId)
                    Log.d(TAG, "$taskId marked done by user")
                }
            }
            "snooze_15m", "snooze_1h", "snooze_3h" -> {
                val delta = when (kind) {
                    "snooze_15m" -> 15L * 60_000
                    "snooze_1h" -> 60L * 60_000
                    "snooze_3h" -> 3L * 60 * 60_000
                    else -> 15L * 60_000
                }
                val newSchedule = now + delta
                // Re-arm: rewrite the row with new schedule + PENDING
                // status so the scheduler observer registers a fresh
                // alarm. syncedAtMs cleared so the new state ships.
                val updated = task.copy(
                    status = TaskStatus.PENDING.name,
                    scheduledForMs = newSchedule,
                    syncedAtMs = null,
                )
                taskRepo.dao.upsert(updated)
                cancelNotif(ctx, taskId)
                Log.d(TAG, "$taskId snoozed by ${delta / 60_000}m → ${java.util.Date(newSchedule)}")
            }
            else -> Log.w(TAG, "unknown action kind '$kind' for $taskId")
        }
    }

    // ----------------------------------------------------- notification

    private fun postNotification(ctx: Context, taskId: String, title: String, body: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(nm)
        val openApp = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setSubText("Lumi reminder")
            .setContentTitle("⟳ $title")
            .setContentText(body.ifBlank { "Reminder from Lumi" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { title }))
            .setContentIntent(openApp)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Done", actionIntent(ctx, taskId, "done"))
            .addAction(0, "+15m", actionIntent(ctx, taskId, "snooze_15m"))
            .addAction(0, "+1h", actionIntent(ctx, taskId, "snooze_1h"))
            .build()
        nm.notify(notifIdFor(taskId), notif)
    }

    private fun cancelNotif(ctx: Context, taskId: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(notifIdFor(taskId))
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mythara reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Wake-up notifications for scheduled reminders + tasks."
            },
        )
    }

    private fun actionIntent(ctx: Context, taskId: String, kind: String): PendingIntent {
        val intent = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmScheduler.ACTION_ACTION
            putExtra(ReminderAlarmScheduler.EXTRA_TASK_ID, taskId)
            putExtra(ReminderAlarmScheduler.EXTRA_ACTION_KIND, kind)
        }
        // Distinct requestCode per (taskId, kind) so action intents don't collide.
        val rc = (taskId + kind).hashCode()
        return PendingIntent.getBroadcast(
            ctx, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifIdFor(taskId: String): Int = (taskId.hashCode() and 0x7fffffff)

    companion object {
        private const val TAG = "Mythara/Reminder"
        private const val CHANNEL_ID = "mythara_reminders"
    }
}
