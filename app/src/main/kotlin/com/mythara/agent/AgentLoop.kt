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
    private val userNameStore: com.mythara.data.UserNameStore,
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

        // User name. When the user has told Mythara what to call
        // them ("What should I call you?" in Settings), inject a
        // one-liner so the model uses it sparingly — at greeting,
        // on acknowledgement, occasional callback — not every
        // sentence. Empty string skips the injection.
        val userName = runCatching { userNameStore.name() }.getOrDefault("")
        val nameSystem: ChatMessage? = if (userName.isNotBlank()) {
            ChatMessage(
                role = "system",
                content =
                    "The user's name is $userName. Use it naturally and sparingly — " +
                        "as a greeting (\"morning, $userName\"), acknowledgement " +
                        "(\"got it, $userName\"), or occasional callback. " +
                        "Do NOT sprinkle it through every sentence; that reads as " +
                        "sycophantic and overformal. One use per reply is plenty; " +
                        "zero is also fine.",
            )
        } else {
            null
        }

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
                "Reply like a friend texting, not an assistant generating a deliverable.\n\n" +
                    "WRITE PLAIN PROSE — NEVER MARKDOWN, NEVER LISTS, NEVER TABLES, NEVER ROBOT TEXT.\n" +
                    "Your output is going to be both shown in a chat bubble AND read aloud. Markdown breaks both: the user sees literal pipe characters and asterisks, the TTS reads 'pipe pipe column pipe pipe'.\n\n" +
                    "FORBIDDEN — never emit any of these:\n" +
                    "  • Tables: never `| col1 | col2 |` or any pipe-separated rows.\n" +
                    "  • Bullet points: never `• item` / `- item` / `* item`.\n" +
                    "  • Numbered lists: never `1. item / 2. item` style.\n" +
                    "  • Headers: never `# Title` / `## Subtitle`.\n" +
                    "  • Bold/italic markers: never `**bold**` or `_italic_`.\n" +
                    "  • Code fences: never ``` blocks. Even if asked for code, give a one-line description and ask if they want it pasted.\n" +
                    "  • Backtick inline code: never `text` style.\n" +
                    "  • Bare URLs: drop them unless the user asked specifically for the link.\n\n" +
                    "When a tool returns structured data (a list of events, a contact, a JSON response), TRANSLATE it into a single flowing sentence. Examples:\n" +
                    "  Bad: \"Your calendar:\\n- 9am Standup\\n- 12pm Lunch\\n- 4pm Dentist\"\n" +
                    "  Good: \"Three things today — standup at 9, lunch at noon, and dentist at 4.\"\n" +
                    "  Bad: \"| Name | Phone |\\n|------|-------|\\n| Mom | +1... |\"\n" +
                    "  Good: \"Mom is at plus-one four-one-five etc.\"\n\n" +
                    "OTHER RULES:\n" +
                    "(1) 1–2 short sentences, max ~40 words. Longer ONLY if the user explicitly asked for detail.\n" +
                    "(2) Conversational tone — no formal openers ('Sure!', 'Certainly!', 'I'd be happy to') or sign-offs ('Let me know if you need anything else'). Just answer.\n" +
                    "(3) Numbers + symbols spoken-out where natural ('5%' → 'five percent', '$10' → 'ten bucks').\n" +
                    "(4) Real conversation has variety — sometimes a one-liner, sometimes a question back, sometimes 'I don't know'. Match the mood (see emotional-context message).\n\n" +
                    "TOOL-USE RULES — read carefully, the user has been burned by violations:\n" +
                    "  • If a tool RETURNS the data the user asked for, DO NOT also call open_app to 'show' them the data. Just relay the data in your reply.\n" +
                    "  • Only call open_app, place_call, send_sms_direct, send_whatsapp, tap, swipe, type_text, or any other side-effect tool when the user EXPLICITLY asked for that action. 'list X' / 'show me X' / 'what's on X' = read-only, never launch the app. (EXCEPTION: if this turn is auto-reply or auto-triage mode — see system messages later — opening the messaging app to read conversation context IS the explicit ask, do it without hesitating.)\n" +
                    "  • Pushing the user out of Mythara mid-conversation is a UX failure. If you need to launch something for a chat-driven request, say so first and confirm intent on the next turn. (This caveat does NOT apply to auto-reply / auto-triage turns — those run in autopilot and never confirm.)\n\n" +
                    "SENDING / CALLING / WHATSAPP — these always go through directly, no confirmation, no preview, no draft.\n" +
                    "There is NO composer variant available. Do NOT pretend to call send_sms or send_whatsapp or place_call — those tools do not exist in your toolset. The ONLY message-sending tools are the direct variants below.\n" +
                    "Map user phrasing → tool:\n" +
                    "  • 'text mom <msg>' / 'tell mom <msg>' / 'sms mom' / 'message mom <msg>' → send_sms_direct (SmsManager API — fully silent).\n" +
                    "  • 'call dad' / 'phone dad' → place_call_direct (system in-call screen is unavoidable).\n" +
                    "  • 'whatsapp mom <msg>' / 'wa mom <msg>' / 'message mom on whatsapp' / 'tell mom on whatsapp' → send_whatsapp_direct (Accessibility auto-taps Send for the user, returns to Mythara).\n" +
                    "  • 'message mom' with no app specified → default to send_whatsapp_direct.\n\n" +
                    "DO NOT ASK FOR CONFIRMATION IN THE CHAT. The user has explicitly turned off all confirmation prompts. Never say 'should I send that?', 'want me to message her?', 'shall I go ahead?'. Just call the tool. Your job is to act, not to verify intent the user already expressed.\n\n" +
                    "BANKING / PAYMENT / WALLET / BROKERAGE APPS — these are HARD-BLOCKED by the runtime. NEVER attempt open_app, tap, swipe, or type_text on Chase, Wells Fargo, PayPal, Venmo, Cash App, Robinhood, Coinbase, Google Pay, PhonePe, Paytm, or anything similar. If the user asks you to do something inside a banking/payment app, say: 'I don't automate banking or payment apps — open it yourself.' Do NOT call the tool first to see what happens; the runtime will refuse and you'll waste a turn.\n\n" +
                    "ORDERS / RIDES / CHECKOUT — Uber, Lyft, DoorDash, Amazon, Flipkart, Expedia, Booking.com, Airbnb, Ticketmaster, Play Store in-app purchases. These are ALWAYS gated by a runtime confirmation popup regardless of any other autopilot setting. You CAN call open_app / tap / etc. on these — but a confirmation dialog WILL pop and the user has to allow it. Narrate the action ('booking your uber.') and call the tool; the popup is expected, don't apologise for it.\n\n" +
                    "CALENDAR — when the user accepts, confirms, or arranges a meeting / appointment / call ('let's meet at 3', 'sure 4pm works', 'I'll grab coffee with her tomorrow', 'book dentist at 10am Friday'), do BOTH of these without being asked:\n" +
                    "  (a) First call list_calendar_events for the relevant window to check for conflicts. If anything overlaps, NARRATE the conflict in 1 short sentence and then either propose the nearest free slot or proceed if the user already chose despite it.\n" +
                    "  (b) Then call create_calendar_event with the right title, start/end epoch-millis (resolve relative times against the temporal-anchor system message), location/description when known. ALWAYS create the event — the user said yes, they don't want to be asked again.\n" +
                    "When create_calendar_event returns, the result JSON includes calendarName + accountName + verified. If verified=true, confirm by saying which calendar it landed on ('added to your Google calendar'). If verified=false, say so — 'wrote it but it didn't show up — your calendar might not be syncing, check Calendar app'. Never claim success on verified=false.\n" +
                    "Default duration: 60 minutes when the user doesn't specify. Default reminder: don't set one — the user's calendar already has their preferred default.\n\n" +
                    "NARRATE WHAT YOU'RE ABOUT TO DO — when you decide to call a side-effect tool (send_sms_direct / place_call_direct / send_whatsapp_direct / open_app / tap / swipe / type_text / take_photo / create_calendar_event), emit a SHORT spoken-out-loud preface FIRST, then call the tool in the same turn. Examples:\n" +
                    "  • 'texting mom now.'  → then call send_sms_direct\n" +
                    "  • 'calling dad.'       → then call place_call_direct\n" +
                    "  • 'whatsapping her.'   → then call send_whatsapp_direct\n" +
                    "  • 'opening uber.'      → then call open_app\n" +
                    "  • 'taking a photo.'    → then call take_photo\n" +
                    "  • 'checking your calendar.' → then call list_calendar_events\n" +
                    "  • 'booking it in your calendar.' → then call create_calendar_event\n" +
                    "Keep it to 2–4 words. The user will hear this through TTS as you act — silence-then-result is jarring, narration-then-result feels like a human assistant in motion. For read-only tools (get_time, get_battery, read_screen, read_notifications, list_calendar_events, read_contact) you do NOT need to narrate — those are invisible plumbing. ONLY narrate side-effect actions.\n\n" +
                    "When a direct-send tool returns an error:\n" +
                    "  • accessibility_not_granted: say 'Need accessibility access for that — open Mythara's Accessibility setting and toggle it on.' Do not retry, do not silently use anything else.\n" +
                    "  • send_button_not_found: say 'Opened WhatsApp with the message ready. Tap Send — WhatsApp's UI must have changed.' Do not retry the tool.\n" +
                    "  • permission_denied (SMS / phone): say 'I need <SMS|phone> permission for that. Open Settings → Apps → Mythara → Permissions.'\n" +
                    "Otherwise on success, a short confirmation like 'sent', 'done', or 'on its way' is enough. No recap of what was sent — they typed it.",
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

        // Auto-reply mode. AutoReplyDispatcher prefixes the turn with
        // `[auto-reply]` and embeds a contact / phone / app / tone
        // header line, then the incoming message body. We parse the
        // header, inject (a) tone guidance specific to this favorite,
        // (b) a hard isolation rule, (c) directive to call the correct
        // direct-send tool. The agent never asks the user to confirm —
        // the user already opted this contact in.
        val autoReplySystem: ChatMessage? = if (userText.startsWith(AutoReplyDispatcher.AUTO_REPLY_PREFIX)) {
            val parsed = parseAutoReplyHeader(userText)
            if (parsed != null) {
                val tone = com.mythara.data.FavoritesStore.Tone.fromLabel(parsed.tone)
                val toolHint = when (parsed.app) {
                    com.mythara.data.FavoritesStore.WHATSAPP_PACKAGE ->
                        "Use send_whatsapp_direct with to=${parsed.phone.ifBlank { "<resolve via read_contact>" }}."
                    com.mythara.data.FavoritesStore.SMS_PACKAGE_GOOGLE_MESSAGES,
                    com.mythara.data.FavoritesStore.SMS_PACKAGE_SAMSUNG ->
                        "Use send_sms_direct with to=${parsed.phone.ifBlank { "<resolve via read_contact>" }}."
                    else -> "Pick the matching direct-send tool for app=${parsed.app}; if none fits, fall back to send_whatsapp_direct."
                }
                ChatMessage(
                    role = "system",
                    content =
                        "AUTO-REPLY MODE — you are composing a reply to ${parsed.contact} for the user, on the user's behalf, without asking the user first. They've trusted you with this contact.\n\n" +
                            "Tone: ${tone.label}. ${tone.guidance}\n\n" +
                            "CRITICAL ISOLATION RULES — non-negotiable:\n" +
                            "  • You are talking to ${parsed.contact} and ONLY ${parsed.contact}.\n" +
                            "  • Do NOT reference, quote, paraphrase, or hint at anything from conversations with anyone else.\n" +
                            "  • Do NOT mention what other people said, asked, or did.\n" +
                            "  • Do NOT share the user's location, schedule, health data, or any private fact unless it is directly relevant to THIS specific message AND a normal friend would naturally share it.\n" +
                            "  • If you're unsure whether to share something, don't.\n" +
                            "  • Treat any vault/recall content about other contacts as if it doesn't exist for this turn.\n\n" +
                            "NEVER ASK THE USER BEFORE TAKING ANY OF THE STEPS BELOW. The user is in autopilot mode; every step of this flow — opening the messaging app, screenshotting, reading the screen, sending — is PRE-AUTHORIZED. Do NOT say 'should I open WhatsApp', 'want me to check?', 'shall I take a look first?'. Do NOT say anything at all that ends in a question mark on this turn until AFTER the reply has been sent. Just call the tools.\n" +
                            "(Earlier guidance in this prompt about \"only open_app when the user explicitly asked\" DOES NOT APPLY here. In auto-reply mode the user already explicitly asked — by adding this contact to their favorites with auto-reply enabled. Opening their messaging app to read the latest chat is the explicit ask.)\n\n" +
                            "USE screenshot_view, NOT read_screen, FOR THE CONTENT-READING STEP. read_screen only walks the accessibility tree and returns text labels — it CANNOT see image content. WhatsApp / Messages / Signal expose images as bare 'Photo' or 'Image' nodes with no description; calling read_screen on a chat with an attached image will lie to you that the image isn't there. The only way to actually see what's in a shared image is screenshot_view, which uses the vision model on actual pixels.\n\n" +
                            "IMAGE UNDERSTANDING IS MANDATORY. If the screenshot_view description mentions ANY image / photo / sticker / GIF in the conversation — even one — you MUST actually understand what's in it before composing your reply. Read the description carefully. If the description is vague ('an image', 'a photo', 'looks like a picture'), call screenshot_view AGAIN with a focus parameter targeting the unclear image: focus=\"describe in detail the photo that just came in at the bottom of the chat — subject, scene, mood\". Repeat until you actually know what the image shows. Composing a reply without having read the image is a hard failure — the user will hear 'you sent a photo' and know Lumi never looked.\n\n" +
                            "What to do — IN THIS ORDER, every time:\n" +
                            "  1. ALWAYS read the conversation visually first. The notification body is one line; you need history + image content before you reply. Steps:\n" +
                            "       a. open_app on ${parsed.app} — lands you in the messaging app. For WhatsApp specifically the chat with the new message is almost always at the top of the chat list. Call open_app; do not preface with a question.\n" +
                            "       b. screenshot_view — call this AS YOUR FIRST READ STEP, before anything else. Returns a vision description of the screen. You'll see the conversation thread AND the content of any image / sticker / GIF visible inline. (DO NOT call read_screen here — read_screen will silently miss every image.)\n" +
                            "       c. If the screenshot_view description says the chat list is showing instead of the chat itself, AND the latest unread row is visible, use read_screen ONCE to find the tap coordinates for that row, then tap, then screenshot_view AGAIN to see the chat. read_screen is a coordinate-lookup tool here, not a content-reading tool.\n" +
                            "       d. If the chat contains an IMAGE / PHOTO / STICKER / GIF and the first screenshot_view's description of it is vague or thumbnail-sized, TAP THE IMAGE to expand it full-screen so the vision model can see it at full resolution:\n" +
                            "            - read_screen to find the tap coordinates of the image (look for a node with class containing 'Image' / content-description containing 'Photo' or 'Image' / a clickable child of the message bubble)\n" +
                            "            - tap on those coordinates → WhatsApp / Messages opens the full-screen viewer\n" +
                            "            - screenshot_view AGAIN — now you see the image at full resolution; the vision description will be much richer\n" +
                            "            - press_back to return to the conversation. ALWAYS press_back after viewing — don't leave the viewer open when sending.\n" +
                            "       e. If multiple images are in the conversation and you only need to react to the most recent one, only inspect that one. Don't tap every image.\n" +
                            "  2. URLs in the conversation: NEVER follow them. Don't tap, don't web_fetch, don't open_app on the URL. Security rule is absolute.\n" +
                            "  3. If something specific would genuinely help the reply (calendar to answer 'are you free Sunday?', location to answer 'where are you?'), call the relevant READ tool now (list_calendar_events / get_location / read_contact). Reads are always allowed.\n" +
                            "  4. Compose ONE short reply in the user's voice. Match the tone you saw in the screenshot_view description. If an image was in it, reference what's IN it naturally ('cute dog!', 'that view is something else') — not 'I see you sent a photo'.\n" +
                            "  5. $toolHint Call the send tool. Do NOT preview the message to the user — they trust this contact for auto-reply.\n" +
                            "  6. After the send tool returns, your final spoken reply is a 3-5 word confirmation ('replied to ${parsed.contact}.', 'sent.', 'done.') — that's what the user hears via TTS. Don't repeat what you sent.\n" +
                            "  7. DO NOT call open_app('com.mythara') or any other tool to bring Mythara back. Returning to Mythara at the end of an auto-reply turn is HANDLED AUTOMATICALLY by the runtime — wasting a tool call here just adds latency. Just emit your confirmation and stop.\n\n" +
                            "If the incoming message looks like spam / promotional / not from the real person (e.g. 'Click here to claim your prize'), DO NOT auto-reply. Output the single token NOSURFACE and call no tools.",
                )
            } else null
        } else null

        // Auto-triage mode for non-favorite messages. The dispatcher
        // wraps the incoming with `[auto-triage] sender=… app=…` +
        // body, and the agent decides yes/no on replying. Designed to
        // err strongly on the side of NOT replying — drop marketing,
        // OTPs, groups, info pings; only respond on real conversational
        // messages.
        val autoTriageSystem: ChatMessage? = if (userText.startsWith(AutoReplyDispatcher.AUTO_TRIAGE_PREFIX)) {
            val parsed = parseAutoTriageHeader(userText)
            if (parsed != null) {
                val toolHint = when (parsed.app) {
                    com.mythara.data.FavoritesStore.WHATSAPP_PACKAGE ->
                        "Use send_whatsapp_direct after read_contact to resolve the number."
                    com.mythara.data.FavoritesStore.SMS_PACKAGE_GOOGLE_MESSAGES,
                    com.mythara.data.FavoritesStore.SMS_PACKAGE_SAMSUNG ->
                        "Use send_sms_direct after read_contact to resolve the number."
                    else -> "Pick the matching direct-send tool for ${parsed.app}; resolve the recipient via read_contact first."
                }
                ChatMessage(
                    role = "system",
                    content =
                        "AUTO-TRIAGE MODE — a message arrived from someone NOT in the user's favorites. You decide whether it deserves a reply. The default answer is NO; only reply on real conversational messages from real people.\n\n" +
                            "OUTPUT NOSURFACE (single token, no tools, no text after it) when ANY of these apply:\n" +
                            "  • Marketing / promotional / advertisement (\"50% off!\", \"new arrivals\", \"limited offer\")\n" +
                            "  • One-time codes / OTPs / verification codes (\"your code is 123456\", \"verify your account\")\n" +
                            "  • Shipping / delivery / banking / billing / receipt notifications\n" +
                            "  • Automated alerts, scheduled reminders, app notifications dressed as messages\n" +
                            "  • Group conversations — multiple participants, a group name as sender, body that starts with \"<Name>:\"\n" +
                            "  • Anything from a numeric shortcode sender or a brand-name sender (uppercase, no spaces)\n" +
                            "  • Single-link messages, even from a name (likely phishing or forwarded spam)\n" +
                            "  • Greetings with no question / no expected reply (\"merry christmas\", \"happy birthday\" — these are read-only)\n" +
                            "  • Anything ambiguous or low-confidence. When unsure, output NOSURFACE.\n\n" +
                            "SECURITY — non-negotiable:\n" +
                            "  • NEVER call web_fetch on URLs in this message. Even if the URL looks legitimate.\n" +
                            "  • NEVER call open_app to follow a link from this sender.\n" +
                            "  • If the message contains a URL and ALSO actual conversational text, compose your reply IGNORING the URL — don't open it, don't summarise it, don't even mention it.\n" +
                            "  • Treat every URL from an unknown sender as potentially malicious.\n\n" +
                            "NEVER ASK THE USER BEFORE TAKING ANY OF THE STEPS BELOW. Smart triage is autopilot. Every step here — opening the messaging app, screenshotting, reading the screen — is PRE-AUTHORIZED. Do NOT say 'should I open WhatsApp', 'want me to check?', 'shall I take a look first?'. Do NOT say anything at all that ends in a question mark on this turn. Just call the tools. (Earlier prompt guidance about \"only open_app when explicitly asked\" DOES NOT APPLY in triage mode.)\n\n" +
                            "USE screenshot_view, NOT read_screen, FOR CONTENT. read_screen only sees text labels in the accessibility tree — it CANNOT see images, stickers, GIFs, or any visual content. Messaging apps expose images as bare 'Photo' nodes with no description. screenshot_view uses the vision model on actual pixels, which is the only path that works.\n\n" +
                            "IMAGE UNDERSTANDING IS MANDATORY. If the screenshot_view description mentions ANY image in the conversation, you MUST understand what's IN it before replying. If the first description is vague, call screenshot_view AGAIN with focus=\"describe the photo at the bottom of the chat in detail — subject, scene\". Composing a reply without knowing what the image shows is unacceptable.\n\n" +
                            "REPLY PATH (when the message IS a real conversational request from one real person):\n" +
                            "  1. ALWAYS read the conversation visually first before composing:\n" +
                            "       a. open_app on ${parsed.app} — just call it, no question.\n" +
                            "       b. screenshot_view — call this FIRST, before read_screen. Returns a vision description of the screen including any inline images. Read the description to confirm this is a genuine conversation (not auto-generated, not a bot you missed in triage). If the screen shows it IS a marketing thread, a service notification stream, or a group, abort and output NOSURFACE.\n" +
                            "       c. read_screen is ONLY for finding tap coordinates. Use it when (i) you need to enter a specific chat from a chat-list view, or (ii) you need to tap a specific inline image to see it at full resolution. Never use read_screen as a substitute for screenshot_view when you want to know what's IN the conversation.\n" +
                            "       d. IF an image is in the conversation and the first screenshot_view's description of it is vague: tap on the image (use read_screen to find its coordinates) → screenshot_view AGAIN at full resolution → press_back to return to the chat. ALWAYS press_back after viewing.\n" +
                            "       e. DO NOT scroll beyond the visible window. DO NOT follow any URL — the URL-safety rule above is absolute.\n" +
                            "  2. MIRROR THE SENDER'S TONE based on what you saw in the screen, not just the notification body. Cadence, register, level of formality. Lowercase casual → lowercase casual. Complete formal sentence → matched formal reply. No imposed personality.\n" +
                            "  3. Keep it short — 1 sentence usually, 2 at most. Auto-replies are not monologues.\n" +
                            "  4. If a read tool would genuinely help (calendar / location / contact lookup), call it. Reads are always safe.\n" +
                            "  5. $toolHint Call exactly one direct-send tool. Don't preview the message to the user. If an image was visible, react to what's IN it ('cute pup!', 'great shot') — not 'I see you sent a photo'.\n" +
                            "  6. After the tool returns, emit a 3-5 word confirmation only (\"replied.\", \"sent.\").\n" +
                            "  7. DO NOT call open_app('com.mythara') yourself. Returning to Mythara at the end of a triage turn is HANDLED AUTOMATICALLY by the runtime. Just emit the confirmation and stop.\n\n" +
                            "CRITICAL ISOLATION RULES — same as favorite auto-reply:\n" +
                            "  • You are talking to ${parsed.sender} and ONLY ${parsed.sender}. Do not reference anything from conversations with anyone else.\n" +
                            "  • Do not share the user's location / schedule / health / private details unless they're directly relevant AND a normal friend would naturally share them in this exchange.\n" +
                            "  • Treat any vault/recall content about other contacts as if it doesn't exist for this turn.\n" +
                            "  • When in doubt, NOSURFACE.",
                )
            } else null
        } else null

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
                        "Do not call tools for this turn unless the notification is unclear and a quick read_screen would resolve it. " +
                        "NEVER ask the user 'do you want me to respond' or 'should I reply' — surface the notification only. " +
                        "If they want action, they'll ask in a follow-up turn.",
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
                if (nameSystem != null) add(nameSystem) // user identity (sparingly used)
                add(timeSystem)  // ALWAYS — current time/date/day-of-week/timezone
                add(voiceSystem) // ALWAYS — conversational style default
                if (ttsSystem != null) add(ttsSystem)
                if (moodSystem != null) add(moodSystem)
                if (autoReplySystem != null) add(autoReplySystem)
                if (autoTriageSystem != null) add(autoTriageSystem)
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
            // Auto-reply turns may have left the user on WhatsApp / Messages
            // mid-screenshot; we always want to land them back on Mythara
            // before the turn ends. The flag is also used below to mark
            // tool-execution context with the AutoReplyMarker for prefix
            // injection. Same condition either way — hoist it once.
            val isAutoReplyTurn = userText.startsWith(AutoReplyDispatcher.AUTO_REPLY_PREFIX) ||
                userText.startsWith(AutoReplyDispatcher.AUTO_TRIAGE_PREFIX)
            if (toolCalls.isEmpty() || !toolFinish) {
                if (isAutoReplyTurn) returnToMythara()
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
                // Compose the coroutine context: always carry the user
                // message; additionally mark auto-reply turns so the
                // registry knows whether to apply the configured prefix.
                val toolContext: kotlin.coroutines.CoroutineContext =
                    if (isAutoReplyTurn) UserMessageContext(userText) + AutoReplyMarker()
                    else UserMessageContext(userText)
                val result = kotlinx.coroutines.withContext(toolContext) {
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
        if (userText.startsWith(AutoReplyDispatcher.AUTO_REPLY_PREFIX) ||
            userText.startsWith(AutoReplyDispatcher.AUTO_TRIAGE_PREFIX)
        ) returnToMythara()
        emit(Turn.Finished(lastAssistantText + " [hit max iterations]", iterations = iter, userMoodTrend = effectiveMood))
    }

    /**
     * Best-effort "bring Mythara back to foreground" call invoked at
     * the end of every auto-reply / auto-triage turn. The agent may
     * have opened WhatsApp / Messages for screen reading; without
     * this the user is left staring at the other app after the
     * autopilot action completes. send_whatsapp_direct already calls
     * this internally on success — calling it again is a harmless
     * no-op (the system reuses the existing task at the top).
     *
     * Skipped silently when accessibility isn't granted: the user
     * just stays where they are. No crash, no error to the model.
     */
    private fun returnToMythara() {
        runCatching {
            com.mythara.services.PhoneControlAccessibilityService.instance?.bringMytharaToFront()
        }
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
        val timeOfDay = when (now.hour) {
            in 0..4 -> "the middle of the night"
            in 5..8 -> "early morning"
            in 9..11 -> "mid-morning"
            in 12..13 -> "midday"
            in 14..17 -> "afternoon"
            in 18..20 -> "evening"
            in 21..23 -> "late evening"
            else -> "morning"
        }
        val tz = now.zone.id
        val epochMs = System.currentTimeMillis()
        val humanTime = now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
        val humanDate = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
        return buildString {
            append("RIGHT NOW for the user: it's $humanTime on $humanDate — $timeOfDay. ")
            append("Timezone $tz. Epoch ms $epochMs. ISO $isoLocal.\n\n")
            append(
                "Use this naturally in conversation. Say 'this morning', 'tonight', 'tomorrow', " +
                    "'in an hour', 'a couple of weeks from now' when relevant. " +
                    "If the user asks 'what day is it' / 'what time is it', you already know — " +
                    "answer in one sentence, don't call get_time. " +
                    "If the user references time-relative things ('the dentist tomorrow', " +
                    "'last Tuesday', 'next week'), resolve against THIS moment, not your training " +
                    "cutoff. Don't open every reply with the time — weave it in only when it " +
                    "makes the answer feel grounded ('you've got ~3 hours before that meeting'). " +
                    "On a fresh greeting, the time-of-day window is fair to acknowledge " +
                    "(\"morning\" greeting before 11am, etc.) — but don't force it.",
            )
        }
    }

    /**
     * Parse the auto-reply header line produced by
     * [AutoReplyDispatcher]. Shape:
     *   `[auto-reply] contact=Name phone=DIGITS app=PKG tone=LABEL\nincoming: …`
     * Returns null if the format is malformed (in which case we fall
     * back to normal turn handling).
     */
    private data class AutoReplyHeader(
        val contact: String,
        val phone: String,
        val app: String,
        val tone: String,
    )

    /**
     * Parse the auto-triage header line produced by
     * [AutoReplyDispatcher]. Shape:
     *   `[auto-triage] sender=NAME app=PKG\nincoming: …`
     * Returns null on malformed input (the agent then falls back to
     * the normal turn flow, which is the right fail-safe).
     */
    private data class AutoTriageHeader(
        val sender: String,
        val app: String,
    )

    private fun parseAutoTriageHeader(userText: String): AutoTriageHeader? {
        val firstLine = userText.lineSequence().firstOrNull() ?: return null
        if (!firstLine.startsWith(AutoReplyDispatcher.AUTO_TRIAGE_PREFIX)) return null
        val rest = firstLine.removePrefix(AutoReplyDispatcher.AUTO_TRIAGE_PREFIX).trim()
        val tokens = rest.split(' ').mapNotNull { tok ->
            val eq = tok.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            tok.substring(0, eq) to tok.substring(eq + 1).replace('_', ' ')
        }.toMap()
        val sender = tokens["sender"]?.takeIf { it.isNotBlank() } ?: return null
        val app = tokens["app"].orEmpty()
        return AutoTriageHeader(sender = sender, app = app)
    }

    private fun parseAutoReplyHeader(userText: String): AutoReplyHeader? {
        val firstLine = userText.lineSequence().firstOrNull() ?: return null
        if (!firstLine.startsWith(AutoReplyDispatcher.AUTO_REPLY_PREFIX)) return null
        val rest = firstLine.removePrefix(AutoReplyDispatcher.AUTO_REPLY_PREFIX).trim()
        val tokens = rest.split(' ').mapNotNull { tok ->
            val eq = tok.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            tok.substring(0, eq) to tok.substring(eq + 1).replace('_', ' ')
        }.toMap()
        val contact = tokens["contact"]?.takeIf { it.isNotBlank() } ?: return null
        val phone = tokens["phone"].orEmpty()
        val app = tokens["app"].orEmpty()
        val tone = tokens["tone"].orEmpty()
        return AutoReplyHeader(contact = contact, phone = phone, app = app, tone = tone)
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

/**
 * Marker element set by [AgentLoop.submit] whenever the current turn
 * is an auto-reply (user text starts with
 * [AutoReplyDispatcher.AUTO_REPLY_PREFIX]). Read by
 * [com.mythara.agent.ToolRegistry] at execute time to decide whether
 * the configured auto-reply prefix should be prepended to outgoing
 * messages — only auto-reply turns mark messages as agent-originated;
 * explicit user-driven sends go through verbatim.
 */
class AutoReplyMarker : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AutoReplyMarker>
}
