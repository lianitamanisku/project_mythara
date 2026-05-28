# Architecture

Mythara is one Android app organised around four collaborating layers:

1. **UI / Compose surface** — the chat screen, home hub, alerts hub, contacts, settings.
2. **Agent runtime** — chat composer → runner → loop → tool registry. The brain.
3. **On-device analytics + memory** — Big Five, face index, graph, contact profiles, lifeline photos.
4. **Model adapter** — `MiniMaxClient` today, your local Gemma / Llama / Qwen tomorrow.

```
┌──────────────────────────────────────────────────────────────────┐
│                        ChatViewModel                             │
│        (composer · tool-call rendering · plan cards)             │
└────────────────────┬─────────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────────┐
│                       AgentRunner                                │
│   queue · plan-gate · marker prefix routing · turn lifecycle     │
└────────────────────┬─────────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────────┐
│                       AgentLoop                                  │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌──────────────┐   │
│  │ Context    │ │ Loop       │ │ Hook       │ │  Skill       │   │
│  │ Budget     │ │ Detector   │ │ Runner     │ │  Suggester   │   │
│  │ Guard      │ │            │ │            │ │              │   │
│  └────────────┘ └────────────┘ └────────────┘ └──────────────┘   │
└────────────────────┬─────────────────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────────────────┐
│                     ToolRegistry (65+ tools)                     │
│  send_sms · send_whatsapp · place_call · create_calendar_event   │
│  set_alarm · create_task · screen_read · run_shell · termux_exec │
│  read_file · write_file · web_fetch · render_canvas              │
│  generate_image · open_url · save_skill · run_skill · ...        │
└──────────────────────────────────────────────────────────────────┘

   On-device analytics            Memory                Model
   ─────────────────             ───────              ──────────
   PersonaTraitExtractor         Room DBs             MiniMaxClient
   GraphTurnExtractor            DataStore            (or your local
   ContactProfileRepo            MemorySync            Gemma / Llama /
   FaceTracker                   (your GitHub)         Qwen / DeepSeek)
   NotificationListener
```

## File map (high-impact entry points)

| Concern | File |
|---|---|
| Compose root | `app/src/main/kotlin/com/mythara/ui/MytharaRoot.kt` |
| Chat surface | `app/src/main/kotlin/com/mythara/ui/chat/ChatScreen.kt` + `ChatViewModel.kt` |
| Home with face mesh | `app/src/main/kotlin/com/mythara/ui/home/HomeHubScreen.kt` |
| Alerts hub | `app/src/main/kotlin/com/mythara/ui/notifications/NotificationHubScreen.kt` |
| Contacts list + detail | `app/src/main/kotlin/com/mythara/ui/people/` |
| Theme engine | `app/src/main/kotlin/com/mythara/ui/theme/` |
| Agent runner | `app/src/main/kotlin/com/mythara/agent/AgentRunner.kt` |
| Agent loop (the brain) | `app/src/main/kotlin/com/mythara/agent/AgentLoop.kt` |
| Tool registry | `app/src/main/kotlin/com/mythara/agent/ToolRegistry.kt` |
| Individual tools | `app/src/main/kotlin/com/mythara/agent/tools/` |
| Plan agent + executor | `app/src/main/kotlin/com/mythara/agent/planner/` |
| Skill suggester | `app/src/main/kotlin/com/mythara/agent/SkillSuggestionStore.kt` |
| MiniMax adapter | `app/src/main/kotlin/com/mythara/minimax/StreamingChat.kt` |
| Personality extractor | `app/src/main/kotlin/com/mythara/analytics/PersonaTraitExtractor.kt` |
| Contact graph | `app/src/main/kotlin/com/mythara/memory/graph/GraphTurnExtractor.kt` |
| Face tracker + index | `app/src/main/kotlin/com/mythara/camera/` + `face/` |
| Notification listener | `app/src/main/kotlin/com/mythara/services/NotificationListener.kt` |
| Memory sync (BYO repo) | `app/src/main/kotlin/com/mythara/memory/MemorySync.kt` |

## Sequence: one chat turn

1. User types or speaks. Compose sends `text` to `ChatViewModel.submit()`.
2. `ChatViewModel` delegates to `AgentRunner.submit(text, fromVoice, pcm)`.
3. `AgentRunner` checks the **plan-gate** — long, sequencing-rich prompts decompose into a multi-step plan via `PlannerAgent`. Otherwise the reactive loop fires.
4. `AgentLoop` enters its iteration:
   - **Context-budget guard** trims old history into a summary block when the model's context is near full.
   - System message is assembled from time, recall, mood, persona, voice-style guidance, skill-offer prompts.
   - Streams to `MiniMaxClient` (or your model).
   - Each token → emitted to UI via `Turn.Partial`.
   - Each tool call → `HookRunner` middleware → `ToolRegistry.execute()` → tool result → next loop iteration.
   - **LoopDetector** halts on signature-repeat cycles.
   - **SkillSuggestionStore** watches tool sequences; offers to save reusable skills.
5. Final `Turn.Finished` → ChatViewModel persists message + may TTS-speak.

## Sequence: a tool call

```
  Model decides: send_sms_direct(to=..., body=...)
        │
        ▼
  AgentLoop emits Turn.ToolCallRequested("send_sms_direct", args)
        │
        ▼
  HookRunner.preToolUse:
    - PathSanitiserHook (rewrite ~/foo → /data/...)
    - DangerousShellHook (deny rm -rf etc)
    - AutoApproveHook (consult allowlist)
        │
        ├── if denied → Turn.Error
        ├── if needs confirm → ConfirmationGate notification → user taps
        └── if allowed → ToolRegistry.execute()
        │
        ▼
  Tool runs (Android intent / Room write / Termux process / etc)
        │
        ▼
  HookRunner.postToolUse (optional result rewrite)
        │
        ▼
  AgentLoop appends tool result message → next iteration
```

## Cross-device

The agent runtime is the same on every device you sideload Mythara to. **`MemorySync`** (`memory/MemorySync.kt`) pushes a set of jsonl shards (contact profiles, personality records, skills, lifeline thumbnails) into a GitHub repo *you own and configure*. Other devices pull and merge.

There is no Mythara server. Devices coordinate through your repo. See [Local-First Memory & Personality](Local-First-Memory-and-Personality).

## Where to dive next

- **[Agentic Runtime](Agentic-Runtime)** — every guardrail in the loop + how tools are written.
- **[Local-First Memory & Personality](Local-First-Memory-and-Personality)** — Big Five + face + graph + sync.
- **[Bring Your Own Model](Bring-Your-Own-Model)** — replace the MiniMax adapter with your local LLM.
