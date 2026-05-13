package com.mythara.agent

import com.mythara.agent.tools.BatteryTool
import com.mythara.agent.tools.ReadNotificationsTool
import com.mythara.agent.tools.ReadScreenTool
import com.mythara.agent.tools.TimeTool
import com.mythara.agent.tools.WebFetchTool
import com.mythara.minimax.MiniMaxClient
import com.mythara.minimax.models.Tool as ApiTool
import com.mythara.minimax.models.ToolFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The runtime's tool registry. Holds every [Tool] instance, exposes the
 * MiniMax-compatible schema for `chat/completions`, and dispatches
 * execution by name.
 *
 * In M5+ this grows to include Accessibility-driven tools (read_screen,
 * tap, swipe…), camera capture, notifications, SMS, etc. Each new tool
 * is one constructor arg here — Hilt provides them as `@Singleton + @Inject`,
 * so adding capability surface is purely additive.
 */
@Singleton
class ToolRegistry @Inject constructor(
    timeTool: TimeTool,
    batteryTool: BatteryTool,
    webFetchTool: WebFetchTool,
    readScreenTool: ReadScreenTool,
    readNotificationsTool: ReadNotificationsTool,
) {
    private val tools: List<Tool> = listOf(
        timeTool, batteryTool, webFetchTool, readScreenTool, readNotificationsTool,
    )
    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    /** Names of all registered tools — for UI surfacing and diagnostics. */
    fun names(): List<String> = tools.map { it.name }

    /** MiniMax-compatible `tools` array. Inject straight into [ChatRequest.tools]. */
    fun apiSchema(): List<ApiTool> = tools.map { t ->
        ApiTool(
            type = "function",
            function = ToolFunction(
                name = t.name,
                description = t.description,
                parameters = t.parameters,
            ),
        )
    }

    /**
     * Execute a tool by name. Always returns a [ToolResult] — never throws.
     * The model sees `output` verbatim as the next `tool` message body.
     */
    suspend fun execute(name: String, argsJson: String): ToolResult {
        val tool = byName[name] ?: return ToolResult.fail("unknown tool: $name")
        val args: JsonObject = runCatching {
            MiniMaxClient.json.decodeFromString<JsonObject>(argsJson.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }
        return runCatching { tool.execute(args) }
            .getOrElse { ToolResult.fail(it.message ?: "tool threw ${it.javaClass.simpleName}") }
    }

    /** True if the model returned a name we don't have a binding for. */
    fun unknown(name: String): Boolean = name !in byName
}
