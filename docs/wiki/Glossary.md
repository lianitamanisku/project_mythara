# Glossary

Terms, acronyms, file paths, and shortenings that show up in the codebase + wiki.

## Concepts

| Term | Meaning |
|---|---|
| **Agent loop** | The streaming model-call → tool-call → next-iteration cycle. `agent/AgentLoop.kt`. |
| **Agent runner** | The queue + lifecycle around the loop. `agent/AgentRunner.kt`. |
| **Amulet** | Compose surface that holds the rose chip + constellation. Two flavours: `RoseAmulet` (always-present at the bottom of every screen) + `PopupAmulet` (long-press summoned at the touch point). |
| **Autopilot** | Settings toggle that lets the agent act on its own — wake word + auto-process notifications + proactive scheduling. |
| **BAL** | Background Activity Launch — Android 14+ permission gate for cross-package activity starts. Mythara uses `setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` to defeat it on the user's behalf. |
| **Big Five** | Personality dimensions: openness, conscientiousness, extraversion, agreeableness, neuroticism. Stored 0..1 in `ContactProfileRow`. |
| **BYO** | Bring Your Own — model, sync repo, anything. |
| **Charple** | The brand purple (`#6B50FF`). Used for the primary accent / spine / chevron / agent glyph. |
| **Constellation** | The 9-chip menu that pages out of the rose on the popup amulet (page 1 = primary destinations, page 2 = secondary, page 3+ = installed apps). |
| **Edge glow** | The 2 dp Charple→Bok gradient line under every scaffolded screen. Long-pressable hot zone. |
| **Field intelligence** | Mythara's framing — an agent that lives in the field (with you, mobile, contextual) vs. one that lives in a data centre. |
| **FaceMesh** | The animated particle face on Home + the dedicated Face screen. Tracks head pose via ML Kit. |
| **Glyph** | A character from `Glyphs.kt` — `◆ ◇ ● ✓ × ⋯ ⟳ ▌ │ → ← ⇣ ○ ◉ ┃`. Used everywhere a Material 3 icon would normally go. |
| **Lifeline** | The chronological photo + caption timeline. Each entry is one row in `lifeline_entries`. |
| **Living Rose** | One of the four skins. Backdrop is the geometric rose pulsing with HR. |
| **MemorySync** | The component that pushes / pulls JSONL channels through *your* GitHub repo. No Mythara server involved. |
| **Motif** | A short tone-phrase in the OM-harmonic scale. Music Mode renders each word as a motif. |
| **Mythara Spine** | The 3 dp Charple-breathing vertical bar on the right edge of every screen. Tap → launcher menu. |
| **NotifHub** | Routes name for the Alerts screen — `Routes.NotifHub = "notif-hub"`. |
| **OM-harmonics** | The 9-note scale derived from 136.1 Hz (Sanskrit OM). Music Mode + the constructed-language layer use it. |
| **Particle ring** | The pre-bloom anticipation animation that appears at 300 ms of a long-press, before the amulet commits at 600 ms. |
| **Persona snapshot** | The per-turn injection of "what Mythara knows about the user" into the system prompt. |
| **PII** | Personally identifiable information. Mythara's privacy model is built to keep PII on-device. |
| **PTT** | Push-to-talk. Hold the rose ≥ 3 s. |
| **Rose** | The geometric brand mark — petals + hex nucleus. Same geometry on the live wallpaper, watch face, in-app amulet, and Living Rose skin backdrop. Constants in `RoseGeometry.kt`. |
| **Routes** | The string constants for nav destinations in `MytharaRoot.kt`. |
| **Scaffold** | `MytharaScaffold` — the shared chrome (top sliver + edge glow) every screen wraps in. |
| **Skill** | A saved tool-chain recording. Stored as `filesDir/skills/<name>/SKILL.md`. |
| **Skin** | One of the four visual languages (Spatial / Aurora Glass / Living Rose / Holographic HUD). Each is `palette × SkinSpec`. |
| **SpotlightDrawer** | The full-search app drawer reached from the spine launcher's rose-row → search affordance. |
| **Terminal mode** | Opt-in chat aesthetic — monospace green-on-near-black log lines instead of card bubbles. |
| **ToolRegistry** | The catalogue of every tool the agent can call. ~65 entries. |
| **Vocabulary (Music Mode)** | The user's evolving lexicon of motifs. Persisted as a JSON blob in `MusicVocabularyStore`. |

## Acronyms

| | |
|---|---|
| **ANR** | Application Not Responding (Android system gate; the agent loop has a watchdog to avoid this) |
| **BAL** | Background Activity Launch |
| **DI** | Dependency Injection (Hilt throughout) |
| **EMA** | Exponential Moving Average (face pose smoothing) |
| **FGS** | Foreground Service (notification listener, lockscreen island, glasses connection) |
| **LWP** | Live Wallpaper Service |
| **NNAPI** | Neural Networks API (Android TFLite delegate) |
| **PAT** | Personal Access Token (GitHub, for sync) |
| **POS** | Part Of Speech (used loosely in the constructed-language particle categories) |
| **PTT** | Push To Talk |
| **STT** | Speech To Text |
| **TTS** | Text To Speech |
| **WCAG** | Web Content Accessibility Guidelines (used for the karaoke-highlight contrast picker) |
| **YAML** | Yet Another Markup Language (skill frontmatter) |

## Key file paths

| What | Where |
|---|---|
| App entry | `app/src/main/kotlin/com/mythara/MainActivity.kt` |
| Compose root | `app/src/main/kotlin/com/mythara/ui/MytharaRoot.kt` |
| Agent loop | `app/src/main/kotlin/com/mythara/agent/AgentLoop.kt` |
| Tool registry | `app/src/main/kotlin/com/mythara/agent/ToolRegistry.kt` |
| Individual tools | `app/src/main/kotlin/com/mythara/agent/tools/` |
| Plan agent + executor | `app/src/main/kotlin/com/mythara/agent/planner/` |
| Theme + palette | `app/src/main/kotlin/com/mythara/ui/theme/` |
| Brand geometry | `app/src/main/kotlin/com/mythara/ui/amulet/RoseGeometry.kt` |
| Markdown renderer | `app/src/main/kotlin/com/mythara/ui/markdown/MarkdownText.kt` |
| Music mode | `app/src/main/kotlin/com/mythara/music/` |
| Face pipeline | `app/src/main/kotlin/com/mythara/camera/` + `app/src/main/kotlin/com/mythara/face/` |
| Sync layer | `app/src/main/kotlin/com/mythara/memory/MemorySync.kt` |
| MiniMax adapter | `app/src/main/kotlin/com/mythara/minimax/StreamingChat.kt` |
| Privacy doc | `docs/PRIVACY.md` |
| Self-organising memory | `docs/SELF_ORGANIZING_LEARNING.md` |

See also: [Architecture](Architecture), [Mobile UX Patterns](Mobile-UX-Patterns).
