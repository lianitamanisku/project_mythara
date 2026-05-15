package com.mythara.resonance

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side ring buffer for the watch's live HR stream
 * (`/mythara/resonance/hr`). Keeps the last few minutes of samples in
 * memory for the analyzer + the (Phase 4) closed loop, and batches
 * them into the [LearningVault] every [FLUSH_INTERVAL_MS] so the same
 * data is durable + queryable later.
 *
 * Per-sample vault rows would be noisy and quotas-unfriendly; one row
 * per ~60s window with min/max/avg is plenty for downstream analysis
 * (mirrors the snapshot shape `HealthLearningWorker` already writes).
 *
 * Created by [ResonanceController] on session start; [stop]ped on
 * session end so the flush job doesn't outlive the session.
 */
@Singleton
class ResonanceHrStore @Inject constructor(
    private val vault: LearningVault,
) {
    private data class Sample(val bpm: Int, val tsMillis: Long)

    private val buffer = ArrayDeque<Sample>(BUFFER_CAP)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    /** Add a fresh HR reading from the watch. Drops out-of-range
     *  samples silently; trims the buffer to [BUFFER_CAP]. */
    fun push(bpm: Int, tsMillis: Long) {
        if (bpm !in VALID_BPM) return
        synchronized(lock) {
            buffer.addLast(Sample(bpm, tsMillis))
            while (buffer.size > BUFFER_CAP) buffer.removeFirst()
        }
    }

    /** Most recent valid sample, or null if none. */
    fun latest(): Int? = synchronized(lock) { buffer.lastOrNull()?.bpm }

    /** Mean BPM across samples received within [windowMs] of now. */
    fun recentAverage(windowMs: Long = ANALYSIS_WINDOW_MS): Int? {
        val cutoff = System.currentTimeMillis() - windowMs
        val recent = synchronized(lock) { buffer.filter { it.tsMillis >= cutoff } }
        return if (recent.isEmpty()) null else recent.map { it.bpm }.average().toInt()
    }

    /** True when the most recent sample is younger than [maxStaleMs]. */
    fun isFresh(maxStaleMs: Long = STALE_AFTER_MS): Boolean {
        val last = synchronized(lock) { buffer.lastOrNull() } ?: return false
        return System.currentTimeMillis() - last.tsMillis < maxStaleMs
    }

    /** Begin the periodic flush-to-vault loop. Idempotent. */
    fun start() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushOnce()
            }
        }
    }

    /** Cancel the flush loop and write one last batch (if any). */
    fun stop() {
        flushJob?.cancel()
        flushJob = null
        scope.launch { flushOnce() }
    }

    private suspend fun flushOnce() {
        val snapshot: List<Sample> = synchronized(lock) {
            if (buffer.isEmpty()) emptyList() else buffer.toList()
        }
        if (snapshot.isEmpty()) return
        val cutoff = System.currentTimeMillis() - FLUSH_INTERVAL_MS
        val window = snapshot.filter { it.tsMillis >= cutoff }
        if (window.isEmpty()) return

        val bpms = window.map { it.bpm }
        val avg = bpms.average().toInt()
        val min = bpms.min()
        val max = bpms.max()
        val content = """{"avg":$avg,"min":$min,"max":$max,"n":${bpms.size}}"""
        runCatching {
            vault.add(
                content = content,
                tier = Tier.Working,
                src = "resonance:hr-stream",
                facets = listOf(
                    "kind:heart-rate",
                    "source:watch",
                    "topic:resonance",
                ),
                conf = 0.95,
            )
        }.onFailure { Log.w(TAG, "flush failed: ${it.message}") }
    }

    /** Drop everything in the buffer + cancel the flush job. */
    fun clear() {
        flushJob?.cancel()
        flushJob = null
        synchronized(lock) { buffer.clear() }
    }

    companion object {
        private const val TAG = "Mythara/ResonanceHr"
        private val VALID_BPM = 30..240

        /** Most recent ~5 min of samples at 1Hz fits comfortably. */
        private const val BUFFER_CAP = 320

        /** Default analyzer window: average HR over the last 30s. */
        const val ANALYSIS_WINDOW_MS = 30_000L

        /** A sample older than this is considered stale (watch BT drop). */
        const val STALE_AFTER_MS = 30_000L

        /** One vault row per ~60s of streamed HR — matches the
         *  granularity HealthLearningWorker already writes. */
        private const val FLUSH_INTERVAL_MS = 60_000L
    }
}
