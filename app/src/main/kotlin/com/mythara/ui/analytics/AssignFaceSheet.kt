package com.mythara.ui.analytics

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mythara.analytics.ContactProfileRow
import com.mythara.face.UnknownFaceRow
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bottom sheet for promoting an untagged face cluster captured by
 * [com.mythara.face.FaceAnalysisWorker]. Offers four routes:
 *
 *  1. **Assign to existing contact** — opens an inline search/list
 *     of every contact in [profiles]; selecting one wires the
 *     embedding into [com.mythara.face.ContactFaceIndex] so the
 *     face matches that contact in every future photo, and (if the
 *     contact has no avatar yet) silently sets the cropped face as
 *     their avatar.
 *
 *  2. **Create new Mythara contact** — types a name into a text
 *     field; we create a [ContactProfileRow] with that name + the
 *     face as the avatar, and add the embedding to the face index.
 *     Optionally chains into the system Contacts "Add new contact"
 *     activity so the user can ALSO save it to their device address
 *     book (via [ContactsContract.Intents.Insert.ACTION]) — that
 *     path needs no special permission since we're just launching
 *     the system UI.
 *
 *  3. **Not a person** — marks the cluster dismissed. The embedding
 *     stays in the table so future detections of the same face
 *     re-cluster to it and get silently dropped (no resurfacing the
 *     same false positive every photo).
 *
 *  4. **Delete** — hard delete + drop the crop file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignFaceSheet(
    face: UnknownFaceRow,
    profiles: List<ContactProfileRow>,
    onDismiss: () -> Unit,
    onAssign: (nameKey: String) -> Unit,
    onCreateContact: (displayName: String, alsoSaveToDevice: Boolean) -> Unit,
    onMarkNotAPerson: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Internal sheet "view" — root chooser ↔ assign-existing ↔ create-new
    var mode by remember { mutableStateOf<Mode>(Mode.Root) }
    var contactSearch by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var alsoSaveToDevice by remember { mutableStateOf(true) }

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
            FaceHeader(face)
            Spacer(Modifier.height(12.dp))
            when (mode) {
                Mode.Root -> RootChooser(
                    onAssignExisting = { mode = Mode.AssignExisting },
                    onCreateNew = { mode = Mode.CreateNew },
                    onMarkNotAPerson = {
                        onMarkNotAPerson()
                        onDismiss()
                    },
                    onDelete = {
                        onDelete()
                        onDismiss()
                    },
                )
                Mode.AssignExisting -> AssignExistingPane(
                    profiles = profiles,
                    query = contactSearch,
                    onQueryChange = { contactSearch = it },
                    onPick = { profile ->
                        onAssign(profile.nameKey)
                        onDismiss()
                    },
                    onBack = { mode = Mode.Root },
                )
                Mode.CreateNew -> CreateNewPane(
                    name = newName,
                    onNameChange = { newName = it.take(80) },
                    alsoSaveToDevice = alsoSaveToDevice,
                    onAlsoSaveChange = { alsoSaveToDevice = it },
                    onSave = {
                        if (newName.trim().isNotEmpty()) {
                            onCreateContact(newName.trim(), alsoSaveToDevice)
                            onDismiss()
                        }
                    },
                    onBack = { mode = Mode.Root },
                )
            }
        }
    }
}

private enum class Mode { Root, AssignExisting, CreateNew }

@Composable
private fun FaceHeader(face: UnknownFaceRow) {
    val ctx = LocalContext.current
    var bmp by remember(face.cropPath) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(face.cropPath) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(face.cropPath)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(MytharaColors.SurfaceMid)
                .border(2.dp, MytharaColors.Charple, RoundedCornerShape(36.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = "?",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                text = "${Glyph.DiamondOutline} untagged face",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "seen ${face.seenCount}× across your photos",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RootChooser(
    onAssignExisting: () -> Unit,
    onCreateNew: () -> Unit,
    onMarkNotAPerson: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "is this someone you know?",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(2.dp))
        Action(
            label = "${Glyph.DiamondFilled} assign to existing contact",
            description = "match with someone Mythara already knows",
            color = MytharaColors.Charple,
            onClick = onAssignExisting,
        )
        Action(
            label = "${Glyph.DiamondFilled} create a new contact",
            description = "name this person — saves locally + optionally to your device contacts",
            color = MytharaColors.Bok,
            onClick = onCreateNew,
        )
        Spacer(Modifier.height(4.dp))
        Action(
            label = "${Glyph.Cross} not a person",
            description = "false positive — won't surface again",
            color = MytharaColors.FgMute,
            onClick = onMarkNotAPerson,
        )
        Action(
            label = "${Glyph.Cross} delete",
            description = "hard delete this cluster",
            color = MytharaColors.Sriracha,
            onClick = onDelete,
        )
    }
}

@Composable
private fun Action(
    label: String,
    description: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.SurfaceMid)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = description,
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun AssignExistingPane(
    profiles: List<ContactProfileRow>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (ContactProfileRow) -> Unit,
    onBack: () -> Unit,
) {
    val q = query.trim().lowercase()
    val filtered = remember(profiles, q) {
        if (q.isEmpty()) profiles
        else profiles.filter { it.displayName.lowercase().contains(q) || it.nameKey.contains(q) }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("${Glyph.Arrow}", color = MytharaColors.FgDim)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = "pick a contact",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("search contacts…", color = MytharaColors.FgDim) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MytharaColors.Fg,
                unfocusedTextColor = MytharaColors.Fg,
                focusedBorderColor = MytharaColors.Charple,
                unfocusedBorderColor = MytharaColors.SurfaceHigh,
                cursorColor = MytharaColors.Charple,
            ),
        )
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Text(
                text = "${Glyph.CircleOutline} no contacts match",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = { it.nameKey }) { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MytharaColors.SurfaceMid)
                            .clickable { onPick(profile) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = profile.displayName,
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (profile.photoUri.isNullOrBlank()) {
                            Text(
                                text = "no avatar",
                                color = MytharaColors.FgMute,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateNewPane(
    name: String,
    onNameChange: (String) -> Unit,
    alsoSaveToDevice: Boolean,
    onAlsoSaveChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("${Glyph.Arrow}", color = MytharaColors.FgDim)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = "name this person",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text("e.g. Sam, Mom, Sarah Chen", color = MytharaColors.FgDim) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MytharaColors.Fg,
                unfocusedTextColor = MytharaColors.Fg,
                focusedBorderColor = MytharaColors.Charple,
                unfocusedBorderColor = MytharaColors.SurfaceHigh,
                cursorColor = MytharaColors.Charple,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onAlsoSaveChange(!alsoSaveToDevice) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (alsoSaveToDevice) MytharaColors.Charple else MytharaColors.SurfaceMid,
                    )
                    .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (alsoSaveToDevice) {
                    Text(
                        text = "✓",
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = "also open device contacts to save them there",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) {
                Text("cancel", color = MytharaColors.FgDim)
            }
            Spacer(Modifier.size(4.dp))
            TextButton(
                onClick = onSave,
                enabled = name.trim().isNotEmpty(),
            ) {
                Text(
                    "save",
                    color = if (name.trim().isNotEmpty()) MytharaColors.Charple else MytharaColors.FgMute,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Launch the system Contacts app pre-filled with [displayName] +
 * (optionally) a photo URI. Uses [ContactsContract.Intents.Insert.ACTION]
 * which requires NO special permission — the user explicitly
 * confirms the save in the standard Android Contacts UI.
 *
 * Called from [PeopleScreen] after a CreateNew → save when the
 * user opted to "also save to device contacts".
 */
fun launchAddDeviceContact(
    ctx: android.content.Context,
    displayName: String,
    avatarFile: File?,
) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, displayName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The Intents.Insert protocol doesn't take a photo URI
        // directly, but most OEM Contacts apps will respect a
        // standard data extras row if we provide one. We keep this
        // best-effort: the canonical Mythara avatar is already set
        // on the local profile, so missing the device photo is
        // graceful (the user sees their typed name in the system
        // Contacts editor and can attach the photo manually).
    }
    runCatching { ctx.startActivity(intent) }
}
