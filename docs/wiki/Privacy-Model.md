# Privacy Model

The short version: **everything stays on your phone unless YOU configure cross-device sync to a repo YOU own.** No telemetry, no analytics SDKs, no vendor cloud.

The long version is in [`docs/PRIVACY.md`](https://github.com/ankurCES/project_mythara/blob/main/docs/PRIVACY.md). This page summarises the model + answers common questions.

## What's stored locally

| Category | Storage | What gets in |
|---|---|---|
| Chat history | Room (`mythara_history.db`) | Every user + assistant message, persisted |
| Big Five + persona | Room (`mythara_contact_profiles.db`) | Per-contact traits + insights + relationship summary derived from chats |
| Personality records | DataStore + LearningVault | LIWC-derived trait observations |
| Memory graph | Room (`mythara_graph.db`) | Entity nodes + edges extracted from chat |
| Contact interactions | Room (`mythara_interactions.db`) | Send / receive / call / mention / physical-meet rows |
| Face embeddings | Room (`mythara_contact_faces.db`) + private filesDir PNG crops | Only photos you explicitly add via the picker |
| Lifeline timeline | Room (`mythara_lifeline.db`) + private filesDir thumbnails | Phone-roll photos you've opted to caption |
| Skills | `filesDir/skills/<name>/SKILL.md` | Saved tool-chain recordings |
| Audit log | Room (`mythara_audit.db`) | Every tool call, with args + result snippet |
| Settings | DataStore | API keys, allowlists, kind overrides, autopilot toggle, theme prefs |

## What's NOT stored

- Live camera frames — `FaceTracker` operates in-memory only.
- Raw audio after wake / PTT capture — once the transcript is in, the PCM is released.
- Browsing history.
- Location history beyond per-lifeline-entry coords (when you take a geotagged photo).
- Notification bodies on disk — `NotificationListener`'s buffer is 50 rows in memory.

## What leaves the device

**By default: nothing.** Specifically:

- No analytics SDK is included. Zero. Grep the dep tree.
- No remote logging — every `Log.d/i/w/e` lands in `logcat` only.
- No silent "phone home" anywhere in the code.

**If you enable a remote chat model** (MiniMax is the default), the things that leave when you submit a turn:
- The last N messages of chat history.
- The system prompt (which embeds: today's date, your name, mood snapshot, persona snapshot, recall results from `LearningVault`).
- The tool catalogue.

The model's response comes back. Nothing else is sent in the background.

**If you configure cross-device sync** (Settings → Memory Sync), Mythara writes the channels listed in [Local-First Memory & Personality](Local-First-Memory-and-Personality) as jsonl into your own GitHub repo via a fine-grained PAT you scope yourself. No Mythara server is involved.

**If you opt into face-sample sync** (separately), face embeddings + crop PNGs are bundled into the sync stream. Defaults to OFF. Documented warning in the UI.

## Audit it yourself

```bash
# Every place a remote endpoint is hit
git grep -nE "(https?://|HttpUrl|OkHttp|Url\.parse)" app/src/main/kotlin

# Every place a key is read
git grep -nE "(API|api).*key" app/src/main/kotlin

# Every place data is written off the device
git grep -nE "(github|MemorySync|upload)" app/src/main/kotlin
```

The repo is small enough that 30 minutes will get you a complete picture.

## How to wipe

| Wipe | How |
|---|---|
| Single contact | open contact detail → ⋯ → wipe profile |
| All people analytics | Settings → Memory → "wipe everything Mythara has learned about me" |
| Face index | Settings → Memory → wipe local face index |
| Skills | Settings → Skills → delete per skill |
| Allowlist | Settings → Allowlist → clear |
| Synced data | wipe locally → next sync push overwrites the remote JSONL |
| Nuclear | `pm clear com.mythara.debug` (loses everything except external-storage photos) |

## Mythara's stance on the Android 17 features

| Feature | Stance |
|---|---|
| Gemini scanning photo library for passport auto-fill | Out. Face index is opt-in per-contact; never auto-scans for ID docs. |
| Cross-device cloud sync via Google account | Out. Sync, if any, goes through your repo. |
| Cloud-side wake word | Out. Vosk runs on-device. |
| Targeted-ad signal | Out. No tracking SDKs at all. |
| "Smart suggestions" trained on your data | We don't centralise data; you can't train on a corpus you don't have. |

See also: [Why Mythara](Why-Mythara), [Local-First Memory & Personality](Local-First-Memory-and-Personality), `docs/PRIVACY.md` (in the repo).
