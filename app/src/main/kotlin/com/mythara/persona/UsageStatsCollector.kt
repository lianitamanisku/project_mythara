package com.mythara.persona

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls app-foreground usage data from Android's [UsageStatsManager]
 * for the period the caller asks for. Pure read-only access — we
 * don't write back to the system anywhere.
 *
 * Output is a sorted descending list of (package, label, total
 * foreground ms, last-used ms) so the persona builder can pick the
 * top-K apps and build a behaviour record. Anything below
 * [MIN_FG_MS] is dropped to suppress 50ms launcher peek-throughs
 * that aren't real user time.
 *
 * Failure modes:
 *  - permission not granted → returns empty list (caller checks
 *    via UsageAccessHelper.isGranted before invoking)
 *  - API throws (rare; some OEMs return null) → empty list
 */
@Singleton
class UsageStatsCollector @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    data class AppUsage(
        val packageName: String,
        val label: String,
        val totalForegroundMs: Long,
        val lastUsedMs: Long,
        val launchCount: Int,
    )

    /**
     * Aggregate usage over [windowMs] ending at `now`. Defaults to
     * the last 24 hours.
     */
    fun collect(windowMs: Long = DEFAULT_WINDOW_MS): List<AppUsage> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val now = System.currentTimeMillis()
        val begin = now - windowMs
        val raw = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, now)
        }.getOrNull() ?: return emptyList()

        // queryUsageStats can return multiple entries per package
        // (one per bucket). Merge by package so the agent sees a
        // single per-app summary.
        val merged = mutableMapOf<String, UsageStats>()
        for (s in raw) {
            val pkg = s.packageName ?: continue
            val existing = merged[pkg]
            if (existing == null) merged[pkg] = s
            else {
                existing.add(s)
                merged[pkg] = existing
            }
        }
        val pm = ctx.packageManager
        return merged.values
            .asSequence()
            .filter { it.totalTimeInForeground >= MIN_FG_MS }
            .map { stat ->
                AppUsage(
                    packageName = stat.packageName,
                    label = labelFor(pm, stat.packageName) ?: stat.packageName,
                    totalForegroundMs = stat.totalTimeInForeground,
                    lastUsedMs = stat.lastTimeUsed,
                    // Best-effort — not all OEMs populate this. Fall
                    // back to a rough estimate from foreground time
                    // if zero (treats one minute of fg as one launch).
                    launchCount = ofUsageCount(stat),
                )
            }
            .sortedByDescending { it.totalForegroundMs }
            .toList()
    }

    private fun labelFor(pm: PackageManager, pkg: String): String? {
        return runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    /**
     * UsageStats.appLaunchCount is API 28+ public but unreliable
     * on Pixel (often zero). Fall back to estimating from total
     * foreground time at a rough 60s-per-launch heuristic so the
     * persona builder can still distinguish compulsive checkers
     * (many short visits) from deep-focus users.
     */
    private fun ofUsageCount(stat: UsageStats): Int {
        return runCatching {
            val direct = stat.javaClass.getMethod("getAppLaunchCount").invoke(stat) as? Int ?: 0
            if (direct > 0) direct
            else (stat.totalTimeInForeground / 60_000L).toInt().coerceAtLeast(1)
        }.getOrDefault(1)
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 24L * 60 * 60 * 1000   // 24h
        const val MIN_FG_MS = 30_000L                         // drop <30s
    }
}
