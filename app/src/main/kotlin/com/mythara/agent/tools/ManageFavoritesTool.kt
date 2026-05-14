package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.data.FavoritesStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `manage_favorites` — add / remove / list the user's favorite
 * contacts.
 *
 * Favorites are the curated allowlist of people Mythara is allowed to
 * auto-respond to, each tagged with a reply tone (friendly /
 * professional / realistic). Use this whenever the user says things
 * like "add my mom to favorites", "make Alex a favorite, professional
 * tone", "remove Sam from favorites", or "who are my favorites".
 */
@Singleton
class ManageFavoritesTool @Inject constructor(
    private val store: FavoritesStore,
) : Tool {

    override val name: String = "manage_favorites"
    override val description: String =
        "Add, remove, or list the user's favorite contacts. Favorites are the curated set of people Mythara is " +
            "allowed to auto-respond to, each with a reply tone (friendly / professional / realistic). " +
            "action='add' needs `name` (optionally `phone` + `tone`); action='remove' needs `name`; " +
            "action='list' returns the current favorites. Use whenever the user asks to add or remove someone " +
            "from favorites, or asks who's on the list."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "action",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "One of: add, remove, list.")
                    },
                )
                put(
                    "name",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Contact display name. Required for add + remove.")
                    },
                )
                put(
                    "phone",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Optional phone number for an added favorite (any format).")
                    },
                )
                put(
                    "tone",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Reply tone for an added favorite: friendly, professional, or realistic. Default realistic.",
                        )
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        val name = (args["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
        return when (action) {
            "add" -> {
                if (name.isEmpty()) return ToolResult(false, """{"error":"missing_name"}""")
                val phone = (args["phone"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val toneLabel = (args["tone"] as? JsonPrimitive)?.content?.trim()
                    ?.let { FavoritesStore.Tone.fromLabel(it).label }
                    ?: FavoritesStore.Tone.Realistic.label
                runCatching {
                    store.upsert(
                        FavoritesStore.Favorite(name = name, phone = phone, toneLabel = toneLabel),
                    )
                }.getOrElse {
                    return ToolResult(
                        false,
                        """{"error":"add_failed","detail":${JsonPrimitive(it.message ?: "unknown")}}""",
                    )
                }
                Log.d(TAG, "favorite added: $name ($toneLabel)")
                ToolResult(
                    true,
                    """{"ok":true,"action":"add","name":${JsonPrimitive(name)},"tone":${JsonPrimitive(toneLabel)},"detail":"Added $name to favorites with $toneLabel tone."}""",
                )
            }
            "remove" -> {
                if (name.isEmpty()) return ToolResult(false, """{"error":"missing_name"}""")
                runCatching { store.remove(name) }.getOrElse {
                    return ToolResult(
                        false,
                        """{"error":"remove_failed","detail":${JsonPrimitive(it.message ?: "unknown")}}""",
                    )
                }
                Log.d(TAG, "favorite removed: $name")
                ToolResult(
                    true,
                    """{"ok":true,"action":"remove","name":${JsonPrimitive(name)},"detail":"Removed $name from favorites."}""",
                )
            }
            "list" -> {
                val favs = runCatching { store.list() }.getOrDefault(emptyList())
                val payload = buildJsonObject {
                    put("ok", true)
                    put("action", "list")
                    put("count", favs.size)
                    put(
                        "favorites",
                        buildJsonArray {
                            favs.forEach { f ->
                                add(
                                    buildJsonObject {
                                        put("name", f.name)
                                        put("phone", f.phone)
                                        put("tone", f.toneLabel)
                                        put("enabled", f.enabled)
                                    },
                                )
                            }
                        },
                    )
                }
                ToolResult(true, payload.toString())
            }
            else -> ToolResult(
                false,
                """{"error":"bad_action","detail":"action must be add, remove, or list."}""",
            )
        }
    }

    companion object {
        private const val TAG = "Mythara/Favorites"
    }
}
