package com.mythara.memory.graph

import com.mythara.memory.SecretScrubber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public surface for the on-device temporal knowledge graph.
 *
 * Higher layers (chat extractor, Observe-mode summariser, agent
 * tools) call:
 *   - [recordEntity]  to assert a person/place/concept exists
 *   - [recordEdge]    to assert a fact relating two entities
 *   - [supersedeEdges] when a new fact replaces an older one of
 *                     the same predicate (e.g. job change)
 *   - [neighbours]    to fetch what's currently true around an
 *                     entity (for SemanticRecall enrichment)
 *
 * Two responsibilities the DAO doesn't carry:
 *   1. **Canonicalisation** — entity ids are derived from `kind +
 *      nameKey` so two callers asserting the same person yield
 *      one row, not duplicates. Edge ids are derived from
 *      `subject + predicate + object + validAt` so re-assertions
 *      of the same fact are idempotent.
 *   2. **Scrubbing** — every text field passes through
 *      [SecretScrubber] so no raw OTPs / emails / card numbers
 *      survive into the graph.
 *
 * Returns Boolean from the record methods: true = a NEW row was
 * inserted; false = a duplicate / already-existing fact was
 * recognised. Useful for the caller to decide whether to log a
 * UI "added X new facts" line.
 */
@Singleton
class GraphMemoryRepository @Inject constructor(
    private val dao: GraphMemoryDao,
) {
    /**
     * Assert an entity exists. Returns the canonical entity id
     * (stable across re-assertions of the same name+kind).
     * When the entity already exists, the call is a no-op other
     * than potentially refreshing the conf score upward.
     */
    suspend fun recordEntity(
        name: String,
        kind: String,
        attrsJson: String? = null,
        conf: Float = 1f,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val cleanName = SecretScrubber.scrub(name).trim()
        if (cleanName.isBlank()) return ""
        val cleanKind = kind.trim().lowercase().ifBlank { "thing" }
        val nameKey = cleanName.lowercase().trim()
        val id = entityId(cleanKind, nameKey)

        val existing = dao.entityById(id)
        if (existing != null) {
            // Idempotent re-assertion. Bump confidence upward if
            // the new claim is stronger; keep the original
            // createdAt + name spelling.
            if (conf > existing.conf) {
                dao.upsertEntity(existing.copy(conf = conf, synced = false))
            }
            return id
        }
        dao.upsertEntity(
            GraphEntity(
                id = id,
                kind = cleanKind,
                name = cleanName,
                nameKey = nameKey,
                createdAtMs = nowMs,
                attrsJson = attrsJson,
                conf = conf,
            ),
        )
        return id
    }

    /**
     * Record a fact "subject `predicate` object, valid as of
     * [validAtMs]". When [validAtMs] is omitted, defaults to NOW.
     * The fact persists indefinitely until [supersedeEdges]
     * closes it.
     *
     * Idempotent: re-asserting the same triple at the same
     * validAt returns false without inserting.
     */
    suspend fun recordEdge(
        subjectId: String,
        predicate: String,
        objectId: String,
        validAtMs: Long? = null,
        factText: String? = null,
        conf: Float = 1f,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (subjectId.isBlank() || objectId.isBlank()) return false
        val cleanPred = predicate.trim().lowercase().ifBlank { return false }
        val validAt = validAtMs ?: nowMs
        val id = edgeId(subjectId, cleanPred, objectId, validAt)

        if (dao.edgeById(id) != null) return false
        val cleanText = factText?.let { SecretScrubber.scrub(it).trim().take(280) }
        dao.upsertEdge(
            GraphEdge(
                id = id,
                subjectId = subjectId,
                predicate = cleanPred,
                objectId = objectId,
                validAtMs = validAt,
                validUntilMs = null,
                createdAtMs = nowMs,
                factText = cleanText,
                conf = conf,
            ),
        )
        return true
    }

    /**
     * Atomic supersede — close the listed edges as of [closedAtMs]
     * and insert the new edge in one transaction. Use when a new
     * fact replaces older same-predicate facts (Bob's job changed:
     * close all his prior `works_at` edges, insert the new one).
     */
    suspend fun supersedeEdges(
        oldEdgeIds: List<String>,
        new: GraphEdge,
        closedAtMs: Long = System.currentTimeMillis(),
    ) {
        dao.supersedeAndInsert(oldEdgeIds, closedAtMs, new)
    }

    /**
     * Pull the currently-valid neighbourhood of an entity — both
     * outgoing and incoming edges. Returns at most [limit] edges
     * total, oldest-valid first when both directions overflow.
     *
     * Used by the agent's recall path to add "what's around this
     * person right now?" context to a query that mentions them.
     */
    suspend fun neighbours(
        entityId: String,
        atMs: Long = System.currentTimeMillis(),
        limit: Int = 40,
    ): List<GraphEdge> {
        val outgoing = dao.edgesFromEntity(entityId, atMs)
        val incoming = dao.edgesToEntity(entityId, atMs)
        return (outgoing + incoming).distinctBy { it.id }.take(limit)
    }

    /** Resolve a free-text mention to an entity id, or null when
     *  no entity with a matching nameKey exists. Caller can then
     *  decide whether to upsert a new entity. */
    suspend fun resolveByName(name: String, kind: String? = null): GraphEntity? {
        val nameKey = name.trim().lowercase()
        if (nameKey.isBlank()) return null
        val byKey = dao.entityByNameKey(nameKey) ?: return null
        if (kind != null && byKey.kind != kind.lowercase()) return null
        return byKey
    }

    /**
     * Rows the sync worker should ship. Combines unsynced entities
     * + edges so a single payload covers both. Caller marks them
     * synced via [markEntitiesSynced] / [markEdgesSynced] after
     * the upload returns success.
     */
    suspend fun syncPayload(limit: Int = 200): SyncPayload {
        val ents = dao.unsyncedEntities(limit)
        val edges = dao.unsyncedEdges(limit * 2)
        return SyncPayload(entities = ents, edges = edges)
    }

    suspend fun markEntitiesSynced(ids: List<String>) {
        ids.forEach { dao.markEntitySynced(it) }
    }

    suspend fun markEdgesSynced(ids: List<String>) {
        ids.forEach { dao.markEdgeSynced(it) }
    }

    data class SyncPayload(
        val entities: List<GraphEntity>,
        val edges: List<GraphEdge>,
    )

    // ─── id derivation ───────────────────────────────────────────

    private fun entityId(kind: String, nameKey: String): String =
        "ent_" + sha8("$kind|$nameKey")

    private fun edgeId(s: String, p: String, o: String, validAtMs: Long): String =
        "edg_" + sha8("$s|$p|$o|$validAtMs")

    private fun sha8(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
