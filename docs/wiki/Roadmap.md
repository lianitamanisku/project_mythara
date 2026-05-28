# Roadmap

The repo's already-shipped scope is in the v6 / v7 plans. This page is forward-looking: what's next + what's wanted from the community.

## Near term (next 1–2 releases)

### Local-model module (community-wanted)
Gemma Nano via MediaPipe LLM inference, swappable from MiniMax at runtime via Settings. **See [Bring Your Own Model](Bring-Your-Own-Model)** — this is the single highest-leverage PR for the project. Status: open for contributions.

### Onboarding tutorial
Gesture-by-gesture intro for the rose amulet, spine, PTT, alerts, popup constellation. First-launch only; persisted via `OnboardingStore`. Status: not started.

### Plugin SDK
Third-party tools as Android Services discovered at runtime; Mythara loads them with permission gates. Lets contributors ship a tool as a separate APK without forking the whole repo. Status: design phase.

## Medium term

### Watch face + complications
The geometric rose lives on your Wear OS face and pulses with HR. Mood-driven palette swap. Status: planned post-local-model.

### Foldable tabletop layout
Pixel 9 Pro Fold tabletop posture — face mesh to the top half, chat composer to the bottom, alerts as a peek column. The `FoldPostureProvider` already detects the posture; the rendering work is what's left.

### Per-user model fine-tuning (on-device LoRA)
Once local Gemma lands, a worker that periodically fine-tunes a small LoRA on the user's chat history. The model gradually adapts to their voice without any data leaving the device. Status: speculative.

### Skill marketplace (federated)
Skills are markdown files. Build a viewing layer that browses skills shared in *other people's* GitHub repos (URL-pasted, not centralised). Each skill carries the tools it expects + a permission preview. Status: post-plugin-SDK.

### Glasses integration (v3 plan)
Meta Display Glasses neural-band PTT + face recognition through the glasses → ProfileCard overlay. The v3 architecture is documented in `there-are-afew-tasks-ethereal-crab.md`; only Phase 0-2 has shipped.

## Long term / speculative

- **Mythara Mesh** — many devices, your own private compute mesh. The `MemorySync` repo is the substrate; we want a deterministic task router that says "this prompt is best answered by your laptop's bigger model".
- **Visual command palette via Canvas** — the agent renders an interactive UI in a WebView when the user's task warrants it. Already works at a low level (`render_canvas`); needs more design.
- **Hardware partner** — if you make Android phones and want one whose AI is local-first, [email Ankur](https://github.com/ankurCES).

## Won't do

- **Centralised account system.** No Mythara cloud. Ever.
- **Ad SDK / tracking.** Never.
- **Closed-source releases.** The whole point is to be auditable.
- **Multi-tenant / B2B SaaS.** Out of scope. This is for individuals.
- **Crypto.** Mythara is not a wallet, not a chain, not a token.

## Out-of-scope-but-related

- **Replacing Android.** Mythara is an OS *layer*, not a replacement for AOSP. If you want to ship a custom AOSP build with Mythara pre-installed, that's a separate project we'd happily reference.
- **iOS port.** The architecture would survive but Apple's restrictions on accessibility / notification listeners / shell access kill most of the agent tools. Not planned.

## How priorities are set

- **Local-model module** is #1 because every other improvement gets multiplied by it.
- **What real users keep tripping over** is #2. Discoverability, build issues, bad defaults — file an issue and it gets queued.
- **Maintainer's daily-driver pain** is #3. The repo is built by someone who uses Mythara as their actual phone OS layer. Bugs that hurt that experience get fixed quickly.

## How to influence the roadmap

- Open issues.
- Send PRs.
- Talk on the [Discussions tab](https://github.com/ankurCES/project_mythara/discussions) (once enabled).

See also: [Contributing](Contributing), [Bring Your Own Model](Bring-Your-Own-Model).
