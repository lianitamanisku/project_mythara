package com.mythara.agent.tools

import com.mythara.agent.ConfirmationGate
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.skills.Skill
import com.mythara.skills.SkillRunner
import com.mythara.skills.SkillStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Four tools that together implement self-evolving skills:
 *
 *  - [ListSkillsTool] — what skills does Lumi know?
 *  - [GetSkillTool]   — pull the JSON of a specific skill (incl. failures)
 *  - [SaveSkillTool]  — write a new (or refined) skill from a JSON blob
 *  - [RunSkillTool]   — execute a skill with named params
 *
 * The agent uses these as a closed loop:
 *   1. list_skills to see what's already there
 *   2. if no skill exists for the task, walk through manually with
 *      read_screen + tap_text + type_text, then save_skill the
 *      sequence as a Skill JSON
 *   3. next time, run_skill with the recorded steps + params
 *   4. on failure, get_skill to read past failures, refine the
 *      steps, save_skill with version+1
 */

@Singleton
class ListSkillsTool @Inject constructor(
    private val store: SkillStore,
) : Tool {

    @Serializable
    data class Entry(
        val name: String,
        val description: String,
        val params: List<String>,
        val version: Int,
        val runCount: Int,
        val successCount: Int,
        val failuresRecent: Int,
    )

    @Serializable
    data class Response(val count: Int, val skills: List<Entry>)

    override val name: String = "list_skills"
    override val description: String =
        "List every skill the agent has saved — name, description, parameters, run history. " +
            "Call this BEFORE attempting a multi-step task to see if you already know how to do it. " +
            "Returns empty when no skills exist yet; that's the signal to record one."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val skills = store.list().map { s ->
            Entry(
                name = s.name,
                description = s.description,
                params = s.params,
                version = s.version,
                runCount = s.runCount,
                successCount = s.successCount,
                failuresRecent = s.failures.size,
            )
        }
        return ToolResult(
            ok = true,
            output = JSON.encodeToString(
                Response.serializer(),
                Response(count = skills.size, skills = skills),
            ),
        )
    }

    companion object {
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}

@Singleton
class GetSkillTool @Inject constructor(
    private val store: SkillStore,
) : Tool {

    override val name: String = "get_skill"
    override val description: String =
        "Fetch the full JSON body of a saved skill including its steps + failure history. " +
            "Use to inspect WHAT a skill does before running it, or to read past failures " +
            "when deciding whether to refine the skill."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "name",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Skill name as returned by list_skills.")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("name"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = (args["name"] as? JsonPrimitive)?.content
            ?: return ToolResult(false, """{"error":"missing_name"}""")
        val skill = store.get(name)
            ?: return ToolResult(false, """{"error":"not_found","detail":"No skill named '$name'."}""")
        return ToolResult(true, store.encode(skill))
    }
}

@Singleton
class SaveSkillTool @Inject constructor(
    private val store: SkillStore,
) : Tool {

    override val name: String = "save_skill"
    override val description: String =
        "Save (or refine) a skill. Pass the FULL skill JSON — name, description, params, steps. " +
            "Step `action` discriminator: open_app | wait | tap_text | tap_desc | tap_id | type_text | " +
            "swipe | tap | verify_visible | read_screen. " +
            "Prefer tap_text / tap_desc over pixel-coord tap so the skill survives UI redesigns. " +
            "End with a verify_visible step to confirm the final state. Use {paramName} placeholders " +
            "in text/desc/type-text fields — values come from run_skill's args. " +
            "Bump `version` when refining; new failures inline are kept across versions."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "skill_json",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Full Skill JSON as defined by Mythara's Skill schema. Example: " +
                                """{"name":"whatsapp-send","description":"...","params":["contact","message"],""" +
                                """"steps":[{"action":"open_app","pkg":"com.whatsapp"},""" +
                                """{"action":"tap_text","text":"{contact}"},""" +
                                """{"action":"type_text","text":"{message}"},""" +
                                """{"action":"tap_desc","desc":"Send"}]}""",
                        )
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("skill_json"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = (args["skill_json"] as? JsonPrimitive)?.content
            ?: return ToolResult(false, """{"error":"missing_skill_json"}""")
        val skill = store.decode(raw)
            ?: return ToolResult(false, """{"error":"parse_failed","detail":"skill_json didn't decode as a Skill — check action discriminators and shape."}""")
        if (skill.name.isBlank()) return ToolResult(false, """{"error":"missing_name"}""")
        if (skill.steps.isEmpty()) return ToolResult(false, """{"error":"empty_steps"}""")
        val ok = store.save(skill)
        return if (ok) ToolResult(
            true,
            """{"ok":true,"name":${JsonPrimitive(skill.name)},"version":${skill.version},"steps":${skill.steps.size}}""",
        ) else ToolResult(false, """{"error":"save_failed"}""")
    }
}

@Singleton
class RunSkillTool @Inject constructor(
    private val store: SkillStore,
    private val runner: SkillRunner,
) : Tool {

    override val name: String = "run_skill"
    override val description: String =
        "Execute a saved skill by name. Pass a string-map of named params; the runner substitutes " +
            "{paramName} placeholders in the skill's steps. Failure surfaces as a structured result " +
            "with failureAtStep + failureReason + trace — use that to decide whether to refine the " +
            "skill (save a new version) or fall back to manual primitives."

    /**
     * Skills are user-authored multi-step automations — every run
     * pops a confirmation dialog by default. The model can grant
     * always-allow per skill in the dialog to skip future prompts.
     */
    override val requiresConfirmation: Boolean = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "name",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Skill name as returned by list_skills.")
                    },
                )
                put(
                    "params",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "description",
                            "Map of param name to string value. Keys must match the skill's `params` list.",
                        )
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("name"))))
    }

    override fun confirmationFor(args: JsonObject): ConfirmationGate.ConfirmRequest? {
        val name = (args["name"] as? JsonPrimitive)?.content ?: "?"
        return ConfirmationGate.ConfirmRequest(
            id = "",
            toolName = this.name,
            title = "Run skill '$name'?",
            body = "Lumi will drive other apps on your behalf — tap, type, swipe. Confirm to allow.",
            allowlistKey = "run_skill:$name",
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = (args["name"] as? JsonPrimitive)?.content
            ?: return ToolResult(false, """{"error":"missing_name"}""")
        val skill = store.get(name)
            ?: return ToolResult(false, """{"error":"not_found","detail":"No skill named '$name'."}""")

        val params = (args["params"] as? JsonObject)?.entries
            ?.associate { (k, v) -> k to ((v as? JsonPrimitive)?.content ?: "") }
            ?: emptyMap()

        val result = runner.run(skill, params)
        return ToolResult(
            ok = result.ok,
            output = Json.Default.encodeToString(
                com.mythara.skills.SkillRunResult.serializer(),
                result,
            ),
        )
    }
}
