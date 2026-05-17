package com.mythara.memory

import android.content.Context
import android.util.Log
import com.mythara.tasks.TaskExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process 5-minute auto-sync. Triggers [MemorySyncScheduler.fireNow]
 * on a loop AND runs the [TaskExecutor.tick] heartbeat so each device
 * picks up its share of the cross-device task queue.
 *
 * Why a coroutine timer instead of WorkManager: Android's
 * PeriodicWorkRequest floors at 15 minutes — the user explicitly
 * asked for 5. When the process is alive (which is most of the
 * time thanks to AgentForegroundService + the AutoReply path), this
 * loop drives the cadence. When the process dies, the existing
 * MemorySyncScheduler 24h periodic + the new TaskHeartbeatWorker
 * (15-min WorkManager job, the cadence floor) are the safety nets.
 *
 * Self-gates on:
 *  - memory sync configured (PAT + repo set)
 *  - memory sync enabled (user toggle)
 *  Otherwise the loop sleeps without doing anything — no Wi-Fi /
 *  battery cost when the user hasn't turned the feature on.
 */
@Singleton
class HeartbeatSyncer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val scheduler: MemorySyncScheduler,
    private val memorySettings: MemorySettings,
    private val taskExecutor: TaskExecutor,
    private val presenceCache: DevicePresenceCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loop: Job? = null
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        loop = scope.launch {
            // First tick after a short delay so we don't spam on cold
            // start while everything else is still warming up.
            delay(INITIAL_DELAY_MS)
            while (true) {
                runCatching { tick() }.onFailure {
                    Log.w(TAG, "heartbeat tick failed: ${it.message}")
                }
                delay(INTERVAL_MS)
            }
        }
        Log.d(TAG, "HeartbeatSyncer started (interval ${INTERVAL_MS / 1000}s)")
    }

    private suspend fun tick() {
        val snap = runCatching { memorySettings.snapshot() }.getOrNull() ?: return
        if (!snap.enabled || !snap.configured) {
            Log.v(TAG, "heartbeat: sync disabled/unconfigured — skipping")
            return
        }
        Log.d(TAG, "heartbeat: firing sync + task tick + presence refresh")
        // 1) Sync: ships pending tasks + heartbeat presence, pulls
        //    remote task state.
        scheduler.fireNow(force = false)
        // 2) Task pick + execute: serially claim + run anything that
        //    targets THIS device or any device. Bounded run per tick
        //    so a backlog of tasks can't wedge the heartbeat.
        runCatching { taskExecutor.tick(maxTasks = 3) }
            .onFailure { Log.w(TAG, "task tick failed: ${it.message}") }
        // 3) Presence refresh: pull the canonical device_messages/devices
        //    directory into the in-memory cache so EnvironmentContext can
        //    emit `proximity:<device>` facets synchronously per utterance
        //    without hitting GitHub each time.
        runCatching { presenceCache.refreshFromHeartbeats() }
            .onFailure { Log.v(TAG, "presence refresh failed: ${it.message}") }
    }

    /** Single-shot manual kick — used by the "sync now" button + the
     *  Tasks screen's "check now" affordance. */
    fun fireNow() {
        scope.launch { runCatching { tick() } }
    }

    companion object {
        private const val TAG = "Mythara/Heartbeat"
        const val INTERVAL_MS = 5L * 60 * 1000  // 5 min
        const val INITIAL_DELAY_MS = 30L * 1000   // 30 s after boot
    }
}
