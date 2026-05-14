package com.mythara.ui.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One installed app surfaced in the drawer. Icon is decoded lazily
 * from the package's launcher drawable on a background dispatcher.
 */
data class DrawerApp(
    val pkg: String,
    val label: String,
    val iconPainter: Painter,
)

/**
 * Launcher-style app drawer. Full-width grid of icons (5 columns on
 * compact phones), search bar at the top, tap to launch. This is the
 * affordance that makes Mythara feel like a real home-replacement —
 * the chat surface is the home screen, the yellow pill in the header
 * is the "drawer" button, and tapping it brings up this sheet.
 *
 * Why a grid (not the [com.mythara.ui.settings.AppPickerSheet] list
 * style): the picker is built for "find ONE app and pick it"; the
 * drawer is built for "scan + open". Icons are the primary handle —
 * users recognise glyphs faster than labels. The list-style picker
 * is wrong for that.
 *
 * Why `expanded` sheet state: in [SheetValue.Expanded] the sheet
 * covers the full screen, which is what users expect from a launcher
 * drawer. The partial-expand state is skipped so the first tap
 * always lands on the full grid.
 *
 * App launch path: standard PackageManager.getLaunchIntentForPackage
 * with NEW_TASK. Mythara itself is filtered out — no point in
 * "launching" the app you're already in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerSheet(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MytharaColors.Bg,
    ) {
        AppDrawerBody(onLaunched = onDismiss)
    }
}

/**
 * Non-sheet variant. For the two-pane layout the drawer renders
 * inline in the right pane (same surface People / Settings appear
 * in), so we can't wrap it in a bottom sheet — the parent already
 * provides the surface.
 */
@Composable
fun AppDrawerPane(onClose: () -> Unit) {
    AppDrawerBody(onLaunched = onClose)
}

@Composable
private fun AppDrawerBody(
    onLaunched: () -> Unit,
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<DrawerApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadDrawerApps(ctx) }
        apps = loaded
        loading = false
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter {
            it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MytharaColors.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .padding(top = 14.dp, bottom = 20.dp),
        ) {
            Text(
                text = "${Glyph.DiamondFilled} apps",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = MytharaColors.Mustard,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("search apps…", color = MytharaColors.FgDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Mustard,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Mustard,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            when {
                loading -> {
                    Text(
                        text = "${Glyph.Ellipsis} loading installed apps…",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                filtered.isEmpty() -> {
                    Text(
                        text = "${Glyph.CircleOutline} no apps match \"$query\".",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 76.dp),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.pkg }) { app ->
                            DrawerCell(
                                app = app,
                                onClick = {
                                    launchApp(ctx, app.pkg)
                                    onLaunched()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerCell(app: DrawerApp, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MytharaColors.Surface)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            Image(
                painter = app.iconPainter,
                contentDescription = app.label,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// -------------------------------------------------------------------- helpers

/**
 * Query every package with a LAUNCHER intent (excluding Mythara
 * itself), decode the launcher icon, sort by label. Same query shape
 * AppPickerSheet uses — but we don't dedupe by package here because
 * the drawer is a snapshot of "launchable surfaces" and a package
 * with two launcher activities (rare but exists — work-profile dual
 * launchers, e.g. Outlook Personal vs Outlook Work) should appear
 * twice so the user can pick the right one.
 */
internal fun loadDrawerApps(ctx: Context): List<DrawerApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val infos = pm.queryIntentActivities(intent, 0)
    val selfPkg = ctx.packageName
    val out = mutableListOf<DrawerApp>()
    for (info in infos) {
        val pkg = info.activityInfo?.applicationInfo?.packageName ?: continue
        if (pkg == selfPkg) continue
        val appInfo: ApplicationInfo = info.activityInfo.applicationInfo
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrDefault(pkg)
        val painter: Painter = runCatching {
            val d = pm.getApplicationIcon(appInfo)
            val bmp = d.toIcon()
            BitmapPainter(bmp.asImageBitmap())
        }.getOrElse { ColorPainter(Color(0xFF6B50FF)) }
        out.add(DrawerApp(pkg = pkg, label = label, iconPainter = painter))
    }
    // Dedup by pkg: keep the first entry. Dual-launcher packages are
    // rare and the secondary entries usually have unhelpful labels.
    val seen = mutableSetOf<String>()
    val deduped = out.filter { seen.add(it.pkg) }
    return deduped.sortedBy { it.label.lowercase() }
}

internal fun launchApp(ctx: Context, pkg: String) {
    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (intent == null) {
        Log.w(TAG, "no launcher intent for $pkg — skipping")
        return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    runCatching { ctx.startActivity(intent) }.onFailure {
        Log.w(TAG, "launch failed for $pkg: ${it.message}")
    }
}

private fun android.graphics.drawable.Drawable.toIcon(): Bitmap {
    val w = if (intrinsicWidth > 0) intrinsicWidth else 96
    val h = if (intrinsicHeight > 0) intrinsicHeight else 96
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

private const val TAG = "Mythara/Drawer"
