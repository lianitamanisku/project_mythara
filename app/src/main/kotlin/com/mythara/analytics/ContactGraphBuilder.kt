package com.mythara.analytics

import android.util.Log
import com.mythara.secret.observe.extract.gemma.GemmaExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Derives a relationship graph for the Insights screen.
 *
 * The graph is EGO-CENTRIC: there's a single [ME_KEY] node at its
 * centre and every contact hangs off it — because that's exactly how
 * it is, every contact IS connected to the user. Four kinds of edge:
 *
 *  - [EdgeKind.RELATES]      — ME → each contact. The label is the
 *                              relationship TYPE with the user
 *                              ("friend", "family", "colleague",
 *                              "transactional", …). A cheap heuristic
 *                              label is shown instantly; the Gemma
 *                              pass refines it from the user's notes.
 *  - [EdgeKind.KNOWS]        — contacts who actually know each other,
 *                              inferred by the local Gemma model from
 *                              the user's notes + relationship
 *                              summaries (one batched call).
 *  - [EdgeKind.SIMILAR]      — similar personalities, from Big Five
 *                              vector closeness. Pure arithmetic.
 *  - [EdgeKind.SHARED_TOPIC] — overlapping conversation topics. Pure
 *                              set arithmetic.
 *
 * [buildCheap] returns the ME node + every contact + RELATES edges
 * (heuristic labels) + the two arithmetic edge kinds, instantly.
 * [buildFull] additionally runs Gemma to refine the RELATES labels and
 * add the KNOWS edges — slower (model load + inference) so the UI
 * shows the cheap graph first and folds the rest in when it arrives.
 */
@Singleton
class ContactGraphBuilder @Inject constructor(
    private val repo: ContactProfileRepository,
    private val gemma: GemmaExtractor,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    enum class EdgeKind { RELATES, KNOWS, SIMILAR, SHARED_TOPIC }

    data class Node(
        val key: String,
        val name: String,
        val isFavorite: Boolean,
        val messageCount: Int,
        val topics: List<String>,
        val hasNotes: Boolean,
        val summary: String?,
        val photoUri: String?,
    )

    data class Edge(
        val fromKey: String,
        val toKey: String,
        val kind: EdgeKind,
        /** 0..1 — drives line opacity + thickness. */
        val weight: Float,
        /** Short human label, e.g. "siblings", "similar personality",
         *  "shares: hiking, python". */
        val label: String,
    )

    data class Graph(
        val nodes: List<Node>,
        val edges: List<Edge>,
        val gemmaUsed: Boolean,
    )

    /** The ME node + every contact + heuristic RELATES edges + the two
     *  arithmetic edge kinds. Instant — no model. */
    suspend fun buildCheap(): Graph = withContext(Dispatchers.IO) {
        val profiles = runCatching { repo.dao.listAll() }.getOrDefault(emptyList())
        val nodes = listOf(meNode()) + profiles.map { it.toNode() }
        val edges = relatesEdges(profiles, refined = emptyMap()) +
            similarEdges(profiles) + sharedTopicEdges(profiles)
        Graph(nodes, edges, gemmaUsed = false)
    }

    /** Cheap graph + Gemma-refined RELATES labels + the KNOWS edges
     *  (two batched calls). */
    suspend fun buildFull(): Graph = withContext(Dispatchers.IO) {
        val profiles = runCatching { repo.dao.listAll() }.getOrDefault(emptyList())
        val nodes = listOf(meNode()) + profiles.map { it.toNode() }
        val arithmetic = similarEdges(profiles) + sharedTopicEdges(profiles)
        val refined = runCatching { relatesLabels(profiles) }.getOrElse {
            Log.w(TAG, "relates-label inference failed: ${it.message}")
            emptyMap()
        }
        val knows = runCatching { knowsEdges(profiles) }.getOrElse {
            Log.w(TAG, "knows-edge inference failed: ${it.message}")
            emptyList()
        }
        Graph(
            nodes,
            relatesEdges(profiles, refined) + arithmetic + knows,
            gemmaUsed = gemma.isReady(),
        )
    }

    // ----------------------------------------------------------- nodes

    private fun ContactProfileRow.toNode() = Node(
        key = nameKey,
        name = displayName,
        isFavorite = isFavorite,
        messageCount = messageCount,
        topics = parseList(topTopicsJson),
        hasNotes = !userNotes.isNullOrBlank(),
        summary = relationshipSummary,
        photoUri = photoUri,
    )

    /** The synthetic "you" node at the centre of the ego graph. Sized
     *  big (max message count) so it always reads as the hub. */
    private fun meNode() = Node(
        key = ME_KEY,
        name = "Me",
        isFavorite = true,
        messageCount = 999,
        topics = emptyList(),
        hasNotes = false,
        summary = "You — every contact connects back here.",
        photoUri = null,
    )

    // ------------------------------------------------------- ego edges

    /**
     * One RELATES edge ME → every contact. [refined] (when non-empty)
     * supplies the Gemma-classified relationship type; contacts without
     * a refined label fall back to the cheap heuristic.
     */
    private fun relatesEdges(
        profiles: List<ContactProfileRow>,
        refined: Map<String, String>,
    ): List<Edge> = profiles.map { p ->
        Edge(
            fromKey = ME_KEY,
            toKey = p.nameKey,
            kind = EdgeKind.RELATES,
            weight = relatesWeight(p),
            label = refined[p.nameKey] ?: cheapRelationLabel(p),
        )
    }

    /** Instant heuristic relationship type — refined later by Gemma. */
    private fun cheapRelationLabel(p: ContactProfileRow): String = when {
        !ContactClassifier.isPersonal(p.displayName, p.phone) -> "transactional"
        p.isFavorite -> "close contact"
        !p.userNotes.isNullOrBlank() -> "known"
        p.messageCount > 20 -> "regular contact"
        else -> "contact"
    }

    /** Edge weight → line thickness/opacity. Favorites + people the
     *  user wrote notes about read as the strongest ties. */
    private fun relatesWeight(p: ContactProfileRow): Float = when {
        p.isFavorite -> 1f
        !p.userNotes.isNullOrBlank() -> 0.8f
        p.messageCount > 20 -> 0.6f
        else -> 0.4f
    }

    /**
     * One batched Gemma call: hand it every contact that has notes or a
     * relationship summary and ask for a short relationship-type label
     * for how each relates TO THE USER. The notes are the primary
     * signal. Returns nameKey → label; contacts not covered keep the
     * cheap heuristic label.
     */
    private suspend fun relatesLabels(profiles: List<ContactProfileRow>): Map<String, String> {
        if (!gemma.isReady()) return emptyMap()
        val candidates = profiles
            .filter { !it.userNotes.isNullOrBlank() || !it.relationshipSummary.isNullOrBlank() }
            .sortedWith(
                compareByDescending<ContactProfileRow> { !it.userNotes.isNullOrBlank() }
                    .thenByDescending { it.isFavorite }
                    .thenByDescending { it.messageCount },
            )
            .take(MAX_GEMMA_CANDIDATES)
        if (candidates.isEmpty()) return emptyMap()

        val keyByName = candidates.associate { it.displayName.trim().lowercase() to it.nameKey }
        val prompt = buildString {
            append(
                "Below are people the USER knows. For each, the user's own NOTES and a relationship " +
                    "SUMMARY are given. Classify how each person relates TO THE USER with ONE short " +
                    "lowercase label — e.g. friend, close friend, family, partner, colleague, manager, " +
                    "client, mentor, acquaintance, transactional. Base it on the NOTES first (the " +
                    "user's authoritative statements), then the summary.\n\n",
            )
            candidates.forEachIndexed { i, c ->
                append("${i + 1}. ${c.displayName}\n")
                c.userNotes?.takeIf { it.isNotBlank() }?.let {
                    append("   notes: ").append(it.trim().take(240)).append('\n')
                }
                c.relationshipSummary?.takeIf { it.isNotBlank() }?.let {
                    append("   summary: ").append(it.trim().take(240)).append('\n')
                }
            }
            append(
                "\nReturn ONLY a JSON array of objects, each: " +
                    "{\"name\": \"<name>\", \"relationship\": \"<short label>\"}. " +
                    "Use the names EXACTLY as written above. No prose, no markdown.\n\n" +
                    "Return the JSON array now.",
            )
        }

        val raw = runCatching { gemma.runRaw(prompt, maxLen = KNOWS_MAX_LEN) }.getOrNull()
            ?: return emptyMap()
        val arrText = extractFirstJsonArray(raw) ?: return emptyMap()
        val arr = runCatching { json.parseToJsonElement(arrText) as? JsonArray }.getOrNull()
            ?: return emptyMap()

        val out = HashMap<String, String>()
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val name = (o["name"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: continue
            val rel = (o["relationship"] as? JsonPrimitive)?.content?.trim()
                ?.takeIf { it.isNotBlank() } ?: continue
            val key = keyByName[name] ?: continue
            out[key] = rel.take(28)
        }
        Log.d(TAG, "relates-labels: ${out.size} from ${candidates.size} candidates")
        return out
    }

    // ------------------------------------------------------- arithmetic

    /** Big Five vector closeness. Edge when the average per-trait
     *  difference is small enough that the two read alike. */
    private fun similarEdges(profiles: List<ContactProfileRow>): List<Edge> {
        val withBig5 = profiles.filter { it.openness != null }
        val out = ArrayList<Edge>()
        for (i in withBig5.indices) {
            for (j in i + 1 until withBig5.size) {
                val a = withBig5[i]
                val b = withBig5[j]
                val diffs = listOf(
                    abs((a.openness ?: 0.5) - (b.openness ?: 0.5)),
                    abs((a.conscientiousness ?: 0.5) - (b.conscientiousness ?: 0.5)),
                    abs((a.extraversion ?: 0.5) - (b.extraversion ?: 0.5)),
                    abs((a.agreeableness ?: 0.5) - (b.agreeableness ?: 0.5)),
                    abs((a.neuroticism ?: 0.5) - (b.neuroticism ?: 0.5)),
                )
                val avgDiff = diffs.average()
                if (avgDiff <= SIMILAR_MAX_DIFF) {
                    out.add(
                        Edge(
                            fromKey = a.nameKey,
                            toKey = b.nameKey,
                            kind = EdgeKind.SIMILAR,
                            weight = (1.0 - avgDiff / SIMILAR_MAX_DIFF).toFloat().coerceIn(0.1f, 1f),
                            label = "similar personality",
                        ),
                    )
                }
            }
        }
        return out
    }

    /** Shared conversation topics — Jaccard over the topic sets. */
    private fun sharedTopicEdges(profiles: List<ContactProfileRow>): List<Edge> {
        val topicSets = profiles.associate { it.nameKey to parseList(it.topTopicsJson).map { t -> t.lowercase() }.toSet() }
        val keys = profiles.map { it.nameKey }
        val nameByKey = profiles.associate { it.nameKey to it.displayName }
        val out = ArrayList<Edge>()
        for (i in keys.indices) {
            for (j in i + 1 until keys.size) {
                val sa = topicSets[keys[i]] ?: emptySet()
                val sb = topicSets[keys[j]] ?: emptySet()
                if (sa.isEmpty() || sb.isEmpty()) continue
                val shared = sa intersect sb
                if (shared.size < MIN_SHARED_TOPICS) continue
                val jaccard = shared.size.toFloat() / (sa union sb).size.toFloat()
                out.add(
                    Edge(
                        fromKey = keys[i],
                        toKey = keys[j],
                        kind = EdgeKind.SHARED_TOPIC,
                        weight = jaccard.coerceIn(0.1f, 1f),
                        label = "shares: ${shared.take(3).joinToString(", ")}",
                    ),
                )
            }
        }
        // nameByKey kept for symmetry with knowsEdges; not used here.
        nameByKey.size
        return out
    }

    // ------------------------------------------------------------ gemma

    /**
     * One batched Gemma call: hand it every contact that has notes or a
     * relationship summary, ask which pairs actually know each other,
     * and how. The user's notes are the primary signal — "knows her
     * from college", "Alice's brother", "my manager at Stripe" — so the
     * graph's KNOWS edges are described THROUGH those notes.
     */
    private suspend fun knowsEdges(profiles: List<ContactProfileRow>): List<Edge> {
        if (!gemma.isReady()) return emptyList()
        val candidates = profiles
            .filter { !it.userNotes.isNullOrBlank() || !it.relationshipSummary.isNullOrBlank() }
            .sortedWith(
                compareByDescending<ContactProfileRow> { !it.userNotes.isNullOrBlank() }
                    .thenByDescending { it.isFavorite }
                    .thenByDescending { it.messageCount },
            )
            .take(MAX_GEMMA_CANDIDATES)
        if (candidates.size < 2) return emptyList()

        val keyByName = candidates.associate { it.displayName.trim().lowercase() to it.nameKey }
        val prompt = buildString {
            append(
                "Below is a list of people the user knows. For each, the user's own NOTES " +
                    "and a relationship SUMMARY are given. Identify which pairs of these people " +
                    "KNOW EACH OTHER or are otherwise CONNECTED — family, partners, colleagues, " +
                    "mutual friends, or people clearly mentioned together. Base it on the NOTES " +
                    "first (they are the user's authoritative statements), then the summaries.\n\n",
            )
            candidates.forEachIndexed { i, c ->
                append("${i + 1}. ${c.displayName}\n")
                c.userNotes?.takeIf { it.isNotBlank() }?.let {
                    append("   notes: ").append(it.trim().take(280)).append('\n')
                }
                c.relationshipSummary?.takeIf { it.isNotBlank() }?.let {
                    append("   summary: ").append(it.trim().take(280)).append('\n')
                }
            }
            append(
                "\nReturn ONLY a JSON array of objects, each: " +
                    "{\"a\": \"<name>\", \"b\": \"<name>\", \"relationship\": \"<short label, e.g. siblings, colleagues, friends>\"}. " +
                    "Use the names EXACTLY as written above. Only include pairs clearly supported by the notes or summaries. " +
                    "If none, return []. No prose, no markdown.\n\nReturn the JSON array now.",
            )
        }

        val raw = runCatching { gemma.runRaw(prompt, maxLen = KNOWS_MAX_LEN) }.getOrNull()
            ?: return emptyList()
        val arrText = extractFirstJsonArray(raw) ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(arrText) as? JsonArray }.getOrNull()
            ?: return emptyList()

        val out = ArrayList<Edge>()
        val seen = HashSet<String>()
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val aName = (o["a"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: continue
            val bName = (o["b"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: continue
            val rel = (o["relationship"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
                ?: "connected"
            val aKey = keyByName[aName] ?: continue
            val bKey = keyByName[bName] ?: continue
            if (aKey == bKey) continue
            val dedup = listOf(aKey, bKey).sorted().joinToString("|")
            if (!seen.add(dedup)) continue
            out.add(
                Edge(
                    fromKey = aKey,
                    toKey = bKey,
                    kind = EdgeKind.KNOWS,
                    weight = 0.9f,
                    label = rel.take(40),
                ),
            )
        }
        Log.d(TAG, "knows-edges: ${out.size} from ${candidates.size} candidates")
        return out
    }

    // ----------------------------------------------------------- helpers

    private fun parseList(s: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), s) }
            .getOrDefault(emptyList())

    private fun extractFirstJsonArray(text: String): String? {
        val start = text.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '[') depth++
            else if (c == ']') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    companion object {
        private const val TAG = "Mythara/ContactGraph"

        /** Key of the synthetic "you" node at the centre of the ego graph. */
        const val ME_KEY = "__me__"

        private const val SIMILAR_MAX_DIFF = 0.14
        private const val MIN_SHARED_TOPICS = 3
        private const val MAX_GEMMA_CANDIDATES = 40
        private const val KNOWS_MAX_LEN = 900

        /** Cosine, kept for callers that want it; unused internally. */
        fun cosine(a: DoubleArray, b: DoubleArray): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom == 0.0) 0.0 else dot / denom
        }
    }
}
