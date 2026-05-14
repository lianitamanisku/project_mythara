package com.mythara.agent

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mythara.growth.LearningJournal
import com.mythara.secret.observe.vault.LearningDao
import com.mythara.secret.observe.vault.LearningEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nightly maintenance pass over the local learning vault — the
 * organisation half of M8.3 SelfOrganizer. Pairs with the live recall
 * path in [SemanticRecall].
 *
 * The live write path in [com.mythara.secret.observe.vault.LearningVault.add]
 * now deduplicates new inserts against existing rows by `sha`, so
 * fresh dupes don't accumulate. But the vault carries pre-fix history
 * from before that dedup landed, and edge cases (clock skew, races on
 * the upsert transaction) can still produce duplicates. This worker
 * sweeps once a day to consolidate them.
 *
 * Today's pass (v1):
 *   - Group all records by `sha`. For each group with >1 row:
 *       * keep the oldest row (lowest ULID)
 *       * sum the `seen` counters across the group
 *       * take max(lastSeenMs) for the keeper
 *       * upgrade the keeper's embedding/embModel from any duplicate
 *         that has them (in case the first observation lacked an
 *         embedding because USE-Lite wasn't ready yet)
 *       * take max(conf) across the group
 *       * delete the other rows
 *   - Journal a one-line summary so the user can see in the growth
 *     log that the worker ran.
 *
 * Future passes (M8.3 part 3+):
 *   - Cluster working-tier records by embedding cosine, summarise
 *     each dense cluster into one episodic-tier record via Gemma
 *   - Demote stale semantic facts (low seen + last-seen > 1y) by
 *     deletion or archive-tier promotion
 *   - Compact Room DB
 */
@HiltWorker
class SelfOrganizerWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val dao: LearningDao,
    private val journal: LearningJournal,
    private val episodic: EpisodicPromoter,
    private val decayer: StaleDecayer,
    private val daySummaryBuilder: com.mythara.lifeline.DaySummaryBuilder,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Step 1: dedup by sha. Always runs — cheap, idempotent.
        val dedupReport = runCatching { dedupBySha() }.getOrElse {
            Log.w(TAG, "self-organiser dedup threw ${it.message}", it)
            return Result.retry()
        }
        Log.d(TAG, "dedup: groups=${dedupReport.groupsConsolidated} rowsDeleted=${dedupReport.rowsDeleted}")

        // Step 2: episodic promotion. No-ops when Gemma isn't enabled.
        // Wrapped in its own runCatching — a Gemma summarisation
        // failure shouldn't fail the whole worker (dedup already
        // succeeded), so we log + carry on.
        val episodicReport = runCatching { episodic.promote() }.getOrElse { e ->
            Log.w(TAG, "episodic promotion threw ${e.message}", e)
            EpisodicPromoter.Report(0, 0, 0, "threw: ${e.message}")
        }
        Log.d(
            TAG,
            "episodic: seen=${episodicReport.workingSeen} clusters=${episodicReport.clustersFound} " +
                "created=${episodicReport.episodicCreated} skip=${episodicReport.skippedReason ?: "-"}",
        )

        // Step 3: stale decay. Halves confidence on year-old semantic
        // facts that were never reinforced; deletes ones that have
        // decayed past the floor. Cheap (single DAO scan) and
        // tolerant of failures — log + carry on like episodic.
        val decayReport = runCatching { decayer.decay() }.getOrElse { e ->
            Log.w(TAG, "stale decay threw ${e.message}", e)
            StaleDecayer.Report(0, 0, 0)
        }
        Log.d(
            TAG,
            "decay: scanned=${decayReport.candidatesScanned} decayed=${decayReport.confidenceDecayed} " +
                "deleted=${decayReport.recordsDeleted}",
        )

        // Step 4: day-summary cards — digest the last few completed days
        // into the timeline's life-log cards (interactions + memories +
        // learnings + photos). Self-gates per day, so this is cheap.
        val daySummaries = runCatching { daySummaryBuilder.buildRecent(daysBack = 4) }.getOrElse { e ->
            Log.w(TAG, "day-summary build threw ${e.message}", e)
            0
        }
        Log.d(TAG, "day-summaries: built=$daySummaries")

        // Journal: combined summary so the growth log shows what happened.
        val notes = buildList {
            if (dedupReport.groupsConsolidated > 0) {
                add("dedup ${dedupReport.groupsConsolidated} group(s)/${dedupReport.rowsDeleted} row(s)")
            }
            if (episodicReport.episodicCreated > 0) {
                add("promoted ${episodicReport.episodicCreated} episodic record(s) from ${episodicReport.clustersFound} cluster(s)")
            }
            if (decayReport.confidenceDecayed > 0 || decayReport.recordsDeleted > 0) {
                add("decayed ${decayReport.confidenceDecayed} stale fact(s), deleted ${decayReport.recordsDeleted}")
            }
            if (daySummaries > 0) {
                add("built $daySummaries day-summary card(s)")
            }
        }
        if (notes.isNotEmpty()) {
            journal.append(
                LearningJournal.Entry(
                    tsMillis = System.currentTimeMillis(),
                    kind = "self-organiser",
                    note = notes.joinToString("; "),
                ),
            )
        }
        return Result.success()
    }

    /** Result envelope so callers can log + journal in one place. */
    data class DedupReport(val groupsConsolidated: Int, val rowsDeleted: Int)

    /**
     * Iterates the whole vault once, groups by `sha`, and merges any
     * group containing >1 row. Linear time + memory in vault size;
     * at 10k records this is single-digit-ms work. We do not stream
     * because the merge logic needs all rows of a group at once and
     * the vault is small enough that memory pressure isn't a concern.
     */
    private suspend fun dedupBySha(): DedupReport {
        val all: List<LearningEntity> = dao.listAll()
        val groups = all.groupBy { it.sha }.filter { it.value.size > 1 }
        if (groups.isEmpty()) return DedupReport(0, 0)

        var rowsDeleted = 0
        for ((_, dupes) in groups) {
            val keeper = dupes.minByOrNull { it.id } ?: continue
            val totalSeen = dupes.sumOf { it.seen }
            val newestLastSeen = dupes.maxOf { it.lastSeenMs }
            val bestEmbedded = dupes.firstOrNull { it.embedding != null && it.id != keeper.id }
            val maxConf = dupes.maxOf { it.conf }
            // Merge keeper carries the union of properties. Flip
            // `synced` back to false so the next MemorySync.runSync
            // re-uploads the consolidated row — otherwise the GitHub
            // copy would still show the pre-merge `seen`/`conf` and
            // the merger's "more reinforced wins" rule wouldn't have
            // anything fresher to pick. This is what ensures
            // compaction never drops memory: the strongest version
            // wins both locally AND on backup.
            dao.update(
                keeper.copy(
                    seen = totalSeen,
                    lastSeenMs = newestLastSeen,
                    embedding = keeper.embedding ?: bestEmbedded?.embedding,
                    embModel = keeper.embModel ?: bestEmbedded?.embModel,
                    conf = maxConf,
                    synced = false,
                    syncedAtMs = null,
                ),
            )
            for (dup in dupes) {
                if (dup.id == keeper.id) continue
                dao.deleteById(dup.id)
                rowsDeleted++
            }
        }
        return DedupReport(groupsConsolidated = groups.size, rowsDeleted = rowsDeleted)
    }

    companion object {
        private const val TAG = "Mythara/SelfOrg"
        const val UNIQUE_PERIODIC = "mythara_self_organiser_periodic"
    }
}

@Singleton
class SelfOrganizerScheduler @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    /**
     * Daily cadence with battery-friendly constraints. Idempotent: safe
     * to call on every app cold-start; WorkManager's `UPDATE` policy
     * leaves the existing schedule in place when one already exists.
     */
    fun start() {
        val req = PeriodicWorkRequestBuilder<SelfOrganizerWorker>(Duration.ofHours(24))
            .setConstraints(
                Constraints.Builder()
                    // Doesn't need charging — the work is small (vault
                    // scan + a handful of updates), but battery-not-low
                    // is a good citizen.
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            // Stagger after MemorySyncWorker so we don't slam the
            // first-night cadence with two large workers back-to-back.
            .setInitialDelay(Duration.ofHours(2))
            .build()
        wm.enqueueUniquePeriodicWork(
            SelfOrganizerWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    fun pause() {
        wm.cancelUniqueWork(SelfOrganizerWorker.UNIQUE_PERIODIC)
    }
}
