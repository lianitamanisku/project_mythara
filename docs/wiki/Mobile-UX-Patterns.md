# Mobile UX Patterns

Mythara's interaction model isn't a clone of Material 3. It's built around four primitives that let you drive an agent from anywhere on the screen, with one thumb, while distracted.

## The four primitives

### 1. Rose-amulet PTT (push-to-talk)
- A spinning rose at the bottom-centre of every screen.
- **Tap (< 250 ms)** → navigate to Chat.
- **Hold ≥ 3 s** → mic engages, face mesh enters listening state, charple ring grows around the rose.
- **Release** → transcript submits via `AgentRunner.submit(text, fromVoice=true, pcm=...)`.
- Internal: `ui/amulet/RoseAmulet.kt` + `voice/PttController.kt`.

### 2. Spine launcher
- A 3 dp Charple-breathing vertical strip on the right edge of every screen.
- **Tap** → teardrop-expanding menu: rose chip + Mythara nav rows + status microdots (clock / WiFi / battery / model-health).
- **Deep press 300 ms** → fires `ACTION_ASSIST` (PTT shortcut).
- **Tap the rose chip** → slim apps-only strip with search at the top → tap search → full SpotlightDrawer.
- **Tap outside** → dismiss.
- Internal: `ui/system/MytharaSpine.kt`, `ui/launcher/AppDock.kt`, `ui/launcher/SpotlightDrawer.kt`.

### 3. Popup amulet (long-press anywhere)
- Long-press on any non-scrollable surface for ~600 ms.
- Particle pre-bloom forms at the touch point at 300 ms (anticipation).
- At 600 ms the rose blooms outward into a 9-chip constellation:
  - Page 0: command palette (fuzzy search every route)
  - Page 1: primary destinations
  - Page 2: secondary (memory, tasks, notes, music vocab, about me, about)
  - Page 3+: installed apps, 8 per page with icons
- Tap a chip → navigate.
- Internal: `ui/amulet/PopupAmulet.kt`, `ui/amulet/Constellation.kt`, `ui/amulet/GlobalLongPress.kt`.

### 4. Quick-action wheel (long-press the rose)
- 4-segment radial wheel on a sustained rose hold.
- Segments: STT mute · mic one-shot · music-mode toggle · continuous voice toggle.
- Release on a segment → fire the action; release outside → cancel.

## The Home surface

- **Top strip** — up to 4 key notifications. Tap any chip → Alerts hub (not the source app — see "Alerts" below).
- **Centre** — camera-tracked particle face mesh. Smooth gather animation when face detected (1.8 s ease-in), 0.8 s disperse when face leaves.
- **Bottom** — spinning rose amulet.
- **Right edge** — spine launcher.
- No 6-tile grid. Navigation is fully gesture-driven.

## Alerts (notification triage)
- Live grouped-by-app list of phone notifications.
- Each row: app glyph, title, text, `→` open-source chevron, **open / dismiss / ask** chips.
- **Tap the chevron / "open" chip** → opens the source app via `PendingIntent.send()` with the BAL exemption (Android 14+).
- **"Ask Mythara"** → forwards into the agent loop with "Help me handle this notification from $app: ...".
- **Pin** chip → mark the app as "important" → its notifications float to the top.
- Tab switcher: **live** (current) / **triaged** (what Mythara auto-dismissed).
- Internal: `ui/notifications/NotificationHubScreen.kt`, `services/NotificationListener.kt`, `services/NotificationFeedRepository.kt`.

## People

- Address-book contacts merged with Mythara's interaction history.
- Sections: **Favorites** → **Frequently contacted** → **All contacts (A–Z)** + a top search bar.
- Per-row actions: phone call (direct), SMS (composer), WhatsApp (chat).
- **Tap a row** → contact detail.
- **Top-right star** on the detail header → toggle favorite (writes through `FavoritesStore` + `contact_profiles.is_favorite`).
- Detail card order: personality (Big Five + traits chips + insights prose) → stats → relationship summary → notes → face samples → photos-of grid → recent interactions.

## Camera lifecycle (pickup-only)

The front camera is dark unless you physically pick up the phone:

- `PhonePickupDetector` registers `TYPE_SIGNIFICANT_MOTION` — a hardware-batched, ultra-low-power wake sensor.
- On trigger fire → opens an 8 s "active window" → `FaceTracker.bind()`.
- Each successful face detection refreshes the window via `extendWindow()` — the camera stays alive while a face is looking at the screen.
- Window expires (no face for 8 s) → `FaceTracker.unbind()` → trigger sensor re-registers for the next pickup.
- Falls back to "always-on" when the hardware lacks the sensor (rare on modern devices).

Net effect: idle camera path draws ~0 between interactions; full-rate face tracking the moment you pick up the phone.

## BAL-exempted notification launch

Android 14+ enforces Background-Activity-Launch restrictions; even when Mythara is foreground, `PendingIntent.send()` can land as `BAL_BLOCK` because the PendingIntent creator (e.g. Outlook) hasn't granted BAL for cross-app fires.

`openNotificationSource()` uses:
```kotlin
ActivityOptions.makeBasic()
    .setPendingIntentBackgroundActivityStartMode(
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
    )
    .toBundle()
```
…and passes it through the 7-arg `pi.send(ctx, code, fillIn, null, null, null, opts)`. The activity context (`LocalContext.current`) is passed in, NOT the application context — otherwise the BAL gate fails the package binding.

Internal: `services/NotificationFeedRepository.kt`.

## Discoverability

- **Coach mark** — first launch, a ghosted particle ring under the rose with "press & hold anywhere". 3 s, dismissible, persisted via `OnboardingStore.seenAmuletHint`.
- **Idle hint** — when you're idle on a screen for 8 s, the bottom edge-glow emits 2 particles inward. Reminds the surface is live.
- **Command palette** — page 0 of every popup amulet invocation. Fuzzy-search every route + every glyph.

## Markdown everywhere

Agent prose + user notes render via `ui/markdown/MarkdownText.kt` — bold, italic, code, lists, quotes, links, headings. See [Architecture](Architecture).

## Music Mode

- Toggle in chat composer (♪ button).
- Every word of an agent reply gets a colour + a tone in the OM-harmonic scale.
- Function words (and, or, is, the, my, ...) ride single-note **grammatical particle** tones — verbs ring on the bright 5th, copulas on the octave, negators on the 7th, etc.
- Morphological suffixes (`-ing`, `-ed`, `-s`, `-ly`) add a one-note **morphology marker** to the stem motif.
- Karaoke highlight: the active word gets a solid 92 %-alpha colour block + auto-contrast foreground.
- Vocabulary screen has a glossary explaining the constructed language.

Internal: `music/`, `ui/music/MusicVocabularyScreen.kt`.

See also: [Design Language & Skins](Design-Language-and-Skins), [Agentic Runtime](Agentic-Runtime).
