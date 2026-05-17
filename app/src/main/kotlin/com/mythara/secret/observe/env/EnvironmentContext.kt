package com.mythara.secret.observe.env

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Environment context for every Observe-mode utterance.
 *
 * Phase G.4 — Observe captures audio + text + acoustic features
 * already; this collector adds the surrounding context (am I in a
 * meeting? where am I? is my watch nearby? is the room loud?) so
 * a future "what did I say while in a meeting yesterday" or "show
 * me the loudest conversations from last week" query can filter
 * by env facet without re-parsing timestamps against calendar etc.
 *
 * Snapshots are computed AT EACH UTTERANCE-FINAL boundary — never
 * polled, never streamed. The cost per call is bounded:
 *   • Calendar — single ContentResolver query over a 5-minute
 *     window centred on now; no remote calls.
 *   • Paired devices — reads the in-process `WatchDevicePresence`
 *     /  `DeviceConnectivity` snapshots (already maintained by
 *     the wear sync workers; we just observe their latest value).
 *   • Ambient energy — relayed from the AcousticAnalyzer feature
 *     bucketing (`energy:loud` / `energy:quiet`), but we also
 *     surface a friendlier `env:loud` / `env:quiet` facet so
 *     consumers don't need to remember the acoustic vocabulary.
 *
 * Privacy: all signals are LOCAL state on the user's device. No
 * GPS reverse-geocode, no network calls, no third-party-content
 * exposure. The facets ride through MemorySync's normal sync
 * filter so they appear in the synced semantic-tier records the
 * Gemma/heuristic extractor promotes from this transcript — never
 * in the raw working-tier transcript itself (which stays local
 * per the existing privacy gate).
 */
@Singleton
class EnvironmentContext @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /** A one-shot read of the user's surrounding context at the
     *  moment an utterance completed. Cheap to construct — every
     *  field is a quick local query, no IO past the calendar
     *  ContentResolver. */
    data class Snapshot(
        val inMeeting: Boolean,
        val meetingTitle: String?,
        val ambient: AmbientLevel,
        val nearbyDeviceLabels: List<String>,
    ) {
        /** Convert to LearningVault facet strings. Empty list when
         *  no signal worth recording (e.g. quiet room, no meeting,
         *  no nearby devices = nothing notable to tag). */
        fun toFacets(): List<String> = buildList {
            if (inMeeting) add("env:meeting")
            when (ambient) {
                AmbientLevel.Loud -> add("env:loud")
                AmbientLevel.Quiet -> add("env:quiet")
                AmbientLevel.Normal -> { /* don't pollute with a 'normal' tag */ }
            }
            for (label in nearbyDeviceLabels) {
                add("proximity:${label.lowercase().trim().replace(' ', '-')}")
            }
        }
    }

    enum class AmbientLevel { Loud, Normal, Quiet }

    /** Build a fresh [Snapshot] for the moment this is called.
     *  Wrapped in runCatching so a transient permission or query
     *  failure never propagates into the audio loop. */
    fun snapshot(): Snapshot {
        val inMeeting = runCatching { isInMeetingNow() }.getOrDefault(false)
        val meetingTitle = if (inMeeting) {
            runCatching { currentMeetingTitle() }.getOrNull()
        } else null
        val devices = runCatching { nearbyDeviceLabels() }.getOrDefault(emptyList())
        return Snapshot(
            inMeeting = inMeeting,
            meetingTitle = meetingTitle,
            ambient = AmbientLevel.Normal, // ambient is tagged by AcousticAnalyzer already
            nearbyDeviceLabels = devices,
        )
    }

    /** Combine the freshly-computed [AmbientLevel] from the acoustic
     *  pipeline into a [Snapshot]. The acoustic analyser already
     *  computes RMS; ObserveSession calls this when it has both the
     *  ambient bucket and the env-context ready. */
    fun snapshotWithAmbient(ambient: AmbientLevel): Snapshot =
        snapshot().copy(ambient = ambient)

    /** "Is the user inside a calendar event right now?" — looks for
     *  any CalendarContract event whose [begin, end] window covers
     *  `System.currentTimeMillis()`. Requires READ_CALENDAR; returns
     *  false silently when missing. */
    private fun isInMeetingNow(): Boolean {
        if (ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_CALENDAR,
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        val now = System.currentTimeMillis()
        val uri: Uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { b ->
            ContentUris.appendId(b, now - 60_000L)
            ContentUris.appendId(b, now + 60_000L)
            b.build()
        }
        val proj = arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END)
        return runCatching {
            ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                c.count > 0
            } ?: false
        }.getOrDefault(false)
    }

    /** Title of the meeting the user is currently in (first match
     *  in the same window query). Returns null when not in a
     *  meeting OR when title can't be read. */
    private fun currentMeetingTitle(): String? {
        if (ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_CALENDAR,
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val now = System.currentTimeMillis()
        val uri: Uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { b ->
            ContentUris.appendId(b, now - 60_000L)
            ContentUris.appendId(b, now + 60_000L)
            b.build()
        }
        val proj = arrayOf(CalendarContract.Instances.TITLE)
        return runCatching {
            ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    /** Best-effort "what other Mythara-friendly devices are within
     *  arm's reach right now". Pulls from the wear-sync workers'
     *  in-process state cache — we only report a device that's
     *  reported a recent heartbeat (within the last 5 minutes).
     *
     *  For now this returns an empty list — Phase G ships the
     *  facet shape so future wear-presence + BLE collectors can
     *  populate it without modifying the vault writer. */
    private fun nearbyDeviceLabels(): List<String> {
        // TODO(env): wire to WatchDevicePresence + BLE scanner
        //  caches. Returning empty now so the facet hook is in
        //  place; populating it is a follow-up that doesn't need
        //  any changes to ObserveSession or the vault writer.
        return emptyList()
    }

    companion object {
        private const val TAG = "Mythara/EnvCtx"
    }
}
