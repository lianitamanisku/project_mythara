package com.mythara.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mythara.reminders.ReminderAlarmReceiver
import com.mythara.reminders.ReminderAlarmScheduler
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-chat reminder card. Shows when a scheduled task is "fired" (alarm
 * has fired AND status is RUNNING / PENDING but past-due). Three quick
 * actions surface as Crush-styled chips: Done, +15m, +1h. Tapping
 * fires the same broadcast as the notification's actions, so the
 * state machine has exactly one entry point (ReminderAlarmReceiver).
 *
 * Visual treatment: Citron-accented border so a live reminder pops
 * against the surrounding chat. Pending-future reminders (the alarm
 * hasn't fired yet) render in a muted Malibu so the user sees them
 * inline-time but doesn't conflate them with "do this now".
 */
@Composable
fun ReminderCard(item: ChatViewModel.ChatItem.ReminderCard) {
    val ctx = LocalContext.current
    // Live wall-clock tick — re-renders the card every minute so the
    // "in 14m" countdown actually counts down on screen instead of
    // freezing at the value computed when the card was first composed.
    // Keyed on the task id so different cards in the timeline tick
    // independently without overlap.
    val now by produceState(initialValue = System.currentTimeMillis(), key1 = item.id) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L)
        }
    }
    val isLive = item.scheduledForMs <= now && !item.terminal
    val borderColor = when {
        item.terminal -> MytharaColors.SurfaceHigh
        isLive -> MytharaColors.Citron
        else -> MytharaColors.Malibu
    }
    val titlePrefix = when {
        item.terminal -> if (item.status == "DONE") "${Glyph.Check} done" else "${Glyph.Cross} ${item.status.lowercase()}"
        isLive -> "${Glyph.Dot} reminder"
        else -> "${Glyph.CircleOutline} scheduled"
    }
    // Build the subtitle as an AnnotatedString so the leading
    // countdown ("In 14m") shows in bold while the absolute clock
    // suffix ("· 4:34 PM") stays at regular weight — visual weight
    // matches the user's glance order: "how soon" first, "what
    // exact time" second.
    val whenLabel = remember(item.scheduledForMs, now) {
        val raw = formatScheduledFor(item.scheduledForMs, now)
        val sepIdx = raw.indexOf(" · ").takeIf { it >= 0 }
        if (sepIdx == null) {
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw) }
            }
        } else {
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.substring(0, sepIdx)) }
                append(raw.substring(sepIdx))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = titlePrefix,
                color = borderColor,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = whenLabel,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        if (item.body.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.body,
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (item.terminal) {
            // No actions on terminal cards — the user already resolved
            // this. Result text (if any) shows below the title.
            if (item.resultText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${Glyph.AccentBar} ${item.resultText}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            return@Column
        }
        Spacer(Modifier.height(10.dp))
        var missedDialogOpen by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionChip("${Glyph.Check} done", MytharaColors.Julep) { fireAction(ctx, item.id, "done") }
            ActionChip("+15m", MytharaColors.Mustard) { fireAction(ctx, item.id, "snooze_15m") }
            ActionChip("+1h", MytharaColors.Mustard) { fireAction(ctx, item.id, "snooze_1h") }
            ActionChip("+3h", MytharaColors.Mustard) { fireAction(ctx, item.id, "snooze_3h") }
            // "Missed it" — opens a reason picker; the reason gets
            // recorded as a behaviour-event vault row so the
            // daily-review agent + (planned) Auto-Resonance can
            // learn user patterns ("missed because tired" → suggest
            // earlier wind-down tomorrow night).
            ActionChip("missed it", MytharaColors.Charple) { missedDialogOpen = true }
        }
        if (missedDialogOpen) {
            MissedReasonDialog(
                onDismiss = { missedDialogOpen = false },
                onPick = { actionKind ->
                    missedDialogOpen = false
                    fireAction(ctx, item.id, actionKind)
                },
            )
        }
    }
}

/**
 * Reason picker dialog shown when the user taps "missed it" on a
 * fired reminder card. Each option maps to a `missed_*` action kind
 * the [com.mythara.reminders.ReminderAlarmReceiver] understands —
 * the receiver writes the reason facet into the behaviour vault and
 * marks the task TaskStatus.MISSED.
 *
 * Last entry is "Other (free text)" — opens an inline text field so
 * the user can give context the fixed taxonomy doesn't capture
 * ("had a panic attack", "phone was charging in another room", etc.).
 * Currently the free-text note is logged in the receiver's debug log
 * only; wiring it through to the vault row is a follow-up — the
 * fixed-reason path is enough for the agent to start learning
 * patterns.
 */
@Composable
private fun MissedReasonDialog(
    onDismiss: () -> Unit,
    onPick: (actionKind: String) -> Unit,
) {
    var freeText by remember { mutableStateOf("") }
    var showFreeText by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why did you miss it?", color = MytharaColors.Fg) },
        text = {
            Column {
                Text(
                    text = "Pick the closest reason. Mythara learns your patterns " +
                        "from this — frequent overbookings might suggest spacing your " +
                        "calendar; lots of \"slept off\" might mean an earlier wind-down.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                ReasonRow("I was overbooked", MytharaColors.Mustard) { onPick("missed_overbooked") }
                ReasonRow("I slept through it", MytharaColors.Malibu) { onPick("missed_slept") }
                ReasonRow("I was deep in work", MytharaColors.Charple) { onPick("missed_working") }
                ReasonRow("I just forgot", MytharaColors.FgMute) { onPick("missed_forgot") }
                ReasonRow("It's not relevant anymore", MytharaColors.SurfaceHigh) { onPick("missed_not_relevant") }
                ReasonRow("Other…", MytharaColors.Bok) { showFreeText = true }
                if (showFreeText) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        placeholder = {
                            Text(
                                text = "tell Mythara more — e.g. \"phone died\", " +
                                    "\"unexpected meeting ran over\"",
                                color = MytharaColors.FgDim,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MytharaColors.Fg,
                            unfocusedTextColor = MytharaColors.Fg,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { onPick("missed_other") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("save", color = MytharaColors.Bok)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("cancel", color = MytharaColors.FgMute)
            }
        },
    )
}

@Composable
private fun ReasonRow(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, accent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${Glyph.DiamondOutline} $label",
            color = accent,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ActionChip(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Bg)
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun fireAction(ctx: Context, taskId: String, kind: String) {
    val intent = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
        action = ReminderAlarmScheduler.ACTION_ACTION
        putExtra(ReminderAlarmScheduler.EXTRA_TASK_ID, taskId)
        putExtra(ReminderAlarmScheduler.EXTRA_ACTION_KIND, kind)
    }
    ctx.sendBroadcast(intent)
}

private fun formatScheduledFor(ms: Long, now: Long = System.currentTimeMillis()): String {
    val diff = ms - now
    val abs = Math.abs(diff)
    val fmt = when {
        abs < 24L * 3600 * 1000 -> SimpleDateFormat("h:mm a", Locale.getDefault())
        abs < 7L * 24 * 3600 * 1000 -> SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    }
    val whenStr = fmt.format(Date(ms))
    // Lead with the live countdown so the glance order is
    // "how soon" → "exact clock time"; the title sits below on the
    // next line of the card.
    return when {
        diff > 60_000 -> "In ${humanDelta(diff)} · $whenStr"
        diff > -60_000 -> "Now · $whenStr"
        else -> "${humanDelta(-diff)} ago · $whenStr"
    }
}

private fun humanDelta(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val h = m / 60
    val d = h / 24
    return when {
        d > 0 -> "${d}d ${h % 24}h"
        h > 0 -> "${h}h ${m % 60}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}
