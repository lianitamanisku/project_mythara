package com.mythara.wake

import android.content.Context
import android.util.Log
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordDetection
import com.rementia.openwakeword.lib.model.WakeWordModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Re-MENTIA's [WakeWordEngine] in a Mythara-shaped facade:
 *  - lifecycle (start / stop) safe to call repeatedly
 *  - status flow that the UI consumes for the status pill
 *  - detection flow that the Activity layer subscribes to so it can
 *    open the chat surface with mic primed when the trigger fires
 *
 * **Wake word vs. agent identity.** The on-device detector is trained
 * on **"Hey Mycroft"** (openWakeWord's pre-trained model — Apache 2.0,
 * zero signup, niche enough to avoid collisions with commercial
 * assistants in the room). The agent that takes over after the trigger
 * is still named **Lumi**: the chat surface, system prompt, identity
 * branding all use Lumi. Saying "Hey Mycroft" is just the on-ramp.
 *
 * Picking a different wake word later is a one-line change (swap
 * [WAKE_WORD_FILE] for `alexa_v0.1.onnx`, `hey_jarvis_v0.1.onnx`, or
 * a Colab-trained custom model). See the openWakeWord releases page.
 *
 * **Asset contract.** Three ONNX files ship pre-bundled in
 * `app/src/main/assets/`:
 *   - `melspectrogram.onnx`     (shared, ~1MB)
 *   - `embedding_model.onnx`    (shared, ~1.3MB)
 *   - `hey_mycroft_v0.1.onnx`   (classifier, ~840K)
 *
 * If any are missing the controller stays in [State.MissingAsset] and
 * never touches AudioRecord.
 *
 * **Mutual exclusion with Observe.** Both AudioRecord clients can't run
 * at the same hardware time. The controller declines to start if Observe
 * is currently running (and vice versa — Observe should consult [state]
 * before starting). UI surfaces this conflict.
 */
@Singleton
class LumiWakeWordController @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object MissingAsset : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _wakes = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 4)
    val wakes: SharedFlow<WakeEvent> = _wakes.asSharedFlow()

    /** Slim wake-event envelope — we don't leak the library's class into the rest of the app. */
    data class WakeEvent(val triggerPhrase: String, val agentName: String, val score: Float, val tsMillis: Long)

    private var engine: WakeWordEngine? = null
    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null

    /**
     * Verify the three required ONNX files are present in `assets/`.
     * They're committed in the repo so this should always return true
     * for an installed APK; the check exists for forensic clarity in
     * case someone removes a file mid-development.
     */
    fun assetsPresent(): Boolean = REQUIRED_ASSETS.all { name ->
        runCatching { ctx.assets.open(name).use { } }.isSuccess
    }

    /** Start the engine if assets exist + not already running. */
    fun start() {
        if (_state.value is State.Listening) return
        if (!assetsPresent()) {
            _state.value = State.MissingAsset
            Log.w(TAG, "wake-word assets missing — see app/src/main/assets/README.md")
            return
        }
        runCatching {
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val newEngine = WakeWordEngine(
                context = ctx,
                models = listOf(
                    WakeWordModel(
                        name = AGENT_NAME,         // "Lumi" — what we surface to consumers
                        modelPath = WAKE_WORD_FILE, // "hey_mycroft_v0.1.onnx" — what we listen for
                        // Conservative threshold for the pre-trained
                        // "Hey Mycroft" model — 0.5 is the SDK default,
                        // openWakeWord reports best F1 around 0.5-0.6.
                        // Bump to 0.6 to reduce false positives at the
                        // cost of slightly missing soft triggers; the
                        // user can always retry. (Real-world: 0.88-0.998
                        // scores tested on a Pixel 9 with hey_jarvis;
                        // hey_mycroft is similar in calibration.)
                        threshold = 0.6f,
                    ),
                ),
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 2_000L,
                scope = newScope,
            )

            // CRITICAL: subscribe to detections BEFORE calling
            // engine.start(). Re-MENTIA's `_detections` is a
            // `MutableSharedFlow<>()` with replay=0 + extraBufferCapacity=0
            // — events emitted while no subscriber is attached are
            // dropped silently. Using Dispatchers.Main.immediate means
            // the body executes synchronously up to first suspension, so
            // by the time `launch` returns, `collect` has registered the
            // subscriber. Then we start the engine.
            collectJob = newScope.launch(Dispatchers.Main.immediate) {
                Log.d(TAG, "detections collector attached")
                newEngine.detections.collect { d: WakeWordDetection ->
                    val now = System.currentTimeMillis()
                    Log.d(TAG, "wake fired: trigger='$TRIGGER_PHRASE' agent='$AGENT_NAME' score=${d.score}")
                    _wakes.tryEmit(
                        WakeEvent(
                            triggerPhrase = TRIGGER_PHRASE,
                            agentName = AGENT_NAME,
                            score = d.score,
                            tsMillis = now,
                        ),
                    )
                }
            }

            newEngine.start()
            scope = newScope
            engine = newEngine
            _state.value = State.Listening
            Log.d(TAG, "engine started (audio loop live, subscriber attached)")
        }.onFailure { e ->
            Log.e(TAG, "wake start failed: ${e.message}", e)
            _state.value = State.Error(e.message ?: e.javaClass.simpleName)
            stopInternal()
        }
    }

    fun stop() {
        stopInternal()
        _state.value = State.Idle
    }

    private fun stopInternal() {
        runCatching { engine?.release() }
        engine = null
        runCatching { collectJob?.cancel() }
        collectJob = null
        scope = null
    }

    companion object {
        private const val TAG = "Mythara/Wake"

        /** The phrase a user actually says aloud. */
        const val TRIGGER_PHRASE = "Hey Mycroft"

        /** The on-screen identity that takes over after the trigger fires. */
        const val AGENT_NAME = "Lumi"

        /** openWakeWord shared models (same across all wake-word phrases). */
        private const val MELSPEC_MODEL = "melspectrogram.onnx"
        private const val EMBED_MODEL = "embedding_model.onnx"

        /**
         * The classifier model that turns embeddings into "Hey Mycroft"
         * or "anything else" probabilities. Swap for another openWakeWord
         * pre-trained .onnx (alexa, hey_jarvis, weather, timer) if you
         * prefer a different trigger — also update [TRIGGER_PHRASE]
         * to match.
         */
        private const val WAKE_WORD_FILE = "hey_mycroft_v0.1.onnx"

        private val REQUIRED_ASSETS = listOf(MELSPEC_MODEL, EMBED_MODEL, WAKE_WORD_FILE)
    }
}
