package com.mythara.memory

import android.util.Log
import com.mythara.memory.github.GitHubClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of every OTHER Mythara device's last-known
 * heartbeat — a fast synchronous read for code paths that ask
 * "which Mythara installs are online right now?" without paying
 * the GitHub round-trip every time.
 *
 * Populated by [HeartbeatSyncer] (same 5-minute tick as the
 * heartbeat write loop itself), so the cache and the canonical
 * `device_messages/devices/` directory listing track each other
 * within one tick. The cache is otherwise dormant — no background
 * polling of its own.
 *
 * Self is excluded by construction at refresh time: the caller
 * is THIS device, it's never useful to surface "ourselves" as
 * a nearby device facet on an env-context snapshot.
 *
 * Why an in-memory cache at all (instead of just calling
 * `ListMytharaDevicesTool` per snapshot): [EnvironmentContext]
 * builds a Snapshot at every utterance-final boundary while
 * Observe mode is running — that's many calls a minute when
 * the user is speaking. Each call hitting GitHub would burn
 * battery + API quota for a value that only meaningfully
 * changes on a tens-of-minutes timescale.
 */
@Singleton
class DevicePresenceCache @Inject constructor(
    private val deviceIdStore: DeviceIdStore,
    private val memorySettings: MemorySettings,
) {
    @Volatile private var entries: Map<String, MemorySync.DevicePresence> = emptyMap()
    @Volatile private var lastRefreshMs: Long = 0L
    private val refreshLock = Mutex()

    /** Friendly labels (one per device — model name preferred, else
     *  manufacturer, else the device id) for every OTHER Mythara
     *  install whose last heartbeat write to the shared repo landed
     *  within [withinMs]. Synchronous — never hits IO. Returns
     *  whatever the most-recent [refreshFromHeartbeats] populated;
     *  empty list on cold start before the first successful tick. */
    fun nearbyLabels(withinMs: Long = DEFAULT_NEARBY_WINDOW_MS): List<String> {
        val now = System.currentTimeMillis()
        val snap = entries
        if (snap.isEmpty()) return emptyList()
        return snap.values
            .asSequence()
            .filter { now - it.lastSyncMs <= withinMs }
            .map { labelFor(it) }
            .filter { it.isNotBlank() }
            .toList()
    }

    /** Pull the canonical heartbeat directory from GitHub and
     *  replace the in-memory cache. Safe to call concurrently —
     *  a mutex serialises overlapping calls so the last writer
     *  wins. Errors are swallowed; on failure the cache simply
     *  keeps its previous value until the next successful pull. */
    suspend fun refreshFromHeartbeats() {
        refreshLock.withLock {
            val cfg = runCatching { memorySettings.snapshot() }.getOrNull() ?: return
            if (!cfg.configured) return
            val pat = cfg.pat ?: return
            val myId = runCatching { deviceIdStore.id() }.getOrNull()
            val client = GitHubClient(pat)
            val next = mutableMapOf<String, MemorySync.DevicePresence>()
            when (val r = client.listDirectory(cfg.owner, cfg.repo, "device_messages/devices")) {
                is GitHubClient.Outcome.Ok -> {
                    for (f in r.value) {
                        if (f.type != "file" || !f.name.endsWith(".json")) continue
                        val read = client.readFile(
                            cfg.owner, cfg.repo, "device_messages/devices/${f.name}",
                        )
                        if (read is GitHubClient.Outcome.Ok) {
                            runCatching {
                                JSON.decodeFromString(
                                    MemorySync.DevicePresence.serializer(),
                                    read.value.text,
                                )
                            }.onSuccess { presence ->
                                // Exclude self at write time so nearbyLabels()
                                // never needs to consult the id store.
                                if (presence.id != myId) next[presence.id] = presence
                            }.onFailure {
                                Log.w(TAG, "bad heartbeat ${f.name}: ${it.message}")
                            }
                        }
                    }
                    entries = next
                    lastRefreshMs = System.currentTimeMillis()
                    Log.d(TAG, "presence refresh: ${next.size} peer device(s) cached")
                }
                else -> {
                    // Leave cache untouched on failure — better to
                    // surface a slightly-stale facet than to drop
                    // proximity entirely because GitHub blipped.
                    Log.v(TAG, "presence refresh skipped (outcome=$r)")
                }
            }
        }
    }

    /** Timestamp of the last successful [refreshFromHeartbeats]
     *  pull, or 0 if we've never pulled. Exposed for diagnostics
     *  (SecretSettings live panel etc.) — not needed by callers. */
    fun lastRefreshMs(): Long = lastRefreshMs

    private fun labelFor(p: MemorySync.DevicePresence): String =
        when {
            p.model.isNotBlank() -> p.model
            p.manufacturer.isNotBlank() -> p.manufacturer
            else -> p.id
        }

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val TAG = "Mythara/DevicePresence"

        /** A peer device counts as "nearby" if its last heartbeat
         *  write landed within this window. Matches [HeartbeatSyncer]'s
         *  own 5-minute cadence so a healthy peer is always inside
         *  the window. */
        const val DEFAULT_NEARBY_WINDOW_MS = 5L * 60 * 1000
    }
}
