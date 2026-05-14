package com.mythara.reminders

import java.util.Calendar

/**
 * Compact recurrence spec for repeating reminders/tasks, stored as a
 * single string on `TaskEntity.recurrence`. Three forms:
 *
 *   DAILY:HH:mm                e.g. "DAILY:09:00"        — every day at 09:00
 *   WEEKLY:MON,WED,FRI:HH:mm   e.g. "WEEKLY:MON,FRI:18:30"
 *   EVERY:<n>m|h|d             e.g. "EVERY:30m", "EVERY:2h", "EVERY:1d"
 *
 * [nextAfter] computes the next fire strictly after a given instant.
 * DAILY/WEEKLY resolve in the device's local time zone, so they honour
 * wall-clock + DST; EVERY is a pure interval off the previous fire.
 *
 * Used by [ReminderAlarmReceiver] (re-arm the next occurrence when a
 * recurring task fires) and `CreateReminderTool` (let the agent set
 * recurring reminders).
 */
sealed interface Recurrence {

    /** Next fire strictly after [afterMs] (epoch millis, local TZ). */
    fun nextAfter(afterMs: Long): Long

    /** Human one-liner for the agent's confirmation + the UI. */
    fun describe(): String

    /** Canonical string form — round-trips through [parse]. */
    fun encode(): String

    data class Daily(val hour: Int, val minute: Int) : Recurrence {
        override fun nextAfter(afterMs: Long): Long {
            val c = atTime(afterMs, hour, minute)
            if (c.timeInMillis <= afterMs) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }

        override fun describe() = "every day at ${hhmm(hour, minute)}"
        override fun encode() = "DAILY:${pad(hour)}:${pad(minute)}"
    }

    /** [days] holds Calendar.MONDAY..Calendar.SUNDAY constants. */
    data class Weekly(val days: Set<Int>, val hour: Int, val minute: Int) : Recurrence {
        override fun nextAfter(afterMs: Long): Long {
            for (i in 0..7) {
                val c = atTime(afterMs, hour, minute)
                c.add(Calendar.DAY_OF_YEAR, i)
                if (c.timeInMillis > afterMs && c.get(Calendar.DAY_OF_WEEK) in days) {
                    return c.timeInMillis
                }
            }
            // Unreachable for a non-empty day set, but stay total.
            return afterMs + 7L * 86_400_000
        }

        override fun describe() =
            "every ${days.sorted().joinToString(", ") { DOW_NAME[it] ?: "?" }} at ${hhmm(hour, minute)}"

        override fun encode() =
            "WEEKLY:${days.sorted().joinToString(",") { DOW_TOKEN[it] ?: "" }}:${pad(hour)}:${pad(minute)}"
    }

    data class Interval(val everyMs: Long) : Recurrence {
        override fun nextAfter(afterMs: Long) = afterMs + everyMs

        override fun describe(): String {
            val min = everyMs / 60_000
            return when {
                min % 1440 == 0L -> "every ${min / 1440} day(s)"
                min % 60 == 0L -> "every ${min / 60} hour(s)"
                else -> "every $min minute(s)"
            }
        }

        override fun encode(): String {
            val min = everyMs / 60_000
            return when {
                min % 1440 == 0L -> "EVERY:${min / 1440}d"
                min % 60 == 0L -> "EVERY:${min / 60}h"
                else -> "EVERY:${min}m"
            }
        }
    }

    companion object {
        const val SYNTAX =
            "DAILY:HH:mm | WEEKLY:MON,WED,FRI:HH:mm | EVERY:<n>m|h|d " +
                "(e.g. DAILY:09:00, WEEKLY:MON,FRI:18:30, EVERY:30m)"

        private val DOW_TOKEN = mapOf(
            Calendar.MONDAY to "MON", Calendar.TUESDAY to "TUE", Calendar.WEDNESDAY to "WED",
            Calendar.THURSDAY to "THU", Calendar.FRIDAY to "FRI", Calendar.SATURDAY to "SAT",
            Calendar.SUNDAY to "SUN",
        )
        private val DOW_NAME = mapOf(
            Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu", Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat",
            Calendar.SUNDAY to "Sun",
        )
        private val TOKEN_DOW = DOW_TOKEN.entries.associate { (k, v) -> v to k }

        /** Parse a spec string. Returns null when blank or malformed. */
        fun parse(spec: String?): Recurrence? {
            val s = spec?.trim()?.uppercase() ?: return null
            if (s.isEmpty()) return null
            return runCatching {
                when {
                    s.startsWith("DAILY:") -> {
                        val (h, m) = parseHhMm(s.removePrefix("DAILY:")) ?: return null
                        Daily(h, m)
                    }
                    s.startsWith("WEEKLY:") -> {
                        // WEEKLY:MON,WED,FRI:HH:mm
                        val rest = s.removePrefix("WEEKLY:")
                        val firstColon = rest.indexOf(':')
                        if (firstColon < 0) return null
                        val days = rest.substring(0, firstColon)
                            .split(',')
                            .mapNotNull { TOKEN_DOW[it.trim()] }
                            .toSet()
                        if (days.isEmpty()) return null
                        val (h, m) = parseHhMm(rest.substring(firstColon + 1)) ?: return null
                        Weekly(days, h, m)
                    }
                    s.startsWith("EVERY:") -> {
                        val body = s.removePrefix("EVERY:").trim()
                        val unit = body.lastOrNull() ?: return null
                        val n = body.dropLast(1).toLongOrNull() ?: return null
                        if (n <= 0) return null
                        val ms = when (unit) {
                            'M' -> n * 60_000
                            'H' -> n * 3_600_000
                            'D' -> n * 86_400_000
                            else -> return null
                        }
                        if (ms < 60_000) return null // floor at 1 minute
                        Interval(ms)
                    }
                    else -> null
                }
            }.getOrNull()
        }

        private fun parseHhMm(t: String): Pair<Int, Int>? {
            val parts = t.trim().split(':')
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h to m
        }

        private fun atTime(baseMs: Long, hour: Int, minute: Int): Calendar =
            Calendar.getInstance().apply {
                timeInMillis = baseMs
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

        private fun pad(n: Int) = n.toString().padStart(2, '0')
        private fun hhmm(h: Int, m: Int) = "${pad(h)}:${pad(m)}"
    }
}
