package com.mythara.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.mythara.branding.LiveWallpaperPulseSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide PRIMARY heart-rate source (v6 directive).
 *
 * Reads the latest [HeartRateRecord] samples from **Google Health /
 * Health Connect** and publishes them to [LiveWallpaperPulseSink], so
 * the in-app Living-Rose backdrop, the bottom-centre rose amulet, and
 * the live wallpaper all breathe with the user's actual heartbeat
 * regardless of whether the watch's Data-Layer push is flowing.
 *
 * Why Health Connect is primary: Samsung/Pixel watches batch HR into
 * Health Connect every ~1 min, and third-party direct sensor streaming
 * is unreliable across vendors (see [com.mythara.resonance.ResonanceHcHrPoller]
 * for the full write-up). The legacy watch Data-Layer path
 * ([com.mythara.wear.MytharaWearListenerService] → pulse sink) still
 * publishes; whichever source posts a fresher sample wins because the
 * sink just keeps the most-recent `(bpm, ts)` pair. Health Connect
 * being polled continuously makes it the de-facto primary.
 *
 * Lifecycle: foreground-gated — [start] from `ProcessLifecycleOwner`
 * ON_START, [stop] on ON_STOP. The rose backdrop only matters while
 * the app is visible, so there's no point draining battery polling HC
 * in the background (the wallpaper service has its own HR path). Both
 * calls are idempotent.
 */
@Singleton
class HealthConnectHrPoller @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    @Volatile private var lastPushedTsMs: Long = 0L
    @Volatile private var samplesPushed: Int = 0

    /** Begin polling. Idempotent. */
    fun start() {
        if (pollJob?.isActive == true) return
        lastPushedTsMs = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        pollJob = scope.launch {
            pollOnce()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                pollOnce()
            }
        }
        Log.d(TAG, "Health Connect HR poller started (primary HR source)")
    }

    /** Stop polling. Idempotent. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        Log.d(TAG, "Health Connect HR poller stopped ($samplesPushed sample(s) this run)")
    }

    private suspend fun pollOnce() {
        runCatching {
            if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) return
            val client = HealthConnectClient.getOrCreate(ctx)
            val hrPerm = HealthPermission.getReadPermission(HeartRateRecord::class)
            if (hrPerm !in client.permissionController.getGrantedPermissions()) return

            val now = Instant.now()
            val since = Instant.ofEpochMilli(lastPushedTsMs - OVERLAP_MS)
                .coerceAtLeast(now.minusMillis(MAX_LOOKBACK_MS))
            val records = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(since, now)),
            ).records

            val flat = records.flatMap { rec ->
                rec.samples.map { s -> s.time.toEpochMilli() to s.beatsPerMinute.toInt() }
            }.sortedBy { it.first }

            val fresh = flat.filter { it.first > lastPushedTsMs }
            if (fresh.isEmpty()) return

            // Publish each fresh sample (sink keeps the latest + drives
            // its baseline / spike-detection off the stream).
            for ((_, bpm) in fresh) {
                LiveWallpaperPulseSink.update(bpm)
            }
            lastPushedTsMs = fresh.last().first
            samplesPushed += fresh.size
            Log.d(TAG, "HC HR → sink: pushed ${fresh.size}, latest ${fresh.last().second} bpm")
        }.onFailure {
            Log.w(TAG, "HC HR poll failed: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "Mythara/HcHrPoller"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val INITIAL_LOOKBACK_MS = 30L * 60 * 1000
        private const val MAX_LOOKBACK_MS = 30L * 60 * 1000
        private const val OVERLAP_MS = 5_000L
    }
}
