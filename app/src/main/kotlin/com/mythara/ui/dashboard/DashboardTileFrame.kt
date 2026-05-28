package com.mythara.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Shared shell every dashboard tile renders inside. Crush-styled
 * surface + accent border (color-coded per tile so the dashboard
 * reads at a glance), tiny header with title + optional badge +
 * expand chevron, content slot below.
 *
 * Tapping anywhere on the tile fires [onTap] — convention is "navigate
 * to the full screen for this subsystem", same destination the
 * matching chat-header pill opens.
 *
 * Borders are 1dp so multiple tiles next to each other don't compete
 * for visual weight; the accent comes from the colour, not the
 * thickness.
 */
@Composable
fun DashboardTileFrame(
    title: String,
    accent: Color,
    badge: String? = null,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Skin-aware surface: solid on Spatial (as before); translucent on
    // Aurora Glass so the aurora backdrop glows through the tiles.
    val spec = com.mythara.ui.theme.LocalSkinSpec.current
    val shape = RoundedCornerShape(spec.cornerRadius)
    val fill = when (spec.surfaceTreatment) {
        com.mythara.ui.theme.SurfaceTreatment.Translucent -> MytharaColors.Surface.copy(alpha = 0.42f)
        com.mythara.ui.theme.SurfaceTreatment.LineArt -> MytharaColors.Bg.copy(alpha = 0.35f)
        com.mythara.ui.theme.SurfaceTreatment.Solid -> MytharaColors.Surface
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(fill)
            .border(1.dp, accent, shape)
            .clickable(onClick = onTap)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = accent,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                if (badge != null) {
                    Spacer(Modifier.padding(end = 6.dp))
                    Text(
                        text = badge,
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = Glyph.Arrow,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
