package com.mythara.secret.observe.vosk

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Mythara's local copies of Vosk speech models, one per
 * [Language]. At any moment one language is **active** — that's the
 * one [VoskAsr] loads into memory and ObserveSession transcribes
 * with. Other languages may be downloaded too (idle on disk, ready
 * to switch into) but only one Model object exists in RAM at a time.
 *
 * On-disk layout (after migration from M8.1b's single-language path):
 *
 *   filesDir/vosk-models/
 *     ├── en-US/  ← extracted Alpha Cephei model
 *     ├── es/
 *     ├── fr/
 *     └── …
 *
 *   cacheDir/
 *     ├── vosk-model-small-en-us-0.15.zip       (during download)
 *     └── vosk-model-small-en-us-0.15.zip.size  (Content-Length marker;
 *           cached zip only honoured when its byte length matches.)
 *
 * The active language is persisted in a per-store DataStore. Migrate
 * runs on construction to move M8.1b's `en-us-small` directory to the
 * new `en-US` location so existing users don't re-download.
 */
@Singleton
class VoskModelStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val lang: Language, val bytes: Long, val total: Long) : State {
            val pct: Int get() = if (total > 0) ((bytes * 100) / total).toInt() else 0
        }
        data class Extracting(val lang: Language) : State
        data class Ready(val lang: Language, val path: String) : State
        data class Failed(val lang: Language, val message: String) : State
    }

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("mythara_observe_settings")
    private val keyActiveLang = stringPreferencesKey("active.language.code")

    private val _activeState = MutableStateFlow<State>(State.Missing)
    val activeState: StateFlow<State> = _activeState.asStateFlow()

    /** Per-language availability map (true = extracted to disk). Updated whenever
     *  any path-touching operation completes — UI observes for picker glyphs. */
    private val _availability = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val availability: StateFlow<Map<String, Boolean>> = _availability.asStateFlow()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val modelsRoot: File get() = ctx.filesDir.resolve("vosk-models")

    init {
        migrateLegacyPath()
        refreshAvailability()
        // Lock the active language to English-US for the v1 simplified UI.
        // The multi-language plumbing (Language enum, per-lang download)
        // is left in code in case a future "advanced mode" toggle restores
        // the picker. Until then, Observe is English-only and Gemma
        // extracts in English regardless of source-language transcripts.
        runBlocking { setActiveLanguage(Language.EnglishUS) }
        val active = Language.EnglishUS
        _activeState.value = if (isExtractedFor(active)) {
            State.Ready(active, modelDirFor(active).absolutePath)
        } else State.Missing
    }

    fun modelDirFor(lang: Language): File = modelsRoot.resolve(lang.code)
    private fun zipTmpFor(lang: Language): File =
        ctx.cacheDir.resolve("${lang.modelName}.zip")
    private fun zipMarkerFor(lang: Language): File =
        ctx.cacheDir.resolve("${lang.modelName}.zip.size")

    fun activeLanguageFlow(): Flow<Language> = ctx.dataStore.data.map { p ->
        Language.fromCode(p[keyActiveLang])
    }

    suspend fun activeLanguage(): Language =
        Language.fromCode(ctx.dataStore.data.first()[keyActiveLang])

    suspend fun setActiveLanguage(lang: Language) {
        ctx.dataStore.edit { it[keyActiveLang] = lang.code }
        // Reflect new active in activeState immediately based on disk.
        _activeState.value = if (isExtractedFor(lang)) {
            State.Ready(lang, modelDirFor(lang).absolutePath)
        } else State.Missing
    }

    fun isExtractedFor(lang: Language): Boolean {
        val dir = modelDirFor(lang)
        return dir.resolve("am").exists() && dir.resolve("conf").exists()
    }

    fun pathOrNullFor(lang: Language): String? =
        if (isExtractedFor(lang)) modelDirFor(lang).absolutePath else null

    fun isActiveReady(): Boolean = runBlocking { isExtractedFor(activeLanguage()) }

    /** Path of the currently-active language's extracted model, or null. */
    fun activePathOrNull(): String? = runBlocking { pathOrNullFor(activeLanguage()) }

    suspend fun ensureReadyFor(lang: Language): State = withContext(Dispatchers.IO) {
        if (isExtractedFor(lang)) {
            return@withContext State.Ready(lang, modelDirFor(lang).absolutePath).also {
                if (lang == activeLanguage()) _activeState.value = it
            }
        }
        runCatching {
            download(lang)
            extract(lang)
            cleanupZip(lang)
            refreshAvailability()
            State.Ready(lang, modelDirFor(lang).absolutePath).also {
                if (lang == activeLanguage()) _activeState.value = it
            }
        }.getOrElse { e ->
            Log.e(TAG, "fetch failed for ${lang.code}: ${e.message}", e)
            runCatching { if (zipTmpFor(lang).exists()) zipTmpFor(lang).delete() }
            runCatching { if (zipMarkerFor(lang).exists()) zipMarkerFor(lang).delete() }
            runCatching { if (modelDirFor(lang).exists()) modelDirFor(lang).deleteRecursively() }
            refreshAvailability()
            State.Failed(lang, e.message ?: e.javaClass.simpleName).also {
                if (lang == activeLanguage()) _activeState.value = it
            }
        }
    }

    suspend fun ensureReady(): State = ensureReadyFor(activeLanguage())

    fun forgetLanguage(lang: Language) {
        runCatching { if (modelDirFor(lang).exists()) modelDirFor(lang).deleteRecursively() }
        runCatching { if (zipTmpFor(lang).exists()) zipTmpFor(lang).delete() }
        runCatching { if (zipMarkerFor(lang).exists()) zipMarkerFor(lang).delete() }
        refreshAvailability()
        runBlocking {
            if (lang == activeLanguage()) _activeState.value = State.Missing
        }
    }

    fun forgetAll() {
        Language.entries.forEach { forgetLanguage(it) }
    }

    // Legacy convenience for callers still in the M8.1b world.
    fun isExtracted(): Boolean = isActiveReady()
    fun pathOrNull(): String? = activePathOrNull()
    fun forgetModel() = runBlocking { forgetLanguage(activeLanguage()) }

    /** Compatibility alias — old API surface from M8.1b. */
    val state: StateFlow<State> get() = activeState

    // ---------------------------------------------------------------- internals

    private fun migrateLegacyPath() {
        val legacy = modelsRoot.resolve("en-us-small")
        val target = modelsRoot.resolve(Language.EnglishUS.code)
        if (legacy.exists() && !target.exists()) {
            runCatching {
                modelsRoot.mkdirs()
                if (legacy.renameTo(target)) {
                    Log.d(TAG, "migrated legacy en-us-small → ${target.absolutePath}")
                }
            }
        }
    }

    private fun refreshAvailability() {
        val map = Language.entries.associate { it.code to isExtractedFor(it) }
        _availability.value = map
    }

    private fun isCachedZipComplete(lang: Language): Boolean {
        val zipTmp = zipTmpFor(lang)
        val marker = zipMarkerFor(lang)
        if (!zipTmp.exists() || !marker.exists()) return false
        val expected = marker.readText().trim().toLongOrNull() ?: return false
        return expected >= MIN_VALID_BYTES && zipTmp.length() == expected
    }

    private fun download(lang: Language) {
        val zipTmp = zipTmpFor(lang)
        val marker = zipMarkerFor(lang)
        if (isCachedZipComplete(lang)) {
            Log.d(TAG, "cached zip complete for ${lang.code}; skipping download")
            return
        }
        modelsRoot.mkdirs()
        ctx.cacheDir.mkdirs()
        if (zipTmp.exists()) zipTmp.delete()
        if (marker.exists()) marker.delete()

        val req = Request.Builder().url(lang.modelUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching ${lang.code} model")
            val body = resp.body ?: error("empty body fetching ${lang.code} model")
            val total = body.contentLength().coerceAtLeast(0L)
            _activeState.value = State.Downloading(lang, 0, total)
            body.byteStream().use { input ->
                FileOutputStream(zipTmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReportMs = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs > PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            _activeState.update { State.Downloading(lang, read, total) }
                        }
                    }
                }
            }
            val downloaded = zipTmp.length()
            if (total > 0 && downloaded != total) {
                runCatching { zipTmp.delete() }
                error("download truncated: got $downloaded, expected $total")
            }
            if (downloaded < MIN_VALID_BYTES) {
                runCatching { zipTmp.delete() }
                error("download too small ($downloaded bytes) — server probably returned an error page")
            }
            marker.writeText(downloaded.toString())
            Log.d(TAG, "downloaded ${lang.code}: $downloaded bytes")
        }
    }

    private fun extract(lang: Language) {
        _activeState.value = State.Extracting(lang)
        val modelDir = modelDirFor(lang)
        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.mkdirs()

        val zipTmp = zipTmpFor(lang)
        ZipInputStream(zipTmp.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name
                val stripped = name.substringAfter('/', missingDelimiterValue = name)
                if (stripped.isBlank()) { zin.closeEntry(); continue }
                val out = modelDir.resolve(stripped)
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { fos -> zin.copyTo(fos) }
                }
                zin.closeEntry()
            }
        }
        Log.d(TAG, "extracted ${lang.code} → ${modelDir.absolutePath}")
    }

    private fun cleanupZip(lang: Language) {
        runCatching { if (zipTmpFor(lang).exists()) zipTmpFor(lang).delete() }
        runCatching { if (zipMarkerFor(lang).exists()) zipMarkerFor(lang).delete() }
    }

    companion object {
        private const val TAG = "Mythara/Vosk"
        // Floor at 8MB catches HTML error pages without rejecting the
        // smaller language models (Portuguese 31MB, Vietnamese 32MB,
        // Turkish 35MB). The real correctness check is Content-Length
        // matching the recorded marker.
        private const val MIN_VALID_BYTES = 8L * 1024 * 1024
        private const val PROGRESS_REPORT_MS = 250L
    }
}
