# Local-First Memory & Personality

Everything Mythara learns about you and the people around you lives on your phone. Nothing is sent to a vendor cloud unless you explicitly enable a remote model for the chat layer. Optional cross-device sync goes through a GitHub repo *you own and configure*, not ours.

## What Mythara learns

### Big Five personality model (per contact AND for yourself)
- Trait extractor: `PersonaTraitExtractor.kt`.
- LIWC-style lexical category frequencies → trait deltas (openness, conscientiousness, extraversion, agreeableness, neuroticism).
- LLM cascade for the prose summary ("how to message this contact") via the same model that runs the chat.
- Stored in `ContactProfileRow`:
  - `openness`, `conscientiousness`, `extraversion`, `agreeableness`, `neuroticism` (each 0..1 double)
  - `bigFiveSampleSize` (the number of observed facts)
  - `notableTraitsJson` (chips like "apologetic", "task-oriented", "reserved")
  - `personalityInsights` (prose paragraph — how to message them)
  - `relationshipSummary` (prose paragraph — how the user relates to / talks with this person)
  - `userNotes` (user-typed, fully editable)
  - `topTopicsJson` (durable topics)
- Tiered confidence disclaimer:
  - `n == 0` → "no facts observed yet"
  - `n < 10` → "low confidence"
  - `n < 30` → "moderate confidence"
  - `n >= 30` → "estimated from N observed facts"

### Face recognition
- **`FaceDetector`** wraps ML Kit (`PERFORMANCE_MODE_ACCURATE` + classification).
- **`FaceEmbedder`** loads MobileFaceNet via TFLite (NNAPI delegate when available, GPU fallback, CPU last resort).
- **`ContactFaceIndex`** stores 128-D embeddings + the source-crop PNG path per (nameKey, sourcePhoto).
- **`ContactFaceMatcher`** does cosine matching with a 0.65 threshold (tunable in `Phase 11`).
- The matcher feeds `FaceAnalysisWorker`, which tags `lifeline_entries.detected_contacts_json` after every photo.

### Graph
- **`GraphTurnExtractor`** runs after each chat turn — pulls entities + edges + predicates from the model's reply via a structured JSON prompt, dedups against existing nodes, writes to a Room graph DB.
- **Typed entities** (v4+):
  - `person` — your actual contacts
  - `place` — gazetteer matches (cafés, cities, parks)
  - `organization` — brands, companies, services
  - `app` — system + utility packages
  - `notification-source` — weather alerts, news pushes, package-delivery codes
  - `unknown` — pre-classification fallback
- **`EntityKindClassifier`** runs at insert time AND during the `PeopleCleanupRunner` batch — demotes spam senders / notification sources / brands out of the People list automatically.
- **Insights graph** paints each node in its kind's brand colour.

### Lifeline (photo + caption timeline)
- **`LifelineDb.lifeline_entries`** rows store: photo URI, taken-ms, location, source device type (`phone` / `watch` / `glasses`), AI caption, detected contacts JSON, user-typed context, hash, dedupe metadata.
- **`LifelineCaptioner`** generates captions via Gemini Flash (when an image-gen key is configured) OR via the local agent's vision tool — either way the request is one-shot, not streamed to a logging endpoint.

### Skill library
- **`filesDir/skills/<name>/SKILL.md`** — YAML frontmatter (`name`, `description`, `created_at`, `last_used_ms`, `use_count`, `params`) + markdown body.
- Skills are surfaced in Settings → Skills, executable via `RunSkillTool`.

### Contact interactions log
- **`ContactInteractionDb.contact_interactions`** rows: per-contact `(kind, source, ts, lat, lng, place_label, note, ref_lifeline_id, ref_audit_id)`.
- `kind` ∈ `message_sent`, `message_received`, `call_outgoing`, `call_incoming`, `physical_meet`, `mention`.
- Backfill worker populates from existing vault + audit rows once on app upgrade.

### Notifications
- **`NotificationListener`** captures a rolling 50-row buffer in-memory.
- **`NotificationFeedRepository`** exposes it as a `StateFlow` for the Alerts hub.
- **Ephemeral by design** — nothing notification-derived is written to durable storage by the listener itself.

## What's NOT stored

- Live camera frames — `FaceTracker` runs detection in-memory and only persists embeddings the user explicitly added via the "add samples" picker.
- Raw audio after the wake / PTT capture — once the transcript is in, the PCM is dropped.
- Browsing history.
- Location history beyond per-lifeline-entry coordinates.

## Cross-device sync (optional)

`MemorySync.kt` is the sync layer. Configure your own GitHub repo in Settings → Memory Sync (private repo, fine-grained PAT with `contents:write`).

Channels pushed:
- `analytics/contact_profiles.jsonl` — Big Five rows
- `analytics/personality_records.jsonl` — LearningVault rows
- `analytics/contact_interactions.jsonl` — interaction log
- `analytics/contact_face_samples.jsonl` — face embeddings + crop PNG references (opt-in within sync)
- `analytics/skills.jsonl` — skill library
- `analytics/plans.jsonl` + `plan_executions.jsonl` — planner outputs

Merge rules:
- Last-writer-wins on row-level data.
- Per-cell max-timestamp for face samples + skill use counts.
- A device that hasn't synced a channel yet adopts whatever's there.

**Heartbeat:** `HeartbeatSyncer` triggers a sync after any large local write (e.g. completing a `PeopleCleanupRunner` pass).

## How to wipe

Settings → Memory → "wipe everything Mythara has learned about me" — drops all the analytics + memory tables.

Per-channel wipes via:
- Settings → Memory → wipe local face index
- Settings → People → hidden rows → restore / re-classify
- Settings → Skills → delete (per skill)
- Per-contact: open detail → ⋯ → wipe profile

If you sync, the wipe ALSO overwrites the synced JSONL on next push.

## Privacy stance

- **No analytics SDKs.** Zero telemetry libraries in the dependency tree.
- **No remote logging.** Every `Log.*` call lands in `logcat` only.
- **No vendor cloud.** Your sync bucket is yours. If you don't configure one, nothing leaves the device.
- **Read [PRIVACY.md](https://github.com/ankurCES/project_mythara/blob/main/docs/PRIVACY.md)** for the exhaustive list.

See also: [Privacy Model](Privacy-Model), [Architecture](Architecture).
