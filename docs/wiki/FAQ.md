# FAQ

## Why not just use Gemini?

Because Gemini is cloud-side, always-on, and tied to a brand whose primary business is targeted advertising. Every prompt you send touches their pipeline. Mythara is the same demo wired through your phone — you keep the data, you keep the trail.

If you trust Google's data stewardship and don't mind the trade, Gemini is excellent. Mythara exists for the audience that doesn't.

## Will this work without internet?

**With a local model**, yes — fully airplane-mode. With MiniMax (cloud) as the model, you need internet for chat but everything else (analytics, face index, alerts, contacts) is local. See [Bring Your Own Model](Bring-Your-Own-Model).

## Does it phone home?

No. There's no Mythara server. The dependency tree contains zero analytics SDKs. If you don't configure a remote model + don't configure cross-device sync, nothing leaves the device.

`git grep -nE "https?://" app/src/main/kotlin` — audit it yourself.

## What model does it use by default?

MiniMax M2 family. Cheap, fast, supports OpenAI-compatible streaming + native tool calls. The first 5M tokens / month are free, which covers daily use comfortably.

You can swap this in 200 LOC. See [Bring Your Own Model](Bring-Your-Own-Model).

## What devices is this tested on?

- **Pixel 10 Pro** — daily driver
- **Pixel 9 Pro Fold** — fold posture + glasses bridge dev
- **Pixel Watch 4** — watch face + complications dev
- **Older Pixels (6+)** — should work; less tested
- **Samsung One UI** — should work; the rose spine + Samsung's edge gestures may collide

## Does it work on Android 13 / 12?

Compile-target is API 36, minSdk is 30 (Android 11). It SHOULD run on 11+, but:
- `setPendingIntentBackgroundActivityStartMode` is API 34+ — falls back to bare `pi.send()` on older.
- `ActivityOptions.makeBasic()` exists pre-34 but BAL didn't, so the fallback path works.
- TFLite NNAPI delegate is best on Pixel 8 / 9 / 10 with the Tensor chip.

## Is there an iOS version?

No. Apple's sandbox restrictions on accessibility, notification access, and shell tools would kill 80 % of the agent's tool catalogue.

## How do I uninstall everything Mythara learned?

- Settings → Memory → **"wipe everything Mythara has learned about me"** — drops all the analytics tables.
- Nuclear: `pm clear com.mythara.debug` (loses everything including chat history).

If you sync, the wipe overwrites the synced JSONL on next push.

## Can I run Mythara in the background as my voice assistant?

Yes — Settings → Autopilot ON. The wake word ("Hey Mythara"), notification listener, accessibility-driven screen reader, and lockscreen island stay alive. Tap "OFF" to make the agent fully passive.

## How do I add a custom skill?

Two ways:

1. **Let Mythara learn it.** Do the same multi-step thing a few times; the skill suggester offers to save it.
2. **Hand-author.** Drop a `SKILL.md` into `filesDir/skills/<name>/` with YAML frontmatter (`name`, `description`, `params`) + a markdown body describing the steps.

Then call it from chat: "run my morning routine".

## Why is the rose spinning?

It's the brand mark. It rotates at 1 revolution / 90 s — slow enough not to distract, present enough to remind you the agent is alive. It pulses with your HR when the Living Rose skin is on.

It's also the PTT button. Hold ≥ 3 seconds to talk.

## What's the Music Mode thing?

A constructed-language take on TTS. Every word maps to a melody in the OM-harmonic scale. Function words ride on category-specific particle tones (linker, copula, pronoun, etc); morphological suffixes (`-ing`, `-ed`, `-s`) add a one-note tail. Karaoke highlight tracks the playing word.

It's not for everyone. Toggle it off in the chat composer (♪ button).

## Is the "intelligence system" branding Mythara mocks Google's exact word?

Yes. [The Android Show 2025 presenter said it on stage](https://www.youtube.com/watch?v=DYFoun7w0VA). The framing is the framing.

## Does Mythara use the LLM to spy on my contacts?

No. The personality analysis is **local-only by default**. Big Five derivation is lexical (LIWC-style category frequencies) running on your phone; the LLM is invoked only to write the prose summary, and only with data you've already chatted about — the model never sees raw notification text or message bodies in bulk.

If you opt into sync, the analysis JSONL goes to *your* GitHub repo, not Mythara's.

## How do I bring this to my company?

You don't. Mythara is for individuals. If you want a corporate AI mobile layer, the architecture is reusable but the data-stewardship choices an enterprise needs (audit trails, RBAC, etc.) aren't in scope here.

## Who built this?

[Ankur Nair](https://github.com/ankurCES). Engineered using Lumi (Ankur's multi-agent platform at CES). Mythara is the field-deployed test bed.

## Can I sponsor or hire?

Open an issue or [reach out on GitHub](https://github.com/ankurCES).

See also: [Why Mythara](Why-Mythara), [Privacy Model](Privacy-Model).
