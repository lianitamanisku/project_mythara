package com.mythara.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.analytics.interactions.ContactInteractionRepository
import com.mythara.analytics.interactions.ContactInteractionRow
import com.mythara.people.ContactActions
import com.mythara.people.SystemContact
import com.mythara.people.SystemContactsRepository
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Everything the detail screen renders, fetched once on entry. */
data class ContactDetailData(
    val row: ContactProfileRow?,
    val sys: SystemContact?,
    val interactions: List<ContactInteractionRow>,
)

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val profiles: ContactProfileRepository,
    private val interactions: ContactInteractionRepository,
    private val systemContacts: SystemContactsRepository,
    private val faceSampler: com.mythara.face.ContactFaceSampler,
    private val contactFaceIndex: com.mythara.face.ContactFaceIndex,
    private val lifelineRepo: com.mythara.lifeline.LifelineRepository,
    private val favoritesStore: com.mythara.data.FavoritesStore,
) : ViewModel() {

    private val _data = MutableStateFlow<ContactDetailData?>(null)
    val data: StateFlow<ContactDetailData?> = _data.asStateFlow()

    /** Live favorite flag — true when EITHER the curated FavoritesStore
     *  list contains this nameKey OR the profile row's is_favorite
     *  column is set. The detail header's star reads this flow so the
     *  fill state stays in sync the moment a toggle lands. */
    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    /** Status of the in-flight face-sample add for this contact. Same
     *  shape PeopleViewModel uses — both render through
     *  [com.mythara.ui.analytics.FaceSamplesPanel]. */
    private val _sampleStatus = MutableStateFlow<com.mythara.ui.analytics.SampleStatus?>(null)
    val sampleStatus: StateFlow<com.mythara.ui.analytics.SampleStatus?> = _sampleStatus.asStateFlow()

    fun load(nameKey: String) {
        viewModelScope.launch {
            val row = profiles.dao.byKey(nameKey)
            val sys = systemContacts.loadAll().firstOrNull {
                it.displayName.lowercase().trim() == nameKey
            }
            val inter = interactions.dao.listForContact(nameKey, limit = 100)
            _data.value = ContactDetailData(row = row, sys = sys, interactions = inter)
            // Seed the favorite flag from the union of the two stores.
            val displayName = row?.displayName ?: sys?.displayName ?: nameKey
            val favList = runCatching { favoritesStore.list() }.getOrDefault(emptyList())
            val inStore = favList.any { it.name.equals(displayName, ignoreCase = true) }
            _isFavorite.value = inStore || (row?.isFavorite == true)
        }
    }

    /**
     * Toggle the contact's favorite state. Writes through BOTH stores:
     *
     *  1. FavoritesStore — the curated auto-reply allowlist. Adds an
     *     entry with default tone "Realistic" when promoting; removes
     *     the entry when demoting. This is the canonical, user-facing
     *     favorites list.
     *
     *  2. contact_profiles.is_favorite — the column the People-list
     *     ordering reads. Keeping the column in sync makes the list
     *     reorder immediately without waiting for the next analytics
     *     rebuild.
     *
     * The local _isFavorite flow updates optimistically so the star
     * fills/empties on tap with no visual delay.
     */
    fun toggleFavorite() {
        val cur = _data.value ?: return
        val displayName = cur.row?.displayName ?: cur.sys?.displayName ?: return
        val nameKey = cur.row?.nameKey ?: displayName.lowercase().trim()
        val phone = cur.sys?.primaryPhone ?: cur.row?.phone.orEmpty()
        val nowFav = !_isFavorite.value
        _isFavorite.value = nowFav  // optimistic
        viewModelScope.launch {
            runCatching {
                if (nowFav) {
                    favoritesStore.upsert(
                        com.mythara.data.FavoritesStore.Favorite(
                            name = displayName,
                            phone = phone,
                        ),
                    )
                } else {
                    favoritesStore.remove(displayName)
                }
                profiles.dao.updateIsFavorite(nameKey, nowFav)
                // Refresh the row inside _data so a subsequent recompose
                // sees the new flag (header reads from _isFavorite, but
                // other consumers of ContactDetailData.row should see
                // truth too).
                val refreshed = profiles.dao.byKey(nameKey)
                _data.value = cur.copy(row = refreshed)
            }.onFailure {
                // Revert optimistic update on failure.
                _isFavorite.value = !nowFav
            }
        }
    }

    // ─── Face-recognition panel hooks ───────────────────────────────
    // These mirror the PeopleViewModel surface so the same Composable
    // panels render identically here.

    fun observeFaceSamplesFor(nameKey: String) =
        contactFaceIndex.dao.observeForContact(nameKey)

    fun observePhotosOf(nameKey: String) =
        lifelineRepo.dao.observeForContact(nameKey, limit = 60)

    fun isFaceModelInstalled(): Boolean = faceSampler.modelInstalled()

    fun faceBackendLabel(): String = faceSampler.backendLabel()

    fun clearSampleStatus() { _sampleStatus.value = null }

    fun addFaceSamples(nameKey: String, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val needsModel = !faceSampler.modelInstalled()
        val firstMsg = if (needsModel) {
            "… downloading face model (~5MB), then processing ${uris.size} photo${if (uris.size == 1) "" else "s"}…"
        } else {
            "… processing ${uris.size} photo${if (uris.size == 1) "" else "s"}…"
        }
        _sampleStatus.value = com.mythara.ui.analytics.SampleStatus(
            nameKey = nameKey, message = firstMsg, inFlight = true,
        )
        viewModelScope.launch {
            val result = runCatching { faceSampler.addSamples(nameKey, uris) }
                .getOrElse {
                    _sampleStatus.value = com.mythara.ui.analytics.SampleStatus(
                        nameKey, "× error: ${it.message}", isError = true,
                    )
                    return@launch
                }
            if (!result.embedderReady) {
                val why = if (result.modelDownloadFailed) {
                    "× couldn't download the face model — check connection and tap 'install face model'."
                } else {
                    "× face model not installed — tap 'install face model'."
                }
                _sampleStatus.value = com.mythara.ui.analytics.SampleStatus(
                    nameKey, why, isError = true,
                )
                return@launch
            }
            val rescanCount = if (result.embeddingsAdded > 0) {
                runCatching { faceSampler.retroactiveRescan(nameKey) }.getOrDefault(0)
            } else 0
            val msg = buildString {
                if (result.modelDownloaded) append("✓ face model installed · ")
                append("${if (result.modelDownloaded) "added" else "✓ added"} ${result.embeddingsAdded} face sample")
                if (result.embeddingsAdded != 1) append("s")
                append(" from ${result.urisProcessed} photo${if (result.urisProcessed == 1) "" else "s"}")
                if (result.facesFound == 0 && result.urisProcessed > 0) {
                    append(" · no faces detected in any photo")
                }
                if (rescanCount > 0) {
                    append(" · rescanning $rescanCount existing photo${if (rescanCount == 1) "" else "s"}")
                }
            }
            _sampleStatus.value = com.mythara.ui.analytics.SampleStatus(
                nameKey, msg,
                isError = result.embeddingsAdded == 0 && result.facesFound > 0,
            )
        }
    }

    fun installFaceModel(nameKey: String) {
        _sampleStatus.value = com.mythara.ui.analytics.SampleStatus(
            nameKey, "… downloading face model (~5MB)…", inFlight = true,
        )
        viewModelScope.launch {
            val ok = runCatching { faceSampler.ensureModelInstalled() }.getOrDefault(false)
            _sampleStatus.value = com.mythara.ui.analytics.SampleStatus(
                nameKey,
                if (ok) "✓ face model installed — you can add samples now."
                else "× download failed — check your connection and try again.",
                isError = !ok,
            )
        }
    }

    fun removeFaceSample(sourcePath: String) {
        viewModelScope.launch {
            runCatching { faceSampler.removeSample(sourcePath) }
        }
    }
}

/**
 * Per-contact detail (v7 P7+). Re-instates the interactions / memory
 * / Big Five view that the original PeopleScreen carried, with the
 * call / SMS / WhatsApp action chips inline at the top. Loaded by
 * [nameKey] (the canonical lowercase display-name key used across
 * Mythara's analytics stack).
 */
@Composable
fun ContactDetailScreen(
    nameKey: String,
    vm: ContactDetailViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(nameKey) { vm.load(nameKey) }
    val data by vm.data.collectAsState()

    val displayName = data?.row?.displayName ?: data?.sys?.displayName ?: nameKey
    val phone = data?.sys?.primaryPhone ?: data?.row?.phone
    val hasWa = data?.sys?.hasWhatsApp == true
    // v7+ — photo URI from system contact (content://) takes priority,
    // then the app-side override on the Mythara profile row.
    val photoUri = data?.sys?.photoUri ?: data?.row?.photoUri

    // collectAsState() must be called from a Composable scope, not
    // the LazyListScope DSL inside LazyColumn { ... }. Lift it here.
    val isFav by vm.isFavorite.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("hdr") {
            HeaderCard(
                displayName = displayName,
                phone = phone,
                photoUri = photoUri,
                isFavorite = isFav,
                hasWhatsApp = hasWa,
                onToggleFavorite = { vm.toggleFavorite() },
                onCall = { phone?.let { ContactActions.phoneCall(ctx, it) } },
                onSms = { phone?.let { ContactActions.sms(ctx, it) } },
                onWaChat = { phone?.let { ContactActions.whatsAppChat(ctx, it) } },
            )
        }
        data?.row?.let { row ->
            // ─── Personality — the headline card. Big Five bars +
            //     mythara's prose insights live together at the top
            //     of the page so the first thing the user sees is
            //     "who is this person, in Mythara's read". Always
            //     rendered (bars show "—" + tiered confidence
            //     disclaimer when sample size is still 0).
            // Notable traits + Big Five + insights all fold into one
            // headline PersonalityCard: short tag chips read first,
            // bars give the quantitative read below them, prose
            // grounds both in narrative. Single card so the user
            // never has to scan three independently-styled blocks
            // to form a mental model of who this person is.
            val notable = decodeStringList(row.notableTraitsJson)
            item("personality") { PersonalityCard(row = row, notableTraits = notable) }
            item("stats") { StatsCard(row = row) }
            row.relationshipSummary?.takeIf { it.isNotBlank() }?.let {
                item("summary") { MemoryCard(title = "relationship summary", body = it) }
            }
            row.userNotes?.takeIf { it.isNotBlank() }?.let {
                item("notes") { MemoryCard(title = "your notes", body = it) }
            }
            val topics = decodeStringList(row.topTopicsJson)
            if (topics.isNotEmpty()) {
                item("topics") { ChipsCard(title = "top topics", chips = topics) }
            }
        }
        // ─── Faces — user-curated face samples + auto-tagged
        //     photos. Available for every contact (system address-
        //     book row OR Mythara profile row OR even a bare nameKey
        //     with no analytics yet) since the user might want to
        //     seed face recognition before any chat history exists.
        val faceKey = data?.row?.nameKey ?: nameKey
        val faceDisplay = displayName
        if (data != null) {
            item("faces") {
                val samplesFlow = remember(faceKey) { vm.observeFaceSamplesFor(faceKey) }
                val samples by samplesFlow.collectAsState(initial = emptyList())
                val status by vm.sampleStatus.collectAsState()
                com.mythara.ui.analytics.FaceSamplesPanel(
                    displayName = faceDisplay,
                    nameKey = faceKey,
                    samples = samples,
                    status = status,
                    modelReady = vm.isFaceModelInstalled(),
                    backendLabel = vm.faceBackendLabel(),
                    onInstallModel = { vm.installFaceModel(faceKey) },
                    onAddSamples = { uris -> vm.addFaceSamples(faceKey, uris) },
                    onRemoveSample = { p -> vm.removeFaceSample(p) },
                    onClearStaleStatus = { vm.clearSampleStatus() },
                )
            }
            item("photos-of") {
                val photosFlow = remember(faceKey) { vm.observePhotosOf(faceKey) }
                val photos by photosFlow.collectAsState(initial = emptyList())
                com.mythara.ui.analytics.PhotosOfContactPanel(
                    displayName = faceDisplay,
                    photos = photos,
                )
            }
        }
        val inter = data?.interactions.orEmpty()
        if (inter.isNotEmpty()) {
            item("inter-h") { SectionHeader("◆ recent interactions") }
            items(inter.take(40)) { InteractionRow(r = it) }
        }
        if (data != null && data?.row == null && inter.isEmpty()) {
            item("none") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "no analytics yet for this contact — start chatting about them and Mythara will fill this page in",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item("end-pad") { Spacer(Modifier.height(40.dp)) }
    }
}

// ─── Header / actions ───────────────────────────────────────────────

@Composable
private fun HeaderCard(
    displayName: String,
    phone: String?,
    photoUri: String?,
    isFavorite: Boolean,
    hasWhatsApp: Boolean,
    onToggleFavorite: () -> Unit,
    onCall: () -> Unit,
    onSms: () -> Unit,
    onWaChat: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MytharaColors.Surface.copy(alpha = 0.80f))
            .border(1.dp, MytharaColors.Charple.copy(alpha = 0.45f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigAvatar(name = displayName, photoUri = photoUri)
            Spacer(Modifier.size(12.dp))
            // Name + phone in one Column expanded to push the
            // favorite star to the right edge. Tapping the star
            // toggles the curated FavoritesStore entry + the row's
            // is_favorite column (see ContactDetailViewModel.toggleFavorite).
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                phone?.let {
                    Text(
                        text = it,
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            FavoriteStar(isFavorite = isFavorite, onToggle = onToggleFavorite)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phone != null) {
                ContactActionChip("${Glyph.AccentBar} call", MytharaColors.Bok, onCall)
                ContactActionChip("✉ sms", MytharaColors.Malibu, onSms)
                if (hasWhatsApp) {
                    ContactActionChip("wa chat", MytharaColors.Charple, onWaChat)
                }
            } else {
                Text(
                    text = "no phone number on file",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Tappable favorite star sitting at the top-right of the header
 * card. Filled mustard "★" when this contact is in the curated
 * favorites list; outlined "☆" + muted color when not. Tap toggles
 * via [ContactDetailViewModel.toggleFavorite] which writes through
 * both the FavoritesStore and the profile row's is_favorite column.
 *
 * 40 dp circular hit area — comfortable thumb target without being
 * visually loud. Bg tints subtly when active so the star reads as a
 * status pill rather than a decoration.
 */
@Composable
private fun FavoriteStar(isFavorite: Boolean, onToggle: () -> Unit) {
    val bg = if (isFavorite) {
        MytharaColors.Mustard.copy(alpha = 0.18f)
    } else {
        MytharaColors.Surface.copy(alpha = 0.5f)
    }
    val border = if (isFavorite) {
        MytharaColors.Mustard.copy(alpha = 0.55f)
    } else {
        MytharaColors.SurfaceHigh.copy(alpha = 0.5f)
    }
    val starColor = if (isFavorite) MytharaColors.Mustard else MytharaColors.FgMute
    val glyph = if (isFavorite) "★" else "☆"
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = starColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun BigAvatar(name: String, photoUri: String?) {
    val photo = rememberContactPhoto(photoUri)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MytharaColors.Charple.copy(alpha = 0.32f))
            .border(2.dp, MytharaColors.Charple.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Text(
                text = initial,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * Resolve a contact-photo content URI (or any URI we can openInputStream
 * on) into an [ImageBitmap]. Loads async on Dispatchers.IO; returns
 * null while loading or on failure → caller falls back to the initial-
 * letter avatar.
 */
@Composable
private fun rememberContactPhoto(photoUri: String?): ImageBitmap? {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, key1 = photoUri) {
        val uri = photoUri?.takeIf { it.isNotBlank() }
        if (uri == null) { value = null; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val parsed = android.net.Uri.parse(uri)
                ctx.contentResolver.openInputStream(parsed)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }.value
}

@Composable
private fun ContactActionChip(label: String, color: Color, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

// ─── Stats / memory cards ───────────────────────────────────────────

@Composable
private fun StatsCard(row: ContactProfileRow) {
    DetailCard(title = "◆ stats") {
        StatRow("interactions", "${row.messageCount}")
        if (row.imageCount > 0) StatRow("photos shared", "${row.imageCount}")
        row.firstSeenMs.takeIf { it > 0 }?.let {
            StatRow("first seen", dateFmt.format(Date(it)))
        }
        row.lastInteractionMs.takeIf { it > 0 }?.let {
            StatRow("last interaction", dateFmt.format(Date(it)))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
        Text(value, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MemoryCard(title: String, body: String) {
    DetailCard(title = "◇ $title") {
        // Split on blank lines so multi-paragraph relationship
        // summaries breathe — single-line bodies render
        // identically. Bumped line-height for readability of dense
        // analytics text (line-height 1.45× the font size matches
        // editorial typography defaults).
        val paragraphs = remember(body) {
            body.trim().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
        }
        paragraphs.forEachIndexed { i, para ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Text(
                text = para,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = androidx.compose.ui.unit.TextUnit(
                        20f, androidx.compose.ui.unit.TextUnitType.Sp,
                    ),
                ),
            )
        }
    }
}

/**
 * Headline "who is this person" card — Big Five trait bars on top,
 * confidence disclaimer in the middle, and Mythara's prose
 * personality insights (`how to message them`) below in the same
 * card. Sits as the FIRST card under the header so the user reads
 * personality before raw stats.
 */
@Composable
private fun PersonalityCard(row: ContactProfileRow, notableTraits: List<String>) {
    DetailCard(title = "● personality — mythara's read") {
        // Notable traits ride at the TOP — short word-level tags
        // ("apologetic", "task-oriented") that summarise the
        // contact at a glance before the Big Five bars give the
        // quantitative read.
        if (notableTraits.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                notableTraits.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MytharaColors.Charple.copy(alpha = 0.18f))
                            .border(
                                1.dp,
                                MytharaColors.Charple.copy(alpha = 0.40f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = tag,
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        TraitBar("openness", row.openness, MytharaColors.Charple)
        TraitBar("conscientiousness", row.conscientiousness, MytharaColors.Malibu)
        TraitBar("extraversion", row.extraversion, MytharaColors.Bok)
        TraitBar("agreeableness", row.agreeableness, MytharaColors.Mustard)
        TraitBar("neuroticism", row.neuroticism, MytharaColors.Sriracha)
        Spacer(Modifier.height(6.dp))
        val n = row.bigFiveSampleSize
        val disclaimer = when {
            n == 0 -> "no facts observed yet — talk more about this person and Mythara will start estimating"
            n < 10 -> "estimated from $n facts — low confidence, keep chatting to sharpen"
            n < 30 -> "estimated from $n facts — moderate confidence"
            else -> "estimated from $n observed facts"
        }
        Text(
            text = disclaimer,
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
        // Prose insights — folded into the same card so personality
        // bars + the narrative read it grounds in arrive as one unit.
        // Multi-paragraph bodies get the same blank-line splitter +
        // 20 sp line-height treatment used by [MemoryCard], plus a
        // small accent divider above so the prose visually clicks
        // onto the bars without a hard card break.
        row.personalityInsights?.takeIf { it.isNotBlank() }?.let { insights ->
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MytharaColors.Charple.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "◇ how mythara reads them",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
            val paragraphs = remember(insights) {
                insights.trim().split(Regex("\\n\\s*\\n"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
            paragraphs.forEachIndexed { i, para ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Text(
                    text = para,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = androidx.compose.ui.unit.TextUnit(
                            20f, androidx.compose.ui.unit.TextUnitType.Sp,
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun TraitBar(label: String, value: Double?, color: Color) {
    // ContactProfileRow stores Big Five as 0..1 doubles (see
    // ContactProfiles.kt:60). The displayed pct is value × 100;
    // the bar fill is the raw 0..1 value directly. When the
    // dimension hasn't been estimated yet (sample size too small
    // for that contact / dimension) the underlying value is null
    // and we show "—" with a faint placeholder bar so the row still
    // hints at what gets tracked.
    val clamped = value?.coerceIn(0.0, 1.0)
    val barFill = clamped?.toFloat() ?: 0f
    val pctText = clamped?.let { (it * 100).toInt().toString() } ?: "—"
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = MytharaColors.FgMute, style = MaterialTheme.typography.labelSmall)
            Text(
                text = pctText,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { barFill },
            color = color,
            trackColor = MytharaColors.SurfaceHigh.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun ChipsCard(title: String, chips: List<String>) {
    DetailCard(title = "◇ $title") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { c ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MytharaColors.SurfaceHigh.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = c,
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MytharaColors.Surface.copy(alpha = 0.65f))
            .border(1.dp, MytharaColors.SurfaceHigh.copy(alpha = 0.6f), shape)
            .padding(12.dp),
    ) {
        Text(
            text = title,
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        color = MytharaColors.FgMute,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun InteractionRow(r: ContactInteractionRow) {
    val (glyph, color) = when (r.kind) {
        "message_sent" -> "↑ msg" to MytharaColors.Malibu
        "message_received" -> "↓ msg" to MytharaColors.Bok
        "call_outgoing" -> "↑ call" to MytharaColors.Charple
        "call_incoming" -> "↓ call" to MytharaColors.Bok
        "physical_meet" -> "● met" to MytharaColors.Citron
        "mention" -> "@ ref" to MytharaColors.Mustard
        else -> "·" to MytharaColors.FgMute
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Surface.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = glyph,
            color = color,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = dateFmt.format(Date(r.tsMs)),
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodySmall,
            )
            val sub = listOfNotNull(r.source, r.placeLabel, r.note)
                .joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

private fun decodeStringList(json: String): List<String> = runCatching {
    Json { ignoreUnknownKeys = true }.decodeFromString(
        ListSerializer(String.serializer()),
        json,
    )
}.getOrDefault(emptyList())

