package com.mythara.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.HeartbeatSyncer
import com.mythara.reminders.Recurrence
import com.mythara.tasks.TaskEntity
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `create_reminder` — set a reminder that fires a notification + a
 * spoken announcement from Lumi on THIS device at the scheduled time,
 * and shows as a card in the chat timeline.
 *
 * This is the tool to reach for whenever the user says "remind me…".
 * It creates a scheduled task pinned to this device; the existing
 * ReminderAlarmScheduler arms an exact AlarmManager wake, and
 * ReminderAlarmReceiver does the notification + TTS + chat card.
 *
 * Time resolution:
 *  - When `at_epoch_ms` is given, the reminder fires exactly then.
 *  - When it's omitted, Lumi picks the time AUTOMATICALLY from calendar
 *    availability — it reads upcoming events across every linked
 *    calendar (Google / Outlook / Exchange / local) and schedules the
 *    reminder in the next free slot within waking hours, so it never
 *    interrupts a meeting.
 */
@Singleton
class CreateReminderTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val deviceIdStore: DeviceIdStore,
    private val taskRepo: TaskRepository,
    /** dagger.Lazy — see SendNoteToDeviceTool for the cycle-break rationale. */
    private val heartbeat: dagger.Lazy<HeartbeatSyncer>,
) : Tool {

    override val name: String = "create_reminder"
    override val description: String =
        "Set a reminder for the user — use this whenever the user says 'remind me…'. " +
            "The reminder fires a notification + a spoken announcement from Lumi on this device at the scheduled " +
            "time, and shows as a card in the chat timeline. " +
            "Provide at_epoch_ms ONLY when the user named a specific time (resolve relative times like 'tomorrow 3pm' " +
            "with the time tool first). OMIT at_epoch_ms to let Lumi auto-pick the time from the user's calendar " +
            "availability — it reads every linked calendar and schedules the reminder in the next free slot within " +
            "waking hours so it won't clash with a meeting. " +
            "For RECURRING reminders ('every day at 9am', 'every Mon/Wed/Fri at 6:30pm', 'every 2 hours') pass " +
            "`recurrence` — syntax: " + Recurrence.SYNTAX + ". When recurrence is set it also picks the FIRST fire " +
            "time, so at_epoch_ms is ignored; the reminder then re-arms itself after every fire."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "what",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "What to remind the user about. One short line.")
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Optional extra detail spoken / shown with the reminder.")
                    },
                )
                put(
                    "at_epoch_ms",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Exact fire time as Unix epoch millis. OMIT to let Lumi auto-pick from calendar availability.",
                        )
                    },
                )
                put(
                    "search_hours",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "When auto-picking, how far ahead to look for a free slot. Default 48, max 336 (14 days).",
                        )
                    },
                )
                put(
                    "duration_minutes",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "When auto-picking, the size of free slot to find. Default 15.")
                    },
                )
                put(
                    "recurrence",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional — makes the reminder repeat. Syntax: " + Recurrence.SYNTAX +
                                ". When set, it also picks the first fire time and at_epoch_ms is ignored.",
                        )
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("what")) })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val what = (args["what"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (what.isEmpty()) return ToolResult(false, """{"error":"missing_what"}""")
        val body = (args["body"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val explicit = (args["at_epoch_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
        val searchHours = ((args["search_hours"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 48)
            .coerceIn(1, 336)
        val durationMin = ((args["duration_minutes"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 15)
            .coerceIn(5, 240)
        val recurrenceArg = (args["recurrence"] as? JsonPrimitive)?.content?.trim()
            ?.takeIf { it.isNotBlank() }
        val recurrence = if (recurrenceArg != null) {
            Recurrence.parse(recurrenceArg) ?: return ToolResult(
                false,
                buildJsonObject {
                    put("error", "bad_recurrence")
                    put("got", recurrenceArg)
                    put("syntax", Recurrence.SYNTAX)
                }.toString(),
            )
        } else {
            null
        }

        val now = System.currentTimeMillis()
        var calendarAware = false
        var note = ""

        val fireAt: Long = when {
            // A recurrence spec already encodes the time-of-day, so it
            // also defines the FIRST fire — calendar-slotting + explicit
            // time don't apply to a repeating reminder.
            recurrence != null -> recurrence.nextAfter(now)
            explicit != null && explicit > now + 30_000L -> explicit
            else -> {
                calendarAware = true
                val (slot, n) = withContext(Dispatchers.IO) {
                    resolveFreeSlot(now, searchHours, durationMin)
                }
                note = n
                slot
            }
        }

        val myId = deviceIdStore.id()
        val id = UUID.randomUUID().toString()
        val row = TaskEntity(
            id = id,
            title = what,
            body = body,
            requesterDeviceId = myId,
            // Pinned to this device — a reminder fires where it was set.
            targetDeviceId = myId,
            status = TaskStatus.PENDING.name,
            createdMs = now,
            scheduledForMs = fireAt,
            recurrence = recurrence?.encode(),
        )
        runCatching { taskRepo.dao.insertIfAbsent(row) }
            .onFailure {
                Log.w(TAG, "reminder insert failed: ${it.message}")
                return ToolResult(
                    false,
                    """{"error":"insert_failed","detail":${JsonPrimitive(it.message ?: "unknown")}}""",
                )
            }
        // Verify the row actually persisted WITH a schedule — never claim
        // success blind (the whole point of this rewrite).
        val saved = runCatching { taskRepo.dao.byId(id) }.getOrNull()
        if (saved?.scheduledForMs == null) {
            return ToolResult(
                false,
                """{"error":"verify_failed","detail":"The reminder row didn't persist with a schedule."}""",
            )
        }
        runCatching { heartbeat.get().fireNow() }

        val human = HUMAN_FMT.format(Date(fireAt))
        Log.d(TAG, "reminder $id set for $human (calendarAware=$calendarAware, recurs=${recurrence != null})")
        val payload = buildJsonObject {
            put("ok", true)
            put("reminder_id", id)
            put("scheduled_for_ms", fireAt)
            put("scheduled_for", human)
            put("calendar_aware", calendarAware)
            put("recurring", recurrence != null)
            if (recurrence != null) put("recurrence", recurrence.describe())
            put(
                "detail",
                buildString {
                    if (recurrence != null) {
                        append("Recurring reminder set — ").append(recurrence.describe())
                        append(". First fire ").append(human).append(". ")
                        append("It re-arms itself after every fire.")
                    } else {
                        append("Reminder set for ").append(human).append(". ")
                        if (calendarAware) append(note.ifBlank { "Auto-picked from calendar availability. " })
                        append("Lumi will notify + announce it then; it's on the chat timeline now.")
                    }
                },
            )
        }
        return ToolResult(true, payload.toString())
    }

    /**
     * Find the next free slot of [durationMin] minutes within waking
     * hours by scanning the user's calendars. Returns (epochMs, note).
     * Falls back to the soonest reasonable time when calendar
     * permission is missing or the window is fully booked.
     */
    private fun resolveFreeSlot(now: Long, searchHours: Int, durationMin: Int): Pair<Long, String> {
        val bufferMs = 10L * 60 * 1000
        val durationMs = durationMin * 60_000L
        val windowStart = now + bufferMs
        val windowEnd = now + searchHours * 3_600_000L

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return snapToWaking(windowStart) to
                "Calendar permission isn't granted, so I picked the next reasonable time instead. "
        }
        val busy = queryBusyBlocks(windowStart, windowEnd)
        var cursor = windowStart
        var guard = 0
        while (cursor + durationMs <= windowEnd && guard < 600) {
            guard++
            val snapped = snapToWaking(cursor)
            if (snapped != cursor) {
                cursor = snapped
                continue
            }
            val slotEnd = cursor + durationMs
            val conflict = busy.firstOrNull { (s, e) -> s < slotEnd && e > cursor }
            if (conflict == null) {
                return cursor to "Picked a free slot around your calendar. "
            }
            cursor = conflict.second
        }
        return snapToWaking(windowStart) to
            "Your calendar looked full in that window, so I picked the soonest reasonable time. "
    }

    /** Snap a timestamp into waking hours (08:00–21:00 local). */
    private fun snapToWaking(ms: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return when {
            cal.get(Calendar.HOUR_OF_DAY) < 8 -> cal.apply {
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            cal.get(Calendar.HOUR_OF_DAY) >= 21 -> cal.apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            else -> ms
        }
    }

    /** Timed (non-all-day) calendar events in the window, as (begin,end) pairs. */
    private fun queryBusyBlocks(startMs: Long, endMs: Long): List<Pair<Long, Long>> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<Pair<Long, Long>>()
        runCatching {
            ctx.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val bIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val eIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val adIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                while (c.moveToNext()) {
                    // All-day events don't block a reminder slot.
                    if (c.getInt(adIdx) == 1) continue
                    out.add(c.getLong(bIdx) to c.getLong(eIdx))
                }
            }
        }.onFailure { Log.w(TAG, "calendar query failed: ${it.message}") }
        return out
    }

    companion object {
        private const val TAG = "Mythara/CreateReminder"
        private val HUMAN_FMT = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
    }
}
