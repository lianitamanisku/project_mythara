package com.mythara.memory

import android.util.Log
import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.data.SettingsStore
import com.mythara.growth.LearningJournal
import com.mythara.memory.github.GitHubClient
import com.mythara.memory.github.GitHubClient.Outcome
import com.mythara.minimax.Region
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes Mythara's durable state to a user-owned GitHub repo. Single
 * entry point per direction: [runSync] pushes; [runRestore] pulls.
 *
 * **Repo layout** (mobile-optimised, agentmemory-style tiers — see
 * github.com/rohitg00/agentmemory for the architectural pattern):
 *
 * ```
 * mythara_memory/
 *   README.md
 *   MEMORY.md                       — bridge file: top-K active records (Markdown)
 *   manifest.json                   — version + per-file SHA cache + lastSync
 *   working/<YYYY-MM-DD>.jsonl      — raw observations, one per line
 *   episodic/<YYYY-W##>.jsonl       — weekly session summaries  (M8.3+)
 *   semantic/facts.jsonl            — durable facts / preferences (M8.3+)
 *   procedural/workflows.jsonl      — action patterns           (M8.4+)
 *   settings/preferences.json       — region + model + non-secret prefs
 *   conversations/<YYYY-MM-DD>.jsonl — opt-in chat history per local day
 * ```
 *
 * Each memory record uses short keys (`t`, `src`, `conf`, `sha`, `ref`,
 * `seen`) for byte-efficient JSONL — see [MemoryRecord]. Compaction +
 * cross-tier promotion are M8.3+ work; today only the [Tier.Working]
 * tier is populated, with per-day partitioning. The tier directories
 * for episodic/semantic/procedural are written as empty placeholders
 * so they show up in `git ls-tree` and the layout reads correctly.
 *
 * **Privacy invariants** (enforced by code):
 *  - MiniMax API key, GitHub PAT, Tink keyset, Secret-mode password,
 *    Observe raw audio/transcripts — never written to this repo.
 *  - All record content runs through [SecretScrubber] before write.
 *  - The repo MUST be private. We don't enforce this — but the README
 *    and the create-repo flow set `private = true`.
 */
@Singleton
class MemorySync @Inject constructor(
    private val memorySettings: MemorySettings,
    private val journal: LearningJournal,
    private val appSettings: SettingsStore,
    private val history: HistoryRepository,
    private val vault: LearningVault,
    private val deviceIdStore: DeviceIdStore,
    private val contactProfiles: com.mythara.analytics.ContactProfileRepository,
    private val favorites: com.mythara.data.FavoritesStore,
    private val userAliases: com.mythara.data.UserAliasesStore,
    private val auditRepo: com.mythara.audit.AuditRepository,
    private val deviceMessageSync: com.mythara.memory.devices.DeviceMessageSync,
    private val lifelineRepo: com.mythara.lifeline.LifelineRepository,
    private val taskRepo: com.mythara.tasks.TaskRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val ctx: android.content.Context,
) {
    data class Report(
        val ok: Boolean,
        val message: String,
        val filesWritten: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
    )

    data class RestoreReport(
        val ok: Boolean,
        val message: String,
        val learningsRestored: Int = 0,
        val vaultRecordsRestored: Int = 0,
        val chatRowsRestored: Int = 0,
        val settingsRestored: Boolean = false,
        val filesRead: List<String> = emptyList(),
    )

    private val tag = "Mythara/Memory"

    /** Compact (no pretty-print) — bytes matter. Tolerant of unknown keys for forward compat. */
    private val json = Json {
        encodeDefaults = false
        prettyPrint = false
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** For the human-facing manifest only — pretty-printed. */
    private val manifestJson = Json {
        encodeDefaults = false
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun runSync(forcePush: Boolean = false): Report {
        val cfg = memorySettings.snapshot()
        if (!cfg.enabled) return Report(ok = false, message = "Memory sync disabled.")
        if (!cfg.configured) return Report(ok = false, message = "Set a GitHub token + repo in Settings.")
        val deviceId = deviceIdStore.id()

        val client = GitHubClient(cfg.pat!!)
        when (val v = client.validateToken()) {
            is Outcome.Ok -> Log.d(tag, "PAT ok for ${v.value}")
            is Outcome.Unauthorized -> return Report(ok = false, message = "GitHub token rejected.")
            else -> return Report(ok = false, message = "GitHub auth check failed.")
        }

        when (val r = client.ensureRepo(cfg.owner, cfg.repo, createIfMissing = true)) {
            is Outcome.Ok -> Log.d(tag, "repo ${r.value.fullName} ready (private=${r.value.private})")
            is Outcome.Unauthorized -> return Report(ok = false, message = "Token lacks `repo` scope.")
            is Outcome.Conflict -> return Report(ok = false, message = "Repo create rejected: ${r.message}")
            is Outcome.NotFound -> return Report(ok = false, message = "Repo missing and could not be created.")
            is Outcome.Error -> return Report(ok = false, message = "GitHub: ${r.message}")
        }

        val manifest: ManifestV2 = cfg.manifestJson?.let {
            runCatching { manifestJson.decodeFromString(ManifestV2.serializer(), it) }.getOrNull()
        } ?: ManifestV2()

        val now = System.currentTimeMillis()
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // README + MEMORY.md (always; they're cheap and the format docs need to be visible).
        putWithCache(client, cfg, README_PATH, README_BODY, manifest,
            "mythara: seed README", written, skipped)

        // Device heartbeat. Every sync writes (or refreshes)
        // device_messages/devices/<myId>.json so the canonical "list of
        // Mythara installs signed into this repo" can be derived by
        // listing that directory. Without this, a brand-new device
        // would be invisible to ListMytharaDevicesTool until either
        // someone sent it a cross-device message (which creates the
        // inbox file) or its audit log shipped enough rows to surface
        // it on the other side — both are too lazy.
        val heartbeatPath = "device_messages/devices/$deviceId.json"
        val installedAppsList = runCatching { queryInstalledLaunchablePackages() }
            .getOrDefault(emptyList())
        val heartbeatBody = json.encodeToString(
            DevicePresence.serializer(),
            DevicePresence(
                id = deviceId,
                model = android.os.Build.MODEL ?: "unknown",
                manufacturer = android.os.Build.MANUFACTURER ?: "unknown",
                androidSdk = android.os.Build.VERSION.SDK_INT,
                lastSyncMs = now,
                installedApps = installedAppsList,
            ),
        )
        // No putWithCache — heartbeats should rewrite every sync so the
        // lastSyncMs stays fresh. The cache skip would suppress that.
        runCatching {
            val existing = client.readFile(cfg.owner, cfg.repo, heartbeatPath)
            val sha = if (existing is com.mythara.memory.github.GitHubClient.Outcome.Ok) existing.value.sha else null
            client.writeFile(
                owner = cfg.owner, repo = cfg.repo, path = heartbeatPath,
                text = heartbeatBody,
                commitMessage = "mythara: device heartbeat $deviceId",
                branch = cfg.branch, previousSha = sha,
            )
        }.onFailure { Log.w(tag, "device heartbeat failed: ${it.message}") }

        // ---- working/<day>.jsonl  — current tier of raw learnings
        if (cfg.syncLearnings) {
            val entries = journal.read()
            val recordsByDay = entries.groupBy { isoLocalDate(it.tsMillis) }
            for ((day, dayEntries) in recordsByDay) {
                val body = dayEntries.joinToString("\n") { entry ->
                    val rec = entryToWorkingRecord(entry, deviceId)
                    json.encodeToString(MemoryRecord.serializer(), rec)
                }
                val path = "${Tier.Working.dir}/$day.jsonl"
                putWithCache(client, cfg, path, body, manifest,
                    "mythara: working/$day (+${dayEntries.size})", written, skipped)
            }

            // Update MEMORY.md bridge — top recent working records, human-readable.
            val memoryMd = renderMemoryBridge(entries, cfg)
            putWithCache(client, cfg, MEMORY_PATH, memoryMd, manifest,
                "mythara: bridge MEMORY.md", written, skipped)
        }

        // ---- semantic/<topic>.jsonl — durable extracted facts from the vault.
        //      We sync only `tier=semantic` records, never raw transcripts
        //      (working tier stays local-only per the privacy contract).
        //      Records are grouped by their first `topic:*` facet so the
        //      repo's semantic/ directory reads like a Karpathy-style wiki.
        val unsynced = vault.unsyncedRecords()
        if (unsynced.isNotEmpty()) {
            // -- semantic/<topic>.jsonl
            val semanticOnly = unsynced.filter { it.tier == Tier.Semantic.code }
            val byTopic: Map<String, List<com.mythara.secret.observe.vault.LearningEntity>> =
                semanticOnly.groupBy { entity ->
                    val facets = vault.decodeFacets(entity)
                    facets.firstOrNull { it.startsWith("topic:") }?.removePrefix("topic:")?.ifBlank { "misc" } ?: "misc"
                }
            for ((topic, records) in byTopic) {
                val body = records.joinToString("\n") { entity ->
                    json.encodeToString(MemoryRecord.serializer(), vault.toMemoryRecord(entity, dev = deviceId))
                }
                val path = "${Tier.Semantic.dir}/$topic.jsonl"
                putWithCache(client, cfg, path, body, manifest,
                    "mythara: semantic/$topic (+${records.size})", written, skipped)
                val syncTs = System.currentTimeMillis()
                for (r in records) vault.markSynced(r.id, syncTs)
            }

            // -- episodic/<YYYY-W##>.jsonl  (Gemma-summarised clusters
            //    from the SelfOrganizerWorker). Partitioned by ISO week
            //    so the repo's episodic/ directory reads as a temporal
            //    journal — drag-scroll through weeks to see what
            //    Mythara crystallised from each session.
            val episodicOnly = unsynced.filter { it.tier == Tier.Episodic.code }
            val byWeek: Map<String, List<com.mythara.secret.observe.vault.LearningEntity>> =
                episodicOnly.groupBy { isoWeek(it.tsMillis) }
            for ((week, records) in byWeek) {
                val body = records.joinToString("\n") { entity ->
                    json.encodeToString(MemoryRecord.serializer(), vault.toMemoryRecord(entity, dev = deviceId))
                }
                val path = "${Tier.Episodic.dir}/$week.jsonl"
                putWithCache(client, cfg, path, body, manifest,
                    "mythara: episodic/$week (+${records.size})", written, skipped)
                val syncTs = System.currentTimeMillis()
                for (r in records) vault.markSynced(r.id, syncTs)
            }

            // -- vault/working/<YYYY-MM-DD>.jsonl  (M5 part 2c)
            //    Working-tier vault rows whose source is *not* a raw
            //    Observe transcript — today that's notifications (src
            //    `notif:<pkg>`) and chat-derived working observations
            //    once we add them. Backing these up is the long-term
            //    memory contract: a fresh install on a new device
            //    should be able to pull this file and re-learn the
            //    user's notification history via SemanticRecall.
            //
            //    Privacy gate: src starting with `observe:transcript`
            //    or `observe:audio` stays local-only — raw on-device
            //    capture must not leave the device. The vault holds
            //    those rows so the local pipeline can promote them,
            //    but they're never serialised to GitHub.
            val workingTierSyncable = unsynced.filter { entity ->
                entity.tier == Tier.Working.code && isWorkingSrcSyncable(entity.src)
            }
            if (workingTierSyncable.isNotEmpty()) {
                val byDay = workingTierSyncable.groupBy { isoLocalDate(it.tsMillis) }
                for ((day, records) in byDay) {
                    val body = records.joinToString("\n") { entity ->
                        json.encodeToString(MemoryRecord.serializer(), vault.toMemoryRecord(entity, dev = deviceId))
                    }
                    val path = "vault/${Tier.Working.dir}/$day.jsonl"
                    putWithCache(client, cfg, path, body, manifest,
                        "mythara: vault/working/$day (+${records.size})", written, skipped)
                    val syncTs = System.currentTimeMillis()
                    for (r in records) vault.markSynced(r.id, syncTs)
                }
            }

            // Raw working-tier records that *aren't* syncable (Observe
            // transcripts) deliberately stay unsynced=false forever in
            // the local vault.
        }

        // ---- tier placeholders for episodic/procedural. semantic/ has
        //      real content above (or will once Observe runs); we still
        //      seed it with .gitkeep on the first sync if no records yet.
        for (tier in TIER_PLACEHOLDERS) {
            val path = "${tier.dir}/.gitkeep"
            if (!manifest.files.containsKey(path)) {
                putWithCache(client, cfg, path, PLACEHOLDER_BODY, manifest,
                    "mythara: seed ${tier.dir}/", written, skipped)
            }
        }

        // ---- settings/preferences.json (non-secret prefs only)
        if (cfg.syncSettings) {
            val snap = appSettings.snapshot()
            val obj = SettingsExport(region = snap.region.name, model = snap.model)
            val body = manifestJson.encodeToString(SettingsExport.serializer(), obj)
            putWithCache(client, cfg, "settings/preferences.json", body, manifest,
                "mythara: sync settings", written, skipped)
        }

        // ---- conversations per-day (opt-in)
        if (cfg.syncChat) {
            val rows = history.dao.listAll()
            val byDay = rows.groupBy { isoLocalDate(it.tsMillis) }
            for ((day, dayRows) in byDay) {
                val body = dayRows.joinToString("\n") {
                    json.encodeToString(ChatRowExport.serializer(), ChatRowExport(
                        t = it.tsMillis,
                        role = it.role,
                        content = SecretScrubber.scrub(it.content.orEmpty()).ifBlank { null },
                        toolCallsJson = it.toolCallsJson,
                        toolCallId = it.toolCallId,
                        name = it.name,
                        dev = deviceId,
                    ))
                }
                val path = "conversations/$day.jsonl"
                putWithCache(client, cfg, path, body, manifest,
                    "mythara: chat $day", written, skipped)
            }
        }

        // ---- analytics — DERIVED LEARNINGS, never raw payloads.
        //      The learning vault above already carries the raw
        //      per-contact message-history + observe-tier rows
        //      (sha-deduped on insert). This section adds the
        //      Gemma-distilled output: per-contact profiles +
        //      curated favorites + user aliases. Cheap to write
        //      (small JSON), expensive to rebuild on a new device
        //      from scratch (~40s/contact Gemma run), so syncing
        //      them is a big win for cross-device continuity.
        //
        //      What's in each file:
        //        analytics/contact_profiles.jsonl  — one row per
        //          contact: name + Big Five + summary + key points
        //          + personality insights + user notes
        //        analytics/favorites.json — the curated favorites
        //          list with tones + app allowlists
        //        analytics/user_aliases.json — "this is me" labels
        //          (names + phones the user has marked as
        //          themselves). Critical for cross-device imports —
        //          without these synced, every new device repeats
        //          the import-preview alias dance.
        //
        //      All three are LEARNINGS / CURATIONS only; no raw
        //      images, audio, zips, or API keys touch the repo.
        if (cfg.syncLearnings) {
            val profiles = runCatching { contactProfiles.dao.listAll() }.getOrDefault(emptyList())
            if (profiles.isNotEmpty()) {
                val body = profiles.joinToString("\n") { row ->
                    json.encodeToString(ContactProfileExport.serializer(), row.toExport(deviceId))
                }
                putWithCache(
                    client, cfg, "analytics/contact_profiles.jsonl", body, manifest,
                    "mythara: contact profiles (${profiles.size})", written, skipped,
                )
            }

            val favs = runCatching { favorites.list() }.getOrDefault(emptyList())
            val favBody = manifestJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(FavoriteExport.serializer()),
                favs.map { FavoriteExport(name = it.name, phone = it.phone, apps = it.apps, enabled = it.enabled, tone = it.toneLabel) },
            )
            putWithCache(
                client, cfg, "analytics/favorites.json", favBody, manifest,
                "mythara: favorites (${favs.size})", written, skipped,
            )

            val aliases = runCatching { userAliases.list() }.getOrDefault(emptyList())
            val aliasBody = manifestJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(UserAliasExport.serializer()),
                aliases.map { UserAliasExport(name = it.name, phone = it.phone) },
            )
            putWithCache(
                client, cfg, "analytics/user_aliases.json", aliasBody, manifest,
                "mythara: user aliases (${aliases.size})", written, skipped,
            )

            // analytics/audit_log.jsonl — every tool call / redirect /
            // user-cancel / system event. Each row carries the device
            // id stamped at write-time, so a multi-device repo lets
            // each device see ALL devices' actions (rendered in the
            // panel with a dev:xxxxxx tag on foreign entries).
            //
            // Cross-device dedup via (tsMillis + toolName + deviceId
            // + argsPreview) on restore — same write from the same
            // device at the same millisecond can't conflict, two
            // different devices firing the same tool at the same
            // ms produce distinct rows.
            val auditEntries = runCatching { auditRepo.dao.listRecent(limit = 5_000) }
                .getOrDefault(emptyList())
            if (auditEntries.isNotEmpty()) {
                val body = auditEntries.joinToString("\n") { entry ->
                    json.encodeToString(AuditExport.serializer(), entry.toExport(deviceId))
                }
                putWithCache(
                    client, cfg, "analytics/audit_log.jsonl", body, manifest,
                    "mythara: audit log (${auditEntries.size})", written, skipped,
                )
            }
        }

        // ---- lifeline/<YYYY-MM>.jsonl — life-timeline photo metadata
        //      + caption, partitioned by month. Photo bytes NEVER leave
        //      the originating device; only the metadata + caption
        //      ships, so the cross-device timeline reads "photo on
        //      phone-B" when scrolling on phone-A without paying any
        //      storage / bandwidth penalty for the raw image bytes.
        if (cfg.syncLearnings) {
            val unsyncedLifeline = runCatching { lifelineRepo.dao.listUnsynced() }.getOrDefault(emptyList())
            if (unsyncedLifeline.isNotEmpty()) {
                val byMonth: Map<String, List<com.mythara.lifeline.LifelineEntity>> =
                    unsyncedLifeline.groupBy { isoYearMonth(it.takenMs) }
                for ((month, rows) in byMonth) {
                    val body = rows.joinToString("\n") { row ->
                        json.encodeToString(LifelineExport.serializer(), row.toExport())
                    }
                    val path = "lifeline/$month.jsonl"
                    // Line-union merge: same row id (device:mediaStoreId)
                    // gets deduped via writeFileMerging's caller-supplied
                    // merge fn. We just newline-append; the JSON Lines
                    // semantics + (device_id + media_store_id) tuple keep
                    // each device's writes idempotent.
                    putWithCache(
                        client, cfg, path, body, manifest,
                        "mythara: lifeline/$month (+${rows.size})", written, skipped,
                    )
                }
                val syncedNow = System.currentTimeMillis()
                lifelineRepo.dao.markSynced(unsyncedLifeline.map { it.id }, syncedNow)
            }
        }

        // ---- tasks/<deviceId>/<YYYY-MM>.jsonl — cross-device task
        //      queue, per-device-file layout. THIS device writes only
        //      its own `tasks/<deviceId>/` subtree (every row it
        //      created or claimed), so there is exactly one writer per
        //      file — concurrent task writes from different devices can
        //      never collide, and the old shared-file 409→corruption
        //      failure mode is structurally impossible. Readers union
        //      every device's files and reconcile per task id.
        if (cfg.syncLearnings) {
            // Publish the FULL set of rows this device owns EVERY sync —
            // not just unsynced ones. The per-device file must be a
            // complete view (a partial write would shrink it and drop
            // rows), and publishing unconditionally is what re-seeds the
            // new layout after a migration / fresh repo. putWithCache's
            // content-hash cache makes a no-change sync a cheap no-op
            // (the file lands in `skipped`, no network write).
            val owned = runCatching { taskRepo.dao.listOwnedBy(deviceId) }.getOrDefault(emptyList())
            if (owned.isNotEmpty()) {
                val byMonth: Map<String, List<com.mythara.tasks.TaskEntity>> =
                    owned.groupBy { isoYearMonth(it.createdMs) }
                // Only mark a row synced once its file ACTUALLY landed
                // (path in `written` = pushed, or `skipped` = already
                // current remote). Anything else means the PUT failed
                // and the rows stay unsynced for the next heartbeat.
                val syncedIds = mutableListOf<String>()
                for ((month, rows) in byMonth) {
                    val body = rows.joinToString("\n") { row ->
                        json.encodeToString(TaskExport.serializer(), row.toExport())
                    }
                    val path = "tasks/$deviceId/$month.jsonl"
                    putWithCache(
                        client, cfg, path, body, manifest,
                        "mythara: tasks/$deviceId/$month (${rows.size})", written, skipped,
                    )
                    if (path in written || path in skipped) {
                        syncedIds.addAll(rows.map { it.id })
                    } else {
                        Log.w(tag, "tasks/$deviceId/$month push did not land — leaving rows unsynced for retry")
                    }
                }
                if (syncedIds.isNotEmpty()) {
                    taskRepo.dao.markSynced(syncedIds, System.currentTimeMillis())
                }
            }

            // One-time legacy cleanup: the old layout wrote a single
            // shared `tasks/<YYYY-MM>.jsonl`. Those rows have already
            // been pulled into every device's DB and re-published under
            // the per-device layout above, so the flat file is now
            // redundant — delete it. Whichever device syncs first does
            // this; the others find it already gone (deleteFile treats
            // a missing file as success).
            runCatching {
                val listing = client.listDirectory(cfg.owner, cfg.repo, "tasks")
                if (listing is Outcome.Ok) {
                    for (entry in listing.value) {
                        if (entry.type == "file" && entry.name.endsWith(".jsonl")) {
                            val legacyPath = "tasks/${entry.name}"
                            val del = client.deleteFile(
                                cfg.owner, cfg.repo, legacyPath,
                                "mythara: drop legacy task ledger $legacyPath", cfg.branch,
                            )
                            if (del is Outcome.Ok) {
                                manifest.files.remove(legacyPath)
                                Log.d(tag, "removed legacy task ledger $legacyPath")
                            }
                        }
                    }
                }
            }.onFailure { Log.w(tag, "legacy task cleanup failed: ${it.message}") }
        }

        // ---- device-to-device messaging (location requests etc.)
        //      Runs as a side-channel: pushes our pending outbox to
        //      every recipient's `device_messages/inbox/<id>.jsonl`
        //      and pulls THIS device's inbox + dispatches handlers.
        //      Each sync is a full exchange — request + handle in
        //      one round-trip — so a "where are you?" from device A
        //      gets answered next time device B syncs.
        if (cfg.syncLearnings) {
            val report = runCatching {
                deviceMessageSync.exchange(client, cfg.owner, cfg.repo, cfg.branch)
            }.getOrNull()
            if (report != null) {
                Log.d(tag, "device-msg exchange: pushed=${report.pushed} pulled=${report.pulled} handled=${report.handled}")
            }
        }

        // ---- PULL leg — every push leg above has a matching pull here
        //      so the 5-minute heartbeat is bidirectional. Without this
        //      a device's learnings / vault / chat / analytics / tasks
        //      only flowed OUT; peer devices' state never flowed back
        //      until a manual full restore. All pulls here use MERGE
        //      semantics (union / upsert / reconcile) — never the
        //      REPLACE semantics runRestore uses for cold bootstrap.
        runCatching { pullSharedState(client, cfg) }
            .onFailure { Log.w(tag, "shared-state pull failed: ${it.message}") }

        // ---- manifest.json (always last; lastSyncTs always changes)
        manifest.lastSyncTsMillis = now
        manifest.version = ManifestV2.CURRENT_VERSION
        val manifestBody = manifestJson.encodeToString(ManifestV2.serializer(), manifest)
        putWithCache(client, cfg, "manifest.json", manifestBody, manifest,
            "mythara: manifest @ ${isoUtc(now)}", written, skipped, isManifestItself = true)

        memorySettings.setLastSyncTs(now)
        memorySettings.setManifestJson(manifestJson.encodeToString(ManifestV2.serializer(), manifest))

        return Report(
            ok = true,
            message = "Synced ${written.size} file(s) to ${cfg.owner}/${cfg.repo}.",
            filesWritten = written,
            skipped = skipped,
        )
    }

    /**
     * The PULL counterpart to [runSync]'s push legs. Fetches every
     * cross-device subsystem the heartbeat also pushes and reconciles
     * it into the local stores using **MERGE** semantics:
     *
     *  - learnings journal (`working/`)   — union by (ts, kind, note)
     *  - vault tiers (`vault/working/`,
     *    `semantic/`, `episodic/`)        — sha-deduped add (idempotent)
     *  - analytics/contact_profiles       — newest `lastBuiltMs` wins
     *  - analytics/favorites              — additive upsert
     *  - analytics/audit_log              — union, dedup by composite key
     *  - analytics/user_aliases           — atomic upsertAll merge
     *  - lifeline/<month>                 — upsert peer rows, skip own
     *  - tasks/<month>                    — reconcile (terminal-ts wins)
     *
     * This deliberately never uses the REPLACE semantics of
     * [runRestore] (journal.replaceAll-from-scratch, history.clear) —
     * a heartbeat must never clobber un-pushed local state.
     *
     * Two subsystems are intentionally NOT pulled here:
     *  - Settings: `SettingsExport` carries no version/timestamp, so an
     *    auto-pull would ping-pong region/model between devices.
     *  - Conversations: chat rows feed the agent loop's context window;
     *    continuously merging peers' chat would flood (and eventually
     *    400) every device's agent. Both stay restore-only.
     *
     * Each subsystem is independently `runCatching`-guarded so one
     * malformed file can't abort the rest of the pull.
     */
    private suspend fun pullSharedState(client: GitHubClient, cfg: MemorySettings.Snapshot) {
        if (!cfg.syncLearnings) return
        val myId = runCatching { deviceIdStore.id() }.getOrNull()

        // ---- working/<day>.jsonl → LearningJournal (union merge)
        if (cfg.syncLearnings) runCatching {
            val remoteEntries = mutableListOf<LearningJournal.Entry>()
            listJsonl(client, cfg, Tier.Working.dir).forEach { name ->
                readJsonlLines(client, cfg, "${Tier.Working.dir}/$name", MemoryRecord.serializer())
                    .forEach { rec ->
                        remoteEntries.add(
                            LearningJournal.Entry(
                                tsMillis = rec.t,
                                kind = rec.src.removePrefix("growth:"),
                                note = rec.content,
                            ),
                        )
                    }
            }
            if (remoteEntries.isNotEmpty()) {
                val keyOf: (LearningJournal.Entry) -> String = { "${it.tsMillis}|${it.kind}|${it.note.hashCode()}" }
                val localEntries = journal.read()
                val merged = LinkedHashMap<String, LearningJournal.Entry>()
                remoteEntries.forEach { merged[keyOf(it)] = it }
                localEntries.forEach { merged[keyOf(it)] = it } // local wins ties
                val added = merged.size - localEntries.size
                if (added > 0) {
                    journal.replaceAll(merged.values.toList())
                    Log.d(tag, "journal PULL: +$added entry/ies")
                }
            }
        }.onFailure { Log.w(tag, "journal pull failed: ${it.message}") }

        // ---- vault tiers → LearningVault (sha-deduped, idempotent)
        if (cfg.syncLearnings) {
            pullVaultDir(client, cfg, "vault/${Tier.Working.dir}", Tier.Working)
            pullVaultDir(client, cfg, Tier.Semantic.dir, Tier.Semantic)
            pullVaultDir(client, cfg, Tier.Episodic.dir, Tier.Episodic)
        }

        // NOTE: conversations/<day>.jsonl is intentionally NOT pulled
        // on the heartbeat. Chat rows land in the SAME `history` table
        // the agent loop reads as its context window — continuously
        // merging peers' chat would flood every device's agent context
        // (and 400s MiniMax once the row count gets large). Conversations
        // stay push-only on the heartbeat; the full cross-device chat is
        // materialised only by an explicit [runRestore] (cold bootstrap).

        // ---- analytics/contact_profiles.jsonl (newest lastBuiltMs wins)
        runCatching {
            readJsonlLines(client, cfg, "analytics/contact_profiles.jsonl", ContactProfileExport.serializer())
                .forEach { exp ->
                    val existing = contactProfiles.dao.byKey(exp.nameKey)
                    if (existing == null || existing.lastBuiltMs < exp.lastBuiltMs) {
                        contactProfiles.dao.upsert(exp.toRow())
                    }
                }
        }.onFailure { Log.w(tag, "contact-profiles pull failed: ${it.message}") }

        // ---- analytics/favorites.json (additive upsert)
        runCatching {
            val r = client.readFile(cfg.owner, cfg.repo, "analytics/favorites.json")
            if (r is Outcome.Ok) {
                val incoming = runCatching {
                    manifestJson.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(FavoriteExport.serializer()),
                        r.value.text,
                    )
                }.getOrNull().orEmpty()
                for (f in incoming) {
                    favorites.upsert(
                        com.mythara.data.FavoritesStore.Favorite(
                            name = f.name,
                            phone = f.phone,
                            apps = f.apps.ifEmpty { listOf(com.mythara.data.FavoritesStore.WHATSAPP_PACKAGE) },
                            enabled = f.enabled,
                            toneLabel = f.tone,
                        ),
                    )
                }
            }
        }.onFailure { Log.w(tag, "favorites pull failed: ${it.message}") }

        // ---- analytics/audit_log.jsonl (union, dedup by composite key)
        runCatching {
            val incoming = readJsonlLines(client, cfg, "analytics/audit_log.jsonl", AuditExport.serializer())
            if (incoming.isNotEmpty()) {
                val existing = runCatching { auditRepo.dao.listRecent(limit = 50_000) }
                    .getOrDefault(emptyList())
                val seenKeys = existing.mapTo(HashSet()) {
                    "${it.tsMillis}|${it.toolName ?: ""}|${it.deviceId ?: ""}|${it.argsPreview ?: ""}"
                }
                for (exp in incoming) {
                    val key = "${exp.tsMillis}|${exp.toolName ?: ""}|${exp.deviceId ?: ""}|${exp.argsPreview ?: ""}"
                    if (seenKeys.add(key)) runCatching { auditRepo.dao.insert(exp.toRow()) }
                }
            }
        }.onFailure { Log.w(tag, "audit-log pull failed: ${it.message}") }

        // ---- analytics/user_aliases.json (atomic upsertAll merge)
        runCatching {
            val r = client.readFile(cfg.owner, cfg.repo, "analytics/user_aliases.json")
            if (r is Outcome.Ok) {
                val incoming = runCatching {
                    manifestJson.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(UserAliasExport.serializer()),
                        r.value.text,
                    )
                }.getOrNull().orEmpty()
                if (incoming.isNotEmpty()) {
                    userAliases.upsertAll(
                        incoming.map { com.mythara.data.UserAliasesStore.Alias(name = it.name, phone = it.phone) },
                    )
                }
            }
        }.onFailure { Log.w(tag, "user-aliases pull failed: ${it.message}") }

        // ---- lifeline/<YYYY-MM>.jsonl (upsert peer rows, skip own device)
        runCatching {
            listJsonl(client, cfg, "lifeline").forEach { name ->
                readJsonlLines(client, cfg, "lifeline/$name", LifelineExport.serializer())
                    .forEach { exp ->
                        if (exp.dev == myId) return@forEach
                        val existing = runCatching {
                            lifelineRepo.dao.byLocalRef(exp.dev, exp.msi)
                        }.getOrNull()
                        if (existing != null) {
                            runCatching { lifelineRepo.dao.upsert(exp.toRow().copy(id = existing.id)) }
                        } else {
                            runCatching { lifelineRepo.dao.insertIfAbsent(exp.toRow()) }
                        }
                    }
            }
        }.onFailure { Log.w(tag, "lifeline pull failed: ${it.message}") }

        // ---- tasks — union every device's `tasks/<deviceId>/*.jsonl`
        //      (plus any leftover legacy flat `tasks/*.jsonl`) and
        //      reconcile per task id: terminal-state / latest-stamp
        //      wins (see [taskRemoteIsNewer]). A single id can appear
        //      in several files (creator's + claimer's) — the reconcile
        //      is order-independent and converges to the newest state.
        runCatching {
            var reconciled = 0
            for (path in listTaskFiles(client, cfg)) {
                readJsonlLines(client, cfg, path, TaskExport.serializer()).forEach { exp ->
                    val existing = runCatching { taskRepo.dao.byId(exp.id) }.getOrNull()
                    if (existing == null) {
                        runCatching {
                            taskRepo.dao.insertIfAbsent(exp.toRow().copy(syncedAtMs = System.currentTimeMillis()))
                        }.onSuccess { reconciled++ }
                    } else if (taskRemoteIsNewer(existing, exp)) {
                        runCatching {
                            taskRepo.dao.upsert(exp.toRow().copy(syncedAtMs = System.currentTimeMillis()))
                        }.onSuccess { reconciled++ }
                    }
                }
            }
            if (reconciled > 0) Log.d(tag, "tasks PULL: reconciled $reconciled remote row(s)")
        }.onFailure { Log.w(tag, "tasks pull failed: ${it.message}") }
    }

    /**
     * Enumerate every task-ledger file in the repo as full paths:
     * per-device `tasks/<deviceId>/<month>.jsonl` files, plus any
     * leftover legacy flat `tasks/<month>.jsonl` (kept readable so a
     * mid-migration repo never loses task state). Empty on any failure.
     */
    private suspend fun listTaskFiles(client: GitHubClient, cfg: MemorySettings.Snapshot): List<String> {
        val out = mutableListOf<String>()
        val top = runCatching { client.listDirectory(cfg.owner, cfg.repo, "tasks") }.getOrNull()
        if (top !is Outcome.Ok) return out
        for (entry in top.value) {
            when {
                entry.type == "file" && entry.name.endsWith(".jsonl") ->
                    out.add("tasks/${entry.name}")
                entry.type == "dir" ->
                    listJsonl(client, cfg, "tasks/${entry.name}").forEach { fname ->
                        out.add("tasks/${entry.name}/$fname")
                    }
            }
        }
        return out
    }

    /** List the `.jsonl` file names under `<dir>` in the repo; empty on any failure. */
    private suspend fun listJsonl(client: GitHubClient, cfg: MemorySettings.Snapshot, dir: String): List<String> {
        val listing = runCatching { client.listDirectory(cfg.owner, cfg.repo, dir) }.getOrNull()
        return if (listing is Outcome.Ok) {
            listing.value.filter { it.type == "file" && it.name.endsWith(".jsonl") }.map { it.name }
        } else {
            emptyList()
        }
    }

    /** Read + decode every line of a repo JSONL file; bad lines are dropped. */
    private suspend fun <T> readJsonlLines(
        client: GitHubClient,
        cfg: MemorySettings.Snapshot,
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> {
        val r = runCatching { client.readFile(cfg.owner, cfg.repo, path) }.getOrNull()
        if (r !is Outcome.Ok) return emptyList()
        return r.value.text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .toList()
    }

    /**
     * Directory-listing variant of [restoreVaultTier] for the heartbeat
     * pull. Unlike the restore path it doesn't depend on the local
     * manifest listing the file (a peer device's brand-new topic file
     * won't be in our manifest yet), so it lists the repo dir live.
     * [LearningVault.add] is sha-deduped, so re-pulling is idempotent.
     */
    private suspend fun pullVaultDir(
        client: GitHubClient,
        cfg: MemorySettings.Snapshot,
        dir: String,
        targetTier: Tier,
    ) {
        runCatching {
            var added = 0
            listJsonl(client, cfg, dir).forEach { name ->
                readJsonlLines(client, cfg, "$dir/$name", MemoryRecord.serializer()).forEach { record ->
                    runCatching {
                        val inserted = vault.add(
                            content = record.content,
                            tier = targetTier,
                            src = record.src,
                            facets = record.facets,
                            embedding = null,
                            embModel = null,
                            conf = record.conf,
                            ref = record.ref,
                            now = record.t,
                        )
                        if (inserted) added++
                    }
                }
            }
            if (added > 0) Log.d(tag, "vault PULL [$dir]: +$added record(s)")
        }.onFailure { Log.w(tag, "vault pull [$dir] failed: ${it.message}") }
    }

    /** Pull from repo + materialise into local stores. REPLACE semantics. */
    suspend fun runRestore(): RestoreReport {
        val cfg = memorySettings.snapshot()
        if (!cfg.configured) return RestoreReport(ok = false, message = "Set a GitHub token + repo in Settings first.")

        val client = GitHubClient(cfg.pat!!)
        when (val v = client.validateToken()) {
            is Outcome.Ok -> Log.d(tag, "PAT ok for ${v.value} (restore)")
            is Outcome.Unauthorized -> return RestoreReport(ok = false, message = "GitHub token rejected.")
            else -> return RestoreReport(ok = false, message = "GitHub auth check failed.")
        }

        val manifestRead = client.readFile(cfg.owner, cfg.repo, "manifest.json")
        if (manifestRead is Outcome.NotFound) {
            return RestoreReport(ok = false, message = "Repo has no manifest — nothing to restore yet.")
        }
        if (manifestRead !is Outcome.Ok) {
            return RestoreReport(ok = false, message = "Could not read manifest from repo.")
        }
        val manifest = runCatching {
            manifestJson.decodeFromString(ManifestV2.serializer(), manifestRead.value.text)
        }.getOrElse { return RestoreReport(ok = false, message = "Manifest is malformed.") }

        var learnings = 0
        var vaultRecords = 0
        var chatRows = 0
        var settingsOk = false
        val filesRead = mutableListOf("manifest.json")

        // working/<day>.jsonl files in manifest → LearningJournal.replaceAll
        val workingJournalRecords = mutableListOf<MemoryRecord>()
        for (path in manifest.files.keys.filter {
            it.startsWith("${Tier.Working.dir}/") && it.endsWith(".jsonl")
        }) {
            val r = client.readFile(cfg.owner, cfg.repo, path)
            if (r is Outcome.Ok) {
                r.value.text.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull {
                        runCatching { json.decodeFromString(MemoryRecord.serializer(), it) }.getOrNull()
                    }
                    .forEach { workingJournalRecords.add(it) }
                filesRead.add(path)
            }
        }
        if (workingJournalRecords.isNotEmpty()) {
            val entries = workingJournalRecords.map {
                LearningJournal.Entry(tsMillis = it.t, kind = it.src, note = it.content)
            }
            journal.replaceAll(entries)
            learnings = entries.size
        }

        // vault/working/<day>.jsonl  — working-tier vault rows
        // (notifications etc.). REPLACE-into-vault semantics: we add
        // each restored record; `vault.add` is sha-deduped so a
        // re-restore over an existing install is idempotent — same
        // content reinforces instead of double-inserts.
        vaultRecords += restoreVaultTier(
            client, cfg, manifest, "vault/${Tier.Working.dir}/", Tier.Working, filesRead,
        )
        // semantic/<topic>.jsonl — durable facts
        vaultRecords += restoreVaultTier(
            client, cfg, manifest, "${Tier.Semantic.dir}/", Tier.Semantic, filesRead,
        )
        // episodic/<week>.jsonl — weekly summaries
        vaultRecords += restoreVaultTier(
            client, cfg, manifest, "${Tier.Episodic.dir}/", Tier.Episodic, filesRead,
        )

        // settings/preferences.json
        val prefsPath = "settings/preferences.json"
        if (manifest.files.containsKey(prefsPath)) {
            val r = client.readFile(cfg.owner, cfg.repo, prefsPath)
            if (r is Outcome.Ok) {
                val exp = runCatching {
                    manifestJson.decodeFromString(SettingsExport.serializer(), r.value.text)
                }.getOrNull()
                if (exp != null) {
                    appSettings.setRegion(Region.fromId(exp.region))
                    appSettings.setModel(exp.model)
                    settingsOk = true
                    filesRead.add(prefsPath)
                }
            }
        }

        // conversations per-day jsonl
        val chatRowsBuffer = mutableListOf<MessageRow>()
        for (path in manifest.files.keys.filter { it.startsWith("conversations/") && it.endsWith(".jsonl") }) {
            val r = client.readFile(cfg.owner, cfg.repo, path)
            if (r is Outcome.Ok) {
                r.value.text.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull {
                        runCatching { json.decodeFromString(ChatRowExport.serializer(), it) }.getOrNull()
                    }
                    .forEach { row ->
                        chatRowsBuffer.add(
                            MessageRow(
                                tsMillis = row.t,
                                role = row.role,
                                content = row.content,
                                toolCallsJson = row.toolCallsJson,
                                toolCallId = row.toolCallId,
                                name = row.name,
                                // Preserve the original authoring
                                // device id so cross-device chat rows
                                // render as distinct "from device X"
                                // cards in the UI.
                                deviceId = row.dev,
                            ),
                        )
                    }
                filesRead.add(path)
            }
        }
        if (chatRowsBuffer.isNotEmpty()) {
            history.dao.clear()
            history.dao.insertAll(chatRowsBuffer)
            chatRows = chatRowsBuffer.size
        }

        // analytics/contact_profiles.jsonl — merge by name_key with
        // last-built wins. User notes are pulled in too (other-
        // device-set notes propagate); local notes that are newer
        // survive because the local row's lastBuiltMs is older.
        runCatching {
            client.readFile(cfg.owner, cfg.repo, "analytics/contact_profiles.jsonl")
        }.getOrNull()?.let { r ->
            if (r is Outcome.Ok) {
                val incoming = r.value.text.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull {
                        runCatching { json.decodeFromString(ContactProfileExport.serializer(), it) }.getOrNull()
                    }
                    .toList()
                for (exp in incoming) {
                    val existing = contactProfiles.dao.byKey(exp.nameKey)
                    if (existing == null || existing.lastBuiltMs < exp.lastBuiltMs) {
                        contactProfiles.dao.upsert(exp.toRow())
                    }
                }
                filesRead.add("analytics/contact_profiles.jsonl")
            }
        }

        // analytics/favorites.json — additive merge; remote entries
        // not present locally are added, local-only stays.
        runCatching {
            client.readFile(cfg.owner, cfg.repo, "analytics/favorites.json")
        }.getOrNull()?.let { r ->
            if (r is Outcome.Ok) {
                val incoming = runCatching {
                    manifestJson.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(FavoriteExport.serializer()),
                        r.value.text,
                    )
                }.getOrNull().orEmpty()
                for (f in incoming) {
                    favorites.upsert(
                        com.mythara.data.FavoritesStore.Favorite(
                            name = f.name,
                            phone = f.phone,
                            apps = f.apps.ifEmpty { listOf(com.mythara.data.FavoritesStore.WHATSAPP_PACKAGE) },
                            enabled = f.enabled,
                            toneLabel = f.tone,
                        ),
                    )
                }
                if (incoming.isNotEmpty()) filesRead.add("analytics/favorites.json")
            }
        }

        // analytics/audit_log.jsonl — union across devices, deduped
        // by (tsMillis, toolName, deviceId, argsPreview). Same device
        // can't write two entries at the same ms; different devices
        // firing at the same ms produce distinct rows.
        runCatching {
            client.readFile(cfg.owner, cfg.repo, "analytics/audit_log.jsonl")
        }.getOrNull()?.let { r ->
            if (r is Outcome.Ok) {
                val incoming = r.value.text.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull {
                        runCatching { json.decodeFromString(AuditExport.serializer(), it) }.getOrNull()
                    }
                    .toList()
                if (incoming.isNotEmpty()) {
                    val existing = runCatching { auditRepo.dao.listRecent(limit = 50_000) }
                        .getOrDefault(emptyList())
                    val seenKeys = existing.mapTo(HashSet()) {
                        "${it.tsMillis}|${it.toolName ?: ""}|${it.deviceId ?: ""}|${it.argsPreview ?: ""}"
                    }
                    for (exp in incoming) {
                        val key = "${exp.tsMillis}|${exp.toolName ?: ""}|${exp.deviceId ?: ""}|${exp.argsPreview ?: ""}"
                        if (seenKeys.add(key)) {
                            runCatching { auditRepo.dao.insert(exp.toRow()) }
                        }
                    }
                    filesRead.add("analytics/audit_log.jsonl")
                }
            }
        }

        // analytics/user_aliases.json — atomic merge via upsertAll.
        runCatching {
            client.readFile(cfg.owner, cfg.repo, "analytics/user_aliases.json")
        }.getOrNull()?.let { r ->
            if (r is Outcome.Ok) {
                val incoming = runCatching {
                    manifestJson.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(UserAliasExport.serializer()),
                        r.value.text,
                    )
                }.getOrNull().orEmpty()
                if (incoming.isNotEmpty()) {
                    userAliases.upsertAll(
                        incoming.map { com.mythara.data.UserAliasesStore.Alias(name = it.name, phone = it.phone) },
                    )
                    filesRead.add("analytics/user_aliases.json")
                }
            }
        }

        // lifeline/<YYYY-MM>.jsonl — restore foreign-device photos
        // into the local lifeline DB so the chat scrollback can show
        // the entire cross-device timeline. is_remote=true on inserted
        // rows tells the UI the bytes aren't local; the caption +
        // device label render in their place.
        runCatching {
            client.listDirectory(cfg.owner, cfg.repo, "lifeline")
        }.getOrNull()?.let { listing ->
            if (listing is Outcome.Ok) {
                for (file in listing.value.filter { it.type == "file" && it.name.endsWith(".jsonl") }) {
                    val read = runCatching {
                        client.readFile(cfg.owner, cfg.repo, "lifeline/${file.name}")
                    }.getOrNull()
                    if (read !is Outcome.Ok) continue
                    val incoming = read.value.text.lineSequence()
                        .filter { it.isNotBlank() }
                        .mapNotNull {
                            runCatching { json.decodeFromString(LifelineExport.serializer(), it) }.getOrNull()
                        }
                        .toList()
                    for (exp in incoming) {
                        // Skip rows that originated on THIS device — we
                        // already have the LOCAL version with the real
                        // URI; restoring a remote-shaped row over it
                        // would lose the local uri pointer.
                        val myId = runCatching { deviceIdStore.id() }.getOrNull()
                        if (exp.dev == myId) continue
                        val existing = runCatching {
                            lifelineRepo.dao.byLocalRef(exp.dev, exp.msi)
                        }.getOrNull()
                        if (existing != null) {
                            // Re-pull: caption may have been refreshed on
                            // the origin device. Upsert keeps things current.
                            runCatching { lifelineRepo.dao.upsert(exp.toRow().copy(id = existing.id)) }
                        } else {
                            runCatching { lifelineRepo.dao.insertIfAbsent(exp.toRow()) }
                        }
                    }
                    if (incoming.isNotEmpty()) filesRead.add("lifeline/${file.name}")
                }
            }
        }

        // tasks — union every device's `tasks/<deviceId>/<month>.jsonl`
        // (plus any leftover legacy flat `tasks/<month>.jsonl`) and
        // reconcile with local state. Conflict policy: the row with
        // the latest *terminal* timestamp wins, else the latest claim,
        // else the latest createdMs. A task id can appear in several
        // files (creator's + claimer's) — the reconcile converges.
        runCatching {
            for (path in listTaskFiles(client, cfg)) {
                val incoming = readJsonlLines(client, cfg, path, TaskExport.serializer())
                for (exp in incoming) {
                    val existing = runCatching { taskRepo.dao.byId(exp.id) }.getOrNull()
                    if (existing == null) {
                        runCatching { taskRepo.dao.insertIfAbsent(exp.toRow().copy(syncedAtMs = System.currentTimeMillis())) }
                    } else if (taskRemoteIsNewer(existing, exp)) {
                        runCatching { taskRepo.dao.upsert(exp.toRow().copy(syncedAtMs = System.currentTimeMillis())) }
                    }
                }
                if (incoming.isNotEmpty()) filesRead.add(path)
            }
        }

        memorySettings.setManifestJson(manifestJson.encodeToString(ManifestV2.serializer(), manifest))
        memorySettings.setLastSyncTs(manifest.lastSyncTsMillis)

        return RestoreReport(
            ok = true,
            message = "Restored $learnings journal entry/ies, $vaultRecords vault record(s), $chatRows chat row(s), settings=${if (settingsOk) "ok" else "skipped"}.",
            learningsRestored = learnings,
            vaultRecordsRestored = vaultRecords,
            chatRowsRestored = chatRows,
            settingsRestored = settingsOk,
            filesRead = filesRead,
        )
    }

    /**
     * Generic per-tier restorer. Reads every `<prefix>*.jsonl` in the
     * manifest, decodes each line as a [MemoryRecord], and calls
     * [LearningVault.add] with the tier flipped to [targetTier].
     *
     * Idempotent: re-running over an existing install reinforces by
     * sha rather than double-inserting (the vault's sha index does the
     * work). The tier is forced from [targetTier] not from the record
     * itself — defensive against a malformed JSONL line claiming a
     * different tier than the directory it lived in.
     */
    private suspend fun restoreVaultTier(
        client: GitHubClient,
        cfg: MemorySettings.Snapshot,
        manifest: ManifestV2,
        pathPrefix: String,
        targetTier: Tier,
        filesRead: MutableList<String>,
    ): Int {
        var added = 0
        for (path in manifest.files.keys.filter { it.startsWith(pathPrefix) && it.endsWith(".jsonl") }) {
            val r = client.readFile(cfg.owner, cfg.repo, path)
            if (r !is Outcome.Ok) continue
            filesRead.add(path)
            r.value.text.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull {
                    runCatching { json.decodeFromString(MemoryRecord.serializer(), it) }.getOrNull()
                }
                .forEach { record ->
                    runCatching {
                        val inserted = vault.add(
                            content = record.content,
                            tier = targetTier,
                            src = record.src,
                            facets = record.facets,
                            embedding = null, // emb not in wire shape today
                            embModel = null,
                            conf = record.conf,
                            ref = record.ref,
                            now = record.t,
                        )
                        if (inserted) added++
                    }
                }
        }
        return added
    }

    private suspend fun putWithCache(
        client: GitHubClient,
        cfg: MemorySettings.Snapshot,
        path: String,
        body: String,
        manifest: ManifestV2,
        commitMessage: String,
        written: MutableList<String>,
        skipped: MutableList<String>,
        isManifestItself: Boolean = false,
    ) {
        val prev = manifest.files[path]
        if (!isManifestItself && prev?.contentHash == body.hashCode().toString()) {
            skipped.add(path); return
        }
        val merger = mergerFor(path, manifest)
        when (val r = client.writeFileMerging(
            owner = cfg.owner, repo = cfg.repo, path = path,
            text = body, commitMessage = commitMessage, branch = cfg.branch,
            previousSha = prev?.sha,
            merge = merger,
        )) {
            is Outcome.Ok -> {
                // Persist the hash of whatever actually landed on the remote
                // — that may be the merged blob, not the local body. Skip
                // logic on the next sync compares hash-of-local against this;
                // a divergence triggers another PUT, which the merger will
                // re-stabilise. Eventually convergent.
                manifest.files[path] = FileEntry(
                    sha = r.value.sha,
                    ts = System.currentTimeMillis(),
                    contentHash = r.value.text.hashCode().toString(),
                )
                written.add(path)
            }
            else -> Log.w(tag, "PUT $path failed: $r")
        }
    }

    /**
     * Pick a remote/local merge strategy by file path:
     *
     *  - tasks JSONL: dedup by [TaskExport.id], terminal-state and
     *    later-stamp wins (same policy as [taskRemoteIsNewer]).
     *  - lifeline JSONL: dedup by `(dev, msi)`, later effective
     *    timestamp (delete/caption/taken) wins.
     *  - conversations JSONL: dedup by the `(t, role, content)` tuple.
     *  - other JSONL records (working, semantic, episodic, vault):
     *    dedup by [MemoryRecord.id], reinforced-copy wins.
     *  - `manifest.json`: per-path entry newest-wins, union of
     *    [ManifestV2.files], `lastSyncTsMillis = max(remote, local)`.
     *  - everything else (README, MEMORY.md, settings, .gitkeep,
     *    placeholders): single-author, last-writer-wins — return local.
     *
     * Routing by schema matters: every JSONL file used to fall through
     * to [mergeRecordJsonl], which parses lines as [MemoryRecord]. Task
     * and lifeline rows are a different wire shape — that merger
     * silently dropped every line it couldn't parse, so a 409
     * write-race on a task ledger corrupted the file and lost tasks.
     */
    private fun mergerFor(path: String, manifest: ManifestV2): (String, String) -> String = when {
        path == "manifest.json" -> ::mergeManifests
        path.startsWith("tasks/") && path.endsWith(".jsonl") -> ::mergeTaskJsonl
        path.startsWith("lifeline/") && path.endsWith(".jsonl") -> ::mergeLifelineJsonl
        path.startsWith("conversations/") && path.endsWith(".jsonl") -> ::mergeChatJsonl
        path.endsWith(".jsonl") -> ::mergeRecordJsonl
        else -> { _, local -> local }
    }

    /**
     * Merge two `tasks/<month>.jsonl` blobs. Dedup by [TaskExport.id];
     * on collision the winner is decided by the same rule the read-side
     * reconcile uses — a terminal state (DONE/FAILED/CANCELED) beats a
     * non-terminal one, otherwise the later `completedMs ?: claimedMs ?:
     * createdMs` wins. Lines that don't parse as [TaskExport] are kept
     * verbatim rather than dropped, so a malformed line can never lose
     * real data.
     */
    private fun mergeTaskJsonl(remote: String, local: String): String {
        val terminals = setOf(
            com.mythara.tasks.TaskStatus.DONE.name,
            com.mythara.tasks.TaskStatus.FAILED.name,
            com.mythara.tasks.TaskStatus.CANCELED.name,
        )
        fun stamp(t: TaskExport) = t.completedMs ?: t.claimedMs ?: t.createdMs
        // true if `b` should win over `a`
        fun bWins(a: TaskExport, b: TaskExport): Boolean {
            val aTerm = a.st in terminals
            val bTerm = b.st in terminals
            if (aTerm != bTerm) return bTerm
            return stamp(b) >= stamp(a)
        }
        val byId = linkedMapOf<String, TaskExport>()
        val unparsed = mutableListOf<String>()
        // remote first, then local — so a same-id local row gets the
        // chance to win via bWins rather than being order-clobbered.
        for (blob in listOf(remote, local)) {
            blob.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val t = runCatching { json.decodeFromString(TaskExport.serializer(), line) }.getOrNull()
                if (t == null) {
                    unparsed.add(line)
                } else {
                    val prev = byId[t.id]
                    if (prev == null || bWins(prev, t)) byId[t.id] = t
                }
            }
        }
        val merged = byId.values.sortedBy { it.createdMs }
            .map { json.encodeToString(TaskExport.serializer(), it) }
        return (merged + unparsed.distinct()).joinToString("\n")
    }

    /**
     * Merge two `lifeline/<month>.jsonl` blobs. Dedup by the
     * `(dev, msi)` identity; on collision the row with the later
     * effective timestamp wins — `delAt` (a tombstone) and `capAt`
     * (a caption refresh) both count, so a delete or a re-caption on
     * one device propagates. Unparseable lines are kept verbatim.
     */
    private fun mergeLifelineJsonl(remote: String, local: String): String {
        fun stamp(e: LifelineExport) = maxOf(e.delAt ?: 0L, e.capAt ?: 0L, e.ts)
        val byKey = linkedMapOf<String, LifelineExport>()
        val unparsed = mutableListOf<String>()
        for (blob in listOf(remote, local)) {
            blob.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val e = runCatching { json.decodeFromString(LifelineExport.serializer(), line) }.getOrNull()
                if (e == null) {
                    unparsed.add(line)
                } else {
                    val key = "${e.dev}:${e.msi}"
                    val prev = byKey[key]
                    if (prev == null || stamp(e) >= stamp(prev)) byKey[key] = e
                }
            }
        }
        val merged = byKey.values.sortedBy { it.ts }
            .map { json.encodeToString(LifelineExport.serializer(), it) }
        return (merged + unparsed.distinct()).joinToString("\n")
    }

    private fun mergeRecordJsonl(remote: String, local: String): String {
        val parseLine: (String) -> MemoryRecord? = { line ->
            runCatching { json.decodeFromString(MemoryRecord.serializer(), line) }.getOrNull()
        }
        // Score function: prefer the row that's been reinforced more
        // (`seen` carries the heaviest weight) and has higher confidence.
        // Protects against local decay-passes clobbering a remote record
        // that this device hasn't seen recently — the GitHub copy IS
        // the long-term backup, and a stale local low-conf row must
        // never win against a healthy remote high-conf row.
        //
        // The previous policy was "local always wins" which made decay
        // + dedup compactions destructive across the sync round-trip.
        // Now: dedup-merged keepers (higher `seen`) still win — they're
        // the stronger version — while decayed losers lose to the
        // pristine remote.
        val score: (MemoryRecord) -> Double = { r -> r.seen + r.conf * 10.0 }
        val combined = linkedMapOf<String, MemoryRecord>()
        fun consider(record: MemoryRecord) {
            val prev = combined[record.id]
            if (prev == null || score(record) >= score(prev)) {
                combined[record.id] = record
            }
        }
        remote.lineSequence().filter { it.isNotBlank() }.mapNotNull(parseLine).forEach(::consider)
        local .lineSequence().filter { it.isNotBlank() }.mapNotNull(parseLine).forEach(::consider)
        return combined.values.sortedBy { it.t }
            .joinToString("\n") { json.encodeToString(MemoryRecord.serializer(), it) }
    }

    private fun mergeChatJsonl(remote: String, local: String): String {
        val parseLine: (String) -> ChatRowExport? = { line ->
            runCatching { json.decodeFromString(ChatRowExport.serializer(), line) }.getOrNull()
        }
        // No `id` field on chat rows — key on (t, role, content-hash) so
        // identical messages from the same device aren't duplicated, but
        // genuinely-different rows from a second device are preserved.
        val key: (ChatRowExport) -> String = { r ->
            "${r.t}|${r.role}|${r.content?.hashCode() ?: 0}|${r.toolCallId.orEmpty()}"
        }
        val combined = linkedMapOf<String, ChatRowExport>()
        remote.lineSequence().filter { it.isNotBlank() }.mapNotNull(parseLine).forEach { combined[key(it)] = it }
        local .lineSequence().filter { it.isNotBlank() }.mapNotNull(parseLine).forEach { combined[key(it)] = it }
        return combined.values.sortedBy { it.t }
            .joinToString("\n") { json.encodeToString(ChatRowExport.serializer(), it) }
    }

    private fun mergeManifests(remote: String, local: String): String {
        val r = runCatching { manifestJson.decodeFromString(ManifestV2.serializer(), remote) }.getOrNull()
            ?: return local
        val l = runCatching { manifestJson.decodeFromString(ManifestV2.serializer(), local) }.getOrNull()
            ?: return remote
        val merged = ManifestV2(
            version = maxOf(r.version, l.version),
            lastSyncTsMillis = maxOf(r.lastSyncTsMillis, l.lastSyncTsMillis),
        )
        // Per-path: pick the entry with the higher ts (= newer write).
        val allKeys = r.files.keys + l.files.keys
        for (k in allKeys) {
            val rv = r.files[k]; val lv = l.files[k]
            merged.files[k] = when {
                rv == null -> lv!!
                lv == null -> rv
                else -> if (lv.ts >= rv.ts) lv else rv
            }
        }
        return manifestJson.encodeToString(ManifestV2.serializer(), merged)
    }

    /**
     * Decide whether a working-tier vault row's `src` is safe to upload
     * to the user's GitHub repo. Allowlist on the known safe prefixes
     * (notifications, chat-derived working obs, growth) rather than a
     * denylist on `observe:*` — additive new sources stay local-only
     * by default until explicitly opted in.
     *
     * Privacy contract: anything captured by the Observe pipeline
     * (raw audio frames, ASR transcripts, recogniser intermediate
     * states) MUST stay local. That's the whole point of Observe
     * being on-device.
     */
    private fun isWorkingSrcSyncable(src: String): Boolean {
        return src.startsWith("notif:") ||
            src.startsWith("growth:") ||
            src.startsWith("chat:")
    }

    private fun entryToWorkingRecord(entry: LearningJournal.Entry, deviceId: String): MemoryRecord {
        val scrubbed = SecretScrubber.scrub(entry.note)
        val src = "growth:${entry.kind}"
        val facets = buildList {
            add("kind:${entry.kind}")
            add("tier:working")
        }
        return MemoryRecord.working(
            content = scrubbed, src = src, facets = facets,
            ref = null, now = entry.tsMillis, dev = deviceId,
        )
    }

    /**
     * Bridge file — like agentmemory's "MEMORY.md" + Karpathy LLM-wiki
     * convention. Human-readable digest of what the agent currently
     * "remembers" at the working tier. Will grow to surface promoted
     * semantic facts once M8.3+ extractors land.
     */
    private fun renderMemoryBridge(entries: List<LearningJournal.Entry>, cfg: MemorySettings.Snapshot): String {
        val sorted = entries.sortedByDescending { it.tsMillis }.take(MEMORY_BRIDGE_CAP)
        val sb = StringBuilder()
        sb.append("# Mythara — active memory\n\n")
        sb.append("> Bridge file. Top ${sorted.size} working observations, freshest first.\n")
        sb.append("> Auto-managed by the Mythara Android app. ")
        sb.append("Last sync: ${isoUtc(cfg.lastSyncTs)}\n\n")
        sb.append("---\n\n")
        if (sorted.isEmpty()) {
            sb.append("_(no observations yet — Observe pipeline lands in M8.1+)_\n")
        } else {
            for (e in sorted) {
                val date = isoUtc(e.tsMillis)
                val scrubbed = SecretScrubber.scrub(e.note)
                sb.append("- **$date** · `${e.kind}` — $scrubbed\n")
            }
        }
        sb.append("\n---\n\n")
        sb.append("## Tiers\n\n")
        sb.append("- `working/`     — raw observations (this tier today)\n")
        sb.append("- `episodic/`    — weekly summaries (M8.3+)\n")
        sb.append("- `semantic/`    — durable facts / preferences (M8.3+)\n")
        sb.append("- `procedural/`  — action patterns / workflows (M8.4+)\n")
        return sb.toString()
    }

    private fun isoLocalDate(ms: Long): String {
        val dt = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return DateTimeFormatter.ISO_LOCAL_DATE.format(dt)
    }

    /**
     * `YYYY-Www` ISO week-of-year identifier. Used as the partition
     * key for `episodic/<week>.jsonl` files. Uses the device-local
     * time zone to match the user's intuition of "this week" — the
     * trade-off is that devices in different zones may bin the same
     * timestamp differently, which is fine since auto-merge dedupes
     * by record ID at sync time anyway.
     */
    private fun isoWeek(ms: Long): String {
        val zdt = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val weekFields = java.time.temporal.WeekFields.ISO
        val yr = zdt.get(weekFields.weekBasedYear())
        val wk = zdt.get(weekFields.weekOfWeekBasedYear())
        return "%04d-W%02d".format(yr, wk)
    }

    /**
     * Snapshot of every launchable package on this device. Used in
     * the heartbeat write so peers know which apps each device can
     * automate — the agent's planner can then route "open Uber" to
     * the device with Uber installed rather than picking the wrong
     * one. Capped at [DevicePresence.MAX_INSTALLED_APPS] entries.
     */
    private fun queryInstalledLaunchablePackages(): List<String> {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val infos = runCatching { pm.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
        val pkgs = LinkedHashSet<String>()
        for (info in infos) {
            val pkg = info.activityInfo?.applicationInfo?.packageName ?: continue
            pkgs.add(pkg)
            if (pkgs.size >= DevicePresence.MAX_INSTALLED_APPS) break
        }
        return pkgs.toList()
    }

    /**
     * `YYYY-MM` partition key for lifeline photos. Local time zone —
     * the user thinks of "January" in their own clock, not UTC.
     */
    private fun isoYearMonth(ms: Long): String {
        val dt = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return "%04d-%02d".format(dt.year, dt.monthValue)
    }

    private fun isoUtc(ms: Long): String {
        if (ms <= 0L) return "never"
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    // -------- on-disk shapes (separate from internal Room schema) --------

    @Serializable
    data class ManifestV2(
        var version: Int = CURRENT_VERSION,
        var lastSyncTsMillis: Long = 0,
        val files: MutableMap<String, FileEntry> = mutableMapOf(),
    ) {
        companion object { const val CURRENT_VERSION = 2 }
    }

    @Serializable
    data class FileEntry(val sha: String, val ts: Long, val contentHash: String)

    @Serializable
    data class SettingsExport(val region: String, val model: String)

    /**
     * Per-device heartbeat. Written to device_messages/devices/<id>.json
     * on every sync so the canonical "list of Mythara installs signed
     * into this repo" can be derived by enumerating that directory.
     * lastSyncMs ages out a stale device by inspection — if it hasn't
     * synced in weeks, the user knows it's dormant.
     */
    @Serializable
    data class DevicePresence(
        val id: String,
        val model: String,
        val manufacturer: String,
        val androidSdk: Int,
        val lastSyncMs: Long,
        /**
         * Package names of installed apps with launcher activities.
         * Lets task scheduling and the agent's planning logic route
         * work to the right device — "book an Uber" goes to the
         * device that has Uber installed; "send a WhatsApp" goes to
         * the device with WhatsApp.
         *
         * Capped at [MAX_INSTALLED_APPS] entries to keep heartbeat
         * payloads bounded; on a normal device the count is well
         * under 200, but apps with multiple launcher activities can
         * inflate the raw count.
         */
        val installedApps: List<String> = emptyList(),
    ) {
        companion object {
            const val MAX_INSTALLED_APPS = 400
        }
    }

    /**
     * One row in lifeline/<YYYY-MM>.jsonl. Metadata + caption only —
     * no image bytes. The dev field is the device id that originated
     * the photo, so cross-device hydration knows whose camera roll
     * the entry belongs to (UI shows "📷 phone-B" when the image is
     * not local to the current device).
     */
    /**
     * Cross-device task. Shipped to tasks/<YYYY-MM>.jsonl, partitioned
     * by createdMs. Merging is last-writer-wins on (id, status) tuple
     * — claims surface to everyone on the next heartbeat sync, so two
     * devices grabbing the same null-target task at the same instant
     * race to write their claim. The loser sees the winner's claim
     * on the next pull and respects it.
     */
    @Serializable
    data class TaskExport(
        val id: String,
        val title: String,
        val body: String,
        val req: String,            // requester device id
        val tgt: String? = null,    // null = any
        val st: String,             // TaskStatus.name
        val claimedBy: String? = null,
        val createdMs: Long,
        val claimedMs: Long? = null,
        val completedMs: Long? = null,
        val result: String? = null,
        val schedFor: Long? = null,
        val rec: String? = null,    // recurrence spec; null = one-shot
    )

    @Serializable
    data class LifelineExport(
        val dev: String,
        val msi: Long,                 // media-store id on the originating device
        val ts: Long,                  // taken_ms
        val mime: String,
        val w: Int = 0,
        val h: Int = 0,
        val lat: Double? = null,
        val lng: Double? = null,
        val cap: String? = null,       // caption text
        val capModel: String? = null,
        val capAt: Long? = null,
        val name: String? = null,      // display name
        val place: String? = null,
        /** Tombstone marker — true means "the user deleted this photo,
         *  propagate the deletion". Restore drops the corresponding
         *  local row. */
        val del: Boolean = false,
        val delAt: Long? = null,
    )

    /** Per-contact analytics row — one per line in
     *  analytics/contact_profiles.jsonl. Stable short-key field names
     *  keep the JSONL byte-efficient over time. */
    @Serializable
    data class ContactProfileExport(
        val nameKey: String,
        val displayName: String,
        val phone: String? = null,
        val isFavorite: Boolean = false,
        val toneLabel: String? = null,
        val firstSeenMs: Long,
        val lastInteractionMs: Long,
        val messageCount: Int = 0,
        val imageCount: Int = 0,
        val topTopicsJson: String = "[]",
        val relationshipSummary: String? = null,
        val openness: Double? = null,
        val conscientiousness: Double? = null,
        val extraversion: Double? = null,
        val agreeableness: Double? = null,
        val neuroticism: Double? = null,
        val bigFiveSampleSize: Int = 0,
        val bigFiveLastUpdatedMs: Long? = null,
        val notableTraitsJson: String = "[]",
        val keyPointsJson: String = "[]",
        val userNotes: String? = null,
        val personalityInsights: String? = null,
        val lastBuiltMs: Long = 0,
        val dev: String? = null,
    )

    @Serializable
    data class FavoriteExport(
        val name: String,
        val phone: String = "",
        val apps: List<String> = emptyList(),
        val enabled: Boolean = true,
        val tone: String = "realistic",
    )

    @Serializable
    data class UserAliasExport(
        val name: String,
        val phone: String = "",
    )

    @Serializable
    data class AuditExport(
        val tsMillis: Long,
        val kind: String,
        val toolName: String? = null,
        val argsPreview: String? = null,
        val resultOk: Boolean = true,
        val resultPreview: String? = null,
        val latencyMs: Long = 0L,
        val note: String? = null,
        val contactName: String? = null,
        val deviceId: String? = null,
    )

    @Serializable
    data class ChatRowExport(
        val t: Long,
        val role: String,
        val content: String? = null,
        val toolCallsJson: String? = null,
        val toolCallId: String? = null,
        val name: String? = null,
        /** DeviceIdStore stamp identifying which Mythara install authored this row. */
        val dev: String? = null,
    )

    companion object {
        private const val README_PATH = "README.md"
        private const val MEMORY_PATH = "MEMORY.md"
        private const val MEMORY_BRIDGE_CAP = 50
        // semantic/ and episodic/ are populated by the vault sync above
        // when there are records; only procedural/ remains a pure
        // placeholder until M8.4+ ships an action-pattern extractor.
        private val TIER_PLACEHOLDERS = listOf(Tier.Procedural)

        private val PLACEHOLDER_BODY = """
            # placeholder

            This directory is part of Mythara's memory tier layout
            (agentmemory-style). It gets populated when its corresponding
            extractor lands:

            - `episodic/`    — weekly session summaries (M8.3+)
            - `semantic/`    — durable facts and preferences (M8.3+)
            - `procedural/`  — action patterns and workflows (M8.4+)

            Today only `working/` carries records.
        """.trimIndent()

        private val README_BODY = """
            # mythara_memory

            This repository holds the durable state of one or more **Mythara**
            installations. It's managed entirely by the Android app via the
            GitHub Contents API — there is no external service.

            The format mirrors the architectural pattern of
            [agentmemory](https://github.com/rohitg00/agentmemory), adapted for
            mobile: short JSON keys, per-day partitioning, no embeddings stored
            on disk, no graph relations on disk (those reconstruct from
            `facets` + `ref` at recall time).

            ## Layout

            ```
            mythara_memory/
              README.md                          (this file)
              MEMORY.md                          (bridge — top-K active records)
              manifest.json                      (version + per-file SHA cache)
              working/<YYYY-MM-DD>.jsonl         (raw observations)
              episodic/<YYYY-W##>.jsonl          (weekly summaries — M8.3+)
              semantic/facts.jsonl               (durable facts/preferences — M8.3+)
              procedural/workflows.jsonl         (action patterns — M8.4+)
              settings/preferences.json          (region + model + non-secret prefs)
              conversations/<YYYY-MM-DD>.jsonl   (chat history — opt-in)
            ```

            ## Memory record shape (short keys for mobile byte-efficiency)

            ```json
            { "id": "1a2b...-c3d4e5f6",
              "t": 1684004400000,
              "tier": "w",
              "src": "growth:nightly",
              "conf": 1.0,
              "facets": ["topic:python", "kind:preference"],
              "content": "...",
              "sha": "ab12cd34...(24 hex)",
              "ref": "msg:42",
              "seen": 1 }
            ```

            Field meanings:
              - **id**   — ULID-style time-sortable identifier
              - **t**    — epoch millis
              - **tier** — `w` working / `e` episodic / `s` semantic / `p` procedural
              - **src**  — provenance string
              - **conf** — confidence 0..1
              - **facets** — dimension:value tags
              - **content** — short text payload (secrets scrubbed pre-write)
              - **sha**  — 24-char SHA-256 prefix of content; dedup key
              - **ref**  — optional back-link to source event
              - **seen** — reinforcement counter

            ## What is NEVER in this repo

            - MiniMax API key
            - GitHub PAT (this feature's own auth credential)
            - Tink AEAD wrapping key
            - Secret-mode password hash
            - Observe-mode raw audio or transcripts (auto-purged on-device)

            All record content is also run through a regex-based scrubber
            (`SecretScrubber`) that strips anything *shaped like* an API key
            (ghp_, sk-, eyJ, AKIA, xox*) before write.

            ## Compaction (incoming with M8.3+)

            Working observations get promoted into episodic summaries weekly,
            then into semantic facts when reinforced (high `seen` counter),
            then ride the system prompt every chat turn as durable "things I
            know about the user".

            ## Privacy posture

            Trust model: **private GitHub repo + GitHub access control**. The
            content is plaintext JSON — auditable by you, your own
            git-blame-able. No client-side encryption in v1; if you need it,
            change the repo visibility and `repo` scope of the PAT remain the
            only access lever. Open an issue and we'll add passphrase-derived
            AEAD pre-write if the threat model demands it.
        """.trimIndent()
    }
}

/** Audit Room row → sync-wire shape. Device id falls back to
 *  the syncing device when the entry was written before deviceId
 *  was added (legacy rows). */
private fun com.mythara.audit.AuditEntry.toExport(syncDeviceId: String): MemorySync.AuditExport =
    MemorySync.AuditExport(
        tsMillis = tsMillis,
        kind = kind,
        toolName = toolName,
        argsPreview = argsPreview,
        resultOk = resultOk,
        resultPreview = resultPreview,
        latencyMs = latencyMs,
        note = note,
        contactName = contactName,
        deviceId = deviceId ?: syncDeviceId,
    )

private fun MemorySync.AuditExport.toRow(): com.mythara.audit.AuditEntry =
    com.mythara.audit.AuditEntry(
        tsMillis = tsMillis,
        kind = kind,
        toolName = toolName,
        argsPreview = argsPreview,
        resultOk = resultOk,
        resultPreview = resultPreview,
        latencyMs = latencyMs,
        note = note,
        contactName = contactName,
        deviceId = deviceId,
    )

/** Reverse converter: wire-shape back to the Room row for restore. */
private fun MemorySync.ContactProfileExport.toRow(): com.mythara.analytics.ContactProfileRow =
    com.mythara.analytics.ContactProfileRow(
        nameKey = nameKey,
        displayName = displayName,
        phone = phone,
        isFavorite = isFavorite,
        toneLabel = toneLabel,
        firstSeenMs = firstSeenMs,
        lastInteractionMs = lastInteractionMs,
        messageCount = messageCount,
        imageCount = imageCount,
        topTopicsJson = topTopicsJson,
        relationshipSummary = relationshipSummary,
        openness = openness,
        conscientiousness = conscientiousness,
        extraversion = extraversion,
        agreeableness = agreeableness,
        neuroticism = neuroticism,
        bigFiveSampleSize = bigFiveSampleSize,
        bigFiveLastUpdatedMs = bigFiveLastUpdatedMs,
        notableTraitsJson = notableTraitsJson,
        keyPointsJson = keyPointsJson,
        userNotes = userNotes,
        personalityInsights = personalityInsights,
        lastBuiltMs = lastBuiltMs,
    )

/** Compact converter from the analytics Room row to its sync-wire shape. */
private fun com.mythara.analytics.ContactProfileRow.toExport(deviceId: String): MemorySync.ContactProfileExport =
    MemorySync.ContactProfileExport(
        nameKey = nameKey,
        displayName = displayName,
        phone = phone,
        isFavorite = isFavorite,
        toneLabel = toneLabel,
        firstSeenMs = firstSeenMs,
        lastInteractionMs = lastInteractionMs,
        messageCount = messageCount,
        imageCount = imageCount,
        topTopicsJson = topTopicsJson,
        relationshipSummary = relationshipSummary,
        openness = openness,
        conscientiousness = conscientiousness,
        extraversion = extraversion,
        agreeableness = agreeableness,
        neuroticism = neuroticism,
        bigFiveSampleSize = bigFiveSampleSize,
        bigFiveLastUpdatedMs = bigFiveLastUpdatedMs,
        notableTraitsJson = notableTraitsJson,
        keyPointsJson = keyPointsJson,
        userNotes = userNotes,
        personalityInsights = personalityInsights,
        lastBuiltMs = lastBuiltMs,
        dev = deviceId,
    )

/** Task DB row → sync wire shape. */
internal fun com.mythara.tasks.TaskEntity.toExport(): MemorySync.TaskExport =
    MemorySync.TaskExport(
        id = id,
        title = title,
        body = body,
        req = requesterDeviceId,
        tgt = targetDeviceId,
        st = status,
        claimedBy = claimedByDeviceId,
        createdMs = createdMs,
        claimedMs = claimedMs,
        completedMs = completedMs,
        result = resultText,
        schedFor = scheduledForMs,
        rec = recurrence,
    )

/** Wire shape → DB row. syncedAtMs left null so the next push re-uploads
 *  iff there's a local-side change later. */
internal fun MemorySync.TaskExport.toRow(): com.mythara.tasks.TaskEntity =
    com.mythara.tasks.TaskEntity(
        id = id,
        title = title,
        body = body,
        requesterDeviceId = req,
        targetDeviceId = tgt,
        status = st,
        claimedByDeviceId = claimedBy,
        createdMs = createdMs,
        claimedMs = claimedMs,
        completedMs = completedMs,
        resultText = result,
        scheduledForMs = schedFor,
        recurrence = rec,
    )

/**
 * Conflict policy. Terminal-state rows trump non-terminal (DONE/FAILED
 * never re-opens). Otherwise the row with the later claim or createdMs
 * wins.
 */
internal fun taskRemoteIsNewer(
    local: com.mythara.tasks.TaskEntity,
    remote: MemorySync.TaskExport,
): Boolean {
    val terminals = setOf(
        com.mythara.tasks.TaskStatus.DONE.name,
        com.mythara.tasks.TaskStatus.FAILED.name,
        com.mythara.tasks.TaskStatus.CANCELED.name,
    )
    val localTerminal = local.status in terminals
    val remoteTerminal = remote.st in terminals
    if (localTerminal && !remoteTerminal) return false
    if (!localTerminal && remoteTerminal) return true
    val localStamp = local.completedMs ?: local.claimedMs ?: local.createdMs
    val remoteStamp = remote.completedMs ?: remote.claimedMs ?: remote.createdMs
    return remoteStamp > localStamp
}

/** Lifeline DB row → sync wire shape. */
internal fun com.mythara.lifeline.LifelineEntity.toExport(): MemorySync.LifelineExport =
    MemorySync.LifelineExport(
        dev = deviceId,
        msi = mediaStoreId,
        ts = takenMs,
        mime = mimeType,
        w = width,
        h = height,
        lat = lat,
        lng = lng,
        cap = captionText,
        capModel = captionModel,
        capAt = captionedAtMs,
        name = displayName,
        place = placeLabel,
        del = isDeleted,
        delAt = deletedAtMs,
    )

/**
 * Wire shape → DB row. is_remote=true marks the row as cross-device so
 * the UI knows the photo bytes are NOT on this device — render the
 * caption + a "📷 from <device>" placeholder instead of trying to
 * decode an unreachable mediaStoreId.
 */
internal fun MemorySync.LifelineExport.toRow(): com.mythara.lifeline.LifelineEntity =
    com.mythara.lifeline.LifelineEntity(
        deviceId = dev,
        mediaStoreId = msi,
        uri = "", // remote — no local URI
        displayName = name.orEmpty(),
        bucket = "",
        takenMs = ts,
        addedMs = ts,
        mimeType = mime,
        width = w,
        height = h,
        lat = lat,
        lng = lng,
        placeLabel = place,
        captionStatus = if (cap.isNullOrBlank()) {
            com.mythara.lifeline.LifelineCaptionStatus.PENDING.name
        } else {
            com.mythara.lifeline.LifelineCaptionStatus.CAPTIONED.name
        },
        captionText = cap,
        captionModel = capModel,
        captionedAtMs = capAt,
        isRemote = true,
        isDeleted = del,
        deletedAtMs = delAt,
    )
