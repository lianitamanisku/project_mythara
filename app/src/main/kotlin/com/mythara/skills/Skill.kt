package com.mythara.skills

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A self-defined procedure Lumi can replay to drive other apps.
 *
 * Skills are persistent JSON files stored in the LearningVault as
 * records with `kind:skill` facet — they sync to the user's GitHub
 * backup like every other vault entry, so a new device install
 * picks up everything the agent has learned.
 *
 * The model writes skills by emitting a JSON payload through the
 * `save_skill` tool; the runner executes them step-by-step using
 * the existing AccessibilityService primitives (tap by text /
 * content-description / id, swipe, type, wait, verify).
 *
 * Self-evolution loop:
 *  1. Agent recognises a multi-step task ("text mom on WhatsApp")
 *  2. Walks through manually using read_screen → tap → type → send
 *  3. On success, calls save_skill with the sequence as a Skill
 *  4. Next time, picks it up via list_skills and runs run_skill
 *     instead of redoing the discovery dance
 *  5. If a step fails, the failure is recorded inline in
 *     `failures`; agent reads them on next attempt and decides
 *     whether to refine the steps or give up
 */
@Serializable
data class Skill(
    /** Stable identifier — kebab-case (e.g. "whatsapp-send-message"). */
    val name: String,

    /** One-line natural-language description for the model's reasoning. */
    val description: String,

    /**
     * Named parameters the skill takes. Step text/ids that contain
     * `{paramName}` get substituted at runtime. e.g.
     * `params: ["contact", "message"]` lets the model invoke
     * `run_skill("whatsapp-send-message", {"contact":"Mom","message":"hi"})`.
     */
    val params: List<String> = emptyList(),

    /** Ordered list of steps to execute. */
    val steps: List<SkillStep>,

    /** Failure history — agents read this before re-running to learn from past mistakes. */
    val failures: List<SkillFailure> = emptyList(),

    /** Bumped on every refine; failures fixed in vN+1 stop being relevant. */
    val version: Int = 1,

    val createdMs: Long = System.currentTimeMillis(),
    val lastRunMs: Long? = null,
    val runCount: Int = 0,
    val successCount: Int = 0,
)

/**
 * One executable step in a [Skill]. Sealed with @SerialName so each
 * variant serialises with a clear "action" discriminator the model
 * can write directly without needing an enum lookup.
 *
 * Designed so the model writes steps that survive UI redesigns:
 *  - prefer `tap_text` and `tap_desc` over `tap` with pixel coords
 *  - use `verify_visible` to assert the screen state after each
 *    transition; the runner flags failures here for self-correction
 */
@Serializable
sealed class SkillStep {

    /** Open an app by package name. */
    @Serializable
    @SerialName("open_app")
    data class OpenApp(val pkg: String) : SkillStep()

    /** Pause the runner for `ms` milliseconds. */
    @Serializable
    @SerialName("wait")
    data class Wait(val ms: Long) : SkillStep()

    /**
     * Tap the first visible node whose text (case-insensitive substring)
     * matches. Param substitution supported via `{paramName}`.
     */
    @Serializable
    @SerialName("tap_text")
    data class TapText(val text: String) : SkillStep()

    /**
     * Tap the first visible node whose content description matches.
     * Useful for icons (send-arrow, kebab-menu) that have no
     * user-facing text.
     */
    @Serializable
    @SerialName("tap_desc")
    data class TapDesc(val desc: String) : SkillStep()

    /** Tap the first node with the given accessibility view-id resource name. */
    @Serializable
    @SerialName("tap_id")
    data class TapId(val id: String) : SkillStep()

    /**
     * Type into the currently focused editable. Uses
     * AccessibilityService.typeText. Param-substituted.
     */
    @Serializable
    @SerialName("type_text")
    data class TypeText(val text: String) : SkillStep()

    /** Swipe between two absolute pixel points. Fallback path; brittle. */
    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long = 300,
    ) : SkillStep()

    /** Tap an absolute pixel coordinate. Last resort; coords don't survive screen redesigns. */
    @Serializable
    @SerialName("tap")
    data class Tap(val x: Int, val y: Int) : SkillStep()

    /**
     * Assert text is visible somewhere on screen. If not, the step
     * fails — the runner records a failure with the actual screen
     * state for the agent to learn from.
     */
    @Serializable
    @SerialName("verify_visible")
    data class VerifyVisible(val text: String) : SkillStep()

    /**
     * Read the current screen and store its summary in the runner's
     * trace. Doesn't change state; useful as a checkpoint the agent
     * can inspect on failure.
     */
    @Serializable
    @SerialName("read_screen")
    data class ReadScreen(val label: String? = null) : SkillStep()
}

/**
 * One failure recorded against a skill. Surfaces to the agent on
 * subsequent runs so it can decide to refine the skill (bump
 * version + alter steps) rather than blindly retrying.
 */
@Serializable
data class SkillFailure(
    val tsMillis: Long,
    /** Index of the step that failed in skill.steps. */
    val stepIndex: Int,
    /** Short description of WHAT went wrong. */
    val reason: String,
    /** Optional: short screen-state summary captured at failure time. */
    val screenSummary: String? = null,
    /** Version of the skill when this failure happened. */
    val skillVersion: Int,
)

/**
 * Result of running a skill. Surfaced to the model as JSON in the
 * `run_skill` tool result so it can inspect what happened and
 * decide whether to retry, refine, or move on.
 */
@Serializable
data class SkillRunResult(
    val ok: Boolean,
    val stepsExecuted: Int,
    val totalSteps: Int,
    val failureAtStep: Int? = null,
    val failureReason: String? = null,
    val trace: List<String> = emptyList(),
)
