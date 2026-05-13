package com.mythara.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.imports.ImageIngestProgress
import com.mythara.imports.ImageIngestScheduler
import com.mythara.imports.MessagePersonaExtractor
import com.mythara.imports.SmsImporter
import com.mythara.imports.WhatsAppExportImporter
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessageImportPanelViewModel @Inject constructor(
    private val smsImporter: SmsImporter,
    private val waImporter: WhatsAppExportImporter,
    private val extractor: MessagePersonaExtractor,
    private val imageIngestScheduler: ImageIngestScheduler,
    val imageIngestProgress: ImageIngestProgress,
    private val analyticsBuilder: com.mythara.analytics.ContactAnalyticsBuilder,
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun importSms() {
        viewModelScope.launch {
            _busy.value = true
            _status.value = "${Glyph.Ellipsis} reading SMS history…"
            val out = runCatching { smsImporter.import() }.getOrElse {
                _status.value = "× ${it.message ?: "import failed"}"
                _busy.value = false
                return@launch
            }
            if (!out.ok) {
                _status.value = "× ${out.detail ?: "couldn't read SMS"}"
                _busy.value = false
                return@launch
            }
            _status.value = "${Glyph.Ellipsis} analysing ${out.messages.size} messages (Gemma pass may take ~2 min)…"
            val report = runCatching { extractor.extractAndPersist("sms", out.messages) }
                .getOrElse {
                    _status.value = "× analysis failed: ${it.message}"
                    _busy.value = false
                    return@launch
                }
            _status.value = "${Glyph.Check} learned ${report.recordsWritten} traits from ${report.messagesAnalyzed} SMS messages — rebuilding people profiles…"
            runCatching { analyticsBuilder.rebuildAll(force = true) }
            _status.value = "${Glyph.Check} learned ${report.recordsWritten} traits from ${report.messagesAnalyzed} SMS messages · people profiles updated"
            _busy.value = false
        }
    }

    fun importWhatsApp(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            var totalMessages = 0
            var totalRecords = 0
            var totalImages = 0
            var anyImagesQueued = false
            uris.forEachIndexed { idx, uri ->
                _status.value = "${Glyph.Ellipsis} parsing export ${idx + 1}/${uris.size}…"
                val out = runCatching { waImporter.import(uri) }.getOrElse {
                    _status.value = "× export ${idx + 1}: ${it.message ?: "parse failed"}"
                    return@forEachIndexed
                }
                if (!out.ok) {
                    _status.value = "× export ${idx + 1}: ${out.detail ?: "couldn't parse"}"
                    return@forEachIndexed
                }
                _status.value = "${Glyph.Ellipsis} analysing ${out.messages.size} messages from export ${idx + 1}/${uris.size}…"
                val report = runCatching { extractor.extractAndPersist("whatsapp", out.messages) }
                    .getOrElse {
                        _status.value = "× analysis failed (export ${idx + 1}): ${it.message}"
                        return@forEachIndexed
                    }
                totalMessages += report.messagesAnalyzed
                totalRecords += report.recordsWritten
                val imageCount = out.imagePaths.size
                totalImages += imageCount
                if (imageCount > 0) {
                    // Vision ingest scheduler picks up everything in
                    // the staging dir; calling startIngest() multiple
                    // times is REPLACE-policy so we trigger it ONCE
                    // after the loop. But mark that we have images.
                    anyImagesQueued = true
                }
            }
            // Vision ingest covers all newly-staged images at once.
            if (anyImagesQueued) imageIngestScheduler.startIngest()

            val tail = when {
                uris.size > 1 -> " across ${uris.size} exports"
                else -> ""
            }
            _status.value = "${Glyph.Ellipsis} ingested $totalRecords traits from $totalMessages messages$tail — rebuilding people profiles…"
            // Kick the analytics builder once at the end of the batch.
            // Imports are CUMULATIVE — every import adds rows; nothing
            // is overwritten. Importing the same chat twice will dedupe
            // at write time on the vault's sha column for identical
            // content, but slightly-different chunk boundaries will
            // still produce new rows (acceptable redundancy).
            runCatching { analyticsBuilder.rebuildAll(force = true) }
            val imageTail = if (anyImagesQueued) " · processing $totalImages photos in background" else ""
            _status.value = "${Glyph.Check} ingested $totalRecords traits from $totalMessages messages$tail · people profiles updated$imageTail"
            _busy.value = false
        }
    }

    fun cancelImageIngest() {
        imageIngestScheduler.cancel()
        imageIngestProgress.reset()
    }
}

/**
 * One-time import of message history. SMS via ContentProvider, WhatsApp
 * via the user-exported `.txt` (chat-only) or `.zip` (chat + media)
 * dump.
 *
 * Each import produces persona-trait vault records — top contacts, peak
 * hour, communication style, Gemma-extracted traits over outgoing
 * messages. Zip imports additionally kick off a throttled background
 * vision pass over the included images (one call every 60s by default)
 * which extracts durable traits like "the user spends time outdoors"
 * or "owns a cat". Raw messages and images are never persisted.
 */
@Composable
fun MessageImportPanel(vm: MessageImportPanelViewModel = hiltViewModel()) {
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val ingestState by vm.imageIngestProgress.state.collectAsState()
    val ctx = LocalContext.current

    val smsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.importSms()
    }
    // OpenMultipleDocuments lets the user pick several exports in
    // one shot — typical use case is "I've got 5 zips from different
    // contacts in my Downloads folder, ingest them all". Single
    // selection still works (returns a single-element list).
    val waFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.importWhatsApp(uris)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} import message history",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.READ_SMS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) vm.importSms()
                    else smsPermLauncher.launch(Manifest.permission.READ_SMS)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple,
                    contentColor = MytharaColors.Fg,
                ),
            ) {
                Text("${Glyph.Arrow} import SMS")
            }

            Button(
                enabled = !busy,
                onClick = {
                    // ACTION_OPEN_DOCUMENT with both text/plain AND
                    // application/zip — covers both the "Without media"
                    // (.txt) and "With media" (.zip) exports WhatsApp
                    // produces. The picker UI shows both file types as
                    // acceptable; on the importer side we sniff actual
                    // content (magic bytes + filename) rather than
                    // trusting the picker's mime.
                    waFilePicker.launch(arrayOf("text/plain", "application/zip", "*/*"))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Surface,
                    contentColor = MytharaColors.Fg,
                ),
            ) {
                Text("${Glyph.Arrow} import WhatsApp (multi-OK)")
            }
        }

        status?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = msg,
                color = if (msg.startsWith(Glyph.Check)) MytharaColors.Julep
                else if (msg.startsWith("×")) MytharaColors.Sriracha
                else MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Live image-ingest progress, rendered when an ingest job is
        // running or just finished. Lets the user see how the slow
        // background pass is progressing without burying it in logs.
        when (val s = ingestState) {
            is ImageIngestProgress.State.Idle -> {}
            is ImageIngestProgress.State.Running -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${Glyph.Ellipsis} ingesting photos: ${s.processed}/${s.total}" +
                            if (s.errors > 0) " · ${s.errors} skipped" else "",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { vm.cancelImageIngest() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface,
                            contentColor = MytharaColors.FgMute,
                        ),
                    ) {
                        Text("${Glyph.Cross} stop")
                    }
                }
            }
            is ImageIngestProgress.State.Done -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${Glyph.Check} photo ingest complete: ${s.processed}/${s.total}" +
                        if (s.errors > 0) " · ${s.errors} skipped" else "",
                    color = MytharaColors.Julep,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} Mythara reads your messaging history to learn patterns about you AND about each person you talk to. Imports are CUMULATIVE — you can import multiple exports (pick several at once on the file picker), including old backups from other devices, and every import ADDS to memory without overwriting earlier learnings. Text goes through the on-device Gemma model — raw messages NEVER leave your phone. WhatsApp .zip exports also include photos, which Mythara ingests slowly in the background (one image every 60 seconds by default) through your configured vision model (Gemini if set, otherwise MiniMax-VL); only the extracted traits land in Lumi's memory — the raw images are deleted as they're processed. The slow cadence keeps your vision API quota safe. Export chat in WhatsApp: kebab → More → Export chat → 'Without media' for fastest, 'With media' to also learn from photos. Pick one or many .txt / .zip files.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
