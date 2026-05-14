package com.mythara.agent.tools

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.analytics.ContactAnalyticsWorker
import com.mythara.growth.GrowthScheduler
import com.mythara.health.HealthLearningWorker
import com.mythara.health.HrCorrelationWorker
import com.mythara.memory.HeartbeatSyncer
import com.mythara.persona.PersonaWorker
import com.mythara.sensors.SensorLearningWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `trigger_action` — fire one of Mythara's internal maintenance /
 * analysis pipelines on demand instead of waiting for its periodic
 * cadence.
 *
 * These jobs normally run on WorkManager / heartbeat schedules (hourly
 * to daily). This tool lets the user say "sync now", "re-run the
 * personality analysis", "refresh what you know about my contacts" and
 * have the corresponding pipeline kick off immediately.
 *
 * Two trigger shapes:
 *  - **Immediate** (`sync`): [HeartbeatSyncer.fireNow] / [GrowthScheduler.fireNow]
 *    run in-process right away.
 *  - **Enqueued** (everything else): a one-shot [OneTimeWorkRequest] is
 *    queued with WorkManager under the worker's `_oneshot` unique name
 *    (REPLACE policy, so spamming the tool coalesces). The pipeline runs
 *    as soon as WorkManager schedules it — usually within seconds, but
 *    the tool returns before it finishes, so the result says "queued",
 *    not "done".
 */
@Singleton
class TriggerActionTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
    /** dagger.Lazy — HeartbeatSyncer transitively pulls in the agent stack. */
    private val heartbeat: dagger.Lazy<HeartbeatSyncer>,
    private val growthScheduler: GrowthScheduler,
) : Tool {

    override val name: String = "trigger_action"

    override val description: String =
        "Run one of Mythara's internal maintenance/analysis pipelines on demand instead of waiting for its " +
            "periodic schedule. Use when the user asks you to 'sync now', 're-run the personality analysis', " +
            "'refresh what you know about my contacts', 'take a health/sensor reading now', etc. " +
            "Valid `action` values: " +
            ACTIONS.joinToString("; ") { "'${it.key}' (${it.summary})" } + "."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "action",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { ACTIONS.forEach { add(JsonPrimitive(it.key)) } })
                        put(
                            "description",
                            "Which internal pipeline to trigger. One of: " +
                                ACTIONS.joinToString(", ") { it.key } + ".",
                        )
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return ToolResult(false, """{"error":"missing_action"}""")

        val action = ACTIONS.firstOrNull { it.key == raw || raw in it.aliases }
            ?: return ToolResult(
                false,
                buildJsonObject {
                    put("error", "unknown_action")
                    put("got", raw)
                    put("valid", buildJsonArray { ACTIONS.forEach { add(JsonPrimitive(it.key)) } })
                }.toString(),
            )

        val mode = runCatching { fire(action) }.getOrElse { e ->
            Log.w(TAG, "trigger '${action.key}' failed: ${e.message}")
            return ToolResult(
                false,
                buildJsonObject {
                    put("error", "trigger_failed")
                    put("action", action.key)
                    put("detail", e.message ?: e.javaClass.simpleName)
                }.toString(),
            )
        }

        Log.d(TAG, "triggered '${action.key}' ($mode)")
        return ToolResult(
            true,
            buildJsonObject {
                put("ok", true)
                put("action", action.key)
                put("mode", mode)
                put(
                    "detail",
                    when (mode) {
                        "ran" -> "${action.summary} — kicked off in-process now."
                        else -> "${action.summary} — queued; it runs in the background and finishes shortly."
                    },
                )
            }.toString(),
        )
    }

    /** Returns "ran" for immediate in-process work, "queued" for WorkManager jobs. */
    private fun fire(action: ActionSpec): String {
        when (action.key) {
            "sync" -> {
                heartbeat.get().fireNow()
                return "ran"
            }
            "growth" -> {
                growthScheduler.fireNow(kind = "agent-triggered")
                return "queued"
            }
            else -> {
                val req = action.workRequest()
                    ?: error("no work request for ${action.key}")
                WorkManager.getInstance(ctx).enqueueUniqueWork(
                    "${action.uniqueName}_oneshot",
                    ExistingWorkPolicy.REPLACE,
                    req,
                )
                return "queued"
            }
        }
    }

    private data class ActionSpec(
        val key: String,
        val summary: String,
        val aliases: Set<String> = emptySet(),
        /** WorkManager unique-work name to coalesce under. */
        val uniqueName: String = "",
        /** Builds the one-shot request, or null for the immediate actions. */
        val workRequest: () -> OneTimeWorkRequest? = { null },
    )

    companion object {
        private const val TAG = "Mythara/TriggerAction"

        private val ACTIONS: List<ActionSpec> = listOf(
            ActionSpec(
                key = "sync",
                summary = "cross-device memory + task sync",
                aliases = setOf("sync_now", "memory_sync"),
            ),
            ActionSpec(
                key = "contact_analytics",
                summary = "Gemma re-analysis of every contact profile",
                aliases = setOf("gemma_learnings", "contact_insights", "refresh_contacts"),
                uniqueName = ContactAnalyticsWorker.UNIQUE_PERIODIC,
                workRequest = { OneTimeWorkRequestBuilder<ContactAnalyticsWorker>().build() },
            ),
            ActionSpec(
                key = "personality_insights",
                summary = "rebuild persona traits from recent usage",
                aliases = setOf("persona", "personality", "persona_insights"),
                uniqueName = PersonaWorker.UNIQUE_PERIODIC,
                workRequest = { OneTimeWorkRequestBuilder<PersonaWorker>().build() },
            ),
            ActionSpec(
                key = "health_snapshot",
                summary = "Health Connect 24h snapshot into learning",
                aliases = setOf("health", "health_learning"),
                uniqueName = HealthLearningWorker.UNIQUE_NAME,
                workRequest = { OneTimeWorkRequestBuilder<HealthLearningWorker>().build() },
            ),
            ActionSpec(
                key = "hr_correlation",
                summary = "correlate heart-rate spikes with contact pings",
                aliases = setOf("heart_rate_correlation"),
                uniqueName = HrCorrelationWorker.UNIQUE_NAME,
                workRequest = { OneTimeWorkRequestBuilder<HrCorrelationWorker>().build() },
            ),
            ActionSpec(
                key = "sensor_snapshot",
                summary = "device-sensor snapshot into learning",
                aliases = setOf("sensors", "sensor_learning"),
                uniqueName = SensorLearningWorker.UNIQUE_NAME,
                workRequest = { OneTimeWorkRequestBuilder<SensorLearningWorker>().build() },
            ),
            ActionSpec(
                key = "growth",
                summary = "nightly self-organizing growth job",
                aliases = setOf("self_organize", "growth_job"),
            ),
        )
    }
}
