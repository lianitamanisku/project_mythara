package com.mythara.ui.dashboard.tiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.ui.dashboard.DashboardTileFrame
import com.mythara.ui.launcher.DrawerApp
import com.mythara.ui.launcher.launchApp
import com.mythara.ui.launcher.loadDrawerApps
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Bottom dock strip on the tablet dashboard — horizontally
 * scrolling row of every launcher-listed app on the device. Each
 * icon launches the app directly (NEW_TASK so the app comes to the
 * foreground without disturbing Mythara's task).
 *
 * Mirrors the full-screen [com.mythara.ui.launcher.AppDrawerSheet]
 * but rendered inline so the tablet feels like a real launcher
 * home — apps front and center, no extra tap to reach them.
 *
 * Apps loaded once on construction (PackageManager queries are
 * stable enough that they don't need re-running on every recompose).
 * Tapping the tile's expand chevron opens the full AppDrawer screen
 * with search; the dock itself is the fast-launch surface.
 */
@HiltViewModel
class AppDockViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val _apps = MutableStateFlow<List<DrawerApp>>(emptyList())
    val apps: StateFlow<List<DrawerApp>> = _apps.asStateFlow()

    init {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { loadDrawerApps(ctx) }
        }
    }
}

@Composable
fun AppDockTile(onExpand: () -> Unit) {
    val vm: AppDockViewModel = hiltViewModel()
    val apps by vm.apps.collectAsState()
    val ctx = LocalContext.current

    DashboardTileFrame(
        title = "${Glyph.DiamondFilled} apps",
        accent = MytharaColors.Mustard,
        badge = "${apps.size}",
        onTap = onExpand,
    ) {
        if (apps.isEmpty()) {
            Text(
                text = "${Glyph.Ellipsis} loading apps…",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            return@DashboardTileFrame
        }
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(items = apps, key = { it.pkg }) { app ->
                AppIcon(app = app, onClick = { launchApp(ctx, app.pkg) })
            }
        }
    }
}

@Composable
private fun AppIcon(app: DrawerApp, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MytharaColors.Bg),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = app.iconPainter,
                contentDescription = app.label,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = app.label,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(56.dp),
        )
    }
}

