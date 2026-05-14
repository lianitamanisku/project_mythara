package com.mythara.lifeline

import android.util.Log
import com.mythara.audit.AuditEntry
import com.mythara.audit.AuditRepository
import com.mythara.memory.Tier
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the per-day "life-log" cards interleaved into the memory
 * timeline. For each completed calendar day it digests:
 *  - **interactions** — audit entries that targeted a named contact
 *    (calls / messages / lookups Lumi made on the user's behalf)
 *  - **meaningful memories + learnings** — episodic & semantic vault
 *    rows written that day (raw working-tier noise + persona/health
 *    snapshots are filtered out)
 *  - **photos** — lifeline entries captured that day
 *
 * When Gemma is loaded it writes a short natural-language paragraph of
 * the day; otherwise a clean heuristic line. One [DaySummaryEntity] per
 * day, persisted into [LifelineDb] so the timeline grid picks it up via
 * its Flow.
 *
 * Days are immutable once past, so a built day is never rebuilt (unless
 * forced) — making the nightly catch-up pass cheap.
 */
@Singleton
class DaySummaryBuilder @Inject constructor(
    private val lifelineRepo: LifelineRepository,
    private val auditRepo: AuditRepository,
    private val vault: LearningVault,
    private val gemma: GemmaExtractor,
) {

    /**
     * Build (or backfill) summaries for the [daysBack] most recent
     * COMPLETE days — today is skipped since it isn't over yet.
     * Returns the number of new summaries written.
     */
    suspend fun buildRecent(daysBack: Int = 4): Int = withContext(Dispatchers.IO) {
        var built = 0
        val today = LocalDate.now()
        for (i in 1..daysBack) {
            val day = today.minusDays(i.toLong())
            if (runCatching { buildFor(day) }.getOrDefault(false)) built++
        }
        if (built > 0) Log.d(TAG, "buildRecent wrote $built day-summary card(s)")
        built
    }

    /** Build the summary for a single day. No-ops when one already
     *  exists (days don't change) unless [force]. */
    suspend fun buildFor(day: LocalDate, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val dayEpoch = day.toEpochDay()
        if (!force && lifelineRepo.daySummaryDao.byDay(dayEpoch) != null) return@withContext false

        val zone = ZoneId.systemDefault()
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // Interactions — audited actions that touched a real contact.
        val audits = runCatching { auditRepo.dao.listBetween(dayStart, dayEnd) }.getOrDefault(emptyList())
        val interactions = audits.filter { !it.contactName.isNullOrBlank() }
        val contacts = interactions.mapNotNull { it.contactName?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Meaningful memories + learnings — episodic / semantic rows,
        // minus the routine snapshots that aren't "memories".
        val vaultRows = runCatching { vault.listBetween(dayStart, dayEnd, limit = 250) }
            .getOrDefault(emptyList())
        val meaningful = vaultRows.filter { e ->
            (e.tier == Tier.Episodic.code || e.tier == Tier.Semantic.code) && !isRoutine(e)
        }

        // Photos.
        val photos = runCatching { lifelineRepo.dao.listBetween(dayStart, dayEnd) }
            .getOrDefault(emptyList())

        // A genuinely empty day gets no card.
        if (interactions.isEmpty() && meaningful.isEmpty() && photos.isEmpty()) {
            return@withContext false
        }

        val summary = buildSummaryText(day, interactions, contacts, meaningful, photos)
        runCatching {
            lifelineRepo.daySummaryDao.upsert(
                DaySummaryEntity(
                    dayEpoch = dayEpoch,
                    dateMs = dayStart,
                    summary = summary,
                    interactionCount = interactions.size,
                    learningCount = meaningful.size,
                    photoCount = photos.size,
                    contacts = contacts.take(6).joinToString(", "),
                    builtMs = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "day-summary upsert failed for $day: ${it.message}") }
        Log.d(
            TAG,
            "day $day → ${interactions.size} interactions, ${meaningful.size} learnings, ${photos.size} photos",
        )
        true
    }

    /** Routine snapshot rows aren't "memories" — keep them out of the digest. */
    private fun isRoutine(e: LearningEntity): Boolean {
        val f = runCatching { vault.decodeFacets(e) }.getOrDefault(emptyList())
        return f.any {
            it == "kind:health-snapshot" || it == "kind:health-history" ||
                it == "kind:sensor-snapshot" || it == "kind:persona" ||
                it == "kind:self-profile" || it == "topic:hr-correlation" ||
                it == "kind:skill" || it == "kind:analysis-instruction"
        }
    }

    private suspend fun buildSummaryText(
        day: LocalDate,
        interactions: List<AuditEntry>,
        contacts: List<String>,
        meaningful: List<LearningEntity>,
        photos: List<LifelineEntity>,
    ): String {
        // Gemma narrative when the model's loaded and there's enough to
        // say; heuristic line otherwise.
        if (gemma.isReady() && (meaningful.isNotEmpty() || interactions.size >= 2 || photos.size >= 3)) {
            val material = buildString {
                if (contacts.isNotEmpty()) {
                    append("Connected with: ").append(contacts.joinToString(", ")).append(".\n")
                }
                interactions.take(12).forEach { a ->
                    val what = a.toolName?.takeIf { it.isNotBlank() } ?: a.kind
                    val detail = a.note?.takeIf { it.isNotBlank() }
                        ?: a.resultPreview?.takeIf { it.isNotBlank() }
                        ?: a.argsPreview
                    append("- ").append(what).append(": ")
                        .append(a.contactName.orEmpty()).append(' ')
                        .append(detail?.take(120).orEmpty()).append('\n')
                }
                meaningful.take(14).forEach { append("- ").append(it.content.take(160)).append('\n') }
                photos.mapNotNull { it.captionText?.takeIf { c -> c.isNotBlank() } }.take(5)
                    .forEach { append("- photo: ").append(it.take(120)).append('\n') }
                if (photos.isNotEmpty()) append("(${photos.size} photo(s) captured)\n")
            }.take(1800)
            val prompt =
                "Write ONE short paragraph (2-3 sentences) summarising the user's day from the notes below — " +
                    "the meaningful moments, who they connected with, and what they learned or did. " +
                    "Natural, warm past tense; refer to the user as 'you'. No preamble, no markdown, no lists.\n\n" +
                    "Notes for ${day.format(HUMAN_DATE)}:\n$material\n\nReturn the paragraph."
            val raw = runCatching { gemma.runRaw(prompt, maxLen = 360) }.getOrNull()?.trim()
            if (!raw.isNullOrBlank() && raw.length >= 20) return raw
        }

        // Heuristic fallback.
        return buildString {
            if (contacts.isNotEmpty()) {
                append("Connected with ").append(contacts.take(4).joinToString(", "))
                if (contacts.size > 4) append(" and ${contacts.size - 4} more")
                append(". ")
            }
            if (meaningful.isNotEmpty()) {
                append(meaningful.size).append(if (meaningful.size == 1) " memory" else " memories")
                append(" captured. ")
            }
            if (photos.isNotEmpty()) {
                append(photos.size).append(if (photos.size == 1) " photo" else " photos").append(". ")
            }
        }.trim().ifBlank { "A quiet day." }
    }

    companion object {
        private const val TAG = "Mythara/DaySummary"
        private val HUMAN_DATE = DateTimeFormatter.ofPattern("EEEE, MMM d")
    }
}
