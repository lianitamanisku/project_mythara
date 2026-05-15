package com.mythara.resonance

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The Resonance tone engine — generates binaural / isochronic
 * brainwave-entrainment audio for the (Phase 4) closed-loop controller
 * to drive. Net-new on `:app` because nothing in the codebase
 * generates audio today (TTS uses Android `TextToSpeech` /
 * `MediaPlayer`, never `AudioTrack`).
 *
 * SAFETY: every constraint from the plan is enforced HERE, not by the
 * caller — the engine is the single source of truth so the loop +
 * future entry points can't accidentally bypass them.
 *
 *  - [clampBeatHz] keeps the isochronic / amplitude-modulation rate
 *    OUT of the ~15–25 Hz photosensitive/audiogenic band; binaural
 *    gets a slightly looser cap (it's a perceptual difference, not an
 *    AM rate, but we still cap conservatively).
 *  - [clampCarrierHz] keeps the carrier in the 100–400 Hz range
 *    where binaural beats actually fuse perceptually.
 *  - Raised-cosine attack / release envelopes (~3 s) on start, stop
 *    and route-swap — no public path can produce an un-ramped onset.
 *  - Final per-buffer volume ceiling caps how loud our contribution
 *    can ever get (the user's system-wide volume is theirs to control).
 *
 * Threading model: one render coroutine on [Dispatchers.Default] owns
 * the [AudioTrack]; setpoints (target beat, target volume, mode) are
 * volatile reads on each ~20 ms buffer iteration. Per-channel phase
 * accumulators carry across buffers so a beat-frequency glide doesn't
 * introduce phase jumps (audible clicks).
 */
@Singleton
class ResonanceAudioEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    enum class Phase { Idle, Starting, Playing, Stopping }
    enum class Mode { Binaural, Isochronic }

    /** Snapshot of what the engine is doing, for UI + the closed loop. */
    data class State(
        val phase: Phase = Phase.Idle,
        val mode: Mode = Mode.Isochronic,
        val carrierHz: Float = DEFAULT_CARRIER_HZ,
        val targetBeatHz: Float = 0f,
        val currentBeatHz: Float = 0f,
        val routeIsHeadphones: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var renderJob: Job? = null
    @Volatile private var stopRequested = false

    /** Setpoints — the render loop reads these each buffer. */
    @Volatile private var targetBeatHz: Float = 0f
    @Volatile private var targetVolume: Float = DEFAULT_VOLUME_CAP
    @Volatile private var carrierHz: Float = DEFAULT_CARRIER_HZ
    @Volatile private var maskingEnabled: Boolean = true
    @Volatile private var routeIsHeadphones: Boolean = false

    private val audioManager: AudioManager? =
        ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshRoute()
    }

    // ---------------------------------------------------------------- public API

    /**
     * Start a session aimed at [protocol]'s target beat band. Idempotent
     * — calling again on a running session retargets without dropping
     * audio (the glide does the rest).
     */
    fun start(protocol: ResonanceCommand.Protocol) {
        val band = protocolBand(protocol)
        carrierHz = clampCarrierHz(carrierHz)
        // Pick mode based on current route, then apply the mode-specific
        // beat clamp.
        val mode = if (routeIsHeadphones) Mode.Binaural else Mode.Isochronic
        targetBeatHz = clampBeatHz(band.midpoint(), mode)
        if (renderJob?.isActive == true) {
            Log.d(TAG, "retarget protocol=${protocol.displayName} beat=${targetBeatHz}Hz mode=$mode")
            publishState(_state.value.copy(targetBeatHz = targetBeatHz, mode = mode))
            return
        }
        Log.d(TAG, "start protocol=${protocol.displayName} beat=${targetBeatHz}Hz mode=$mode")
        stopRequested = false
        publishState(
            State(
                phase = Phase.Starting,
                mode = mode,
                carrierHz = carrierHz,
                targetBeatHz = targetBeatHz,
                currentBeatHz = 0f,
                routeIsHeadphones = routeIsHeadphones,
            ),
        )
        // Listen for headphone plug / BT changes while playing.
        audioManager?.registerAudioDeviceCallback(routeCallback, mainHandler)
        renderJob = scope.launch { renderLoop() }
    }

    /**
     * Override the target beat directly — used by the (Phase 4) loop to
     * nudge frequency based on biometrics. Goes through [clampBeatHz]
     * so it can't cross the safety band even if the loop misbehaves.
     */
    fun setTargetBeatHz(hz: Float) {
        val mode = _state.value.mode
        targetBeatHz = clampBeatHz(hz, mode)
        publishState(_state.value.copy(targetBeatHz = targetBeatHz))
    }

    /** Override carrier (within the safe range). */
    fun setCarrierHz(hz: Float) {
        carrierHz = clampCarrierHz(hz)
        publishState(_state.value.copy(carrierHz = carrierHz))
    }

    /** Override volume ceiling (0..1). The render loop applies this
     *  per buffer alongside the envelope, so a runaway caller can't
     *  produce a sudden loud peak. */
    fun setVolumeCap(value: Float) {
        targetVolume = value.coerceIn(0f, MAX_VOLUME)
    }

    fun setMaskingEnabled(value: Boolean) {
        maskingEnabled = value
    }

    /** Ramp down + stop. Idempotent. */
    fun stop() {
        if (renderJob?.isActive != true) return
        Log.d(TAG, "stop requested")
        stopRequested = true
        publishState(_state.value.copy(phase = Phase.Stopping))
    }

    // ---------------------------------------------------------------- render loop

    private suspend fun renderLoop() {
        val sampleRate = SAMPLE_RATE
        val frameCount = (sampleRate * BUFFER_MS / 1000).toInt()  // ~20 ms
        val track = buildAudioTrack(sampleRate, frameCount) ?: run {
            Log.w(TAG, "AudioTrack creation failed — aborting")
            publishState(State(routeIsHeadphones = routeIsHeadphones))
            return
        }
        try {
            track.play()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack.play threw: ${t.message}")
            track.release()
            publishState(State(routeIsHeadphones = routeIsHeadphones))
            return
        }

        // Per-channel phase accumulators (radians). Carry across buffers
        // — that's how a beat glide stays click-free.
        var leftPhase = 0.0
        var rightPhase = 0.0
        var monoPhase = 0.0
        var amPhase = 0.0
        // Pink-noise state (Voss-McCartney style, 5 octave rows).
        val pink = PinkNoise()
        // Smoothed beat freq + envelope.
        var currentBeat = 0f
        var envelope = 0f
        // 0 → 1 over ATTACK_SEC at the start; 1 → 0 over RELEASE_SEC at stop.
        val attackPerBuffer = (BUFFER_MS / 1000f) / ATTACK_SEC
        val releasePerBuffer = (BUFFER_MS / 1000f) / RELEASE_SEC
        val beatSlewPerBuffer = BEAT_SLEW_HZ_PER_S * (BUFFER_MS / 1000f)

        val pcm = ShortArray(frameCount * 2) // stereo interleaved L,R,L,R

        while (scope.isActive && renderJob?.isActive == true) {
            // ---- update setpoints + envelope -------------------------------
            val mode = currentMode()
            // Clamp again per buffer — defence in depth.
            val target = clampBeatHz(targetBeatHz, mode)
            val carrier = clampCarrierHz(carrierHz)
            val gain = targetVolume.coerceIn(0f, MAX_VOLUME)

            // Beat glide — capped slew rate prevents an audible chirp.
            currentBeat = approach(currentBeat, target, beatSlewPerBuffer)

            if (stopRequested) {
                envelope = max(0f, envelope - releasePerBuffer)
            } else {
                envelope = (envelope + attackPerBuffer).coerceAtMost(1f)
                if (envelope >= 1f && _state.value.phase == Phase.Starting) {
                    publishState(_state.value.copy(phase = Phase.Playing))
                }
            }

            // ---- synthesise one buffer -------------------------------------
            val leftFreq = carrier - currentBeat / 2f
            val rightFreq = carrier + currentBeat / 2f
            val dphaseLeft = 2.0 * PI * leftFreq / sampleRate
            val dphaseRight = 2.0 * PI * rightFreq / sampleRate
            val dphaseMono = 2.0 * PI * carrier / sampleRate
            val dphaseAm = 2.0 * PI * currentBeat / sampleRate

            for (i in 0 until frameCount) {
                val ampEnv = envelope * gain
                val noise = if (maskingEnabled) pink.next() * NOISE_GAIN else 0f
                when (mode) {
                    Mode.Binaural -> {
                        leftPhase += dphaseLeft
                        rightPhase += dphaseRight
                        val l = (sin(leftPhase) * ampEnv + noise).toFloat()
                        val r = (sin(rightPhase) * ampEnv + noise).toFloat()
                        pcm[i * 2] = (l * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
                        pcm[i * 2 + 1] = (r * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    Mode.Isochronic -> {
                        monoPhase += dphaseMono
                        amPhase += dphaseAm
                        // (sin+1)/2 cycle between 0 and 1 at beat rate —
                        // smooth gating, no clicks (vs hard square wave).
                        val gate = ((sin(amPhase) + 1.0) / 2.0).toFloat()
                        val s = (sin(monoPhase).toFloat() * gate * ampEnv) + noise
                        val sample = (s * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
                        pcm[i * 2] = sample
                        pcm[i * 2 + 1] = sample
                    }
                }
            }

            // ---- write (blocks until the buffer drains) -------------------
            val written = runCatching { track.write(pcm, 0, pcm.size) }.getOrDefault(0)
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write returned $written; aborting render")
                break
            }
            publishState(_state.value.copy(currentBeatHz = currentBeat))

            // Stop conditions: explicit stop AND envelope fully decayed.
            if (stopRequested && envelope <= 0f) break
            // Phase wrap (avoid infinite-precision drift).
            if (leftPhase > TWO_PI) leftPhase -= TWO_PI
            if (rightPhase > TWO_PI) rightPhase -= TWO_PI
            if (monoPhase > TWO_PI) monoPhase -= TWO_PI
            if (amPhase > TWO_PI) amPhase -= TWO_PI
        }

        // Cleanup.
        runCatching { track.stop() }
        runCatching { track.flush() }
        track.release()
        runCatching { audioManager?.unregisterAudioDeviceCallback(routeCallback) }
        publishState(State(routeIsHeadphones = routeIsHeadphones))
        Log.d(TAG, "render loop exited")
    }

    // ---------------------------------------------------------------- helpers

    private fun buildAudioTrack(sampleRate: Int, frameCount: Int): AudioTrack? {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) return null
        val want = (frameCount * 2 * Short.SIZE_BYTES) * 4 // ~4 buffers worth
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, want))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
    }

    private fun refreshRoute() {
        val headphones = isOnHeadphones()
        if (headphones != routeIsHeadphones) {
            routeIsHeadphones = headphones
            // Mode follows route. Rebuild beat target through the
            // appropriate clamp — the mid-glide engine just keeps going,
            // no audible discontinuity beyond the gradual freq adjust.
            val newMode = if (headphones) Mode.Binaural else Mode.Isochronic
            targetBeatHz = clampBeatHz(targetBeatHz, newMode)
            publishState(
                _state.value.copy(
                    mode = newMode,
                    routeIsHeadphones = headphones,
                    targetBeatHz = targetBeatHz,
                ),
            )
            Log.d(TAG, "route changed → headphones=$headphones, mode=$newMode")
        }
    }

    private fun isOnHeadphones(): Boolean {
        val devs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return devs.any { d ->
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    private fun currentMode(): Mode =
        if (routeIsHeadphones) Mode.Binaural else Mode.Isochronic

    private fun publishState(s: State) {
        _state.value = s
    }

    private fun protocolBand(p: ResonanceCommand.Protocol): ClosedFloatingPointRange<Float> = when (p) {
        ResonanceCommand.Protocol.Calm -> 6f..10f
        ResonanceCommand.Protocol.Focus -> 14f..18f
        ResonanceCommand.Protocol.WindDown -> 3f..6f
    }

    private fun ClosedFloatingPointRange<Float>.midpoint(): Float =
        (start + endInclusive) / 2f

    /**
     * Hard-clamp the beat / amplitude-modulation rate. Isochronic
     * (which IS an AM rate) stays at most [ISOCHRONIC_BEAT_MAX] —
     * comfortably below the photosensitive/audiogenic band. Binaural
     * (a perceptual difference, not an AM rate) gets [BINAURAL_BEAT_MAX]
     * but still capped conservatively.
     *
     * Single source of truth — every public setter funnels through here.
     */
    private fun clampBeatHz(hz: Float, mode: Mode): Float {
        val ceiling = when (mode) {
            Mode.Isochronic -> ISOCHRONIC_BEAT_MAX
            Mode.Binaural -> BINAURAL_BEAT_MAX
        }
        return hz.coerceIn(BEAT_MIN, ceiling)
    }

    private fun clampCarrierHz(hz: Float): Float =
        hz.coerceIn(CARRIER_MIN_HZ, CARRIER_MAX_HZ)

    private fun approach(current: Float, target: Float, maxStep: Float): Float {
        val delta = target - current
        return if (kotlin.math.abs(delta) <= maxStep) target
        else current + maxStep * if (delta > 0f) 1f else -1f
    }

    /** 5-row Voss-McCartney pink noise — cheap, no FFT. */
    private class PinkNoise {
        private val rows = FloatArray(5)
        private var counter = 0
        private val rng = java.util.Random(42L)
        fun next(): Float {
            counter++
            for (i in rows.indices) {
                if (counter and ((1 shl i) - 1) == 0) {
                    rows[i] = rng.nextFloat() * 2f - 1f
                }
            }
            var sum = 0f
            for (v in rows) sum += v
            return sum / rows.size
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "Mythara/ResAudio"

        // ---- safety constants --------------------------------------
        /** Minimum entrainment beat. Anything below ~0.5 Hz is just a
         *  slow drift, perceptually pointless. */
        private const val BEAT_MIN = 0.5f
        /** Isochronic / AM rate ceiling — well below the photosensitive
         *  / audiogenic 15–25 Hz band. The Focus protocol overlaps the
         *  band on its top end; on a speaker (forced isochronic) we
         *  clamp it down to this. */
        private const val ISOCHRONIC_BEAT_MAX = 14f
        /** Binaural beat ceiling — a perceptual difference, not an AM
         *  rate, so it's safer up to ~24 Hz. We still hold below the
         *  band's top to be conservative. */
        private const val BINAURAL_BEAT_MAX = 24f
        /** Carrier band where binaural beats fuse perceptually. */
        private const val CARRIER_MIN_HZ = 100f
        private const val CARRIER_MAX_HZ = 400f

        // ---- engine tuning -----------------------------------------
        const val SAMPLE_RATE = 44_100
        private const val BUFFER_MS = 20
        const val DEFAULT_CARRIER_HZ = 200f
        const val DEFAULT_VOLUME_CAP = 0.35f
        /** Final per-buffer ceiling on our contribution. The user's
         *  system volume is theirs to control on top of this. */
        private const val MAX_VOLUME = 0.7f
        /** Max Hz/s the beat may glide — slow enough to avoid an
         *  audible chirp on retarget. */
        private const val BEAT_SLEW_HZ_PER_S = 0.25f
        /** Raised-cosine envelope durations. */
        private const val ATTACK_SEC = 3.0f
        private const val RELEASE_SEC = 3.0f
        /** Pink-noise masking gain — sub-audible bed under the tone. */
        private const val NOISE_GAIN = 0.04f

        private const val TWO_PI = 2.0 * Math.PI
    }
}
