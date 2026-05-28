# Contributing

Mythara is a personal-AI Android OS layer that needs people to grow into a movement. The agentic runtime, the local memory pipeline, and the design language are yours to fork. Below are concrete contribution paths weighted by impact.

## Highest-leverage PRs

### 🥇 Swap MiniMax for a local model
**This is the most valuable PR anyone can ship.** Build a local-LLM adapter (Gemma Nano via MediaPipe, llama.cpp on JNI, or Ollama via LAN HTTP) and submit it as a `local-<model>/` module.

- See [Bring Your Own Model](Bring-Your-Own-Model) for the contract, system-prompt pattern, and worked Gemma example.
- Target: Gemma 2B IT, Phi-2, Falcon RW 1B for on-device. Qwen-2.5-3B, DeepSeek-Coder for desktop-bridge.
- Acceptance: smoke test with one tool call (e.g. `"what's my battery via termux"`) passing on a Pixel 9.

### 🥈 Add a tool
New tools land in `agent/tools/<NewTool>.kt` and register in `ToolRegistry`. ~30 LOC each. Examples we'd merge today:

- **GitHub PR creation** — open a PR for a repo via the GitHub API + a fine-grained PAT.
- **Obsidian vault writes** — append/edit Markdown notes in a configured vault path.
- **Spotify control** — play / pause / skip / search via the Spotify Web API.
- **Calendar conflict detection** — find collisions across multiple calendars.
- **Expense categorisation** — read a CSV / SMS bank notification, categorise, store.
- **Weather** — local-station scrape, not Google Weather.
- **HomeAssistant bridge** — light / climate / lock control via HA REST.
- **Email triage** — read IMAP, classify, draft replies.

### 🥉 New skin
Ship a `MythPalette` + `SkinSpec` + `MythBackdrop` branch. Light variants for existing skins also welcome.

- CRT (green phosphor + scanlines)
- Paper (warm beige + ink)
- Brutalist (high-contrast B&W + sharp corners)
- Newspaper (serifed type + multi-column)

## Medium-impact PRs

- **On-device STT** — Whisper-cpp port, or Vosk grammar refinement, or a `SpeechRecognizer` fallback that doesn't ship transcripts to Google.
- **On-device image-gen** — Stable Diffusion XS via ONNX or NCNN.
- **Planner LLM** — separate, smaller model dedicated to the plan-gate decision (improves latency).
- **Better skill detector** — more sophisticated pattern recognition over tool sequences (LCS, Levenshtein, etc.).
- **Translations** — i18n for `res/values/strings.xml` + system-prompt translations in `AgentLoop.kt`.
- **Privacy audits** — read `MemorySync` + the audit log; tell us what we missed.
- **Watch face complications** — Wear OS face that pulses with HR via the live-wallpaper sink.
- **Foldable layouts** — tabletop posture optimisations on the Pixel 9 Pro Fold.

## Lower-impact but useful

- **Bug fixes** — search the repo for `// TODO` and `// FIXME`.
- **Tests** — unit tests for the markdown renderer, plan model, entity classifier.
- **Docs** — improve wiki pages, KDoc, README.
- **Examples** — `examples/` directory with reference plugins (custom tool, custom skin, model adapter).

## Code style

- **Kotlin idioms** — favour data classes, sealed types, suspending functions.
- **KDoc** — every public symbol gets a paragraph explaining the *why*, not the *what*. Existing files set the tone — match it.
- **No new dependencies without discussion** — the dep tree is deliberately small. Open an issue first.
- **Tests are appreciated, not required** — but if you're changing something subtle (encoder, parser, classifier), include a test.

## PR template (sketch)

```markdown
## What
One sentence on the change.

## Why
Two sentences on what was wrong / what user need this solves.

## How
A paragraph on the design choice. Reference architecture docs.

## Verification
- Built debug APK
- Installed on Pixel <model>
- Manual test: <one specific behaviour>
- Logcat: <relevant tag, expected line>
```

## What we won't merge

- Telemetry / analytics SDKs.
- Anything that introduces a vendor cloud as a default.
- Crypto / token / NFT integrations.
- Closed-source binary blobs (TFLite weights from open repos are fine; opaque libs are not).
- New permissions added without a clear user-facing feature.

## How to claim a task

- Open an issue describing what you're going to build before you start.
- Tag it with `wip` so we don't duplicate work.
- PR when ready; the reviewer is usually [@ankurCES](https://github.com/ankurCES).

## License + CLA

MIT for the repo. No CLA — your contributions stay yours; merging means they ship under the same MIT umbrella.

## What you get

- A real foothold in the agentic-AI mobile category at the moment Google reframes Android as an "intelligence system".
- The agentic runtime is portable. Your tool / skin / model adapter ships in a private-AI phone, not a cloud-vendor data pipeline.
- The maintainer responds to issues and PRs personally. No bot triage.

See also: [Architecture](Architecture), [Bring Your Own Model](Bring-Your-Own-Model), [Roadmap](Roadmap).
