package com.mythara.secret.observe

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks the Observe scratch directory and deletes anything older than
 * its TTL. M8.1a today only manages the directory itself (the actual
 * file producers — AudioRecorder + Vosk transcripts — arrive in M8.1b);
 * having the purger ready means M8.1b can drop files into the same path
 * and trust they'll be cleaned without further wiring.
 *
 * TTLs match the architecture doc:
 *  - raw audio (.pcm)        : 60 seconds
 *  - transcripts (.txt)      : 24 hours
 *  - everything else         : touched-mtime + 7d (defensive)
 */
@Singleton
class RawDataPurger @Inject constructor(@ApplicationContext private val ctx: Context) {

    fun sweep() {
        val root = ctx.filesDir.resolve("observe")
        if (!root.exists()) return
        val now = System.currentTimeMillis()
        var deleted = 0
        root.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val age = now - f.lastModified()
            val ttl = when (f.extension.lowercase()) {
                "pcm", "wav" -> AUDIO_TTL_MS
                "txt", "json", "jsonl" -> TRANSCRIPT_TTL_MS
                else -> DEFENSIVE_TTL_MS
            }
            if (age > ttl) {
                if (f.delete()) deleted++
            }
        }
        if (deleted > 0) Log.d(TAG, "purged $deleted Observe scratch file(s)")
    }

    companion object {
        private const val TAG = "Mythara/Observe"
        const val AUDIO_TTL_MS = 60_000L         // 60s
        const val TRANSCRIPT_TTL_MS = 24L * 3_600_000L  // 24h
        const val DEFENSIVE_TTL_MS = 7L * 24 * 3_600_000L // 7d
    }
}
