package com.mythara.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.MytharaColors
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * One day-pill in the chat transcript timeline.
 *
 * Phase E.2 redesign (per user spec):
 *   - Compact text-only-width (NOT full width). Sits centred in
 *     the row so each pill reads as a discrete date marker.
 *   - Pure Mythara purple theme — Charple body + Lavender/Bok
 *     accents only. The previous random-palette colour scheme
 *     is gone (felt too "festive" against the minimal aesthetic).
 *   - Current day is auto-added as a pill with a neon-Bok dot
 *     so the user can tell at a glance "this is today".
 *   - Older days show their actual date ("Wed, May 14") instead
 *     of the friendly "Yesterday" / "Today" labels — keeps the
 *     timeline read as a literal sequence.
 *
 * The Transcript composable wraps each pill in [DayPillRow]
 * (below) which draws the timeline gutter line on either side so
 * the row reads as one continuous spine with the pill as a
 * date-stamp node.
 */
@Composable
fun DayPill(
    dayLabel: String,
    iso: String,
    itemCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    isCurrent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val accent = MytharaColors.Charple
    val borderColor = if (isCurrent) MytharaColors.Bok else accent.copy(alpha = 0.55f)
    val dotColor = if (isCurrent) MytharaColors.Bok else accent
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Status dot — neon Bok for today, Charple for older days.
        // The current-day pill thus pops visually as the timeline
        // anchor; older pills sit in the brand purple.
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = dayLabel,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (itemCount > 0) {
            Text(
                text = "·",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "$itemCount",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = if (expanded) "▴" else "▾",
            color = accent,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Wraps a [DayPill] with the horizontal timeline line that runs
 * through the row's centre — gives the chat scrollback a clear
 * "this is a timeline" affordance.
 *
 * The line is rendered on both sides of the pill in Charple
 * α 0.35 so it reads as a guide, not as foreground content.
 */
@Composable
fun DayPillRow(
    modifier: Modifier = Modifier,
    pill: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // Left half of the timeline line — flexes to fill the
        // available space, ending 8 dp before the pill.
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .padding(end = 8.dp),
        ) {
            drawLine(
                color = MytharaColors.Charple.copy(alpha = 0.35f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1f,
            )
        }
        // The pill itself — sized to its text content.
        pill()
        // Right half of the timeline line.
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .padding(start = 8.dp),
        ) {
            drawLine(
                color = MytharaColors.Charple.copy(alpha = 0.35f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1f,
            )
        }
    }
}

/**
 * "It's a brand new day" placeholder shown at the top of the
 * transcript when the user has had ZERO chat activity today yet.
 *
 * Renders a soft system-style bubble with:
 *   - Date headline ("Wednesday, May 15")
 *   - A short greeting generated once per day via ModelRouter
 *     (light path). Cached in [BrandNewDayGreeter] keyed by ISO
 *     date so it stays the same all day; first paint shows a
 *     curated fallback while the LLM is in flight.
 *
 * NO interactive elements on the bubble — it's a system message
 * that disappears the moment the user types anything (which
 * creates today's first ChatItem and pushes the bubble out).
 */
@Composable
fun BrandNewDayBubble(
    todayLabel: String,
    greeting: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MytharaColors.Charple.copy(alpha = 0.18f),
                        MytharaColors.Bok.copy(alpha = 0.12f),
                    ),
                ),
            )
            .border(
                1.dp,
                MytharaColors.Charple.copy(alpha = 0.45f),
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "it's a brand new day · $todayLabel",
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = greeting,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/* -------------------------------------------------- date helpers */

/** Convert a ms timestamp to an ISO-format yyyy-MM-dd string
 *  in the device's default timezone — the grouping key the
 *  Transcript uses. */
fun toIsoDay(tsMillis: Long): String =
    Instant.ofEpochMilli(tsMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()

/** Today's ISO day. */
fun todayIso(): String =
    LocalDate.now(ZoneId.systemDefault()).toString()

/**
 * Pretty label for a day pill — Phase E.2 redesign per user
 * spec: always render the literal date ("Wed, May 14"), no
 * "Yesterday" / "Today" softening. The neon-Bok dot on the
 * current-day pill (driven by `isCurrent` on [DayPill]) is the
 * "this is today" affordance instead.
 */
fun prettyDayLabel(iso: String): String {
    val date = LocalDate.parse(iso)
    return PRETTY_FMT.format(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()))
}

private val PRETTY_FMT = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
