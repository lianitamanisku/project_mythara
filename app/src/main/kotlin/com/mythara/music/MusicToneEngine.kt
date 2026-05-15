package com.mythara.music

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Plays sequences of pure-sine motifs for Music Mode. Architecturally
 * a much simpler cousin of [com.mythara.resonance.ResonanceAudioEngine]
 * — same AudioTrack-streaming foundation, but no binaural / isochronic
 * synthesis, no closed loop, no AudioFocusRequest dance: a Music Mode
 * tone is a short notification-style chirp, not an entrainment session.
 *
 * Safety still applies in microcosm:
 *  - per-note raised-cosine attack/release envelopes prevent clicks
 *  - frequencies are clamped to a vocal-comfortable 200–1500 Hz band
 *  - volume ceiling enforced in the render loop
 *  - one motif at a time — calling [play] while a motif is playing
 *    cancels the old one and starts the new (debounced barge-in)
 */
@Singleton
class MusicToneEngine @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var playJob: Job? = null
    @Volatile private var track: AudioTrack? = null

    /** "Which motif is playing right now?" — the chat bubble subscribes
     *  to this so it can light up the corresponding word in sync with
     *  the audio. [PlaybackState.sourceKey] disambiguates between
     *  bubbles (each Reply uses its own text as the key); the bubble
     *  only highlights when the key matches its own.
     *
     *  Null whenever nothing is playing (or the playback session
     *  didn't supply a source key — back-compat with the original
     *  fire-and-forget overload). */
    private val _nowPlaying = MutableStateFlow<PlaybackState?>(null)
    val nowPlaying: StateFlow<PlaybackState?> = _nowPlaying.asStateFlow()

    data class PlaybackState(val sourceKey: String, val motifIndex: Int)

    /** Back-compat overload — plays without emitting any nowPlaying
     *  state, so callers that don't care about word-sync don't have
     *  to invent a key. */
    fun play(motifs: List<Motif>) = play(motifs, sourceKey = "")

    /** Play a sequence of motifs back-to-back. The total duration is
     *  [motifs.size] × ([NOTE_DURATION_MS] × notes-per-motif + gap).
     *  Idempotent: cancels any in-flight playback before starting.
     *
     *  When [sourceKey] is non-empty, [nowPlaying] is updated before
     *  each motif so the chat bubble for that key can highlight the
     *  matching word in lockstep. */
    fun play(motifs: List<Motif>, sourceKey: String) {
        if (motifs.isEmpty()) return
        playJob?.cancel()
        playJob = scope.launch {
            renderSequence(motifs, sourceKey)
        }
    }

    /** Hard-stop any in-flight playback. */
    fun stop() {
        playJob?.cancel()
        playJob = null
        _nowPlaying.value = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }

    private fun renderSequence(motifs: List<Motif>, sourceKey: String = "") {
        val sampleRate = SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        runCatching { t.play() }.onFailure {
            Log.w(TAG, "AudioTrack play() failed: ${it.message}")
            return
        }

        // Iterate motif → note → gap and write 16-bit PCM to the track.
        // Update nowPlaying before each motif so the bubble can light
        // up the matching word in real time. Skip the publish when
        // sourceKey is empty (back-compat overload).
        for ((mIdx, motif) in motifs.withIndex()) {
            if (sourceKey.isNotEmpty()) {
                _nowPlaying.value = PlaybackState(sourceKey, mIdx)
            }
            for ((nIdx, freq) in motif.notes.withIndex()) {
                val hz = freq.coerceIn(MIN_HZ, MAX_HZ)
                writeNote(t, hz, NOTE_DURATION_MS)
                if (nIdx < motif.notes.size - 1) writeSilence(t, INTRA_NOTE_GAP_MS)
            }
            if (mIdx < motifs.size - 1) writeSilence(t, INTER_MOTIF_GAP_MS)
        }
        // Flush the tail of the buffer so the last note actually plays
        // out (AudioTrack stops mid-buffer otherwise) before tearing
        // down the track.
        runCatching {
            t.stop()
            t.release()
        }
        if (track === t) track = null
        if (sourceKey.isNotEmpty()) _nowPlaying.value = null
    }

    private fun writeNote(t: AudioTrack, freqHz: Float, durationMs: Int) {
        val sampleRate = SAMPLE_RATE
        val totalSamples = (sampleRate * durationMs / 1000)
        val attackSamples = (sampleRate * ATTACK_MS / 1000).coerceAtMost(totalSamples / 2)
        val releaseSamples = (sampleRate * RELEASE_MS / 1000).coerceAtMost(totalSamples / 2)
        val sustainSamples = (totalSamples - attackSamples - releaseSamples).coerceAtLeast(0)
        val omega = 2.0 * PI * freqHz / sampleRate.toDouble()

        val chunk = ShortArray(CHUNK_SAMPLES)
        var i = 0
        var phase = 0.0
        while (i < totalSamples) {
            val n = minOf(CHUNK_SAMPLES, totalSamples - i)
            for (j in 0 until n) {
                val k = i + j
                val envelope = when {
                    k < attackSamples ->
                        // Raised-cosine attack — 0 → 1 over [0, attack].
                        0.5f * (1f - cos(PI.toFloat() * k / attackSamples))
                    k < attackSamples + sustainSamples -> 1f
                    else -> {
                        // Raised-cosine release — 1 → 0 over the tail.
                        val r = (k - attackSamples - sustainSamples).toFloat()
                        0.5f * (1f + cos(PI.toFloat() * r / releaseSamples))
                    }
                }
                val sample = (sin(phase) * envelope * VOLUME * Short.MAX_VALUE).toInt()
                chunk[j] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                phase += omega
                if (phase > 2 * PI) phase -= 2 * PI
            }
            t.write(chunk, 0, n)
            i += n
        }
    }

    private fun writeSilence(t: AudioTrack, durationMs: Int) {
        val sampleRate = SAMPLE_RATE
        val totalSamples = sampleRate * durationMs / 1000
        val chunk = ShortArray(CHUNK_SAMPLES)
        var i = 0
        while (i < totalSamples) {
            val n = minOf(CHUNK_SAMPLES, totalSamples - i)
            t.write(chunk, 0, n)
            i += n
        }
    }

    fun release() {
        scope.cancel()
        stop()
    }

    companion object {
        private const val TAG = "Mythara/MusicTone"

        const val SAMPLE_RATE = 44_100
        private const val CHUNK_SAMPLES = 1024

        /** Per-note duration. Slightly longer than the original 180 ms
         *  so single-tone caveman words have presence and the user
         *  has time to hear + see the colour-glow association. */
        const val NOTE_DURATION_MS = 240

        /** Silence between consecutive notes within a single motif —
         *  short, so each motif feels like one phrase. */
        const val INTRA_NOTE_GAP_MS = 50

        /** Silence between motifs — long, so each word's tone phrase
         *  lands distinctly and the user can mentally map "this tone =
         *  this word" before the next one starts. The word-glow on
         *  the chat bubble updates inside this gap. */
        const val INTER_MOTIF_GAP_MS = 500

        /** Raised-cosine envelope window. 12 ms is long enough that
         *  there are no audible clicks at note edges. */
        private const val ATTACK_MS = 12
        private const val RELEASE_MS = 12

        /** Pitch band wide enough to include the OM fundamental
         *  (136.1 Hz) at the bottom and the 9th harmonic (1224.9 Hz)
         *  at the top. Below ~120 Hz tones get muddy on phone
         *  speakers; above ~1500 Hz they start to feel piercing in a
         *  quiet room. */
        private const val MIN_HZ = 130f
        private const val MAX_HZ = 1500f

        /** Output volume cap. Music Mode runs as USAGE_ASSISTANCE_
         *  SONIFICATION which routes to the notification stream; we
         *  still scale the sine to keep it polite. */
        private const val VOLUME = 0.45f
    }
}
