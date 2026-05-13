package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.NotificationListener
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `read_notifications` — returns the most recent notifications
 * currently in the user's status bar. Powered by
 * [NotificationListener]'s in-memory rolling buffer.
 *
 * Filters out ongoing (FLAG_ONGOING_EVENT) notifications by
 * default since those are usually media controls / foreground
 * service indicators rather than user-actionable messages. The
 * model can ask for them via `include_ongoing: true` if needed.
 *
 * Output is compact JSON suitable for a `role: tool` message body.
 */
@Singleton
class ReadNotificationsTool @Inject constructor() : Tool {

    @Serializable
    data class NotifPreview(
        val pkg: String,
        val postTimeMs: Long,
        val title: String? = null,
        val text: String? = null,
        val sub: String? = null,
        val ongoing: Boolean = false,
    )

    @Serializable
    data class Response(
        val count: Int,
        val notifications: List<NotifPreview>,
    )

    override val name: String = "read_notifications"

    override val description: String =
        "Read the user's recent phone notifications and return them as JSON. Skips ongoing notifications (media controls, FGS indicators) by default; set include_ongoing=true to include them. Use when the user asks 'what notifications do I have' or needs you to triage their inbox."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "include_ongoing",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "When true, include ongoing notifications (media players, FGS indicators) in the result. Default false.",
                        )
                    },
                )
                put(
                    "limit",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of notifications to return (most recent first). Default 20, max 50.",
                        )
                    },
                )
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = NotificationListener.instance
            ?: return ToolResult(
                ok = false,
                output = """{"error":"notification_access_not_granted","detail":"Mythara doesn't have notification access. Open Settings → Notification access in the app to grant it."}""",
            )
        val includeOngoing = args["include_ongoing"]
            ?.let { runCatching { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() }.getOrNull() }
            ?: false
        val rawLimit = args["limit"]
            ?.let { runCatching { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }.getOrNull() }
            ?: DEFAULT_LIMIT
        val limit = rawLimit.coerceIn(1, MAX_LIMIT)

        val snapshot = service.snapshot()
            .asSequence()
            .filter { includeOngoing || !it.ongoing }
            .take(limit)
            .map { r ->
                NotifPreview(
                    pkg = r.packageName,
                    postTimeMs = r.postTimeMs,
                    title = r.title,
                    text = r.text,
                    sub = r.subText,
                    ongoing = r.ongoing,
                )
            }
            .toList()
        val response = Response(count = snapshot.size, notifications = snapshot)
        return ToolResult(ok = true, output = JSON.encodeToString(Response.serializer(), response))
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 50
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
