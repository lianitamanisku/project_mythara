# Build and Install

## Requirements

- **Android Studio** Iguana (2024.1) or newer
- **JDK 17** — install via `brew install openjdk@17` (macOS) or your package manager
- **Android SDK 34+** + platform-tools (adb)
- **An Android 14+ device** — Pixel 9 / 10 / Fold tested. Emulators work for UI but miss camera + sensors.
- **MiniMax API key** for the default model adapter — get one at [minimax.io](https://www.minimax.io). Free tier is enough for daily use. Or wire up your own local LLM (see [Bring Your Own Model](Bring-Your-Own-Model)) before the first run.

## Clone + build

```bash
git clone https://github.com/ankurCES/project_mythara.git
cd project_mythara

# If openjdk@17 was brew-installed and isn't on PATH:
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk` (~85 MB — TFLite weights + Tailwind asset bundle).

## Install via adb

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.mythara.debug/com.mythara.MainActivity
```

## Multi-device cluster install

Mythara is designed to span every device you own. `install-cluster.sh` (in the repo root) installs to every connected `adb devices`:

```bash
./install-cluster.sh
```

It's used during development; works fine for one-off installs too.

## First-run setup

1. **Permissions** — Mythara requests at first launch:
   - `CAMERA` — face tracker on Home + the face screen
   - `RECORD_AUDIO` — voice composer + wake word
   - `READ_CONTACTS` — populate the People screen
   - `READ_CALL_LOG` — call log timeline
   - `POST_NOTIFICATIONS` — agent replies + confirmation gate
   - Notification access (Settings → Apps → Special access) — Alerts hub + auto-triage

2. **Bind the model** — Settings → MiniMax → paste API key OR switch the adapter to your local-LLM module.

3. **Pick a skin** — Settings → Appearance → Spatial (default) / Aurora Glass / Living Rose / Holographic HUD.

4. **Sync (optional)** — Settings → Memory Sync → enter your GitHub PAT + repo slug → first push.

5. **Pickup the phone** — the face mesh + spinning rose at the bottom of Home come alive.

## Build flavours

- **debug** — what you build during dev. `applicationId` is `com.mythara.debug` so it coexists with a release install.
- **release** — `./gradlew :app:assembleRelease` after configuring a signing config in `app/build.gradle.kts`.

## Common build issues

| Problem | Fix |
|---|---|
| `Unable to locate a Java Runtime` | `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| `MiniMax 400 invalid params context window exceeds limit` | Hit Settings → Clear chat history; or the `ContextBudgetGuard` should auto-summarise from v6+. Make sure you're on `main`. |
| `BAL_BLOCK` on Alerts notification tap | Make sure you're on `main` — Android 14+ requires `ActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`. Fixed in `c567a59`. |
| Black face mesh on Home | Camera permission not granted, or the pickup window expired (move the phone). |
| `cmd notification post` notifications don't appear | Notification access not granted to Mythara. |

## Updating

```bash
git pull
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Settings + chat history + memory survive in-place upgrades. Schema migrations are documented in the relevant DB files (`HistoryDb.kt`, `ContactProfiles.kt`, etc.).

See also: [Bring Your Own Model](Bring-Your-Own-Model), [Architecture](Architecture).
