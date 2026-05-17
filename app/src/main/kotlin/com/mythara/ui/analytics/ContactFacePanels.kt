package com.mythara.ui.analytics

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import com.mythara.analytics.ContactProfileRow
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Face-recognition basis panel for the contact-detail screen.
 *
 * Lets the user feed Mythara explicit sample photos of this contact
 * so the matcher reliably recognises them in every future (and past)
 * photo. The sample crops show as a horizontal strip of round
 * thumbnails; each has an inline × to remove it. "Add samples"
 * opens the system multi-photo picker.
 *
 * After samples are added [PeopleViewModel.addFaceSamples] also
 * kicks a retroactive rescan of recent lifeline photos that don't
 * yet have this contact tagged — so the user immediately sees
 * existing photos back-fill into the [PhotosOfContactPanel] below.
 */
@Composable
internal fun FaceSamplesPanel(
    profile: ContactProfileRow,
    vm: PeopleViewModel,
) {
    val samplesFlow = remember(profile.nameKey) { vm.observeFaceSamplesFor(profile.nameKey) }
    val samples by samplesFlow.collectAsState(initial = emptyList())
    val status by vm.sampleStatus.collectAsState()

    // Clear stale status when the user opens a different contact.
    LaunchedEffect(profile.nameKey) {
        if (status?.nameKey != null && status?.nameKey != profile.nameKey) {
            vm.clearSampleStatus()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SAMPLES_PER_BATCH),
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.addFaceSamples(profile.nameKey, uris)
        }
    }

    // Re-check on every recomposition (cheap — just a file-exists
    // call). When the user taps "install face model" this flips
    // false → true once the download finishes, swapping the CTA.
    val modelReady = vm.isFaceModelInstalled()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${Glyph.DiamondOutline} face recognition basis",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (!modelReady) {
                TextButton(onClick = { vm.installFaceModel(profile.nameKey) }) {
                    Text(
                        text = "${Glyph.DiamondFilled} install face model",
                        color = MytharaColors.Bok,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                TextButton(onClick = {
                    picker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                }) {
                    Text(
                        text = "${Glyph.DiamondFilled} add samples",
                        color = MytharaColors.Bok,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Text(
            text = if (!modelReady) {
                "Mythara needs the MobileFaceNet model (~5 MB) to recognise faces. " +
                    "Tap 'install face model' once — it downloads in the background " +
                    "and unlocks face matching for every contact."
            } else {
                "pick a few clear photos of ${profile.displayName}. Mythara learns " +
                    "their face from these — every future (and recent past) photo of " +
                    "them auto-tags into the grid below."
            },
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )

        if (samples.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${Glyph.CircleOutline} no samples yet",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (s in samples) {
                    SampleThumb(
                        path = s.sourcePhotoPath,
                        onRemove = { vm.removeFaceSample(s.sourcePhotoPath) },
                    )
                }
            }
        }

        status?.takeIf { it.nameKey == profile.nameKey }?.let { st ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = st.message,
                color = when {
                    st.inFlight -> MytharaColors.Citron
                    st.isError -> MytharaColors.Sriracha
                    else -> MytharaColors.Julep
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SampleThumb(path: String, onRemove: () -> Unit) {
    val bmp = rememberFileBitmap(path)
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MytharaColors.SurfaceMid)
            .border(1.5.dp, MytharaColors.Charple, RoundedCornerShape(32.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = Glyph.Ellipsis,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(MytharaColors.Sriracha)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * "Photos of <name>" grid for the contact-detail screen. Renders
 * every lifeline photo whose face matcher tagged this contact —
 * updates live as new photos get analysed by FaceAnalysisWorker.
 * Tap a thumbnail to open the full photo in the system photos app.
 */
@Composable
internal fun PhotosOfContactPanel(
    profile: ContactProfileRow,
    vm: PeopleViewModel,
) {
    val ctx = LocalContext.current
    val flow = remember(profile.nameKey) { vm.observePhotosOf(profile.nameKey) }
    val photos by flow.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${Glyph.DiamondFilled} photos of ${profile.displayName}",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (photos.isNotEmpty()) {
                Text(
                    text = "${photos.size}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (photos.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${Glyph.CircleOutline} no photos tagged with ${profile.displayName} yet. " +
                    "add a few face samples above and existing photos will populate here as " +
                    "Mythara recognises them.",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (photo in photos) {
                    PhotoThumb(
                        uri = photo.uri,
                        takenMs = photo.takenMs,
                        onTap = { openInPhotos(ctx, photo.uri) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoThumb(uri: String, takenMs: Long, onTap: () -> Unit) {
    val bmp = rememberUriBitmap(uri)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MytharaColors.SurfaceMid)
                .clickable { onTap() },
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = Glyph.Ellipsis,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = remember(takenMs) {
                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(takenMs))
            },
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun rememberFileBitmap(path: String): ImageBitmap? {
    var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bmp = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    return bmp
}

@Composable
private fun rememberUriBitmap(uri: String): ImageBitmap? {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val parsed = Uri.parse(uri)
                // Downsample on decode — 84-dp thumb doesn't need 12 MP.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val srcW = bounds.outWidth.takeIf { it > 0 } ?: return@runCatching null
                var sample = 1
                while (srcW / sample > 512) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                ctx.contentResolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

private fun openInPhotos(ctx: android.content.Context, uriStr: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriStr), "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}

/** Cap on photos picked per batch — keeps the ingest pipeline
 *  bounded so a single add-samples action doesn't try to process
 *  the user's entire camera roll. The picker enforces this at the
 *  system level. */
private const val MAX_SAMPLES_PER_BATCH = 10
