package com.mythara.persona

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar
import java.util.Locale

/**
 * Reads aggregated [UsageStatsCollector] output and writes
 * persona-trait records into the [LearningVault] so the agent's
 * recall pulls them on every chat turn — the user's behaviour
 * becomes part of Lumi's context.
 *
 * What we extract per daily run:
 *  - **top apps** — natural-language sentence listing the
 *    three most-used apps with their totals.
 *  - **screen time bucket** — "moderate" / "heavy" / "light"
 *    based on total foreground time vs population averages.
 *  - **usage rhythm** — early-bird / day / evening / night-owl
 *    bucket based on which 6h window dominated.
 *  - **engagement pattern** — "compulsive checker" if many
 *    launches with short sessions; "deep focus" for fewer
 *    long sessions.
 *
 * Each record is semantic-tier (durable) with `kind:persona`
 * + `source:usage` facets so SemanticRecall's cosine query
 * finds them like any other fact about the user.
 *
 * Embedded via USE-Lite when available so the cosine path
 * actually surfaces these on relevant chat turns rather than
 * relying on facet-string match.
 */
@Singleton
class PersonaBuilder @Inject constructor(
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
    private val collector: UsageStatsCollector,
) {
    data class Report(
        val ok: Boolean,
        val recordsWritten: Int,
        val message: String? = null,
    )

    suspend fun buildDaily(): Report {
        val rows = runCatching { collector.collect() }.getOrElse {
            return Report(false, 0, "collector threw: ${it.message}")
        }
        if (rows.isEmpty()) return Report(false, 0, "no usage data — permission ungranted or empty buckets")

        val now = System.currentTimeMillis()
        var written = 0

        // 1) Top apps line.
        val top = rows.take(TOP_APPS).joinToString(", ") { row ->
            "${row.label} ${formatMs(row.totalForegroundMs)}"
        }
        addPersonaFact(
            content = "Today's top apps: $top.",
            traits = listOf("trait:top-apps"),
            now = now,
        )?.let { written++ }

        // 2) Total screen time + bucket.
        val totalMs = rows.sumOf { it.totalForegroundMs }
        val totalLabel = formatMs(totalMs)
        val screenBucket = when {
            totalMs >= HEAVY_MS -> "heavy"
            totalMs >= LIGHT_MS -> "moderate"
            else -> "light"
        }
        addPersonaFact(
            content = "Screen time today: $totalLabel (a $screenBucket day).",
            traits = listOf("trait:screen-time", "screen-time:$screenBucket"),
            now = now,
        )?.let { written++ }

        // 3) Usage rhythm — which 6h window dominated.
        val rhythm = computeRhythm(rows)
        if (rhythm != null) {
            addPersonaFact(
                content = "Usage rhythm: most active during the $rhythm window.",
                traits = listOf("trait:rhythm", "rhythm:$rhythm"),
                now = now,
            )?.let { written++ }
        }

        // 4) Engagement pattern — compulsive vs focus.
        val totalLaunches = rows.sumOf { it.launchCount }
        val pattern = engagementBucket(totalLaunches, totalMs)
        addPersonaFact(
            content = "Engagement style today: $pattern (${totalLaunches} launches across $totalLabel).",
            traits = listOf("trait:engagement", "engagement:$pattern"),
            now = now,
        )?.let { written++ }

        return Report(true, written)
    }

    private suspend fun addPersonaFact(
        content: String,
        traits: List<String>,
        now: Long,
    ): Boolean {
        val embedding = runCatching {
            if (embedder.isReady()) embedder.embed(content) else null
        }.getOrNull()
        val facets = buildList {
            add("kind:persona")
            add("source:usage")
            addAll(traits)
        }
        return runCatching {
            vault.add(
                content = content,
                tier = Tier.Semantic, // durable — persona is the user, not an event
                src = "persona:usage",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = 0.8,
                now = now,
            )
        }.getOrElse {
            Log.w(TAG, "vault.add (persona) failed: ${it.message}")
            false
        }
    }

    /**
     * Pick the dominant 6h window of `lastUsedMs` timestamps.
     * Buckets are local-time:
     *   early-morning  06:00–11:59
     *   afternoon      12:00–17:59
     *   evening        18:00–22:59
     *   night-owl      23:00–05:59
     */
    private fun computeRhythm(rows: List<UsageStatsCollector.AppUsage>): String? {
        val cal = Calendar.getInstance()
        val bucket = mutableMapOf("early-morning" to 0L, "afternoon" to 0L, "evening" to 0L, "night-owl" to 0L)
        for (r in rows) {
            cal.timeInMillis = r.lastUsedMs
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val label = when (hour) {
                in 6..11 -> "early-morning"
                in 12..17 -> "afternoon"
                in 18..22 -> "evening"
                else -> "night-owl"   // 23, 0..5
            }
            bucket[label] = (bucket[label] ?: 0L) + r.totalForegroundMs
        }
        val (top, totalMs) = bucket.maxBy { it.value }
        if (totalMs == 0L) return null
        return top
    }

    private fun engagementBucket(launches: Int, totalFgMs: Long): String {
        if (totalFgMs <= 0L) return "light"
        val msPerLaunch = totalFgMs / launches.coerceAtLeast(1)
        return when {
            launches >= COMPULSIVE_LAUNCH_FLOOR && msPerLaunch < SHORT_SESSION_MS -> "compulsive-checker"
            launches < FOCUS_LAUNCH_CEILING && msPerLaunch >= LONG_SESSION_MS -> "deep-focus"
            else -> "balanced"
        }
    }

    private fun formatMs(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    companion object {
        private const val TAG = "Mythara/Persona"
        private const val TOP_APPS = 3
        // 4h screen time / day is roughly average for engaged users.
        private const val HEAVY_MS = 5L * 60 * 60 * 1000
        private const val LIGHT_MS = 60L * 60 * 1000
        // Compulsive: 30+ launches / day with <60s average session.
        private const val COMPULSIVE_LAUNCH_FLOOR = 30
        private const val SHORT_SESSION_MS = 60_000L
        // Deep focus: <10 launches / day with >5min average.
        private const val FOCUS_LAUNCH_CEILING = 10
        private const val LONG_SESSION_MS = 5L * 60 * 1000
    }
}
