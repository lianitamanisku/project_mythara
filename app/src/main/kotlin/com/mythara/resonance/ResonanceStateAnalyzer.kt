package com.mythara.resonance

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.mic.MicBroker
import com.mythara.secret.observe.AudioRecorder
import com.mythara.secret.observe.acoustic.AcousticAnalyzer
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Produces a [ResonanceSnapshot] — a fused emotional-state estimate for
 * the (Phase 4) closed loop to act on. Three signals get folded
 * together:
 *
 *  1. **HR vs baseline** — the live HR average from [ResonanceHrStore]
 *     compared to the user's resting baseline (latest
 *     `kind:health-snapshot` Semantic-tier vault row written by
 *     `HealthLearningWorker`). High live vs baseline → high arousal.
 *  2. **Acoustic sample** — a short ~8s PCM capture run through the
 *     existing [AcousticAnalyzer] (pitch + energy → arousal proxy).
 *     Skipped (HR-only, low confidence) if the mic is busy.
 *  3. **Recent mood context** — `mood:*` rows in the vault from the
 *     last few hours, mapped to a valence axis.
 *
 * Pure on-demand — call [snapshot] whenever a fresh state estimate is
 * wanted. Phase 4's loop will call this on a ~30s cadence; Phase 2
 * just calls it once at session start.
 */
@Singleton
class ResonanceStateAnalyzer @Inject constructor(
    private val vault: LearningVault,
    private val micBroker: MicBroker,
    private val acousticAnalyzer: AcousticAnalyzer,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Build a [ResonanceSnapshot] from the current state of [hrStore]
     * + the user's already-collected baseline + a fresh acoustic
     * sample (when the mic is free) + recent mood context.
     */
    suspend fun snapshot(hrStore: ResonanceHrStore): ResonanceSnapshot = withContext(Dispatchers.IO) {
        val baseline = runCatching { readBaselineBpm() }.getOrNull()
        val liveAvg = hrStore.recentAverage()
        val liveLatest = hrStore.latest()
        val hrFresh = hrStore.isFresh()

        val acoustic = runCatching { captureAcoustic() }.getOrNull()
        val recentValence = runCatching { recentMoodValence() }.getOrNull()

        val arousalHr = arousalFromHr(liveAvg, baseline, hrFresh)
        val arousalAc = acoustic?.let { arousalFromAcoustic(it) }
        val arousal = fuseArousal(arousalHr, arousalAc)
        val valence = (recentValence ?: 0f).coerceIn(-1f, 1f)
        val confidence = computeConfidence(hrFresh, acoustic != null, recentValence != null)

        ResonanceSnapshot(
            tsMillis = System.currentTimeMillis(),
            valence = valence,
            arousal = arousal,
            label = quadrantLabel(valence, arousal),
            liveHrBpm = liveLatest,
            liveHrAvgBpm = liveAvg,
            baselineBpm = baseline,
            acousticAvailable = acoustic != null,
            confidence = confidence,
        ).also {
            // Durable trace so future runs (and the user, via About Me)
            // can see what the analyzer thought + adjust the user's
            // baseline picture over time.
            persist(it)
        }
    }

    // ---------------------------------------------------------- baseline

    private suspend fun readBaselineBpm(): Int? {
        // Latest 24h health snapshot (HealthLearningWorker writes this
        // every ~6h with hr_24h_min/_avg/_max). The 24h MIN is the best
        // resting-HR proxy we have without true HRV.
        val rows = runCatching { vault.listByTier(Tier.Semantic, limit = 200) }
            .getOrDefault(emptyList())
        val snap = rows.firstOrNull { e ->
            val f = vault.decodeFacets(e)
            "topic:health" in f
        } ?: return null
        val obj = runCatching { json.parseToJsonElement(snap.content) as? JsonObject }
            .getOrNull() ?: return null
        val min = (obj["hr_24h_min"] as? JsonPrimitive)?.content?.toIntOrNull()
        val avg = (obj["hr_24h_avg"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
        return min ?: avg
    }

    // ---------------------------------------------------------- acoustic

    private fun captureAcoustic(): AcousticAnalyzer.Features? {
        if (!micBroker.acquire(MicBroker.Client.RESONANCE)) {
            Log.d(TAG, "mic busy; acoustic skipped")
            return null
        }
        val recorder = AudioRecorder()
        return try {
            if (!recorder.start()) {
                Log.w(TAG, "AudioRecord start failed")
                return null
            }
            val totalSamples = AudioRecorder.SAMPLE_RATE * SAMPLE_SECONDS
            val buf = ShortArray(totalSamples)
            var written = 0
            val frameSize = recorder.readFrameSamples
            val frame = ShortArray(frameSize)
            while (written < totalSamples) {
                val n = recorder.read(frame)
                if (n <= 0) break
                val copy = minOf(n, totalSamples - written)
                System.arraycopy(frame, 0, buf, written, copy)
                written += copy
            }
            recorder.stop()
            if (written < AudioRecorder.SAMPLE_RATE * MIN_USABLE_SECONDS) {
                Log.d(TAG, "acoustic sample too short ($written samples)")
                return null
            }
            // wordCount=0 — Resonance doesn't run STT here; the analyzer
            // only uses pitch/energy fields, not wordsPerSec.
            acousticAnalyzer.analyze(buf, written, AudioRecorder.SAMPLE_RATE, wordCount = 0)
        } catch (t: Throwable) {
            Log.w(TAG, "acoustic capture threw: ${t.message}")
            null
        } finally {
            runCatching { recorder.release() }
            micBroker.release(MicBroker.Client.RESONANCE)
        }
    }

    private fun arousalFromAcoustic(f: AcousticAnalyzer.Features): Float {
        // Map pitch/energy to the same -1..1 axis as HR.
        val pitchPart = ((f.meanF0Hz - PITCH_NEUTRAL_HZ) / PITCH_SPREAD_HZ).coerceIn(-1f, 1f)
        val energyPart = ((f.meanRms - ENERGY_NEUTRAL) / ENERGY_SPREAD).coerceIn(-1f, 1f)
        return ((pitchPart + energyPart) / 2f).coerceIn(-1f, 1f)
    }

    // ---------------------------------------------------------- HR

    private fun arousalFromHr(liveAvg: Int?, baseline: Int?, fresh: Boolean): Float? {
        if (!fresh || liveAvg == null) return null
        val ref = (baseline ?: POPULATION_RESTING_BPM).toFloat()
        return ((liveAvg - ref) / HR_SPREAD_BPM).coerceIn(-1f, 1f)
    }

    private fun fuseArousal(hr: Float?, ac: Float?): Float = when {
        hr != null && ac != null -> hr * HR_WEIGHT + ac * (1f - HR_WEIGHT)
        hr != null -> hr
        ac != null -> ac
        else -> 0f
    }

    // ---------------------------------------------------------- mood

    private suspend fun recentMoodValence(): Float? {
        val rows = runCatching { vault.listByTier(Tier.Working, limit = 200) }
            .getOrDefault(emptyList())
        val cutoff = System.currentTimeMillis() - MOOD_LOOKBACK_MS
        var pos = 0
        var neg = 0
        for (e in rows) {
            if (e.tsMillis < cutoff) continue
            val f = vault.decodeFacets(e)
            for (label in f) when (label) {
                "mood:happy", "mood:excited" -> pos++
                "mood:sad", "mood:anxious", "mood:frustrated" -> neg++
                else -> {}
            }
        }
        if (pos == 0 && neg == 0) return null
        return ((pos - neg).toFloat() / (pos + neg)).coerceIn(-1f, 1f)
    }

    // ---------------------------------------------------------- labels

    private fun quadrantLabel(valence: Float, arousal: Float): String {
        val v = valence
        val a = arousal
        if (abs(v) < 0.18f && abs(a) < 0.18f) return "neutral"
        return when {
            a >= 0f && v >= 0f -> "energized"   // high arousal + positive
            a >= 0f && v < 0f -> "tense"        // high arousal + negative
            a < 0f && v >= 0f -> "calm"         // low arousal + positive
            else -> "low"                       // low arousal + negative
        }
    }

    private fun computeConfidence(hrFresh: Boolean, acoustic: Boolean, mood: Boolean): Float {
        var c = 0f
        if (hrFresh) c += 0.50f
        if (acoustic) c += 0.30f
        if (mood) c += 0.20f
        return c.coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------- persist

    private suspend fun persist(snap: ResonanceSnapshot) {
        val content = """{"valence":${"%.3f".format(snap.valence)},""" +
            """"arousal":${"%.3f".format(snap.arousal)},""" +
            """"label":"${snap.label}",""" +
            """"hr":${snap.liveHrAvgBpm ?: "null"},""" +
            """"baseline":${snap.baselineBpm ?: "null"},""" +
            """"conf":${"%.2f".format(snap.confidence)}}"""
        runCatching {
            vault.add(
                content = content,
                tier = Tier.Working,
                src = "resonance:state",
                facets = listOf(
                    "kind:resonance-state",
                    "mood:${snap.label}",
                    "topic:resonance",
                    "source:watch",
                ),
                conf = snap.confidence.toDouble(),
            )
        }.onFailure { Log.w(TAG, "snapshot persist failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "Mythara/ResonanceAnalyzer"

        private const val SAMPLE_SECONDS = 8
        private const val MIN_USABLE_SECONDS = 4

        // HR axis tuning — neutral mid-band; spread = bpm above
        // baseline that maps to fully aroused.
        private const val POPULATION_RESTING_BPM = 70
        private const val HR_SPREAD_BPM = 25f

        // Acoustic axis tuning — drawn from AcousticAnalyzer's own
        // population thresholds (PITCH_HIGH=200, PITCH_LOW=110;
        // ENERGY_HIGH=0.15, ENERGY_LOW=0.03).
        private const val PITCH_NEUTRAL_HZ = 155f
        private const val PITCH_SPREAD_HZ = 70f
        private const val ENERGY_NEUTRAL = 0.09f
        private const val ENERGY_SPREAD = 0.10f

        // Weight HR slightly above acoustic when both are available —
        // HR is the more stable arousal signal moment-to-moment.
        private const val HR_WEIGHT = 0.6f

        // How far back to scan recent mood rows for valence context.
        private const val MOOD_LOOKBACK_MS = 3L * 60 * 60 * 1000   // 3 hours
    }
}
