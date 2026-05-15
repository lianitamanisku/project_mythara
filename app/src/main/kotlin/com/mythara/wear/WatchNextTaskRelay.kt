package com.mythara.wear

import android.util.Log
import com.mythara.tasks.TaskEntity
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the **next upcoming scheduled task** to the watch face's
 * insight complication. Replaces [WatchAgentMessageRelay]'s "last
 * agent chat message" with a forward-looking card so the wrist
 * always tells the user what's coming next today, dynamically as
 * time moves forward.
 *
 * Refresh triggers:
 *  - Task DB changes (new schedule, cancel, fire, recurrence re-arm).
 *    Observed via [TaskRepository.dao.observeRecent].
 *  - Wall-clock tick every [TICK_INTERVAL_MS] (~60 s) so the "in 5m"
 *    style countdown stays correct as time passes — and so a task
 *    whose `scheduledForMs` just crossed `now` rolls off and the
 *    next one slides up automatically.
 *
 * The watch complication shows whichever line is currently in
 * [WatchInsightPusher]; "no upcoming task" pushes an idle line so
 * the wrist doesn't keep showing yesterday's reminder.
 */
@Singleton
class WatchNextTaskRelay @Inject constructor(
    private val taskRepo: TaskRepository,
    private val pusher: WatchInsightPusher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Compute the current next-task line and push it once. Public
     *  so the manual "sync to watch now" path + the periodic
     *  15-min worker can refresh the wrist without waiting for
     *  either the in-process ticker or a task DB change. */
    suspend fun pushNow() {
        val rows = runCatching { taskRepo.dao.listRecent(limit = 200) }.getOrDefault(emptyList())
        val line = formatLine(rows)
        runCatching { pusher.push(line) }
            .onFailure { Log.w(TAG, "manual push failed: ${it.message}") }
    }

    fun start() {
        // Wall-clock loop — every minute, re-read tasks and push the
        // current line. The line includes a live countdown ("in 12m",
        // "in 1h 30m") that drifts as wall-clock time advances, so we
        // need to push regardless of whether row data has changed.
        // The push pipeline (WatchInsightPusher → WearableMessageClient
        // → InsightStore) is dedup-safe — identical strings hit the
        // "insight unchanged; no buzz, no refresh" fast-path on the
        // watch side, so this loop is cheap when the line happens not
        // to change minute-over-minute.
        scope.launch {
            while (true) {
                pushNow()
                delay(TICK_INTERVAL_MS)
            }
        }
        // Also fire on every DB change so a freshly-created or
        // cancelled task shows up immediately, not at the next tick.
        scope.launch {
            taskRepo.dao.observeRecent(limit = 200)
                .collect { _ -> pushNow() }
        }
    }

    private fun formatLine(rows: List<TaskEntity>): String {
        val now = System.currentTimeMillis()
        // PENDING tasks with a future schedule — the queue of "what's
        // coming up." Sorted by scheduledForMs ascending; we surface
        // the soonest.
        val upcoming = rows
            .filter { it.status == TaskStatus.PENDING.name }
            .filter { (it.scheduledForMs ?: 0L) > now }
            .sortedBy { it.scheduledForMs }
        val next = upcoming.firstOrNull() ?: return formatIdle(rows, now)
        val whenMs = next.scheduledForMs ?: return formatIdle(rows, now)
        val deltaMs = whenMs - now
        val whenStr = relativeTime(deltaMs, whenMs)
        // Title comes first so a glance is enough — the time hangs
        // off the end where the eye lands second.
        val title = next.title.trim().take(MAX_TITLE_CHARS)
        return "$title · $whenStr"
    }

    /** Fallback line when there are no upcoming tasks — show a count
     *  of what's already DONE today rather than "nothing planned"
     *  (less useful, more flat). */
    private fun formatIdle(rows: List<TaskEntity>, now: Long): String {
        val startOfDay = startOfDayMs(now)
        val firedToday = rows.count { row ->
            row.status == TaskStatus.DONE.name &&
                (row.completedMs ?: 0L) >= startOfDay
        }
        return when (firedToday) {
            0 -> "no scheduled tasks today"
            1 -> "1 task done today · nothing else queued"
            else -> "$firedToday tasks done today · nothing else queued"
        }
    }

    /** ALWAYS a live countdown so the line re-renders minute-over-
     *  minute as the wall clock advances. The previous version
     *  switched to absolute time ("14:30") for >60-min targets and
     *  appeared frozen on the wrist; the new format keeps "in Nh
     *  Mm" / "in Nd Hh" so the watch is always visibly counting
     *  down toward the next event. */
    private fun relativeTime(deltaMs: Long, whenMs: Long): String {
        val totalMins = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
        return when {
            totalMins < 1 -> "now"
            totalMins < 60 -> "in ${totalMins}m"
            totalMins < 24 * 60 -> {
                val h = totalMins / 60
                val m = totalMins % 60
                if (m == 0L) "in ${h}h" else "in ${h}h ${m}m"
            }
            else -> {
                val days = totalMins / (24 * 60)
                val hoursRem = (totalMins % (24 * 60)) / 60
                if (hoursRem == 0L) "in ${days}d" else "in ${days}d ${hoursRem}h"
            }
        }
    }

    private fun startOfDayMs(now: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    companion object {
        private const val TAG = "Mythara/NextTaskRelay"

        /** Wall-clock tick cadence. 60 s is the right resolution for
         *  the "in N min" countdown — finer than a minute would burn
         *  battery without buying the user anything since the watch
         *  complication itself only refreshes when we push. */
        private const val TICK_INTERVAL_MS = 60_000L

        /** Cap on the task title we copy into the push. The Insight
         *  pusher already trims to 120 chars; this leaves headroom
         *  for the " · in 12m" suffix. */
        private const val MAX_TITLE_CHARS = 80

    }
}
