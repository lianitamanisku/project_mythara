package com.mythara.agent

import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One-shot signal between consecutive chat turns: "the agent just
 * chained N automation tools — on the NEXT turn, tell the model to
 * proactively offer to save the procedure as a skill".
 *
 * Why a separate store instead of just trusting the model to remember
 * the system-prompt instruction: in practice, the model's "be brief"
 * directive dominates the SKILLS subsection, so the follow-up offer
 * rarely fires. Injecting a fresh, explicit, one-turn system message
 * is more reliable than nudging behaviour from a long static prompt.
 *
 * Lifecycle:
 *   1. AgentLoop's per-turn tool execution records each fired
 *      automation tool via [recordTool].
 *   2. When the turn completes, [maybeMarkForOffer] inspects the
 *      list; if ≥ MIN_CHAIN_LEN consecutive automation tools fired,
 *      it stashes them in [pendingOffer].
 *   3. The NEXT [AgentLoop] turn calls [consume] when building
 *      messages; if non-null it injects a system message instructing
 *      the model to offer save_skill.
 *   4. consume() clears the stash so the offer fires exactly once.
 *
 * Single-process, volatile — the offer doesn't survive an app
 * restart (and shouldn't; the user has moved on by then).
 */
@Singleton
class SkillSuggestionStore @Inject constructor(
    /** Cross-turn pattern memory. When the same chain shape has
     *  been seen [SkillPatternDetector.REPEAT_THRESHOLD] times in
     *  the last 30 days, we ALSO fire the offer even when this
     *  turn alone didn't cross the per-turn threshold. */
    private val patternDetector: SkillPatternDetector,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tool names that count as automation steps worth saving. Read-
     *  only / observation tools (get_time, read_screen, search_memory,
     *  etc.) are excluded — a skill is "do these side effects in
     *  sequence", not "look at this state". */
    private val automationTools: Set<String> = setOf(
        // UI driving
        "open_app", "tap", "swipe", "type_text", "press_back",
        // Messaging / calls
        "send_sms_direct", "send_whatsapp_direct", "place_call_direct",
        // Scheduling
        "set_alarm", "create_calendar_event", "create_task", "create_reminder",
        "schedule_agent_task",
        // Visual / device state
        "screenshot", "take_photo", "render_canvas", "update_canvas",
        "open_url", "generate_image",
        // System / shell
        "apply_cosmetic", "linux_vm", "run_shell",
        "write_file",
    )

    /** Tools fired in the current in-flight turn. Cleared by
     *  [maybeMarkForOffer] when the turn ends. */
    private val currentTurnTools = mutableListOf<String>()

    /** Single-slot stash for the next turn's offer prompt. */
    private val pendingOffer = AtomicReference<List<String>?>(null)

    /** Called from AgentLoop after every successful tool execute. */
    @Synchronized
    fun recordTool(name: String) {
        if (name in automationTools) {
            currentTurnTools += name
            Log.d(TAG, "recorded automation tool: $name (chain now ${currentTurnTools.size}: $currentTurnTools)")
        }
    }

    /** Called when a turn finishes. The skill-save offer fires when
     *  EITHER:
     *    1. This turn alone chained ≥ [MIN_CHAIN_LEN] automation
     *       tools (the original per-turn trigger), OR
     *    2. The same chain SHAPE has now been seen
     *       [SkillPatternDetector.REPEAT_THRESHOLD] times in the
     *       rolling 30-day window (the new cross-turn trigger).
     *
     *  The cross-turn check runs async on an IO scope so the turn-
     *  completion path isn't blocked on DataStore IO; if the
     *  pattern hit fires it sets [pendingOffer] for the NEXT next
     *  turn, which is the right cadence anyway. */
    @Synchronized
    fun maybeMarkForOffer() {
        val snapshot = currentTurnTools.toList()
        Log.d(
            TAG,
            "maybeMarkForOffer: chain=${snapshot.size} (${snapshot.joinToString()}) " +
                "threshold=$MIN_CHAIN_LEN",
        )
        // Per-turn trigger — same as before.
        if (snapshot.size >= MIN_CHAIN_LEN) {
            pendingOffer.set(snapshot)
            Log.i(TAG, "stashed skill-save offer for next turn (per-turn trigger): $snapshot")
        }
        currentTurnTools.clear()

        // Cross-turn trigger — record this chain shape into the
        // detector's rolling history and check whether the
        // accumulated repetitions cross the threshold. Async so
        // the turn-end path stays fast.
        if (snapshot.size >= SkillPatternDetector.MIN_INTERESTING_LEN) {
            ioScope.launch {
                runCatching {
                    val hit = patternDetector.recordChainAndCheck(snapshot)
                    if (hit != null && hit.shouldOffer && pendingOffer.get() == null) {
                        pendingOffer.set(hit.chainShape)
                        Log.i(
                            TAG,
                            "stashed skill-save offer (cross-turn pattern, seen " +
                                "${hit.totalRepeats}× in window): ${hit.chainShape}",
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "cross-turn pattern record failed: ${it.message}")
                }
            }
        }
    }

    /** Reset between turns regardless of whether the threshold was hit
     *  — protects against carryover when a turn errored mid-execution
     *  without calling [maybeMarkForOffer]. */
    @Synchronized
    fun resetCurrentTurn() {
        currentTurnTools.clear()
    }

    /** Pull and clear the pending offer. Returns null when nothing's
     *  queued. */
    fun consume(): List<String>? = pendingOffer.getAndSet(null)

    companion object {
        private const val TAG = "Mythara/SkillSuggest"

        /** Minimum automation-tool chain length to suggest a skill
         *  from a SINGLE turn. Raised back to 3 now that we also
         *  have the cross-turn detector — a 2-tool chain (e.g.
         *  "open Spotify, tap focus") doesn't need to fire the
         *  offer immediately, because if the user keeps doing it
         *  the SkillPatternDetector will catch the repetition over
         *  3 turns and fire the offer then with strong signal.
         *
         *  Net behaviour:
         *    1-tool turn  → never offer
         *    2-tool turn  → offer only after the SAME 2-tool shape
         *                    has appeared 3+ times in 30 days
         *    3+ tool turn → offer this turn (per-turn trigger) */
        const val MIN_CHAIN_LEN = 3
    }
}
