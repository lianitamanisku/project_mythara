# Mythara wake-word assets

This directory ships the three ONNX files openWakeWord needs for
on-device wake detection. **They are committed in the repo**, so a
fresh clone + build "just works" — no signup, no Colab, no
configuration. Total disk cost: ~3MB.

## Files

| Filename                | Size  | Role                                   |
|-------------------------|-------|----------------------------------------|
| `melspectrogram.onnx`   | ~1.0M | audio → mel-spectrogram features       |
| `embedding_model.onnx`  | ~1.3M | mel-spec → speech embeddings           |
| `hey_mycroft_v0.1.onnx` | ~840K | embeddings → "Hey Mycroft" probability |

All three come from
[openWakeWord v0.5.1 release assets](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1).
Apache 2.0 licensed.

## Why "Hey Mycroft"?

openWakeWord publishes a small catalogue of pre-trained wake words:
`alexa`, `hey_jarvis`, `hey_mycroft`, `weather`, `timer`. We picked
**hey_mycroft** because:

- Niche enough that nearby commercial assistants (Echo, Google
  Assistant, Siri) won't react to it.
- Comfortable to say aloud, two-syllable cadence ("hey MY-kroft").
- Pre-trained model has solid recall — openWakeWord reports F1 > 0.9
  on the standard test set at threshold 0.5.

The trigger phrase is decoupled from Mythara's agent identity: when
"Hey Mycroft" fires, **Lumi** takes over (the chat surface, system
prompt, TTS voice — all branded as Lumi). Think of "Hey Mycroft" as
the on-ramp, not the conversation partner.

## Swapping the wake word

Want a different phrase? Two options:

### Use another pre-trained model

1. Download from the openWakeWord release page — e.g.
   [hey_jarvis_v0.1.onnx](https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/hey_jarvis_v0.1.onnx)
   or [alexa_v0.1.onnx](https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/alexa_v0.1.onnx).
2. Drop next to the other files here.
3. In `LumiWakeWordController.kt` update the `WAKE_WORD_FILE` and
   `TRIGGER_PHRASE` constants to match.
4. Rebuild.

### Train a custom wake word (e.g. "Hey Lumi")

This is the original M8.3a path — still works if you change your mind
later:

1. Open openWakeWord's [automatic_model_training.ipynb](https://github.com/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb) on Colab.
2. Set `target_phrase = "Hey Lumi"`. ~45 min on a Colab T4.
3. Download the resulting `.onnx`, drop it here, point the controller
   constants at it, rebuild.

## Verifying the default install

1. After install: Mythara → main Settings → "wake word" panel.
2. Grant RECORD_AUDIO when prompted.
3. Toggle ON. Status pill should show `● listening for 'Hey Mycroft'`.
4. Speak the phrase. Fires log to `Mythara/Wake` in logcat with the
   model name + score + timestamp. Expect scores in the 0.85–0.99
   range on a clean utterance — verified on a Pixel 9 with hey_jarvis,
   hey_mycroft behaves similarly.
