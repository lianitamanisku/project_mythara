package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.memory.HeartbeatSyncer
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `remember` — durably store something the user explicitly asked Lumi
 * to remember.
 *
 * This is the tool to reach for whenever the user says "remember
 * that…", "don't forget…", "keep in mind…", "note that…". It:
 *
 *  1. Writes the fact to the SEMANTIC (durable) tier of the
 *     LearningVault, WITH an embedding — crucial, because SemanticRecall
 *     only scans vault rows that have an embedding. Without it the fact
 *     would be stored but never surface in answers.
 *  2. Fires an IMMEDIATE cross-device sync (HeartbeatSyncer.fireNow)
 *     instead of waiting for the next 5-minute heartbeat, so a
 *     user-asked memory lands on every Mythara install right away.
 *
 * The result: an explicit user-stated fact becomes a first-class
 * source informing future answers across the whole device cluster.
 */
@Singleton
class RememberTool @Inject constructor(
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
    /** dagger.Lazy — HeartbeatSyncer transitively pulls in the agent stack. */
    private val heartbeat: dagger.Lazy<HeartbeatSyncer>,
) : Tool {

    override val name: String = "remember"
    override val description: String =
        "Durably remember a fact the user explicitly asked you to keep. Use this whenever the user says " +
            "'remember that…', 'don't forget…', 'keep in mind…', or 'note that…'. " +
            "The fact is stored in long-term memory WITH an embedding, so it becomes a first-class source that " +
            "informs your future answers via semantic recall — and it syncs to the user's other Mythara devices " +
            "immediately. Phrase `content` as a clear standalone third-person statement about the user " +
            "(e.g. 'The user's wifi password is hunter2', 'The user's daughter is named Mira'). " +
            "Optionally pass a short hyphenated `topic` slug to group it."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "content",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "The fact to remember, as a clear standalone third-person statement about the user.",
                        )
                    },
                )
                put(
                    "topic",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional short hyphenated topic slug for grouping (e.g. 'wifi', 'family', 'preferences').",
                        )
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("content")) })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val content = (args["content"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (content.isEmpty()) return ToolResult(false, """{"error":"missing_content"}""")
        val topic = (args["topic"] as? JsonPrimitive)?.content?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() }

        // Embed so SemanticRecall can surface this on future turns —
        // recall only scans vault rows that HAVE an embedding.
        val embedding = if (embedder.isReady()) {
            runCatching { embedder.embed(content) }.getOrNull()
        } else {
            null
        }

        val facets = buildList {
            add("kind:user-stated")
            add("src:user-asked")
            if (topic != null) add("topic:$topic")
        }
        val stored = runCatching {
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "user:remember",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = 1.0,
            )
        }.getOrElse {
            Log.w(TAG, "vault.add failed: ${it.message}")
            false
        }
        if (!stored) {
            return ToolResult(
                false,
                """{"error":"store_failed","detail":"Couldn't persist the memory — it may have been blank after scrubbing."}""",
            )
        }

        // Immediate cross-device sync — user-asked memories shouldn't
        // wait for the next 5-minute heartbeat.
        runCatching { heartbeat.get().fireNow() }
            .onFailure { Log.w(TAG, "immediate sync kick failed: ${it.message}") }

        Log.d(TAG, "remembered: \"${content.take(80)}\" (embedded=${embedding != null})")
        val payload = buildJsonObject {
            put("ok", true)
            put("stored", content)
            put("embedded", embedding != null)
            put("syncing", true)
            put(
                "detail",
                buildString {
                    append("Stored in long-term memory")
                    if (embedding != null) {
                        append(" — it will inform future answers via recall")
                    } else {
                        append(" (embedder still warming up — recall kicks in once it's ready)")
                    }
                    append(", and an immediate cross-device sync was kicked off.")
                },
            )
        }
        return ToolResult(true, payload.toString())
    }

    companion object {
        private const val TAG = "Mythara/Remember"
    }
}
