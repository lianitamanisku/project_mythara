# Bring Your Own Model

The agent runtime is **model-agnostic**. MiniMax is the default because it's cheap, fast, and supports the full tool-call streaming protocol. But the only file the agent loop actually talks to is `minimax/StreamingChat.kt` — a 200-LOC streaming adapter. Replace it and Mythara runs on your model.

**This is the highest-leverage PR anyone can ship to this repo.**

## What the adapter contract is

The agent loop expects a function (`StreamingChat.stream(...)`) that:

1. Takes a list of `ChatMessage` (system + user + assistant + tool messages), a tool catalogue, and standard sampling params.
2. Returns a `Flow<StreamingEvent>` where events are one of:
   - `StreamingEvent.TextDelta(content: String)` — streamed tokens
   - `StreamingEvent.ToolCallDelta(index: Int, name: String?, argsDelta: String?)` — incremental tool-call assembly
   - `StreamingEvent.FinishReason(reason: String)` — `"stop"`, `"tool_calls"`, `"length"`, etc.
   - `StreamingEvent.Usage(...)` — token counts (optional)
3. Throws on error; the loop will retry with a backoff.

That's it. The agent loop does the rest.

## Path 1: Local Gemma Nano via MediaPipe (recommended starter)

[MediaPipe LLM Inference](https://developers.google.com/mediapipe/solutions/genai/llm_inference/android) ships a `LlmInference` API that runs Gemma 2B / Phi-2 / Falcon RW 1B on Pixel-class hardware via the NNAPI delegate.

**Steps:**

1. Add to `app/build.gradle.kts`:
   ```kotlin
   implementation("com.google.mediapipe:tasks-genai:0.10.14")
   ```
2. Download a `.task` model file (Gemma 2B Q4_K_M for Pixel 9+) to `/data/local/tmp/llm/`:
   ```bash
   wget https://path-to-gemma-2b-it-gpu-int4.bin \
     -O /tmp/gemma.task
   adb push /tmp/gemma.task /data/local/tmp/llm/gemma.task
   ```
3. Create `app/src/main/kotlin/com/mythara/local/GemmaChat.kt`:

   ```kotlin
   @Singleton
   class GemmaChat @Inject constructor(@ApplicationContext ctx: Context) {
       private val inference = LlmInference.createFromOptions(
           ctx,
           LlmInferenceOptions.builder()
               .setModelPath("/data/local/tmp/llm/gemma.task")
               .setMaxTokens(2048)
               .setTopK(40)
               .setTemperature(0.8f)
               .build(),
       )

       fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>):
           Flow<StreamingEvent> = callbackFlow {
           val prompt = buildPrompt(messages, tools)
           val session = LlmInferenceSession.createFromOptions(
               inference, LlmInferenceSessionOptions.builder().build(),
           )
           session.addQueryChunk(prompt)
           val handle = session.generateResponseAsync { partial, done ->
               trySend(StreamingEvent.TextDelta(partial))
               if (done) {
                   // Parse tool calls out of the completion using a
                   // marker pattern, e.g. <|tool_call|>name(args)
                   parseToolCalls(partial).forEach { trySend(it) }
                   trySend(StreamingEvent.FinishReason("stop"))
                   close()
               }
           }
           awaitClose { session.close() }
       }
   }
   ```

4. **Tool-call protocol** — Gemma doesn't have native tool calls. The cleanest trick: instruct Gemma in the system prompt to emit `<tool>name|{"args":...}</tool>` markers, then parse them out of the completion stream. There's a worked example in `Path 3` below.

5. **Wire it up** — bind `GemmaChat` instead of `MiniMaxClient` in `AgentLoop.kt:104` (the streaming chat field).

## Path 2: Llama / Qwen / DeepSeek via llama.cpp Android port

[llama.cpp has an Android port](https://github.com/ggerganov/llama.cpp/tree/master/examples/llama.android). It's slightly more setup but supports any GGUF model.

- Add the prebuilt `libllama.so` to `app/src/main/jniLibs/`.
- JNI bridge exposes `complete(prompt, callback)`.
- Wrap in a streaming adapter.

This unlocks Qwen2.5-3B and DeepSeek-Coder-1.3B at usable speeds on Pixel 9 Pro+.

## Path 3: External local model via SSH / HTTP

If you want to run a beefier model on a desktop and have the phone talk to it over your LAN:

- Run **Ollama**, **vLLM**, or **llama.cpp server** on your desktop.
- In `OllamaChat.kt`, hit `http://<your-laptop>:11434/api/chat` with the same `StreamingChat.stream` contract.
- Mythara never sends data to a vendor cloud; you've just moved compute to your home network.

## Tool calls without native support

For models that don't have native tool calls (Gemma, Qwen-Chat-no-tools, etc.):

1. **System-prompt injection** — list every tool with its JSON schema and tell the model:
   > To call a tool, emit a single line: `[TOOL] name {"arg": "value"}`. Otherwise reply in plain text.

2. **Stream parser** — your adapter watches the completion for the `[TOOL]` prefix and emits `ToolCallDelta`s when it sees one.

3. **Round trip** — the agent loop runs the tool, formats the result as `[TOOL_RESULT] name {"result": ...}`, prepends it to the next prompt.

`MiniMaxClient` does this transparently because MiniMax has the OpenAI-style native API. Your adapter does it by convention.

## The smaller-the-better case

Mythara's tool catalogue (65+ tools) is what makes the assistant useful. A small local model + tools >> a giant cloud model that can't touch your phone.

Empirically: **Gemma 2B IT** with the structured tool-call prompt above handles ~80 % of daily-driver use cases on a Pixel 9 Pro at ~25 tok/s. The hardest part isn't model quality — it's the model knowing when to call a tool. That's a system-prompt-engineering problem you'll iterate on.

## What's already wired

- `minimax/StreamingChat.kt` — the reference implementation (200 LOC).
- `minimax/MiniMaxApi.kt` — endpoint shapes.
- `minimax/ErrorModels.kt` — error envelope parsing.

Read these three files before you start. The next 200 LOC you write are an isomorphism away.

## PR template

- Branch `local-<model>` (e.g. `local-gemma`, `local-llama`).
- New module `app/src/main/kotlin/com/mythara/local/<Model>Chat.kt`.
- Settings → Model picker entry that swaps the adapter at runtime.
- Smoke test: paste a prompt that calls one tool (e.g. `"what's my battery via termux"`); verify it works.
- README section describing the model + perf numbers on your device.

PRs accepted readily. **The repo wants this.**

See also: [Agentic Runtime](Agentic-Runtime), [Architecture](Architecture).
