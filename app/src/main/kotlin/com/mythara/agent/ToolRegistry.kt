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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    sendWhatsAppDirectTool: com.mythara.agent.tools.SendWhatsAppDirectTool,
    listDismissedNotificationsTool: com.mythara.agent.tools.ListDismissedNotificationsTool,
    listSkillsTool: com.mythara.agent.tools.ListSkillsTool,
    getSkillTool: com.mythara.agent.tools.GetSkillTool,
    saveSkillTool: com.mythara.agent.tools.SaveSkillTool,
    runSkillTool: com.mythara.agent.tools.RunSkillTool,
    screenshotViewTool: com.mythara.agent.tools.ScreenshotViewTool,
    pressBackTool: com.mythara.agent.tools.PressBackTool,
    readRecentChatImageTool: com.mythara.agent.tools.ReadRecentChatImageTool,
    requestRemoteLocationTool: com.mythara.agent.tools.RequestRemoteLocationTool,
    listMytharaDevicesTool: com.mythara.agent.tools.ListMytharaDevicesTool,
    sendNoteToDeviceTool: com.mythara.agent.tools.SendNoteToDeviceTool,
    createTaskTool: com.mythara.agent.tools.CreateTaskTool,
    createReminderTool: com.mythara.agent.tools.CreateReminderTool,
    rememberTool: com.mythara.agent.tools.RememberTool,
    manageFavoritesTool: com.mythara.agent.tools.ManageFavoritesTool,
    triggerActionTool: com.mythara.agent.tools.TriggerActionTool,
    saveAnalysisInstructionTool: com.mythara.agent.tools.SaveAnalysisInstructionTool,
    teamCallTool: com.mythara.agent.tools.TeamCallTool,
    readSensorsTool: com.mythara.agent.tools.ReadSensorsTool,
    requestRemoteSensorsTool: com.mythara.agent.tools.RequestRemoteSensorsTool,
    private val mcpRegistry: com.mythara.mcp.McpRegistry,
    private val gate: ConfirmationGate,
    private val allowlist: com.mythara.data.AllowlistStore,
    private val confirmationSettings: ConfirmationSettings,
    private val audit: com.mythara.audit.AuditLogger,
    private val criticalGuard: CriticalActionGuard,
    private val autoReplyPrefix: com.mythara.data.AutoReplyPrefixStore,
    private val convWriter: ConversationMessageWriter,
) {
    /** Static tools defined in-app. MCP-discovered tools are merged on
     *  demand via [tools] / [byName] so adding an MCP server doesn't
     *  require a process restart. */
    private val nativeTools: List<Tool> = listOf(
        timeTool, batteryTool, webFetchTool,
        readScreenTool, readNotificationsTool, takePhotoTool,
        getLocationTool, readContactTool,
        listCalendarEventsTool, createCalendarEventTool,
        // Composer variants (smsComposerTool, placeCallTool,
        // sendWhatsAppTool) intentionally NOT registered — the user
        // explicitly chose direct-send semantics and the model was
        // still occasionally picking the composer path. Removing them
        // from the registry makes that impossible: the only way to
        // send/call/whatsapp now is direct.
        flashlightTool, openAppTool, listAppsTool,
        spawnAgentTool,
        smsDirectTool, placeCallDirectTool,
        tapTool, swipeTool, typeTextTool,
        sendWhatsAppDirectTool,
        listDismissedNotificationsTool,
        listSkillsTool, getSkillTool, saveSkillTool, runSkillTool,
        screenshotViewTool,
        pressBackTool,
        readRecentChatImageTool,
        requestRemoteLocationTool,
        listMytharaDevicesTool,
        sendNoteToDeviceTool,
        createTaskTool,
        createReminderTool,
        rememberTool,
        manageFavoritesTool,
        triggerActionTool,
        saveAnalysisInstructionTool,
        teamCallTool,
        readSensorsTool,
        requestRemoteSensorsTool,
    )

    /** Native + currently-known MCP tools, merged. Recomputed on every
     *  access — the MCP registry is a live snapshot and we want the
     *  agent to see new MCP tools the moment they're discovered. */
    private val tools: List<Tool>
        get() = nativeTools + mcpRegistry.asMytharaTools()

    private val byName: Map<String, Tool>
        get() = tools.associateBy { it.name }

    /**
     * Composer-style names the model still tries from training bias
     * even though they're no longer in [apiSchema]. We catch them
     * at execute time and transparently redirect to the direct
     * equivalent so the model's slip-up doesn't cost an extra
     * round-trip (model emits send_whatsapp → registry returns
     * unknown_tool → model retries with send_whatsapp_direct →
     * actual send).
     */
    private val REDIRECTS = mapOf(
        "send_whatsapp" to "send_whatsapp_direct",
        "send_sms" to "send_sms_direct",
        "place_call" to "place_call_direct",
    )

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
        // Transparent redirect for composer-style names the model
        // sometimes calls from training bias. Logs the redirect so
        // we can see whether the apiSchema-only diet is enough or
        // we're still seeing slip-throughs.
        val effectiveName = REDIRECTS[name]?.also { target ->
            android.util.Log.d(
                "Mythara/Registry",
                "redirect $name → $target (model called a deprecated composer name)",
            )
            audit.logRedirect(fromName = name, toName = target)
        } ?: name
        val tool = byName[effectiveName] ?: run {
            audit.logToolCall(
                toolName = name,
                argsJson = argsJson,
                ok = false,
                output = "unknown tool",
                latencyMs = 0L,
            )
            return ToolResult.fail("unknown tool: $name")
        }
        val args: JsonObject = runCatching {
            MiniMaxClient.json.decodeFromString<JsonObject>(argsJson.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        // Restricted-apps policy — runs BEFORE the per-tool
        // confirmation hook because:
        //  • A Block decision is final, no popup, no override.
        //  • A RequireConfirm decision forces the gate to pop even
        //    when the user's global "always confirm" toggle is off,
        //    so booking-an-Uber-style critical actions never slip
        //    through autopilot silently.
        // Read tools bypass the guard entirely (see CriticalActionGuard).
        when (val verdict = criticalGuard.evaluate(tool.name, args)) {
            is CriticalActionGuard.Decision.Block -> {
                audit.logToolCall(
                    toolName = tool.name,
                    argsJson = argsJson,
                    ok = false,
                    output = "blocked: ${verdict.reason}",
                    latencyMs = 0L,
                )
                return ToolResult.fail(
                    """{"error":"app_blocked","detail":${JsonPrimitive(verdict.reason)},"pkg":${JsonPrimitive(verdict.pkg)},"override":"Open the app yourself — Mythara never automates banking/payment/wallet apps."}""",
                )
            }
            is CriticalActionGuard.Decision.RequireConfirm -> {
                val req = ConfirmationGate.ConfirmRequest(
                    id = gate.newId(tool.name),
                    toolName = tool.name,
                    title = "Critical action — ${verdict.pkg}?",
                    body = verdict.reason + " Tap allow to proceed once, deny to cancel.",
                    // No allowlistKey — by design, the user CAN'T
                    // pre-authorise these. Every critical action gets
                    // a fresh prompt.
                    allowlistKey = null,
                )
                val decision = gate.request(req)
                if (decision == ConfirmationGate.Decision.Deny) {
                    audit.logUserCanceled(tool.name)
                    return ToolResult.fail(
                        """{"error":"user_canceled","detail":"User declined the critical-action confirmation for ${tool.name} on ${verdict.pkg}."}""",
                    )
                }
                // Approved — fall through to normal execute path.
            }
            CriticalActionGuard.Decision.Allow -> { /* fall through */ }
        }

        val confirmStub = tool.confirmationFor(args)
        if (confirmStub != null) {
            // Three short-circuit conditions:
            // (a) Global "always confirm" toggle is OFF → user has
            //     opted out of the dialog, fire silently.
            // (b) Allowlist contains the per-call key → user said
            //     "always allow this" on a prior prompt, fire silently.
            // Otherwise pop the gate.
            val alwaysConfirm = confirmationSettings.alwaysConfirm()
            val allowKey = confirmStub.allowlistKey
            val preAuthorized = allowKey != null && allowlist.isAllowed(allowKey)
            if (alwaysConfirm && !preAuthorized) {
                val req = confirmStub.copy(id = gate.newId(tool.name))
                val decision = gate.request(req)
                if (decision == ConfirmationGate.Decision.Deny) {
                    audit.logUserCanceled(tool.name)
                    return ToolResult.fail(
                        """{"error":"user_canceled","detail":"User declined the confirmation prompt for ${tool.name}."}""",
                    )
                }
            }
        }

        // Auto-reply prefix injection — only when:
        //   (a) the current turn is an auto-reply (the AgentLoop set
        //       AutoReplyMarker in the coroutine context), AND
        //   (b) the tool is a phone-send tool with a body field, AND
        //   (c) the user has configured a non-blank prefix.
        // Done mechanically here rather than via the LLM prompt because
        // LLMs frequently strip or reflow leading parentheticals — the
        // only guarantee that "LUMI (autopilot):" actually reaches the
        // wire is to splice it in deterministically.
        val effectiveArgs: JsonObject = run {
            val isAutoReplyTurn = currentCoroutineContext()[AutoReplyMarker.Key] != null
            if (!isAutoReplyTurn || tool.name !in PHONE_SEND_TOOLS) return@run args
            val rawPrefix = runCatching { autoReplyPrefix.prefix() }.getOrDefault("")
            val prefix = rawPrefix.takeIf { it.isNotBlank() } ?: return@run args
            val body = (args["body"] as? JsonPrimitive)?.content.orEmpty()
            if (body.isBlank()) return@run args
            // Idempotent: if the model already echoed the prefix at
            // the start of body (shouldn't, but defensive), don't
            // double it up.
            val newBody = if (body.startsWith(prefix)) body else "$prefix$body"
            JsonObject(args.toMutableMap().apply { put("body", JsonPrimitive(newBody)) })
        }

        val t0 = System.nanoTime()
        val result = runCatching { tool.execute(effectiveArgs) }
            .getOrElse { ToolResult.fail(it.message ?: "tool threw ${it.javaClass.simpleName}") }
        val latencyMs = (System.nanoTime() - t0) / 1_000_000
        // Analytics capture — when an auto-reply turn fires a
        // phone-send tool successfully, persist the OUTGOING body
        // into the learning vault facetted with the contact. The
        // dispatcher already wrote the INCOMING side; this pair
        // lets ContactAnalyticsBuilder fold both directions of
        // every conversation into the per-contact profile.
        if (result.ok && tool.name in PHONE_SEND_TOOLS) {
            val marker = currentCoroutineContext()[AutoReplyMarker.Key]
            if (marker != null && marker.contactName.isNotBlank()) {
                val body = (effectiveArgs["body"] as? JsonPrimitive)?.content.orEmpty()
                val pkg = when (tool.name) {
                    "send_whatsapp_direct" -> "com.whatsapp"
                    "send_sms_direct" -> "sms"
                    else -> ""
                }
                runCatching { convWriter.record(marker.contactName, body, pkg, direction = "outgoing") }
            }
        }
        // Audit log records the FINAL args (with prefix applied) so the
        // user sees exactly what landed on the wire, not the LLM's
        // pre-prefix body.
        audit.logToolCall(
            toolName = tool.name,
            argsJson = if (effectiveArgs === args) argsJson else effectiveArgs.toString(),
            ok = result.ok,
            output = result.output,
            latencyMs = latencyMs,
        )
        return result
    }

    companion object {
        /** Tools that carry a user-visible `body` field worth prefixing. */
        private val PHONE_SEND_TOOLS: Set<String> = setOf(
            "send_sms_direct",
            "send_whatsapp_direct",
        )
    }

    /** True if the model returned a name we don't have a binding for. */
    fun unknown(name: String): Boolean = name !in byName && name !in REDIRECTS
}
