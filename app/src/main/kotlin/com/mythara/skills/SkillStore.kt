package com.mythara.skills

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence layer for [Skill]. Skills are stored as
 * single-record JSON blobs in the LearningVault with
 * `kind:skill` + `skill-name:<name>` facets, semantic-tier so
 * they're durable + sync to GitHub via MemorySync.
 *
 * Save semantics:
 *  - `save` upserts by name. New skills land as fresh vault rows;
 *    re-saves write a new row with the same `skill-name:` facet
 *    (we don't update in-place because LearningEntity's sha-dedup
 *    is content-keyed and a refined skill has different content).
 *  - The most-recent version (highest `skillVersion`) wins on read.
 *  - Failures append in-place by re-saving the whole skill JSON.
 */
@Singleton
class SkillStore @Inject constructor(
    private val vault: LearningVault,
) {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
        ignoreUnknownKeys = true
        explicitNulls = false
        // Serialise sealed-class subclasses with their @SerialName
        // discriminator under the "action" key on SkillStep variants.
        classDiscriminator = "action"
    }

    /** Save (or update) a skill. Returns the version that landed. */
    suspend fun save(skill: Skill): Boolean {
        val asJson = encode(skill)
        val facets = buildList {
            add("kind:skill")
            add("skill-name:${skill.name}")
            add("skill-version:${skill.version}")
        }
        return runCatching {
            vault.add(
                content = asJson,
                tier = Tier.Semantic,
                src = "skill:agent-defined",
                facets = facets,
                conf = 1.0,
                now = System.currentTimeMillis(),
            )
        }.getOrElse {
            Log.w(TAG, "save threw: ${it.message}")
            false
        }
    }

    /** Read the most-recent version of a skill by name. */
    suspend fun get(name: String): Skill? {
        val rows = vault.listByTier(Tier.Semantic, limit = SKILL_SCAN_LIMIT)
            .filter { entity ->
                val facets = vault.decodeFacets(entity)
                facets.any { it == "kind:skill" } && facets.any { it == "skill-name:$name" }
            }
            .sortedByDescending { it.tsMillis }
        for (row in rows) {
            val parsed = runCatching { json.decodeFromString(Skill.serializer(), row.content) }
                .getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /** List every saved skill — for `list_skills`. */
    suspend fun list(): List<Skill> {
        val rows = vault.listByTier(Tier.Semantic, limit = SKILL_SCAN_LIMIT)
            .filter { entity ->
                vault.decodeFacets(entity).any { it == "kind:skill" }
            }
        // De-dupe by name, keep newest.
        val byName = mutableMapOf<String, Skill>()
        for (row in rows.sortedByDescending { it.tsMillis }) {
            val parsed = runCatching { json.decodeFromString(Skill.serializer(), row.content) }
                .getOrNull() ?: continue
            byName.putIfAbsent(parsed.name, parsed)
        }
        return byName.values.toList()
    }

    fun encode(skill: Skill): String =
        json.encodeToString(Skill.serializer(), skill)

    fun decode(jsonText: String): Skill? =
        runCatching { json.decodeFromString(Skill.serializer(), jsonText) }.getOrNull()

    companion object {
        private const val TAG = "Mythara/Skills"
        /** Bounded scan — at any reasonable scale (10s of skills), one page is fine. */
        private const val SKILL_SCAN_LIMIT = 200
    }
}
