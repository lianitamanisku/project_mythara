package com.mythara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Reasoning bubble — what the model thought before answering. Same
 * Crush-styled card shape as the ToolCallBubble but with a distinct
 * glyph + colour so they're visually peer-but-separate.
 *
 * Collapsed by default to a one-line preview ("◆ thinking · 12 words").
 * Tap expands to the full reasoning text. The whole point is that the
 * trace stays *available* but not noisy.
 */
@Composable
fun ThoughtBubble(item: ChatViewModel.ChatItem.Thought) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val accent = if (item.streaming) MytharaColors.Citron else MytharaColors.Malibu
    val previewWords = remember(item.text) {
        item.text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (item.streaming) Glyph.Ellipsis else Glyph.DiamondFilled,
                color = accent,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(end = 6.dp))
            Text(
                text = if (item.streaming) "thinking …" else "thought",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.padding(end = 6.dp))
            Text(
                text = "· $previewWords words",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.padding(end = 6.dp))
            Text(
                text = if (expanded) "(tap to hide)" else "(tap to expand)",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MytharaColors.Bg)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = item.text,
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${Glyph.DescendingArrow} " + item.text
                    .replace(Regex("\\s+"), " ")
                    .take(PREVIEW_CHARS)
                    .let { if (item.text.length > PREVIEW_CHARS) "$it…" else it },
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val PREVIEW_CHARS = 100

private fun Color.copy(alpha: Float): Color = Color(red, green, blue, alpha)
