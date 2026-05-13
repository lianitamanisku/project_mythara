package com.mythara.skills

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mythara.services.PhoneControlAccessibilityService
import com.mythara.services.ScreenReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes a [Skill] step-by-step. Uses the existing
 * [PhoneControlAccessibilityService] for gestures + node lookups
 * and [ScreenReader] for verify/read steps.
 *
 * Each step is paramaterised: text/desc/type-text fields can
 * contain `{paramName}` markers that get substituted from the
 * `params` map passed to [run]. Substitution is dumb string
 * replace — fine for v1 since values are normally short
 * proper-noun-ish strings.
 *
 * Failure handling:
 *  - On step failure, [SkillFailure] is appended to the skill
 *    (versioned, with the failing step index, reason, and a
 *    short screen-state summary).
 *  - The runner returns a [SkillRunResult] with `ok=false`,
 *    `failureAtStep=N`, and a trace the agent can inspect.
 *  - The agent's next move is to either refine the skill (bump
 *    version + alter steps) or surface the failure to the user.
 *
 * Read-time substitution preserves `{name}` literally if the
 * param wasn't supplied — surfaces in the failure log as "tap_text
 * '{contact}' — no node matched" which is a clear signal the
 * caller forgot to pass `contact`.
 */
@Singleton
class SkillRunner @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: SkillStore,
    private val screenReader: ScreenReader,
) {

    /**
     * Execute `skill` with `params`. Persists the run's outcome
     * back into the vault (run-count + failures inline on the
     * Skill record) so the agent learns from past runs.
     */
    suspend fun run(skill: Skill, params: Map<String, String>): SkillRunResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return SkillRunResult(
                ok = false,
                stepsExecuted = 0,
                totalSteps = skill.steps.size,
                failureReason = "accessibility_not_granted — enable Mythara in Settings → Accessibility",
            )

        val trace = mutableListOf<String>()
        var failedAt: Int? = null
        var failedReason: String? = null

        for ((index, step) in skill.steps.withIndex()) {
            val ok = runCatching { executeStep(service, step, params, trace) }
                .getOrElse { e ->
                    failedReason = "step ${index} threw: ${e.message ?: e.javaClass.simpleName}"
                    Log.w(TAG, "step $index threw", e)
                    false
                }
            if (!ok) {
                failedAt = index
                if (failedReason == null) {
                    failedReason = "step $index (${stepName(step)}) failed"
                }
                break
            }
        }

        val now = System.currentTimeMillis()
        val finalSkill = if (failedAt == null) {
            skill.copy(
                lastRunMs = now,
                runCount = skill.runCount + 1,
                successCount = skill.successCount + 1,
            )
        } else {
            val screenSummary = runCatching {
                service.currentRootNode()?.let { root ->
                    val snap = screenReader.snapshot(root)
                    runCatching { root.recycle() }
                    snap?.let { screenReader.render(it).take(MAX_SCREEN_SUMMARY) }
                }
            }.getOrNull()
            val failure = SkillFailure(
                tsMillis = now,
                stepIndex = failedAt,
                reason = failedReason ?: "unknown",
                screenSummary = screenSummary,
                skillVersion = skill.version,
            )
            skill.copy(
                lastRunMs = now,
                runCount = skill.runCount + 1,
                failures = (skill.failures + failure).takeLast(MAX_FAILURE_HISTORY),
            )
        }
        // Persist outcome — agent reads failures on the next run.
        runCatching { store.save(finalSkill) }

        return SkillRunResult(
            ok = failedAt == null,
            stepsExecuted = failedAt ?: skill.steps.size,
            totalSteps = skill.steps.size,
            failureAtStep = failedAt,
            failureReason = failedReason,
            trace = trace.toList(),
        )
    }

    /**
     * Resolve `{paramName}` placeholders against [params]. Missing
     * placeholders pass through unchanged so failure messages
     * surface them clearly.
     */
    private fun substitute(template: String, params: Map<String, String>): String {
        if (!template.contains('{')) return template
        var out = template
        for ((k, v) in params) {
            out = out.replace("{$k}", v)
        }
        return out
    }

    private suspend fun executeStep(
        service: PhoneControlAccessibilityService,
        step: SkillStep,
        params: Map<String, String>,
        trace: MutableList<String>,
    ): Boolean = when (step) {
        is SkillStep.OpenApp -> {
            val pkg = substitute(step.pkg, params)
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                trace.add("open_app($pkg) → not_found")
                false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(intent) }
                    .onSuccess { trace.add("open_app($pkg) → ok") }
                    .onFailure { trace.add("open_app($pkg) → ${it.message}") }
                    .isSuccess
            }
        }
        is SkillStep.Wait -> {
            delay(step.ms.coerceIn(0L, MAX_WAIT_MS))
            trace.add("wait(${step.ms}ms) → ok")
            true
        }
        is SkillStep.TapText -> {
            val needle = substitute(step.text, params)
            val ok = service.tapNodeWithText(needle)
            trace.add("tap_text('$needle') → ${if (ok) "ok" else "no_match"}")
            ok
        }
        is SkillStep.TapDesc -> {
            val needle = substitute(step.desc, params)
            val ok = service.tapNodeWithDesc(needle)
            trace.add("tap_desc('$needle') → ${if (ok) "ok" else "no_match"}")
            ok
        }
        is SkillStep.TapId -> {
            val ok = service.tapNodeWithId(step.id)
            trace.add("tap_id('${step.id}') → ${if (ok) "ok" else "no_match"}")
            ok
        }
        is SkillStep.TypeText -> {
            val text = substitute(step.text, params)
            val ok = service.typeText(text)
            trace.add("type_text(len=${text.length}) → ${if (ok) "ok" else "no_focus"}")
            ok
        }
        is SkillStep.Swipe -> {
            val ok = service.swipe(
                step.x1.toFloat(), step.y1.toFloat(),
                step.x2.toFloat(), step.y2.toFloat(),
                step.durationMs,
            )
            trace.add("swipe(${step.x1},${step.y1}→${step.x2},${step.y2}) → ${if (ok) "ok" else "gesture_failed"}")
            ok
        }
        is SkillStep.Tap -> {
            val ok = service.tap(step.x.toFloat(), step.y.toFloat())
            trace.add("tap(${step.x},${step.y}) → ${if (ok) "ok" else "gesture_failed"}")
            ok
        }
        is SkillStep.VerifyVisible -> {
            val needle = substitute(step.text, params)
            val visible = service.isTextVisible(needle)
            trace.add("verify_visible('$needle') → ${if (visible) "ok" else "not_visible"}")
            visible
        }
        is SkillStep.ReadScreen -> {
            val label = step.label ?: "snapshot"
            val root = service.currentRootNode()
            val snap = root?.let { screenReader.snapshot(it) }
            runCatching { root?.recycle() }
            if (snap != null) {
                trace.add("read_screen($label) → ok (${screenReader.render(snap).length} bytes)")
                true
            } else {
                trace.add("read_screen($label) → no_window")
                false
            }
        }
    }

    private fun stepName(step: SkillStep): String = when (step) {
        is SkillStep.OpenApp -> "open_app"
        is SkillStep.Wait -> "wait"
        is SkillStep.TapText -> "tap_text"
        is SkillStep.TapDesc -> "tap_desc"
        is SkillStep.TapId -> "tap_id"
        is SkillStep.TypeText -> "type_text"
        is SkillStep.Swipe -> "swipe"
        is SkillStep.Tap -> "tap"
        is SkillStep.VerifyVisible -> "verify_visible"
        is SkillStep.ReadScreen -> "read_screen"
    }

    companion object {
        private const val TAG = "Mythara/SkillRun"
        /** Cap on wait durations — stops a malformed skill from sleeping for hours. */
        private const val MAX_WAIT_MS = 30_000L
        /** Number of historical failures kept inline on a Skill. */
        private const val MAX_FAILURE_HISTORY = 10
        /** Cap on the screen-state summary stashed with a failure. */
        private const val MAX_SCREEN_SUMMARY = 1500
    }
}
