package com.mythara.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
 * Long-pressing the card opens an "add context" bottom sheet where
 * the user can type a short note about the photo ("birthday dinner
 * with Sam at the cafe"). On save:
 *  - the note persists to `lifeline_entries.user_context`
 *  - the captioner regenerates the AI description with the note
 *    folded into the prompt
 *  - the face-analysis worker re-runs so contacts named in the note
 *    can be reconciled against detected faces (Phase 3 wiring)
 *  - the relationship graph re-extracts contacts from the note text
 *
 * The sheet also surfaces a horizontal strip of NEIGHBOUR photos
 * taken within ±2h of the focused frame. Tap a neighbour to also
 * apply the same note to it — useful for the "I just took 8 photos
 * of the same dinner" case where one note covers the whole moment.
 *
 * Two render modes:
 *  - Local (entry.isLocal = true): thumbnail decoded from the URI;
 *    the card is tappable + long-pressable.
 *  - Remote (entry.isLocal = false): the bytes aren't on this device,
 *    so the thumbnail slot shows a placeholder glyph and the card is
 *    not tappable — the user fetches the original from that device.
 *    Long-press is also disabled (you can't annotate a photo whose
 *    pixels aren't here).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LifelineCard(
    item: ChatViewModel.ChatItem.LifelinePhoto,
    onSaveNote: (List<Long>, String) -> Unit = { _, _ -> },
    onLoadNeighbours: suspend (Long, Long) -> List<ChatViewModel.LifelineNeighbour> =
        { _, _ -> emptyList() },
) {
    val ctx = LocalContext.current
    val dateLabel = remember(item.takenMs) { formatDate(item.takenMs) }
    val deviceLabel = if (item.isLocal) "this device" else "📷 ${item.deviceShortId}"
    val canOpen = item.isLocal && item.uri.isNotBlank()
    val canAnnotate = item.isLocal && item.lifelineId > 0L
    var sheetVisible by remember { mutableStateOf(false) }

    val cardModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 4.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(MytharaColors.Surface)
        .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(12.dp))
        .then(
            when {
                canAnnotate && canOpen -> Modifier.combinedClickable(
                    onClick = { openInPhotos(ctx, item.uri) },
                    onLongClick = { sheetVisible = true },
                )
                canOpen -> Modifier.combinedClickable(
                    onClick = { openInPhotos(ctx, item.uri) },
                )
                else -> Modifier
            },
        )
        .padding(8.dp)

    Row(
        modifier = cardModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Small thumbnail — tap the card to open the full photo.
        if (canOpen) {
            ThumbnailImage(uri = Uri.parse(item.uri), sizeDp = THUMB_DP)
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
            // User-added context — italic Mustard subtitle below the
            // caption so the user can see at a glance whether THEY
            // added detail vs the AI generated everything alone.
            if (!item.userContext.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "you added: ${item.userContext}",
                    color = MytharaColors.Mustard,
                    style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Detected-contacts chip strip — comma-joined nameKeys from
            // on-device face match. Lets the user see WHO Mythara
            // recognised in the frame without opening the contact's
            // profile.
            val detectedNames = remember(item.detectedContactsJson) {
                parseDetectedNames(item.detectedContactsJson)
            }
            if (detectedNames.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${Glyph.DiamondFilled} ${detectedNames.joinToString(", ")}",
                    color = MytharaColors.Charple,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                // Discoverable "+ context" / "✏ edit context" chip.
                // Long-press still works (canonical), but a lot of
                // users never discover long-press gestures — this
                // chip surfaces the affordance inline so the option
                // is visible at a glance.
                if (canAnnotate) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "│",
                        color = MytharaColors.SurfaceHigh,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (item.userContext.isNullOrBlank()) "+ context" else "✏ edit",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .clickable { sheetVisible = true }
                            .padding(vertical = 2.dp, horizontal = 4.dp),
                    )
                }
            }
        }
    }

    if (sheetVisible && canAnnotate) {
        AddNoteSheet(
            focusedId = item.lifelineId,
            focusedTakenMs = item.takenMs,
            initial = item.userContext.orEmpty(),
            onLoadNeighbours = onLoadNeighbours,
            onDismiss = { sheetVisible = false },
            onSave = { ids, note ->
                onSaveNote(ids, note)
                sheetVisible = false
            },
        )
    }
}

/**
 * Modal bottom sheet for adding (or editing) a user note on a
 * lifeline photo. Pre-populates the field with the existing
 * [initial] value so editing is a single-tap-fix flow.
 *
 * Below the text field, a horizontal strip of neighbour photos
 * (taken within ±2h of [focusedTakenMs], loaded via
 * [onLoadNeighbours]) lets the user fan the SAME note across
 * multiple frames they took during the same moment. The focused
 * photo is always part of the save; neighbours are opt-in per item.
 *
 * Empty save = clear the note (the captioner will regenerate without
 * user context).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddNoteSheet(
    focusedId: Long,
    focusedTakenMs: Long,
    initial: String,
    onLoadNeighbours: suspend (Long, Long) -> List<ChatViewModel.LifelineNeighbour>,
    onDismiss: () -> Unit,
    onSave: (List<Long>, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(initial) }
    var neighbours by remember { mutableStateOf<List<ChatViewModel.LifelineNeighbour>>(emptyList()) }
    val selectedNeighbours = remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(focusedId, focusedTakenMs) {
        neighbours = withContext(Dispatchers.IO) {
            onLoadNeighbours(focusedId, focusedTakenMs)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MytharaColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "${Glyph.DiamondOutline} add context",
                style = MaterialTheme.typography.titleSmall.copy(color = MytharaColors.Fg),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "tell Mythara what was happening — who you were with, " +
                    "where this was, why it mattered. the AI description " +
                    "will regenerate with this folded in, and any contact " +
                    "names you mention get auto-tagged.",
                style = MaterialTheme.typography.labelSmall.copy(color = MytharaColors.FgDim),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(280) },
                placeholder = {
                    Text(
                        text = "e.g. \"birthday dinner with Sam at Whole Foods\"",
                        color = MytharaColors.FgDim,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${draft.length}/280",
                style = MaterialTheme.typography.labelSmall.copy(color = MytharaColors.FgMute),
                modifier = Modifier.fillMaxWidth(),
            )
            if (neighbours.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "${Glyph.AccentBar} apply this note to other shots from the same moment",
                    style = MaterialTheme.typography.labelSmall.copy(color = MytharaColors.FgDim),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (n in neighbours) {
                        val checked = n.id in selectedNeighbours.value
                        NeighbourThumb(
                            uri = n.uri,
                            takenMs = n.takenMs,
                            checked = checked,
                            onToggle = {
                                selectedNeighbours.value = if (checked) {
                                    selectedNeighbours.value - n.id
                                } else {
                                    selectedNeighbours.value + n.id
                                }
                            },
                        )
                    }
                }
                val groupCount = 1 + selectedNeighbours.value.size
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (groupCount == 1) {
                        "saving to this photo only"
                    } else {
                        "saving to $groupCount photos"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(color = MytharaColors.FgMute),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("cancel", color = MytharaColors.FgDim)
                }
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = {
                    val ids = buildList {
                        add(focusedId)
                        addAll(selectedNeighbours.value)
                    }
                    onSave(ids, draft)
                }) {
                    Text("save", color = MytharaColors.Charple, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** One thumbnail in the AddNoteSheet's neighbour strip. A small
 *  square photo with a checkmark overlay when [checked]. Tapping
 *  toggles inclusion in the group save. */
@Composable
private fun NeighbourThumb(
    uri: String,
    takenMs: Long,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.SurfaceMid)
            .border(
                width = if (checked) 2.dp else 1.dp,
                color = if (checked) MytharaColors.Charple else MytharaColors.SurfaceHigh,
                shape = RoundedCornerShape(8.dp),
            )
            .clip(RoundedCornerShape(8.dp))
            .padding(4.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(NEIGHBOUR_THUMB_DP.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MytharaColors.SurfaceMid)
                    .combinedClickable(onClick = onToggle, onLongClick = onToggle),
            ) {
                ThumbnailImage(uri = Uri.parse(uri), sizeDp = NEIGHBOUR_THUMB_DP)
            }
            if (checked) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MytharaColors.Charple),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✓",
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = remember(takenMs) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(takenMs))
            },
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ThumbnailImage(uri: Uri, sizeDp: Int) {
    val ctx = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val loaded = withContext(Dispatchers.IO) { decodeBitmap(ctx, uri) }
        if (loaded != null) bitmap = loaded.asImageBitmap() else failed = true
    }

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.SurfaceMid),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(sizeDp.dp),
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

/**
 * Parse the raw JSON array stored in `detected_contacts_json`. The
 * shape is tiny — `["sarah","mom","mark"]` — so a hand-rolled split
 * is cheaper than dragging in a JSON parser per card render. Returns
 * an empty list if anything is off (null, empty array, malformed).
 */
private fun parseDetectedNames(json: String?): List<String> = runCatching {
    json?.trim()
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.split(",")
        ?.map { it.trim().trim('"') }
        ?.filter { it.isNotBlank() }
        .orEmpty()
}.getOrDefault(emptyList())

private const val THUMB_DP = 64
private const val NEIGHBOUR_THUMB_DP = 56

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
