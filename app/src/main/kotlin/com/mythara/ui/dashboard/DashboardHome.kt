package com.mythara.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythara.ui.dashboard.tiles.AppDockTile
import com.mythara.ui.dashboard.tiles.DevicesTile
import com.mythara.ui.dashboard.tiles.HealthTile
import com.mythara.ui.dashboard.tiles.HrCorrelationTile
import com.mythara.ui.dashboard.tiles.PhotosTile
import com.mythara.ui.dashboard.tiles.SensorsTile
import com.mythara.ui.dashboard.tiles.SkillsTile
import com.mythara.ui.dashboard.tiles.TasksTile
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Command-center landing surface for the right pane on tablets. Lays
 * out the six live "subsystem" tiles in an adaptive grid + a
 * full-width skills strip below.
 *
 * Tile taps fire the matching `onOpenX` callback so the parent
 * [DashboardLayout] can navigate the right pane's NavController to
 * the corresponding existing screen. Back returns here.
 */
@Composable
fun DashboardHome(
    onOpenTasks: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenHr: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenSensors: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenAppDrawer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondFilled} command center",
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Live view of every subsystem. Tap any tile to expand.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

        val tiles = remember {
            listOf(
                TileSpec("tasks") { onTap -> TasksTile(onTap) },
                TileSpec("photos") { onTap -> PhotosTile(onTap) },
                TileSpec("devices") { onTap -> DevicesTile(onTap) },
                TileSpec("hr") { onTap -> HrCorrelationTile(onTap) },
                TileSpec("health") { onTap -> HealthTile(onTap) },
                TileSpec("sensors") { onTap -> SensorsTile(onTap) },
            )
        }
        val tapMap = mapOf(
            "tasks" to onOpenTasks,
            "photos" to onOpenTimeline,
            "devices" to onOpenDevices,
            "hr" to onOpenHr,
            "health" to onOpenHealth,
            "sensors" to onOpenSensors,
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = tiles, key = { it.key }) { tile ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.05f),
                ) {
                    tile.content(tapMap[tile.key] ?: {})
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth().height(96.dp)) {
                    SkillsTile(onExpand = onOpenSkills)
                }
            }
            // App dock — bottom strip of installed-app icons that
            // makes the tablet dashboard feel like a real launcher
            // home. Tap an icon → app launches directly. Tap the
            // tile's expand chevron → full app drawer with search.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                    AppDockTile(onExpand = onOpenAppDrawer)
                }
            }
        }
    }
}

private data class TileSpec(
    val key: String,
    val content: @Composable (onTap: () -> Unit) -> Unit,
)

