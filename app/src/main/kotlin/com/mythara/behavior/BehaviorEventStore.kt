package com.mythara.behavior

import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single funnel for the **behaviour-learning substrate** that the
 * Auto-Resonance system (planned next round) reads from when deciding
 * when to intervene.
 *
 * All behaviour events become rows in the same [LearningVault] every
 * other observation lives in — the agent's daily-review hook and the
 * existing `SemanticRecall` already understand vault facets, so we
 * don't need a parallel storage system. What we DO need is a
 * canonical set of facet conventions so the agent can reliably query
 * "all reminder misses by reason in the last 7 days" or "the last
 * tone intervention I gave + what happened to HR after".
 *
 * Facet conventions (every event row has `kind:behavior-event` PLUS
 * one of these `behavior:*` facets describing the event type):
 *
 *   behavior:reminder-miss   user marked a reminder "missed" and
 *                            picked a reason (overbooked / slept /
 *                            working / forgot / other). Content =
 *                            JSON `{"taskId":"...","reason":"...",
 *                            "note":"<free text>"}`.
 *   behavior:user-feedback   periodic "how am I doing" prompt
 *                            response. Content = JSON
 *                            `{"prompt":"...","reply":"..."}`.
 *   behavior:tone-trigger    Auto-Resonance fired a tone session
 *                            because of an observation. Content =
 *                            JSON `{"reason":"...","protocol":"calm"
 *                            |"focus"|"wind-down","triggerHrBpm":N,
 *                            "triggerMood":"..."}`.
 *   behavior:tone-effect     observed change in HR / mood after a
 *                            tone session ended. Content = JSON
 *                            `{"sessionRef":"...","beforeHr":N,
 *                            "afterHr":N,"beforeMood":"...","afterMood":
 *                            "..."}`.
 *   behavior:daily-review    output of the daily-review agent loop
 *                            — patterns observed + interventions
 *                            scheduled. Content = JSON `{"date":
 *                            "YYYY-MM-DD","patterns":[...],
 *                            "interventions":[...]}`.
 *
 * The reason facet is also added separately (`reason:overbooked`,
 * `reason:slept`, etc.) so a vault filter on reason alone returns
 * matching events regardless of behaviour-event type.
 */
@Singleton
class BehaviorEventStore @Inject constructor(
    private val vault: LearningVault,
) {

    /** Reason taxonomy for "missed it" reminder cards. The agent
     *  receives the LABEL string when it queries vault rows; user-
     *  visible copy lives in [ReminderMissReason.label]. */
    enum class ReminderMissReason(val tag: String, val label: String) {
        Overbooked("overbooked", "I was overbooked"),
        Slept("slept", "I slept through it"),
        Working("working", "I was deep in work"),
        Forgot("forgot", "I just forgot"),
        NotRelevant("not-relevant", "It's not relevant anymore"),
        Other("other", "Other (free text)"),
    }

    /** User marked a reminder "missed" + picked a reason. The
     *  caller passes the optional free-text note (only set when
     *  reason == Other, but accepted on any reason in case the
     *  user wants to elaborate). */
    suspend fun recordReminderMiss(
        taskId: String,
        reason: ReminderMissReason,
        note: String? = null,
    ): Boolean {
        val payload = buildString {
            append("{\"taskId\":\"")
            append(jsonEscape(taskId))
            append("\",\"reason\":\"")
            append(reason.tag)
            append("\"")
            if (!note.isNullOrBlank()) {
                append(",\"note\":\"")
                append(jsonEscape(note.trim()))
                append("\"")
            }
            append("}")
        }
        return vault.add(
            content = payload,
            tier = Tier.Working,
            src = "behavior:reminder-miss",
            facets = listOf(
                FACET_KIND,
                "behavior:reminder-miss",
                "reason:${reason.tag}",
                "task:$taskId",
            ),
            conf = 1.0,
        )
    }

    /** Periodic "how am I doing" feedback prompt response. The
     *  prompt id uniquely identifies which question the user
     *  answered (so the daily review can correlate prompt → reply). */
    suspend fun recordFeedback(promptId: String, reply: String): Boolean {
        val payload = "{\"prompt\":\"${jsonEscape(promptId)}\",\"reply\":\"${jsonEscape(reply)}\"}"
        return vault.add(
            content = payload,
            tier = Tier.Working,
            src = "behavior:user-feedback",
            facets = listOf(FACET_KIND, "behavior:user-feedback", "prompt:$promptId"),
            conf = 1.0,
        )
    }

    /** Auto-Resonance fired an intervention. Logged BEFORE the
     *  audio plays so the post-session [recordToneEffect] row can
     *  reference this row's timestamp as the trigger anchor. */
    suspend fun recordToneTrigger(
        reason: String,
        protocol: String,
        triggerHrBpm: Int?,
        triggerMood: String?,
    ): Boolean {
        val payload = buildString {
            append("{\"reason\":\"")
            append(jsonEscape(reason))
            append("\",\"protocol\":\"")
            append(jsonEscape(protocol))
            append("\"")
            if (triggerHrBpm != null) append(",\"triggerHrBpm\":$triggerHrBpm")
            if (!triggerMood.isNullOrBlank()) {
                append(",\"triggerMood\":\"")
                append(jsonEscape(triggerMood))
                append("\"")
            }
            append("}")
        }
        return vault.add(
            content = payload,
            tier = Tier.Working,
            src = "behavior:tone-trigger",
            facets = listOf(
                FACET_KIND,
                "behavior:tone-trigger",
                "protocol:$protocol",
                "reason:$reason",
            ),
            conf = 1.0,
        )
    }

    /** Post-session observation — was the intervention helpful? */
    suspend fun recordToneEffect(
        sessionRef: String,
        beforeHr: Int?,
        afterHr: Int?,
        beforeMood: String?,
        afterMood: String?,
    ): Boolean {
        val payload = buildString {
            append("{\"sessionRef\":\"")
            append(jsonEscape(sessionRef))
            append("\"")
            if (beforeHr != null) append(",\"beforeHr\":$beforeHr")
            if (afterHr != null) append(",\"afterHr\":$afterHr")
            if (!beforeMood.isNullOrBlank()) append(",\"beforeMood\":\"${jsonEscape(beforeMood)}\"")
            if (!afterMood.isNullOrBlank()) append(",\"afterMood\":\"${jsonEscape(afterMood)}\"")
            append("}")
        }
        return vault.add(
            content = payload,
            tier = Tier.Working,
            src = "behavior:tone-effect",
            facets = listOf(FACET_KIND, "behavior:tone-effect", "session:$sessionRef"),
            conf = 1.0,
        )
    }

    /** Daily-review summary written by the (planned) daily-review
     *  agent loop. Captures patterns observed + interventions
     *  scheduled for the day ahead. Stored as a single Episodic
     *  row so it doesn't get pruned aggressively. */
    suspend fun recordDailyReview(date: String, body: String): Boolean {
        return vault.add(
            content = body,
            tier = Tier.Episodic,
            src = "behavior:daily-review",
            facets = listOf(FACET_KIND, "behavior:daily-review", "date:$date"),
            conf = 1.0,
        )
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    companion object {
        const val FACET_KIND = "kind:behavior-event"
    }
}
