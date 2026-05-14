package com.mythara.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One photo entry in the life-timeline, rendered compactly: a small
 * square thumbnail on the left, the caption + date/device footer on
 * the right. Tapping the card opens the full-resolution photo in the
 * system photos/gallery app (ACTION_VIEW on the MediaStore URI).
 *
 * Two render modes:
 *  - Local (entry.isLocal = true): thumbnail decoded from the URI;
 *    the card is tappable.
 *  - Remote (entry.isLocal = false): the bytes aren't on this device,
 *    so the thumbnail slot shows a placeholder glyph and the card is
 *    not tappable — the user fetches the original from that device.
 */
@Composable
fun LifelineCard(item: ChatViewModel.ChatItem.LifelinePhoto) {
    val ctx = LocalContext.current
    val dateLabel = remember(item.takenMs) { formatDate(item.takenMs) }
    val deviceLabel = if (item.isLocal) "this device" else "📷 ${item.deviceShortId}"
    val canOpen = item.isLocal && item.uri.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(12.dp))
            .then(
                if (canOpen) Modifier.clickable { openInPhotos(ctx, item.uri) } else Modifier,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Small thumbnail — tap the card to open the full photo.
        if (canOpen) {
            ThumbnailImage(uri = Uri.parse(item.uri))
        } else {
            Box(
                modifier = Modifier
                    .size(THUMB_DP.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MytharaColors.SurfaceMid),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = Glyph.DiamondOutline,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.size(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            when {
                !item.captionText.isNullOrBlank() -> Text(
                    text = item.captionText,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.captionStatus == "PENDING" -> Text(
                    text = "${Glyph.Ellipsis} captioning…",
                    color = MytharaColors.Citron,
                    style = MaterialTheme.typography.bodySmall,
                )
                item.captionStatus == "FAILED" || item.captionStatus == "SKIPPED" -> Text(
                    text = "${Glyph.Cross} no caption",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                !canOpen -> Text(
                    text = "photo on ${item.deviceShortId} (not on this device)",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateLabel,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "│",
                    color = MytharaColors.SurfaceHigh,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = deviceLabel,
                    color = MytharaColors.Mustard,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                if (item.placeLabel != null) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "│",
                        color = MytharaColors.SurfaceHigh,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = item.placeLabel,
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailImage(uri: Uri) {
    val ctx = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val loaded = withContext(Dispatchers.IO) { decodeBitmap(ctx, uri) }
        if (loaded != null) bitmap = loaded.asImageBitmap() else failed = true
    }

    Box(
        modifier = Modifier
            .size(THUMB_DP.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.SurfaceMid),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(THUMB_DP.dp),
                contentScale = ContentScale.Crop,
            )
            failed -> Text(
                text = Glyph.Cross,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Text(
                text = Glyph.Ellipsis,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Open the full-resolution photo in the system photos / gallery app. */
private fun openInPhotos(ctx: Context, uriStr: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriStr), "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }.onFailure { Log.w("Mythara/Lifeline", "open photo failed: ${it.message}") }
}

private const val THUMB_DP = 64

private fun decodeBitmap(ctx: Context, uri: Uri): Bitmap? {
    // Downsample on decode so we don't load a 12MP photo into RAM for
    // a 64-dp thumbnail. inSampleSize halves dimensions; aim for a
    // loaded width of ≤ ~512px — plenty for the thumbnail.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }.onFailure { return null }
    val srcW = bounds.outWidth.takeIf { it > 0 } ?: return null
    var sample = 1
    while (srcW / sample > 512) sample *= 2

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrElse {
        Log.w("Mythara/Lifeline", "decode failed: ${it.message}")
        null
    }
}

private fun formatDate(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    val sdf = if (diff < 24L * 3600 * 1000) {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    } else if (diff < 7L * 24 * 3600 * 1000) {
        SimpleDateFormat("EEEE HH:mm", Locale.getDefault())
    } else {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    }
    return sdf.format(Date(ms))
}
