# Mythara

> Field intelligence across every device you own.

Voice-first Android AI assistant — **Crush-styled** terminal aesthetic. Runs your agent loop on **MiniMax** with a key you paste in settings: no backend, no telemetry. Two phone-control modes: **Assistive** (read-and-suggest) and **Automation** (taps, swipes, opens apps, sends SMS, places calls) — switchable in-app, with per-call confirmation by default.

What began as a single-device assistant is now a **distributed agent runtime**: every device you own — phone, foldable, tablet, watch — joins one private compute mesh, coordinating through a repo *you* control. No central server.

Plus a hidden **Observe** mode behind a triple-tap and a password: continuous on-device speech recognition, learnings extracted by a local LLM, raw audio and transcripts auto-purged so only durable learnings accumulate over time as evolving assistant memory.

---

## Distributed runtime

The headline. Mythara devices form a private cluster with **no central coordinator** — the nervous system is a private GitHub repo the user owns.

- **Per-device task lanes** — each device writes only `tasks/<device-id>/<month>.jsonl`. One writer per file, so write conflicts are impossible by construction.
- **Heartbeat sync** — every ~5 min each device pushes its own lane and pulls every peer's, reconciling task state (freshest terminal-state wins) like a CRDT made of phones.
- **Claim-and-execute** — a device claims any task addressed to it (or to "any device"), runs it with its *own* tools and sensors, captures the agent's answer, and writes the result back into its own lane.
- **Shared memory** — learnings, vault facts, analytics, the life timeline, and chat all sync bidirectionally through the same private repo.
- **Cross-device tools** — `create_task` / `team_call` fan a request out across the cluster; `list_mythara_devices`, `request_remote_sensors`, and `send_note` reach peers directly.

Each device contributes what only it can: the tablet is a 10" command center, each phone's sensors read a different room, the watch is a push-to-talk mic when your hands are full. The runtime routes work to the device positioned to do it.

---

## Status

**Pre-alpha — actively developed.** Working today:

**Core**
- `[x]` MiniMax streaming chat + agent loop + tool registry
- `[x]` Push-to-talk ASR + native / ElevenLabs TTS
- `[x]` Permission onboarding wizard + on-device model downloads
- `[x]` Crush palette, JetBrains Mono, MYTHARA Charple→Bok gradient wordmark

**Phone control**
- `[x]` Assistive + Automation modes (tap / swipe / type / open_app)
- `[x]` Notifications, camera (`take_photo`), SMS, calls, calendar
- `[x]` Persistent notification reply queue (survives process death)
- `[x]` Launcher mode + app drawer

**Distributed runtime**
- `[x]` Cross-device cluster over a user-owned private GitHub repo
- `[x]` Per-device task-file layout + heartbeat sync + claim-and-execute
- `[x]` Tablet command-center dashboard
- `[x]` Wear OS companion — PTT to phone + branded watch face

**Learning & sensing**
- `[x]` Observe mode — Vosk ASR + on-device Gemma extractor + encrypted vault
- `[x]` Sensors (`read_sensors`) + cross-device sensor requests + periodic learning
- `[x]` Health Connect ingestion + heart-rate correlation
- `[x]` AlarmManager reminders with personalized voice announcer
- `[x]` Notification dismissal-latency learning
- `[x]` Life timeline — auto-ingest photos, captioning, cross-device feed
- `[x]` Skills viewer + MCP server support

**Next**
- `[ ]` Tighter / push-driven sync cadence
- `[ ]` Fuller on-device inference
- `[ ]` Signed APK release

---

## Distribution

**Sideload only.** Mythara uses Android's Accessibility Service for automation, which Google Play's January 2026 policy enforcement explicitly bans for non-accessibility apps. We don't submit to Play.

Signed APKs land on the GitHub Releases page once a release build is cut. See [docs/SIDELOAD.md](docs/SIDELOAD.md) for install instructions.

---

## Build (dev)

Toolchain: **AGP 8.10 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 · Hilt 2.58 · Compose BOM 2024.12.01**. Modules: `:app` (phone/tablet) and `:wear` (Wear OS companion).

Prerequisites (Homebrew on macOS):

```bash
brew install openjdk@17
brew install --cask android-commandlinetools
brew install gradle

# SDK + platform 36
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager --install "platforms;android-36" "build-tools;36.0.0" "platform-tools"

# Build
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :wear:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk

# Install on a USB-connected device
./gradlew :app:installDebug
```

---

## Architecture

Single device — agent loop, tools, gate:

```
Voice mic     ┐
              ├──► Agent loop ──► MiniMax (Bearer + SSE)
Camera frame  │      ▲                │
              │      │     tool_calls │
              │  ┌───┴────────────────▼───┐
              └──┤ Tool Registry          │
                 │  • read_screen         │
                 │  • tap / swipe / type  │
                 │  • open_app, list_apps │
                 │  • read_notifications  │
                 │  • take_photo          │
                 │  • send_sms / call     │
                 │  • create_task / …     │
                 └────────────────────────┘
                              ▲
                              │ confirmation + allowlist
                          ┌───┴────┐
                          │ Gate   │
                          └────────┘
```

The cluster — devices coordinate through a private repo, no server:

```
   Pixel 10 Pro          Pixel Fold           Pixel Tablet         Watch
   ┌─────────┐          ┌─────────┐          ┌─────────┐        ┌──────┐
   │ agent   │          │ agent   │          │ agent   │        │ PTT  │
   │ + tools │          │ + tools │          │ + tools │        │ mic  │
   └────┬────┘          └────┬────┘          └────┬────┘        └──┬───┘
        │ push own lane      │                    │                │
        │ pull peers' lanes  │                    │                │
        └─────────┬──────────┴─────────┬──────────┘────────────────┘
                  ▼                    ▼
          ┌───────────────────────────────────┐
          │  private GitHub repo (user-owned) │
          │  tasks/<device-id>/<month>.jsonl  │
          │  memory · vault · analytics · …   │
          └───────────────────────────────────┘
```

**On-device, local-only:**
- Push-to-talk ASR via Android `SpeechRecognizer`; Observe-mode ASR via Vosk
- Speech-out via Android `TextToSpeech` (ElevenLabs / MiniMax T2A optional)
- Chat history via Room; encrypted learning vault for Observe mode
- API key via DataStore + Tink AEAD (key in Android Keystore)
- On-device LLM (LiteRT-LM / Gemma) for the Observe-mode learning extractor — raw audio and transcripts **never** leave the device

---

## Privacy

No backend, no telemetry, no analytics, no crash reporters. Network calls go only to:

- **your MiniMax endpoint** — the agent loop
- **your own private GitHub repo** — the cross-device sync substrate (memory, tasks, learnings); it's a repo *you* own and control
- **optional services you explicitly enable** — e.g. ElevenLabs for voice, photo captioning

Observe mode's raw audio and transcripts are on-device-only and auto-purged; only durable learnings persist. See [docs/PRIVACY.md](docs/PRIVACY.md) for the full disclosure.

---

## License

(TBD)
