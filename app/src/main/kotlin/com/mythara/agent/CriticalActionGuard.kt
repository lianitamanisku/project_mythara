package com.mythara.agent

import android.util.Log
import com.mythara.data.RestrictedAppsStore
import com.mythara.services.PhoneControlAccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-cutting policy check for side-effect tool calls.
 *
 * Sits between the registry's tool dispatch and the actual tool
 * execution. For every potentially-destructive call we ask:
 *
 *   1. Does this target (open-app pkg or current foreground pkg) sit
 *      in the user's BLOCKED list? → refuse outright, no override.
 *   2. Does it sit in the CRITICAL list (or look like a known
 *      financial/checkout context based on heuristics)? → force the
 *      confirmation gate to pop, regardless of the user's global
 *      "always confirm" setting or any per-call allowlist.
 *   3. Otherwise → allow normal flow.
 *
 * Why this lives separately from the existing per-tool
 * `confirmationFor()` hook:
 *   - The user explicitly turned global confirmation OFF for normal
 *     autopilot flow (texts/calls/whatsapps fire silently). Those
 *     paths must NOT carry the friction of a popup.
 *   - But certain *categories* of side-effects need a popup even
 *     when global confirmation is off — that's a different policy
 *     dimension. Folding it into the existing flag would force the
 *     user to choose between "every tap pops a dialog" (too noisy)
 *     and "Uber rides fire silently" (too dangerous).
 *   - Putting the policy in the registry means every tool inherits
 *     it for free — no per-tool boilerplate, no risk of forgetting
 *     to wire it on a new tool.
 *
 * The guard ONLY applies to side-effect tools. Read tools
 * ([READ_TOOLS] below) pass through unconditionally — pulling
 * calendar/screen/contacts is safe regardless of what app is
 * foreground.
 */
@Singleton
class CriticalActionGuard @Inject constructor(
    private val restricted: RestrictedAppsStore,
) {
    sealed interface Decision {
        data object Allow : Decision
        data class Block(val reason: String, val pkg: String) : Decision
        data class RequireConfirm(val reason: String, val pkg: String) : Decision
    }

    /**
     * Inspect a pending tool call. Returns [Decision.Allow] for
     * read tools and side-effect tools targeting safe apps;
     * [Decision.Block] when the target package is in the user's
     * blocked list; [Decision.RequireConfirm] when the target is
     * in the critical list.
     *
     * Heuristic fallback: tools that drive the on-screen UI
     * ([tap], [swipe], [type_text]) check the current foreground
     * package via the accessibility service. open_app checks its
     * `pkg` arg directly.
     */
    suspend fun evaluate(toolName: String, args: JsonObject): Decision {
        // Read tools never trip the guard.
        if (toolName in READ_TOOLS) return Decision.Allow

        // Compute the target package depending on which tool this is.
        val targetPkg = when (toolName) {
            "open_app" -> (args["pkg"] as? JsonPrimitive)?.content?.trim().orEmpty()
            // For UI automation we look at whatever's currently on
            // screen. If accessibility isn't granted the tool itself
            // fails anyway — leave the decision to it.
            "tap", "swipe", "type_text" ->
                PhoneControlAccessibilityService.instance?.currentForegroundPackage().orEmpty()
            // Direct-send tools target a phone number, not a package.
            // The destination is the user's own pre-trusted contact;
            // they explicitly invoked the send. Default to allow —
            // the user can still add specific senders to per-favorite
            // gating later.
            else -> ""
        }

        if (targetPkg.isEmpty()) return Decision.Allow

        if (restricted.isBlocked(targetPkg)) {
            Log.w(TAG, "BLOCK $toolName → $targetPkg (banking/payment)")
            return Decision.Block(
                reason = "$targetPkg is on the user's blocked-apps list (banking / payment / wallet / brokerage). Mythara never automates these.",
                pkg = targetPkg,
            )
        }
        if (restricted.isCritical(targetPkg)) {
            Log.d(TAG, "CONFIRM $toolName → $targetPkg (critical-action)")
            return Decision.RequireConfirm(
                reason = "$targetPkg is on the critical-actions list (orders / transactions / travel). Mythara always asks before automating these.",
                pkg = targetPkg,
            )
        }
        return Decision.Allow
    }

    companion object {
        private const val TAG = "Mythara/Guard"

        /**
         * Tools that read state without producing side effects. Never
         * gated by the guard — the agent must be free to read calendar,
         * notifications, screen, contacts, location even when sitting
         * in a banking app's screen, because that information is what
         * lets the model give a useful answer ("you're in your bank
         * app, that's fine — I won't touch it, but here's your
         * balance question answered another way").
         */
        val READ_TOOLS: Set<String> = setOf(
            "get_time",
            "get_battery",
            "get_location",
            "read_screen",
            "screenshot_view",
            "read_notifications",
            "list_dismissed_notifications",
            "list_calendar_events",
            "read_contact",
            "list_apps",
            "web_fetch",
            "list_skills",
            "get_skill",
        )
    }
}
