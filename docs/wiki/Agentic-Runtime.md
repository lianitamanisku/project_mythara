# Agentic Runtime

The agent loop is the heart of Mythara. It's a reactive streaming loop with five layered guardrails, a tool registry with hook middleware, an optional planner agent for multi-step tasks, and a skill suggester that learns repeated tool chains.

## Components

### 1. `AgentLoop` (`agent/AgentLoop.kt`)
- Streams to the model adapter (`MiniMaxClient` today).
- Iterates: stream → collect tool calls → execute via `ToolRegistry` → loop until `finish_reason=stop` or guardrail triggers.
- Composes a per-turn system message from: time of day, mood snapshot, persona snapshot, recall results, voice-style hint, skill-offer prompt, autopilot toggle, language preference.
- Emits a `Turn.*` flow for the UI to render in real time.

### 2. `LoopDetector` (`agent/LoopDetector.kt`)
- Per-turn ring buffer of (toolName, argsHash, resultHash) signatures.
- Halts the loop when any signature repeats > 5 times — protects against read → edit → read → edit cycles.

### 3. `ContextBudgetGuard` (`agent/ContextBudgetGuard.kt`)
- Tracks approximate token count of running history (4 chars ≈ 1 token).
- When `(budget − estimated) < SOFT_THRESHOLD`, fires a single non-streaming summarisation call and replaces the oldest 60 % of the conversation with a `<conversation-summary>` block.
- Keeps the most recent 12 turns verbatim, always.

### 4. `HookRunner` (`agent/HookRunner.kt`) + `ToolHook` interface
- Pre-tool middleware: `Decision(Allow | Deny | Rewrite(newArgs))`.
- Post-tool middleware: optional result rewrite.
- Default hooks:
  - **`PathSanitiserHook`** — rewrites `~/foo` and relative paths in `read_file` / `write_file`.
  - **`DangerousShellHook`** — denies `rm -rf`, `dd`, `chmod 777` patterns from `run_shell` and `termux_exec`.
  - **`AutoApproveHook`** — consults `AllowlistStore` so previously-approved (tool, args-shape) pairs skip the confirmation gate.

### 5. `ConfirmationGate` (`agent/ConfirmationGate.kt`)
- Destructive tools (`send_sms_direct`, `place_call_direct`, `write_file` outside `filesDir`, `apply_cosmetic`, `run_shell` / `termux_exec` outside the safe-binary allowlist) always prompt.
- User can tick "always allow" → that (tool, args-shape) is persisted in the allowlist.

### 6. `PlanGate` + `PlannerAgent` + `PlanExecutor` (`agent/planner/`)
- `PlanGate` scores incoming prompts; high enough → invoke the planner.
- `PlannerAgent` is a tool-less, one-shot model call that returns a JSON plan: `[{verb, description, expectedTools, dependsOn, successCriteria}]`.
- `PlanExecutor` walks each step through the same `AgentLoop` with a step-scoped system message, refreshes context between steps with each step's short summary.
- Resumable across process death (Room-backed).

### 7. `SkillSuggestionStore` (`agent/SkillSuggestionStore.kt`)
- Watches finished turns for repeating tool-chain shapes.
- Patterns: repeating noun-verb pair, deterministic 4+ tool UI chains, explicit user "save this as a skill" signal.
- Skills persist as on-disk `filesDir/skills/<name>/SKILL.md` (markdown + YAML frontmatter).
- `RunSkillTool` parses + executes the recorded chain with parameter substitution.

## Tool surface (65+ tools)

All tools are Kotlin objects under `agent/tools/` registered in `ToolRegistry`. Examples:

| Tool | What it does |
|---|---|
| `send_sms_direct` | Compose + send SMS via the system messaging app |
| `send_whatsapp_direct` | Open a chat or send via `whatsapp://send?phone=…` |
| `place_call_direct` | `ACTION_CALL` with `CALL_PHONE` |
| `create_calendar_event` | Insert into Calendar provider |
| `set_alarm` | AlarmClock intent |
| `create_task` | Mythara's own task store |
| `screen_read` | Accessibility-backed screen text capture |
| `run_shell` | `ProcessBuilder` with allowlisted binaries |
| `termux_exec` | `com.termux.RUN_COMMAND` intent (when Termux installed) |
| `termux_api` | termux-clipboard / termux-camera-photo / termux-tts-speak / etc. |
| `read_file` / `write_file` | Allowed roots: filesDir, externalFilesDir, cacheDir, Downloads |
| `list_dir` | Directory listing |
| `web_fetch` | HTTP GET via `OkHttp` |
| `open_url` | Inline WebView or system Chrome (`mode=chrome`) |
| `render_canvas` | Bundle Tailwind + Preact + DaisyUI; agent ships HTML to a WebView |
| `update_canvas` | `webView.evaluateJavascript` incremental update |
| `read_canvas_input` | Suspend until JS posts back |
| `generate_image` | MiniMax image-gen → `content://` URI |
| `apply_cosmetic` | Shizuku-backed `settings put` for accent / font / motion / dark mode |
| `list_cosmetic_options` | Discover what's controllable |
| `linux_vm` | SSH bridge to the Android 15 Linux Terminal Debian VM |
| `save_skill` / `run_skill` / `list_skills` / `get_skill` | Skill management |
| `spawn_agent` | Sub-agent for delimited research |

Adding a new tool: ~30 LOC. See [Contributing](Contributing).

## System prompt

The system prompt (composed in `AgentLoop.kt:218-440` from constants + dynamic injections) tells the model:

- Self-identification: "You are Mythara — a personal field intelligence agent. Built by Ankur (Creator) using Lumi."
- Today's date + timezone.
- Voice-vs-text style rules (terser, no markdown, friendlier in voice mode).
- Tool catalogue with short usage rules.
- Persona snapshot (the user's traits the agent has learned so far).
- Mood snapshot (current inferred mood).
- Recall results (top facts from `LearningVault` matching the prompt).
- Termux preference hint when available.
- Skill-offer prompt when a suggestion is pending.

## Where to dig

- **`AgentLoop.kt`** — heavily commented; read top-to-bottom for the full picture.
- **`AgentRunner.kt`** — the queue + lifecycle around the loop.
- **`ToolRegistry.kt`** — every tool registered here.
- **`agent/planner/PlanModel.kt`** — Plan + PlanStep data classes; understand once and the executor reads obvious.

See also: [Architecture](Architecture), [Bring Your Own Model](Bring-Your-Own-Model).
