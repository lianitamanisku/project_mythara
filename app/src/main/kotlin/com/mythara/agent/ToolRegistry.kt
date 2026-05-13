package com.mythara.agent

import com.mythara.agent.tools.BatteryTool
import com.mythara.agent.tools.CreateCalendarEventTool
import com.mythara.agent.tools.FlashlightTool
import com.mythara.agent.tools.GetLocationTool
import com.mythara.agent.tools.ListAppsTool
import com.mythara.agent.tools.ListCalendarEventsTool
import com.mythara.agent.tools.OpenAppTool
import com.mythara.agent.tools.PlaceCallTool
import com.mythara.agent.tools.ReadContactTool
import com.mythara.agent.tools.ReadNotificationsTool
import com.mythara.agent.tools.ReadScreenTool
import com.mythara.agent.tools.SmsComposerTool
import com.mythara.agent.tools.SpawnAgentTool
import com.mythara.agent.tools.TakePhotoTool
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
    takePhotoTool: TakePhotoTool,
    getLocationTool: GetLocationTool,
    readContactTool: ReadContactTool,
    listCalendarEventsTool: ListCalendarEventsTool,
    createCalendarEventTool: CreateCalendarEventTool,
    smsComposerTool: SmsComposerTool,
    placeCallTool: PlaceCallTool,
    flashlightTool: FlashlightTool,
    openAppTool: OpenAppTool,
    listAppsTool: ListAppsTool,
    spawnAgentTool: SpawnAgentTool,
    smsDirectTool: com.mythara.agent.tools.SmsDirectTool,
    placeCallDirectTool: com.mythara.agent.tools.PlaceCallDirectTool,
    tapTool: com.mythara.agent.tools.TapTool,
    swipeTool: com.mythara.agent.tools.SwipeTool,
    typeTextTool: com.mythara.agent.tools.TypeTextTool,
    sendWhatsAppTool: com.mythara.agent.tools.SendWhatsAppTool,
    listSkillsTool: com.mythara.agent.tools.ListSkillsTool,
    getSkillTool: com.mythara.agent.tools.GetSkillTool,
    saveSkillTool: com.mythara.agent.tools.SaveSkillTool,
    runSkillTool: com.mythara.agent.tools.RunSkillTool,
    private val gate: ConfirmationGate,
    private val allowlist: com.mythara.data.AllowlistStore,
) {
    private val tools: List<Tool> = listOf(
        timeTool, batteryTool, webFetchTool,
        readScreenTool, readNotificationsTool, takePhotoTool,
        getLocationTool, readContactTool,
        listCalendarEventsTool, createCalendarEventTool,
        smsComposerTool, placeCallTool,
        flashlightTool, openAppTool, listAppsTool,
        spawnAgentTool,
        smsDirectTool, placeCallDirectTool,
        tapTool, swipeTool, typeTextTool,
        sendWhatsAppTool,
        listSkillsTool, getSkillTool, saveSkillTool, runSkillTool,
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
     *
     * Destructive tools route through [ConfirmationGate]. When the tool
     * returns a non-null [Tool.confirmationFor] we:
     *   1. Check the allowlist — if the user previously ticked
     *      "Always allow this" for this key, fire without prompting.
     *   2. Otherwise pop a confirmation dialog and suspend until the
     *      user accepts or denies. Denial yields a `user_canceled`
     *      ToolResult that the model sees and can react to ("ok, I
     *      won't send the SMS then").
     */
    suspend fun execute(name: String, argsJson: String): ToolResult {
        val tool = byName[name] ?: return ToolResult.fail("unknown tool: $name")
        val args: JsonObject = runCatching {
            MiniMaxClient.json.decodeFromString<JsonObject>(argsJson.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        val confirmStub = tool.confirmationFor(args)
        if (confirmStub != null) {
            val allowKey = confirmStub.allowlistKey
            val preAuthorized = allowKey != null && allowlist.isAllowed(allowKey)
            if (!preAuthorized) {
                val req = confirmStub.copy(id = gate.newId(tool.name))
                val decision = gate.request(req)
                if (decision == ConfirmationGate.Decision.Deny) {
                    return ToolResult.fail(
                        """{"error":"user_canceled","detail":"User declined the confirmation prompt for ${tool.name}."}""",
                    )
                }
            }
        }

        return runCatching { tool.execute(args) }
            .getOrElse { ToolResult.fail(it.message ?: "tool threw ${it.javaClass.simpleName}") }
    }

    /** True if the model returned a name we don't have a binding for. */
    fun unknown(name: String): Boolean = name !in byName
}
