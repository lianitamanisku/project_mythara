package com.mythara.agent

import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.data.SettingsStore
import com.mythara.minimax.ErrorMapper
import com.mythara.minimax.MiniMaxClient
import com.mythara.minimax.StreamingChat
import com.mythara.minimax.models.ChatMessage
import com.mythara.minimax.models.ChatRequest
import com.mythara.minimax.models.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mythara's agentic runtime — same shape as Crush's main loop.
 *
 * Per user turn:
 *   1. Persist the user message to history.
 *   2. Snapshot the entire conversation, hand it to MiniMax with the
 *      tools-array attached.
 *   3. Stream the model's response. If it ends in `finish_reason=stop`,
 *      we're done — emit Finished.
 *   4. If it ends in `finish_reason=tool_calls`, persist the assistant
 *      message (text + tool_calls), execute each tool, persist a
 *      `role:tool` message per result, then loop back to step 2 with
 *      the enlarged history. The model sees its tool results and either
 *      generates text or calls more tools.
 *   5. MAX_ITERATIONS caps the loop so a buggy model can't burn the
 *      user's quota forever; the cap shows up as Turn.Finished with a
 *      sentinel suffix.
 *
 * The emitted [Turn] flow lets the UI render tool-call cards in real
 * time — Crush-style "● Reading file…" → "✓ Reading file (0.4s)".
 */
@Singleton
class AgentLoop @Inject constructor(
    private val settings: SettingsStore,
    private val history: HistoryRepository,
    private val registry: ToolRegistry,
    private val recall: SemanticRecall,
) {

    sealed interface Turn {
        /** Streamed text fragment to append to the active assistant bubble. */
        data class Delta(val text: String) : Turn

        /** A tool call is about to run. Render the bubble in "● running" state. */
        data class ToolStart(val callId: String, val name: String, val args: String) : Turn

        /** Tool finished. Render "✓ done (durationMs)" or "× failed". */
        data class ToolEnd(
            val callId: String,
            val name: String,
            val ok: Boolean,
            val output: String,
            val durationMs: Long,
        ) : Turn

        /**
         * End of the entire turn (post-loop). Carries the dominant
         * mood trend the agent observed (if any) so the chat layer
         * can pass it through to TTS for prosody modulation.
         */
        data class Finished(
            val finalText: String,
            val iterations: Int,
            val userMoodTrend: String? = null,
        ) : Turn

        /** Stream-level failure (HTTP / SSE / mapped MiniMax code). */
        data class Error(val message: String, val retryable: Boolean) : Turn

        /** No API key configured yet — UI surfaces a "Settings" prompt. */
        data object MissingApiKey : Turn
    }

    fun submit(userText: String, fromVoice: Boolean = false): Flow<Turn> = flow {
        val snap = settings.snapshot()
        val apiKey = snap.apiKey
        if (apiKey.isNullOrBlank()) {
            emit(Turn.MissingApiKey); return@flow
        }

        history.dao.insert(
            MessageRow(tsMillis = System.currentTimeMillis(), role = "user", content = userText),
        )

        // One-shot semantic recall over the user's latest message. The
        // result lasts for the duration of this turn — never persisted
        // to history, never re-computed per tool-use iteration.
        val recalledFacts = recall.recall(userText)
        val recallSystem: ChatMessage? = recall.render(recalledFacts)?.let { rendered ->
            android.util.Log.d(TAG, "injecting ${recalledFacts.size} recalled facts")
            ChatMessage(role = "system", content = rendered)
        }

        // Two mood signals:
        //   1. currentMood — the freshest detected emotion from the
        //      just-spoken / just-typed user input. Lives in a vault
        //      record written by ChatMoodTracker microseconds before
        //      we get here. This is the DOMINANT signal for the
        //      current turn — Lumi adapts THIS reply to it.
        //   2. moodTrend — 6-hour windowed dominant mood. Background
        //      relational context ("user has been stressed lately").
        // The system message renders both when present, with the
        // current one prioritised; the model is given directive
        // per-mood guidance (concrete do/don't) rather than a soft
        // hint, so behaviour actually changes.
        val currentMood = recall.currentMood()
        val moodTrend = recall.recentMoodTrend()
        val moodSystem: ChatMessage? = recall.renderMoodSystemMessage(
            currentMood = currentMood,
            moodTrend = moodTrend,
        )?.let { rendered ->
            android.util.Log.d(TAG, "injecting mood: current=$currentMood trend=$moodTrend")
            ChatMessage(role = "system", content = rendered)
        }
        // Final mood for downstream prosody (TTS pitch/rate, EL voice
        // settings). currentMood wins when present.
        val effectiveMood = currentMood ?: moodTrend

        // Temporal anchor — ALWAYS injected, every turn. The agent
        // gets the current local time + day + timezone + ISO so it
        // can reason about "yesterday", "3 hours ago", "tomorrow
        // morning" without calling get_time first. Cheap (~80
        // tokens) and worth it: without this the model is in an
        // eternal "now is undefined" state, falls back to its
        // training-cutoff date, and gives wrong answers to
        // schedule-aware queries.
        val timeSystem: ChatMessage = ChatMessage(
            role = "system",
            content = buildTimeContext(),
        )

        // Conversational system prompt — applied to EVERY turn now,
        // not just voice. Lumi's whole personality is voice-first; a
        // long markdown-heavy answer is wrong even when typed because
        // the user may have spoken queries upstream or downstream and
        // we want consistency. Length floor is the same regardless of
        // input modality; if the user explicitly asks for more detail
        // ("give me the full breakdown"), the model can override.
        val voiceSystem: ChatMessage = ChatMessage(
            role = "system",
            content =
                "Reply like a friend texting, not an assistant generating a deliverable. " +
                    "Constraints every turn: " +
                    "(1) 1–2 short sentences, max ~40 words. Longer ONLY if the user explicitly asked for detail. " +
                    "(2) Conversational tone — no formal openers ('Sure!', 'Certainly!', 'I'd be happy to'), no sign-offs ('Let me know if you need anything else'). " +
                    "(3) NEVER use markdown, lists, headers, code blocks, URLs, or bullets in spoken-style replies. " +
                    "(4) Numbers + symbols spoken-out ('5%' → 'five percent'). Drop URLs entirely unless the user asked for one. " +
                    "(5) If the full answer is long, lead with the headline and offer to go deeper ('want me to dig in?'). " +
                    "(6) Real conversation has variety — sometimes a one-liner, sometimes a question back, sometimes 'I don't know'. Match the mood (see emotional-context message).\n\n" +
                    "TOOL-USE RULES — read carefully, the user has been burned by violations:\n" +
                    "  • If a tool RETURNS the data the user asked for (e.g. list_calendar_events returns a JSON list of events), DO NOT also call open_app to 'show' them the data in the original app. The user asked for the answer, not the app launch. Just relay the data in your reply.\n" +
                    "  • Only call open_app, place_call, send_sms_direct, send_whatsapp, tap, swipe, type_text, or any other side-effect tool when the user EXPLICITLY asked for that action ('open Spotify', 'text mom', 'tap that button'). 'list X' / 'show me X' / 'what's on X' = read-only, never launch the app.\n" +
                    "  • Pushing the user out of Mythara mid-conversation is a UX failure. If you need to launch something, say so first and confirm intent on the next turn.",
        )

        // ElevenLabs audio tags. When the user has the EL TTS route
        // enabled, the model can embed inline cues that EL renders
        // as actual vocal expressions: [laugh], [sigh], [hmm],
        // [chuckle], [whisper]…[/whisper]. Persisted to chat history
        // verbatim; Android TTS strips them at speak-time so they
        // don't get read literally.
        val elevenLabsEnabled = !snap.elevenLabsKey.isNullOrBlank() && snap.useElevenLabs
        val ttsSystem: ChatMessage? = if (elevenLabsEnabled) {
            ChatMessage(
                role = "system",
                content =
                    "Your reply will be synthesised by ElevenLabs. You can — and should, when appropriate — " +
                        "include audio tags inline that ElevenLabs renders as real vocal expressions:\n" +
                        "  [laugh] / [laughs] — genuine quick laugh, for a real moment of amusement\n" +
                        "  [chuckle] — softer, knowing chuckle\n" +
                        "  [sigh] / [sighs] — resignation, mild exasperation, or relief\n" +
                        "  [hmm] — thoughtful pause before answering\n" +
                        "  [exhale] — settle-down beat before a difficult thought\n" +
                        "Use sparingly — at most one tag per reply, and only when it actually fits the moment. " +
                        "A [laugh] on a serious question is jarring; an unprompted [sigh] reads as judgmental. " +
                        "Use them to BE more human, not to perform humanity. " +
                        "Tags go inline with your text (e.g. '[hmm] yeah, that's tricky — try the second one'); " +
                        "no nesting, no closing tags except for [whisper]…[/whisper] which IS paired.",
            )
        } else {
            null
        }

        // Auto-process notifications mode. When ChatViewModel forwards a
        // status-bar notification into the agent loop, it prefixes the
        // user text with `[notif]`. We inject a one-shot system message
        // that tells the model how to handle it: terse spoken summary
        // for actionable stuff, single token NOSURFACE for noise.
        val notifSystem: ChatMessage? = if (userText.startsWith(NOTIF_PREFIX)) {
            ChatMessage(
                role = "system",
                content =
                    "A phone notification just arrived and you're auto-surfacing it. " +
                        "If it's actionable or worth the user knowing right now (a real message, a calendar reminder, a delivery update, an alert), " +
                        "give them a ≤15-word natural spoken summary — they'll hear this read aloud. " +
                        "If it's just system noise (sync indicators, foreground-service pings, OS updates, generic ads, content the user has already seen), " +
                        "reply with the single token NOSURFACE and nothing else. " +
                        "Do not call tools for this turn unless the notification is unclear and a quick read_screen would resolve it.",
            )
        } else {
            null
        }

        val client = MiniMaxClient(apiKey = apiKey, region = snap.region)
        val streaming = StreamingChat(client)

        var iter = 0
        var lastAssistantText = ""

        loop@ while (iter < MAX_ITERATIONS) {
            iter++

            val rawHistory: List<ChatMessage> = history.dao.listAll().map { row ->
                ChatMessage(
                    role = row.role,
                    content = row.content,
                    toolCalls = row.toolCallsJson?.let { decodeToolCalls(it) },
                    toolCallId = row.toolCallId,
                    name = row.name,
                )
            }
            // Defensive sanitiser — MiniMax 400s with code 2013
            // ("tool call result does not follow tool call") if the
            // history ever has an orphan `role:tool` message or an
            // assistant `tool_calls` message whose results are missing.
            // That can happen when:
            //   - the agent loop crashed mid-iteration (NPE, kill, OOM)
            //     leaving a half-finished pair
            //   - a notification-auto-process or wake-query fires
            //     before the previous turn's tool results were
            //     persisted
            //   - manual history edits during dev
            // Once a bad pair lands in the table, every subsequent
            // turn 400s — bricks chat until the user clears history.
            // Sanitise on every send so we self-heal.
            val historyMessages: List<ChatMessage> = sanitizeHistory(rawHistory)
            // Prepend system messages. Order matters: temporal anchor
            // first (so the model has "right now" before reasoning
            // about anything), then conversational style, then audio
            // tags / mood / notif / recall, then persisted history.
            // MiniMax weights earlier system messages more strongly
            // in our experience.
            val prior: List<ChatMessage> = buildList {
                add(timeSystem)  // ALWAYS — current time/date/day-of-week/timezone
                add(voiceSystem) // ALWAYS — conversational style default
                if (ttsSystem != null) add(ttsSystem)
                if (moodSystem != null) add(moodSystem)
                if (notifSystem != null) add(notifSystem)
                if (recallSystem != null) add(recallSystem)
                addAll(historyMessages)
            }

            val req = ChatRequest(
                model = snap.model,
                messages = prior,
                tools = registry.apiSchema().takeIf { it.isNotEmpty() },
                toolChoice = if (registry.apiSchema().isNotEmpty()) "auto" else null,
                stream = true,
                // MiniMax M2.7 is a reasoning model; without reasoning_split=false
                // it emits thinking tokens through a side channel
                // (delta.reasoning_details) that the SSE parser doesn't surface
                // and the tool-use loop can't round-trip. Keep reasoning baked
                // into `content` so history replay works verbatim.
                reasoningSplit = false,
            )

            val streamedText = StringBuilder()
            var toolCalls: List<ToolCall> = emptyList()
            var finishReason: String? = null
            var failure: ErrorMapper.Mapped? = null

            streaming.stream(snap.region, req).collect { ev ->
                when (ev) {
                    is StreamingChat.StreamEvent.Text -> {
                        streamedText.append(ev.delta)
                        emit(Turn.Delta(ev.delta))
                    }
                    is StreamingChat.StreamEvent.ToolCallsReady -> toolCalls = ev.calls
                    is StreamingChat.StreamEvent.Done -> finishReason = ev.finishReason
                    is StreamingChat.StreamEvent.Failure -> failure = ev.mapped
                }
            }

            if (failure != null) {
                val f = failure!!
                emit(Turn.Error(f.message, retryable = f.isRetryable))
                return@flow
            }

            lastAssistantText = streamedText.toString()

            // Persist the assistant turn (with tool_calls if present) so the
            // next iteration includes it verbatim — MiniMax requires the
            // assistant `tool_calls` message in history before each
            // `role:tool` reply, or the next call 400s.
            history.dao.insert(
                MessageRow(
                    tsMillis = System.currentTimeMillis(),
                    role = "assistant",
                    content = lastAssistantText.takeIf { it.isNotEmpty() },
                    toolCallsJson = if (toolCalls.isNotEmpty()) encodeToolCalls(toolCalls) else null,
                ),
            )

            // MiniMax (per function-call docs) reports `tool_use`; OpenAI-compat
            // implementations also return `tool_calls`. Treat both as the signal
            // that the next iteration should execute tools + resume.
            val toolFinish = finishReason == "tool_calls" || finishReason == "tool_use"
            if (toolCalls.isEmpty() || !toolFinish) {
                emit(Turn.Finished(lastAssistantText, iterations = iter, userMoodTrend = effectiveMood))
                return@flow
            }

            // Execute every requested tool sequentially; emit start/end so
            // the UI can render Crush-style ● / ✓ glyphs in real time.
            // The tool execution is wrapped in [UserMessageContext] so
            // any tool that wants the user's verbatim words (e.g.
            // take_photo's vision pass) can read it from the coroutine
            // context without the agent loop knowing tool-specific details.
            for (call in toolCalls) {
                emit(Turn.ToolStart(call.id, call.function.name, call.function.arguments))
                val t0 = System.nanoTime()
                val result = kotlinx.coroutines.withContext(UserMessageContext(userText)) {
                    registry.execute(call.function.name, call.function.arguments)
                }
                val dt = (System.nanoTime() - t0) / 1_000_000
                history.dao.insert(
                    MessageRow(
                        tsMillis = System.currentTimeMillis(),
                        role = "tool",
                        content = result.output,
                        toolCallId = call.id,
                        name = call.function.name,
                    ),
                )
                emit(Turn.ToolEnd(call.id, call.function.name, result.ok, result.output, dt))
            }
            // Continue the outer loop — the next iteration re-streams with
            // the enlarged context (including all tool results).
        }

        // Hit the iteration cap. Surface a soft-stop so the user sees a
        // bounded conversation instead of an infinite-loop bill.
        emit(Turn.Finished(lastAssistantText + " [hit max iterations]", iterations = iter, userMoodTrend = effectiveMood))
    }

    /**
     * Walk the history and drop:
     *  - `role:tool` messages whose `tool_call_id` isn't claimed by an
     *    earlier `role:assistant` with that exact id in its tool_calls
     *  - `role:assistant` messages whose `tool_calls` weren't ALL
     *    answered (i.e. at least one expected `role:tool` reply is
     *    missing somewhere later in history)
     *
     * Preserves ordering otherwise. Returns a new list; never mutates
     * the input. Mirrors the OpenAI / MiniMax wire contract that every
     * tool_call must have exactly one matching tool result, immediately
     * following the assistant turn (with no user/system interleaved).
     */
    private fun sanitizeHistory(history: List<ChatMessage>): List<ChatMessage> {
        if (history.isEmpty()) return history

        // Pass 1: collect every assistant-tool-calls turn and figure
        // out which of its tool_call ids have a matching `role:tool`
        // reply downstream. Drop the assistant turn if ANY are missing.
        // A "matching" reply has to come before the next non-tool
        // message (user / assistant) — anything else means the loop
        // never completed.
        data class CallSite(val asstIdx: Int, val ids: Set<String>)
        val callSites = mutableListOf<CallSite>()
        for ((idx, msg) in history.withIndex()) {
            val ids = msg.toolCalls?.takeIf { it.isNotEmpty() }?.map { it.id }?.toSet()
            if (msg.role == "assistant" && !ids.isNullOrEmpty()) {
                callSites.add(CallSite(idx, ids))
            }
        }
        val badAssistantIdx = mutableSetOf<Int>()
        val orphanToolIdx = mutableSetOf<Int>()
        // For each call site, scan forward for matching tool replies
        // until we hit something that isn't a tool message — that's
        // the boundary of "this assistant's tool block".
        val claimedToolIdx = mutableSetOf<Int>()
        for (site in callSites) {
            val seen = mutableSetOf<String>()
            var i = site.asstIdx + 1
            while (i < history.size && history[i].role == "tool") {
                val tcid = history[i].toolCallId
                if (tcid != null && tcid in site.ids) {
                    seen += tcid
                    claimedToolIdx += i
                }
                i++
            }
            if (seen.size < site.ids.size) {
                // Missing at least one — assistant turn is dangling.
                badAssistantIdx += site.asstIdx
            }
        }
        // Pass 2: every `role:tool` whose index isn't claimed by ANY
        // call site is an orphan.
        for ((idx, msg) in history.withIndex()) {
            if (msg.role == "tool" && idx !in claimedToolIdx) {
                orphanToolIdx += idx
            }
        }

        if (badAssistantIdx.isEmpty() && orphanToolIdx.isEmpty()) {
            return history
        }
        // If a dangling assistant turn is dropped, its (partially-
        // answered) tool messages must also go — otherwise MiniMax
        // sees orphan tool messages with no preceding tool_calls.
        val dropToolForDroppedAsst = mutableSetOf<Int>()
        for (site in callSites) {
            if (site.asstIdx in badAssistantIdx) {
                var i = site.asstIdx + 1
                while (i < history.size && history[i].role == "tool") {
                    dropToolForDroppedAsst += i
                    i++
                }
            }
        }
        val toDrop = badAssistantIdx + orphanToolIdx + dropToolForDroppedAsst
        android.util.Log.w(
            TAG,
            "sanitiser dropping ${toDrop.size} message(s): " +
                "bad-asst=${badAssistantIdx.size} orphan-tool=${orphanToolIdx.size} " +
                "asst-cascade=${dropToolForDroppedAsst.size}",
        )
        return history.filterIndexed { idx, _ -> idx !in toDrop }
    }

    // ------------------------------------------------------------------
    //  Subagent runtime
    // ------------------------------------------------------------------

    /**
     * Final result of a subagent invocation. [text] is the final
     * assistant message; [iterations] is how many model-tool loops it
     * burned. [ok] is false when the subagent hit an error (missing API
     * key, network failure, mapped MiniMax code) — [text] then carries
     * the human-readable error.
     */
    data class SubagentResult(
        val ok: Boolean,
        val text: String,
        val iterations: Int = 0,
        val toolCalls: Int = 0,
    )

    /**
     * Run a self-contained sub-agent for the given task. The subagent:
     *  - starts with a fresh message context (no chat history)
     *  - uses the same MiniMax model + tool registry as the main agent
     *  - does NOT persist to Room history (the parent's turn does)
     *  - does NOT stream Turn events upward (subagent output is a single
     *    text result returned to the parent's tool channel)
     *
     * Spawned by [com.mythara.agent.tools.SpawnAgentTool]. The depth
     * guard via [AgentDepth] context element prevents a subagent from
     * recursively spawning forever — the spawn_agent tool checks
     * `currentDepth() >= MAX_DEPTH` and refuses.
     *
     * Why a separate path from [submit]: the parent loop emits Turn
     * deltas for streaming UI, persists each iteration to Room, and
     * threads mood + recall system messages. A subagent does none of
     * that — it's a "function call with side-effects" the parent
     * makes to crunch a focused task. Trying to share the same body
     * adds branching that obscures both flows.
     */
    suspend fun runSubagent(
        task: String,
        systemPrompt: String? = null,
        maxIterations: Int = SUBAGENT_MAX_ITERATIONS,
    ): SubagentResult {
        val snap = settings.snapshot()
        val apiKey = snap.apiKey
        if (apiKey.isNullOrBlank()) {
            return SubagentResult(ok = false, text = "missing api key", iterations = 0)
        }

        // Build the subagent's conversation: a focused system prompt
        // (parent-provided or default) followed by the task as a single
        // user turn. No mood, no recall — those are conversational
        // concerns; subagents are task-focused.
        val effectiveSystem = systemPrompt ?: DEFAULT_SUBAGENT_SYSTEM
        val messages = mutableListOf(
            ChatMessage(role = "system", content = effectiveSystem),
            ChatMessage(role = "user", content = task),
        )

        val client = MiniMaxClient(apiKey = apiKey, region = snap.region)
        val streaming = StreamingChat(client)

        var iter = 0
        var toolCallsExecuted = 0
        var finalText = ""

        while (iter < maxIterations) {
            iter++

            val req = ChatRequest(
                model = snap.model,
                messages = messages.toList(),
                tools = registry.apiSchema().takeIf { it.isNotEmpty() },
                toolChoice = if (registry.apiSchema().isNotEmpty()) "auto" else null,
                stream = true,
                reasoningSplit = false,
            )

            val streamedText = StringBuilder()
            var toolCalls: List<ToolCall> = emptyList()
            var finishReason: String? = null
            var failure: ErrorMapper.Mapped? = null

            streaming.stream(snap.region, req).collect { ev ->
                when (ev) {
                    is StreamingChat.StreamEvent.Text -> streamedText.append(ev.delta)
                    is StreamingChat.StreamEvent.ToolCallsReady -> toolCalls = ev.calls
                    is StreamingChat.StreamEvent.Done -> finishReason = ev.finishReason
                    is StreamingChat.StreamEvent.Failure -> failure = ev.mapped
                }
            }

            if (failure != null) {
                return SubagentResult(
                    ok = false,
                    text = "subagent failed: ${failure!!.message}",
                    iterations = iter,
                    toolCalls = toolCallsExecuted,
                )
            }

            val asstText = streamedText.toString()
            finalText = asstText

            // Append the assistant turn to the subagent's in-memory
            // history. Subagent messages never touch Room — they live
            // for the duration of this call only.
            messages.add(
                ChatMessage(
                    role = "assistant",
                    content = asstText.takeIf { it.isNotEmpty() },
                    toolCalls = toolCalls.takeIf { it.isNotEmpty() },
                ),
            )

            val toolFinish = finishReason == "tool_calls" || finishReason == "tool_use"
            if (toolCalls.isEmpty() || !toolFinish) {
                return SubagentResult(
                    ok = true,
                    text = Thinks.strip(finalText),
                    iterations = iter,
                    toolCalls = toolCallsExecuted,
                )
            }

            // Execute each tool call sequentially. Subagents share the
            // parent's tool registry, so they can read_screen, take_photo,
            // web_fetch, etc. The depth marker on the coroutine context
            // is what stops them from spawn_agent-ing recursively.
            // The subagent's task IS its user message for the purposes
            // of UserMessageContext — tools downstream see the task as
            // "what the user is asking about right now".
            for (call in toolCalls) {
                val result = kotlinx.coroutines.withContext(UserMessageContext(task)) {
                    registry.execute(call.function.name, call.function.arguments)
                }
                toolCallsExecuted++
                messages.add(
                    ChatMessage(
                        role = "tool",
                        content = result.output,
                        toolCallId = call.id,
                        name = call.function.name,
                    ),
                )
            }
        }

        return SubagentResult(
            ok = true,
            text = Thinks.strip(finalText) + " [hit subagent max iterations]",
            iterations = iter,
            toolCalls = toolCallsExecuted,
        )
    }

    /**
     * Build the temporal-anchor system-message body. Includes:
     *  - ISO-8601 local timestamp (machine-parseable)
     *  - day-of-week + time-of-day bucket (so the model picks up
     *    "Tuesday evening" framing without computing it)
     *  - timezone offset (so a request like "set a 9am alarm" maps
     *    to the user's local 9am, not UTC)
     *  - epoch millis (for any tool that needs to compute deltas)
     *
     * Regenerated every turn — never stale.
     */
    private fun buildTimeContext(): String {
        val now = java.time.ZonedDateTime.now()
        val isoLocal = now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val dayOfWeek = now.dayOfWeek.getDisplayName(
            java.time.format.TextStyle.FULL,
            java.util.Locale.getDefault(),
        )
        val timeOfDay = when (now.hour) {
            in 0..4 -> "late night"
            in 5..8 -> "early morning"
            in 9..11 -> "morning"
            in 12..13 -> "midday"
            in 14..17 -> "afternoon"
            in 18..20 -> "evening"
            in 21..23 -> "night"
            else -> "morning"
        }
        val tz = now.zone.id
        val epochMs = System.currentTimeMillis()
        val humanTime = now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
        val humanDate = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
        return buildString {
            append("CURRENT TIME (always-fresh, regenerated every turn — trust THIS over your training data):\n")
            append("- right now: $humanTime, $humanDate ($timeOfDay)\n")
            append("- ISO-8601 local: $isoLocal\n")
            append("- timezone: $tz\n")
            append("- epoch millis: $epochMs\n")
            append("Use this anchor for 'yesterday', 'tomorrow', 'last week', " +
                "'in 2 hours' etc. Do NOT call get_time unless the user explicitly asks for the time itself.")
        }
    }

    private fun encodeToolCalls(calls: List<ToolCall>): String =
        MiniMaxClient.json.encodeToString(ListSerializer(ToolCall.serializer()), calls)

    private fun decodeToolCalls(s: String): List<ToolCall>? =
        runCatching { MiniMaxClient.json.decodeFromString(ListSerializer(ToolCall.serializer()), s) }
            .getOrNull()

    companion object {
        private const val TAG = "Mythara/Agent"

        /**
         * Safety cap on tool-use iterations per user turn. 8 is generous
         * for genuine multi-step tasks (most stop after 1–3) but stops a
         * broken model from spinning forever on a malformed function call.
         */
        const val MAX_ITERATIONS = 8

        /**
         * Wire-format marker on the leading user-text line that flips the
         * agent into "notification triage" mode for this turn. Kept short
         * because it's part of the persisted chat history.
         */
        const val NOTIF_PREFIX = "[notif]"

        /** Sentinel the model returns when a notification isn't worth surfacing. */
        const val NOSURFACE_TOKEN = "NOSURFACE"

        /**
         * Hard cap on subagent loop iterations. Smaller than the main
         * agent's [MAX_ITERATIONS] because subagents are meant to be
         * focused single-task workers — if they're not done in 5
         * iterations the parent should refine the task.
         */
        const val SUBAGENT_MAX_ITERATIONS = 5

        /**
         * Subagent recursion ceiling. Main agent (depth 0) → subagent
         * (depth 1) is allowed; nested spawn refuses. Two levels of
         * delegation is plenty for v1; deeper would muddy attribution
         * + control flow.
         */
        const val SUBAGENT_MAX_DEPTH = 1

        /**
         * Default system prompt for unscoped subagent invocations.
         * Caller can override per-call when the task needs different
         * framing (e.g. "you are a research agent — gather facts and
         * cite sources").
         */
        const val DEFAULT_SUBAGENT_SYSTEM =
            "You are a focused sub-agent. The main assistant has delegated " +
                "a specific task to you. Use the available tools as needed, " +
                "then return a concise, well-structured result. Do not chat " +
                "with the user — your output goes back to the main assistant. " +
                "Stay on task; if the task is impossible with the tools you " +
                "have, return a brief explanation and stop."
    }
}

/**
 * Coroutine-context marker tracking how deeply nested the current
 * agent loop is. The main agent runs at depth 0; a subagent spawned
 * from it inherits depth 1 via [withContext]. The [SpawnAgentTool]
 * reads this on each call and refuses when at or above
 * [AgentLoop.SUBAGENT_MAX_DEPTH].
 *
 * Using a coroutine-context element instead of a static counter
 * keeps the depth correctly scoped across structured concurrency —
 * cancelling the parent unwinds the depth automatically; multiple
 * subagents running in parallel each see their own depth.
 */
class AgentDepth(val depth: Int) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentDepth>
}

/**
 * Coroutine-context marker carrying the verbatim user request that
 * kicked off the current agent turn. Tools that benefit from the
 * user's literal words (notably [com.mythara.agent.tools.TakePhotoTool]'s
 * vision pass) read this and weave it into their downstream prompt —
 * so a request like "what disease does this plant have?" gets passed
 * intact to Gemini/VL-01 alongside the captured image, instead of the
 * agent's potentially lossy paraphrase.
 *
 * Scoped to each [AgentLoop.submit] turn and each
 * [AgentLoop.runSubagent] task. Tools that don't read it stay
 * unaffected.
 */
class UserMessageContext(val text: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<UserMessageContext>
}
