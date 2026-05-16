package com.mythara.memory.graph

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * On-device temporal knowledge graph for the Mythara agent's
 * memory of the user, their relationships, and the events they've
 * lived through.
 *
 * Inspired by getzep/graphiti — a server-side temporal knowledge
 * graph framework — but adapted for phone-class storage:
 *
 *   - **Bi-temporal model**. Every fact carries TWO timestamps:
 *     the `validAtMs` when the fact became true in the real world
 *     (e.g. "Bob became CEO on 2024-03-15") and the `createdAtMs`
 *     when Mythara learned it (the user told the agent on
 *     2024-04-02). Optional `validUntilMs` lets us model facts
 *     that have ended ("Bob was CEO until 2025-09-10"). This is
 *     the exact shape Graphiti uses; the difference is we store
 *     it in SQLite via Room rather than Neo4j.
 *
 *   - **Lossless**. Facts are NEVER overwritten. If a new fact
 *     contradicts an old one (Bob's title changed) we add a NEW
 *     edge with a fresh `validAtMs`; the OLD edge gets a
 *     `validUntilMs` patched in but its content + provenance
 *     stay intact. Queries pick the edge whose validity window
 *     contains the query timestamp.
 *
 *   - **Lightweight**. Two tables (entities + edges), no graph DB,
 *     no batch recomputation. All queries are SQL with composite
 *     indexes — sub-millisecond on phone-class hardware up to
 *     ~100k edges. The schema is small enough that a per-row
 *     JSON encoding survives MemorySyncWorker's GitHub round-trip
 *     cleanly (no special graph-format on the wire).
 *
 *   - **Sync-friendly**. Both tables are append-only from the
 *     agent's perspective (the only "update" is patching
 *     `validUntilMs` on an existing edge, which is idempotent).
 *     The existing memory-sync worker can ship rows by
 *     `createdAtMs` since-watermark — same pattern as the
 *     learnings table.
 *
 * Future hooks (not in this scaffold):
 *   - LLM-driven entity + edge extraction from chat / Observe
 *     transcripts (the agent "reads" a conversation, emits
 *     suggested entities + edges, the repo merges with dedup
 *     via SHA-of-canonicalised-text).
 *   - Graph traversal queries (BFS k-hop from an entity, path
 *     between two entities) for SemanticRecall to surface
 *     "people connected to this person" alongside the existing
 *     keyword + vector recall paths.
 *   - Periodic compaction: edges with `validUntilMs` older than
 *     some retention window can be archived to a "history"
 *     table to keep the working set small.
 */
@Entity(
    tableName = "graph_entities",
    indices = [
        Index(value = ["kind"]),
        Index(value = ["nameKey"], unique = true),
    ],
)
data class GraphEntity(
    /** Stable canonical id — sha-shortened from kind + nameKey so
     *  re-deriving the entity from a mention always yields the
     *  same id. Prevents duplicate entities across episodes. */
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** Bucketed kind so queries can filter (e.g. "all people").
     *  Suggested values: person, place, project, org, concept,
     *  thing. Free-form string to allow growth without migration. */
    @ColumnInfo(name = "kind") val kind: String,
    /** User-facing display name. Mutable via re-insert with the
     *  same id (Room OnConflictStrategy.REPLACE). */
    @ColumnInfo(name = "name") val name: String,
    /** Lower-cased trimmed canonical form of [name] used for
     *  uniqueness + lookup. ("Mom" / "MOM" / " mom " all collapse
     *  to "mom".) */
    @ColumnInfo(name = "nameKey") val nameKey: String,
    @ColumnInfo(name = "createdAtMs") val createdAtMs: Long,
    /** Optional opaque JSON for kind-specific attributes
     *  (e.g. person → {"role":"…","joined":…}). Not indexed; the
     *  caller deserializes it. */
    @ColumnInfo(name = "attrsJson") val attrsJson: String? = null,
    /** Confidence score (0..1) of this entity's existence. Useful
     *  when the LLM extractor is uncertain. */
    @ColumnInfo(name = "conf") val conf: Float = 1f,
    /** Sync watermark — true once shipped to the GitHub backup. */
    @ColumnInfo(name = "synced") val synced: Boolean = false,
)

/**
 * A single fact connecting two entities with a predicate. Bi-
 * temporal: `createdAtMs` is when Mythara learned the fact;
 * `validAtMs` is when the fact was/is true in the world.
 *
 * The fact STAYS valid until either (a) [validUntilMs] is set
 * (because a later contradicting fact landed) or (b) the agent
 * explicitly invalidates it. Until then, queries with a
 * timestamp anywhere in [validAtMs, ∞) return the row.
 */
@Entity(
    tableName = "graph_edges",
    indices = [
        Index(value = ["subjectId", "predicate"]),
        Index(value = ["objectId"]),
        Index(value = ["createdAtMs"]),
    ],
)
data class GraphEdge(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "subjectId") val subjectId: String,
    /** Lowercase, snake-cased relationship label. Suggested values:
     *  knows, works_at, lives_in, manages, friends_with, owns,
     *  attended, prefers, dislikes, parent_of. Free-form so
     *  evolving vocabulary doesn't need migrations. */
    @ColumnInfo(name = "predicate") val predicate: String,
    @ColumnInfo(name = "objectId") val objectId: String,
    /** When the fact became true in the world. */
    @ColumnInfo(name = "validAtMs") val validAtMs: Long,
    /** When the fact stopped being true. NULL = still valid. */
    @ColumnInfo(name = "validUntilMs") val validUntilMs: Long? = null,
    /** When Mythara learned the fact. */
    @ColumnInfo(name = "createdAtMs") val createdAtMs: Long,
    /** Source-text snippet that gave rise to this edge (the chat
     *  message, transcript line, etc.). Optional but invaluable
     *  for explainability. Kept short — the full episode lives
     *  in the LearningVault. */
    @ColumnInfo(name = "factText") val factText: String? = null,
    @ColumnInfo(name = "conf") val conf: Float = 1f,
    @ColumnInfo(name = "synced") val synced: Boolean = false,
)

@Dao
interface GraphMemoryDao {
    // ─── Entity CRUD ─────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntity(e: GraphEntity)

    @Query("SELECT * FROM graph_entities WHERE id = :id LIMIT 1")
    suspend fun entityById(id: String): GraphEntity?

    @Query("SELECT * FROM graph_entities WHERE nameKey = :nameKey LIMIT 1")
    suspend fun entityByNameKey(nameKey: String): GraphEntity?

    @Query("SELECT * FROM graph_entities WHERE kind = :kind ORDER BY name LIMIT :limit")
    suspend fun entitiesByKind(kind: String, limit: Int = 200): List<GraphEntity>

    @Query("SELECT * FROM graph_entities WHERE synced = 0 ORDER BY createdAtMs ASC LIMIT :limit")
    suspend fun unsyncedEntities(limit: Int = 200): List<GraphEntity>

    @Query("UPDATE graph_entities SET synced = 1 WHERE id = :id")
    suspend fun markEntitySynced(id: String)

    // ─── Edge CRUD ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdge(e: GraphEdge)

    @Query("UPDATE graph_edges SET validUntilMs = :tsMs WHERE id = :id")
    suspend fun closeEdge(id: String, tsMs: Long)

    @Query("SELECT * FROM graph_edges WHERE id = :id LIMIT 1")
    suspend fun edgeById(id: String): GraphEdge?

    @Query("SELECT * FROM graph_edges WHERE synced = 0 ORDER BY createdAtMs ASC LIMIT :limit")
    suspend fun unsyncedEdges(limit: Int = 500): List<GraphEdge>

    @Query("UPDATE graph_edges SET synced = 1 WHERE id = :id")
    suspend fun markEdgeSynced(id: String)

    // ─── Graph queries ───────────────────────────────────────────

    /** Every edge currently valid at [tsMs] where the entity is
     *  the subject. Use Long.MAX_VALUE for "valid right now". */
    @Query("""
        SELECT * FROM graph_edges
        WHERE subjectId = :entityId
          AND validAtMs <= :tsMs
          AND (validUntilMs IS NULL OR validUntilMs > :tsMs)
        ORDER BY validAtMs DESC
    """)
    suspend fun edgesFromEntity(entityId: String, tsMs: Long): List<GraphEdge>

    /** Every edge currently valid at [tsMs] where the entity is
     *  the object. Symmetric counterpart of [edgesFromEntity]. */
    @Query("""
        SELECT * FROM graph_edges
        WHERE objectId = :entityId
          AND validAtMs <= :tsMs
          AND (validUntilMs IS NULL OR validUntilMs > :tsMs)
        ORDER BY validAtMs DESC
    """)
    suspend fun edgesToEntity(entityId: String, tsMs: Long): List<GraphEdge>

    /** Edges of a specific predicate from an entity, valid at
     *  [tsMs]. Used for queries like "Bob's CURRENT employer"
     *  (predicate=works_at, tsMs=now). */
    @Query("""
        SELECT * FROM graph_edges
        WHERE subjectId = :entityId AND predicate = :predicate
          AND validAtMs <= :tsMs
          AND (validUntilMs IS NULL OR validUntilMs > :tsMs)
        ORDER BY validAtMs DESC
        LIMIT :limit
    """)
    suspend fun edgesByPredicate(
        entityId: String,
        predicate: String,
        tsMs: Long,
        limit: Int = 50,
    ): List<GraphEdge>

    /** Full history of edges for an entity (subject side), ALL
     *  validity windows. Used for the agent's "tell me about
     *  Bob's career" path. */
    @Query("""
        SELECT * FROM graph_edges
        WHERE subjectId = :entityId
        ORDER BY validAtMs DESC
        LIMIT :limit
    """)
    suspend fun edgeHistory(entityId: String, limit: Int = 200): List<GraphEdge>

    @Query("SELECT COUNT(*) FROM graph_entities")
    fun observeEntityCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM graph_edges")
    fun observeEdgeCount(): Flow<Int>

    /**
     * Atomic close-old-and-open-new pattern for the case where a
     * new fact contradicts existing facts of the same shape
     * (e.g. "Bob's new title is X" implies the old title row is
     * no longer current). Caller selects which existing edges to
     * close — the LLM pre-pass is responsible for that decision.
     */
    @Transaction
    suspend fun supersedeAndInsert(
        edgesToClose: List<String>,
        closedAtMs: Long,
        newEdge: GraphEdge,
    ) {
        edgesToClose.forEach { closeEdge(it, closedAtMs) }
        upsertEdge(newEdge)
    }
}
