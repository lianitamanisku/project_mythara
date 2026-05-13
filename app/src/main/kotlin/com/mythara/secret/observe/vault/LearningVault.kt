package com.mythara.secret.observe.vault

import com.mythara.memory.MemoryRecord
import com.mythara.memory.Tier
import com.mythara.memory.SecretScrubber
import com.mythara.secret.observe.embed.LocalEmbedder
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level facade over [LearningDao]. Owns:
 *  - the canonical (de)serialisation of `facets` (DB column is a JSON
 *    string; callers work with `List<String>` here)
 *  - the secret-scrubber pass applied to every `content` field on write
 *  - the embedding-bytes ↔ FloatArray conversion via [LocalEmbedder]
 *  - the mapping to the wire-format [MemoryRecord] that syncs to GitHub
 *
 * Today this is populated by [com.mythara.secret.observe.ObserveSession]
 * (every Vosk transcript becomes a working-tier record) and the
 * heuristic [com.mythara.secret.observe.extract.LearningExtractor]
 * (lifts a coarse set of semantic facts from each transcript). M8.2.1
 * will additionally fill the vault from MediaPipe Gemma extraction.
 */
@Singleton
class LearningVault @Inject constructor(
    private val dao: LearningDao,
) {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private val facetSerializer = ListSerializer(String.serializer())

    suspend fun add(
        content: String,
        tier: Tier,
        src: String,
        facets: List<String> = emptyList(),
        embedding: FloatArray? = null,
        embModel: String? = null,
        conf: Double = 1.0,
        ref: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val scrubbed = SecretScrubber.scrub(content.trim())
        if (scrubbed.isBlank()) return false
        val sha = sha24(scrubbed)
        val entity = LearningEntity(
            id = newId(now),
            tsMillis = now,
            tier = tier.code,
            src = src,
            content = scrubbed,
            sha = sha,
            conf = conf,
            facets = json.encodeToString(facetSerializer, facets),
            embedding = embedding?.let { LocalEmbedder.encode(it) },
            embModel = embModel,
            ref = ref,
            seen = 1,
            lastSeenMs = now,
            synced = false,
        )
        return dao.upsert(entity)
    }

    fun observeRecent(limit: Int): Flow<List<LearningEntity>> = dao.observeRecent(limit)
    fun observeCount(): Flow<Int> = dao.observeCount()
    suspend fun listRecent(limit: Int = 50, offset: Int = 0): List<LearningEntity> = dao.listRecent(limit, offset)
    suspend fun countByTier(tier: Tier): Int = dao.countByTier(tier.code)
    suspend fun listByTier(tier: Tier, limit: Int = 100): List<LearningEntity> = dao.listByTier(tier.code, limit)
    suspend fun unsyncedRecords(): List<LearningEntity> = dao.listUnsynced()

    suspend fun markSynced(id: String, now: Long = System.currentTimeMillis()) {
        dao.markSynced(id, now)
    }

    suspend fun clear() = dao.clear()

    fun decodeFacets(entity: LearningEntity): List<String> = runCatching {
        json.decodeFromString(facetSerializer, entity.facets)
    }.getOrDefault(emptyList())

    /**
     * Translate a [LearningEntity] into the GitHub-bound wire format. The
     * embedding rides along as base64 of the LE-float32 bytes.
     *
     * @param dev DeviceIdStore stamp written into the record's `dev` field
     *            so each line in the synced repo carries authorship from
     *            this install. Null leaves the field absent for back-compat
     *            with records written before M8.2.3.
     */
    fun toMemoryRecord(entity: LearningEntity, dev: String? = null): MemoryRecord {
        val facets = decodeFacets(entity)
        val emb = entity.embedding?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        return MemoryRecord(
            id = entity.id,
            t = entity.tsMillis,
            tier = entity.tier,
            src = entity.src,
            conf = entity.conf,
            facets = facets,
            content = entity.content,
            sha = entity.sha,
            ref = entity.ref,
            seen = entity.seen,
            dev = dev,
        ).let {
            // MemoryRecord doesn't currently carry `emb` — store it as a
            // facet for now ("emb:<base64>" is too long; the actual emb
            // round-trips through the local vault, not GitHub). Embeddings
            // will get a proper field on MemoryRecord once we add an
            // `emb_bytes` JSONL column in M8.2.1 alongside the Gemma
            // extractor's structured output.
            //
            // For today: the sync includes the textual content + facets;
            // the vector stays in the local DB.
            it
        }
    }

    companion object {
        fun newId(now: Long = System.currentTimeMillis()): String {
            val ts = now.toString(36).padStart(9, '0')
            val rnd = UUID.randomUUID().toString().replace("-", "").take(12)
            return "$ts-$rnd"
        }

        fun sha24(text: String): String {
            val md = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return md.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}
