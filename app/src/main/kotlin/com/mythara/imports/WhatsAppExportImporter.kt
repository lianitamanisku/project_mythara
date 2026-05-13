package com.mythara.imports

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses WhatsApp's "Export chat" `.txt` files.
 *
 * WhatsApp offers no public API for reading message history — the
 * on-disk SQLite database is encrypted with a key only the WhatsApp
 * process can read. The export feature is the only first-party path,
 * triggered by the user manually:
 *
 *   WhatsApp → open a chat → top-right kebab → more →
 *   Export chat → Without media → choose "Mythara" / Save to Files
 *
 * That produces a `.txt` like:
 *
 *   [12/3/2024, 10:42:15 AM] John Doe: hey
 *   [12/3/2024, 10:43:01 AM] You: not much
 *   12/3/24, 22:42 - John Doe: another format some locales use
 *
 * Two main formats:
 *   • bracketed: `[date, time] name: body`
 *   • dash:      `date, time - name: body`
 * Date/time layouts vary by locale; we try a handful of common ones
 * and skip lines we can't parse. The "You" sender marks the user's
 * own messages.
 */
@Singleton
class WhatsAppExportImporter @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /**
     * Outcome of an import. [imagePaths] are absolute paths to staged
     * JPEG/PNG files extracted from a `.zip` export, ready for the
     * throttled vision pass to ingest. Empty list for plain `.txt`
     * imports. These files live in app-private storage and are
     * deleted after the ingest finishes (or the next import wipes
     * the staging dir).
     */
    data class Outcome(
        val ok: Boolean,
        val messages: List<MessageRecord>,
        val imagePaths: List<String> = emptyList(),
        val detail: String? = null,
    )

    suspend fun import(fileUri: Uri): Outcome = withContext(Dispatchers.IO) {
        // Sniff the mime / filename to decide between zip and plain text.
        // Don't trust just the type from the picker — Files app on some
        // OEMs returns application/octet-stream for .zip and text/plain
        // for .txt regardless of actual content. Magic-byte check the
        // first 4 bytes when in doubt.
        val isZip = isZipUri(fileUri)
        return@withContext if (isZip) importFromZip(fileUri) else importFromText(fileUri)
    }

    private fun importFromText(fileUri: Uri): Outcome {
        val stream = runCatching { ctx.contentResolver.openInputStream(fileUri) }
            .getOrNull()
            ?: return Outcome(false, emptyList(), detail = "Couldn't open the chosen file.")

        val out = mutableListOf<MessageRecord>()
        var skipped = 0
        try {
            BufferedReader(InputStreamReader(stream)).use { reader ->
                parseTranscript(reader, out, onSkip = { skipped++ })
            }
        } catch (t: Throwable) {
            return Outcome(false, emptyList(), detail = "Couldn't read the export file: ${t.message}")
        }
        Log.d(TAG, "imported ${out.size} text messages (skipped $skipped unparseable lines)")
        return Outcome(ok = true, messages = out)
    }

    /**
     * Process a `.zip` export. WhatsApp's "Export chat → With media"
     * produces a zip containing:
     *   - `_chat.txt`         — the transcript (same format as plain .txt)
     *   - `IMG-*.jpg` etc.    — referenced inline as `<attached: IMG…>`
     *   - `VID-*.mp4`, `AUD-*.opus` — we ignore non-image media for now
     *
     * Behaviour:
     *   1. Wipe the staging dir from any previous import.
     *   2. Stream the zip entries: parse `_chat.txt` in-memory, extract
     *      every image to staging dir up to [MAX_IMAGES] / [MAX_IMAGE_BYTES].
     *   3. Return a single Outcome with messages + imagePaths.
     *
     * The caller is responsible for kicking off the vision-ingest job
     * over [Outcome.imagePaths]. We don't fire it here — that decision
     * belongs to the import panel (throttled background WorkRequest)
     * not the importer (one-shot parse).
     */
    private fun importFromZip(fileUri: Uri): Outcome {
        val stream = runCatching { ctx.contentResolver.openInputStream(fileUri) }
            .getOrNull()
            ?: return Outcome(false, emptyList(), detail = "Couldn't open the chosen zip.")

        val stagingDir = stagingDir()
        // Wipe stale staging. The previous import may have left files
        // around if the throttled vision pass got interrupted.
        runCatching { stagingDir.listFiles()?.forEach { it.delete() } }
        stagingDir.mkdirs()

        val messages = mutableListOf<MessageRecord>()
        val imagePaths = mutableListOf<String>()
        var skipped = 0
        var transcriptFound = false
        var imagesSkippedBySize = 0
        var imagesSkippedByCap = 0

        try {
            ZipInputStream(stream).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val name = entry.name
                    // Defence against zip-slip: refuse any path with
                    // ".." or that would write outside staging dir.
                    if (name.contains("..") || name.startsWith("/")) {
                        zin.closeEntry(); continue
                    }
                    when {
                        // Transcript file — varies by locale but always
                        // ends `.txt` and contains "chat" somewhere. The
                        // canonical name from WhatsApp is "_chat.txt".
                        name.endsWith(".txt", ignoreCase = true) &&
                            (name.contains("chat", ignoreCase = true) || name.equals("_chat.txt", ignoreCase = true)) -> {
                            transcriptFound = true
                            // Non-closing reader so we can keep iterating
                            // entries after this one.
                            val reader = BufferedReader(InputStreamReader(NonClosingInput(zin)))
                            parseTranscript(reader, messages, onSkip = { skipped++ })
                        }
                        isImageName(name) -> {
                            if (imagePaths.size >= MAX_IMAGES) {
                                imagesSkippedByCap++
                                zin.closeEntry()
                                continue
                            }
                            val safeName = sanitiseEntryName(name)
                            val out = File(stagingDir, safeName)
                            val ok = copyEntryTo(NonClosingInput(zin), out)
                            if (!ok) {
                                imagesSkippedBySize++
                                runCatching { out.delete() }
                            } else if (out.length() > MAX_IMAGE_BYTES) {
                                imagesSkippedBySize++
                                runCatching { out.delete() }
                            } else {
                                imagePaths.add(out.absolutePath)
                            }
                        }
                        else -> {
                            // Videos, audio, other media — ignore. The
                            // user can re-export "without media" if
                            // they just want the text path.
                        }
                    }
                    zin.closeEntry()
                }
            }
        } catch (t: Throwable) {
            return Outcome(false, emptyList(), detail = "Couldn't read the zip: ${t.message}")
        }

        if (!transcriptFound && messages.isEmpty()) {
            return Outcome(false, emptyList(), detail = "Zip didn't contain a _chat.txt transcript. Pick the file WhatsApp produced from 'Export chat'.")
        }

        Log.d(
            TAG,
            "imported ${messages.size} text messages + ${imagePaths.size} images " +
                "from zip (skipped $skipped unparseable lines, $imagesSkippedBySize oversized, $imagesSkippedByCap over-cap)",
        )
        return Outcome(
            ok = true,
            messages = messages,
            imagePaths = imagePaths,
        )
    }

    /**
     * Parse a transcript stream into [out]. Shared between the plain-
     * text and zip paths. We accumulate continuation lines into the
     * previous message's body until we hit a new dated line or cap.
     */
    private fun parseTranscript(
        reader: BufferedReader,
        out: MutableList<MessageRecord>,
        onSkip: () -> Unit,
    ) {
        var pending: MessageRecord? = null
        while (true) {
            val line = reader.readLine() ?: break
            val parsed = parseLine(line)
            if (parsed != null) {
                pending?.let { out.add(it) }
                pending = parsed
            } else if (pending != null && line.isNotBlank()) {
                pending = pending.copy(text = pending.text + "\n" + line.trim())
            } else {
                onSkip()
            }
            if (out.size + (pending?.let { 1 } ?: 0) >= MAX_MESSAGES) break
        }
        pending?.let { out.add(it) }
    }

    private fun isZipUri(uri: Uri): Boolean {
        val mime = ctx.contentResolver.getType(uri)?.lowercase()
        if (mime == "application/zip" || mime == "application/x-zip-compressed") return true
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }?.lowercase()
        if (name?.endsWith(".zip") == true) return true
        // Magic-byte sniff. PK\x03\x04 marks every zip header.
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val head = ByteArray(4)
                val read = s.read(head)
                read == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                    head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
            } ?: false
        }.getOrElse { false }
    }

    private fun isImageName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun sanitiseEntryName(name: String): String {
        val basename = name.substringAfterLast('/').substringAfterLast('\\')
        return basename.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "img_${System.nanoTime()}.jpg" }
    }

    private fun copyEntryTo(input: InputStream, dest: File): Boolean {
        return runCatching {
            FileOutputStream(dest).use { fos ->
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_IMAGE_BYTES) {
                        // Stop copying; the caller deletes the partial file.
                        fos.close()
                        return@runCatching false
                    }
                    fos.write(buf, 0, n)
                }
                true
            }
        }.getOrElse { false }
    }

    fun stagingDir(): File = File(ctx.filesDir, "imports/wa_images").also { it.mkdirs() }

    /**
     * Wipe staging dir. Called by the image ingester when it's done
     * (or the next import) — we never want to keep raw image bytes
     * around once their learnings are in the vault.
     */
    fun clearStaging() {
        runCatching { stagingDir().listFiles()?.forEach { it.delete() } }
    }

    /**
     * Wrapper that ignores [InputStream.close] — `ZipInputStream`'s
     * per-entry streams must not be closed by readers, or the
     * underlying ZIP iteration breaks.
     */
    private class NonClosingInput(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun read(b: ByteArray): Int = inner.read(b)
        override fun close() { /* no-op — owner closes the ZipInputStream */ }
    }

    /**
     * Try to parse a single WhatsApp export line. Returns null when
     * the line is a continuation, blank, or unparseable.
     */
    private fun parseLine(line: String): MessageRecord? {
        // Bracketed format: [12/3/2024, 10:42:15 AM] Sender: body
        BRACKETED.matchEntire(line)?.let { m ->
            val dateTime = m.groupValues[1]
            val sender = m.groupValues[2].trim()
            val body = m.groupValues[3].trim()
            val ts = parseDateTime(dateTime) ?: System.currentTimeMillis()
            return makeRecord(sender, body, ts)
        }
        // Dash format: 12/3/24, 22:42 - Sender: body
        DASH.matchEntire(line)?.let { m ->
            val dateTime = m.groupValues[1]
            val sender = m.groupValues[2].trim()
            val body = m.groupValues[3].trim()
            val ts = parseDateTime(dateTime) ?: System.currentTimeMillis()
            return makeRecord(sender, body, ts)
        }
        return null
    }

    private fun makeRecord(sender: String, body: String, ts: Long): MessageRecord {
        // System-event lines (encryption notice, "Messages and calls
        // are end-to-end encrypted") have no contact prefix — sender
        // is the whole line. We skip those.
        if (body.isBlank()) return MessageRecord(
            source = "whatsapp", tsMillis = ts, isFromUser = false,
            contact = null, text = "",
        )
        val isFromUser = sender.equals("you", ignoreCase = true)
        return MessageRecord(
            source = "whatsapp",
            tsMillis = ts,
            isFromUser = isFromUser,
            contact = sender.takeIf { !isFromUser },
            text = body,
        )
    }

    /**
     * Try a handful of date-time format strings in order. WhatsApp
     * adapts to the device locale at export time so we have to be
     * forgiving. Returns epoch ms or null when nothing matches.
     */
    private fun parseDateTime(s: String): Long? {
        for (fmt in DATE_FORMATS) {
            val ts = runCatching {
                val parser = SimpleDateFormat(fmt, Locale.US)
                parser.isLenient = true
                parser.parse(s)?.time
            }.getOrNull()
            if (ts != null) return ts
        }
        return null
    }

    companion object {
        private const val TAG = "Mythara/WAImport"
        private const val MAX_MESSAGES = 3_000

        /**
         * Per-image upper bound. Larger images get skipped at extract
         * time — the throttled vision pass caps payloads anyway, and
         * staging giant photos eats device storage we'd just delete.
         */
        private const val MAX_IMAGE_BYTES = 6L * 1024L * 1024L

        /**
         * Hard cap on extracted images. A typical WhatsApp chat can
         * have thousands of photos; even at the throttled vision cadence
         * (60s/image default) the user doesn't want to wait 24h. We
         * keep the first N image entries; later images are dropped.
         * Real-world it's enough — extracted personality traits saturate
         * after ~100 images and the heuristic over the transcript carries
         * the rest of the signal.
         */
        private const val MAX_IMAGES = 200

        /** `[12/3/2024, 10:42:15 AM] Name: body`  — Android / web format */
        private val BRACKETED = Regex("""^\[(.+?)] ([^:]+): (.*)$""")

        /** `12/3/24, 22:42 - Name: body`  — older format some locales still emit */
        private val DASH = Regex("""^([0-9/.,: APM-]+) - ([^:]+): (.*)$""")

        /** Common WhatsApp date/time formats. We try them in order. */
        private val DATE_FORMATS = listOf(
            "M/d/yyyy, h:mm:ss a",
            "M/d/yy, h:mm:ss a",
            "M/d/yy, HH:mm:ss",
            "M/d/yy, HH:mm",
            "M/d/yy, h:mm a",
            "d/M/yy, HH:mm",
            "d/M/yy, h:mm a",
            "d/M/yyyy, HH:mm",
            "yyyy-MM-dd, HH:mm:ss",
            "dd.MM.yy, HH:mm",
        )
    }
}
