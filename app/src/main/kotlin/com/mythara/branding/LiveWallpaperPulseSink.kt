package com.mythara.branding

/**
 * Process-wide sink for the most recent live heart-rate sample, read
 * by [WallpaperRenderer] each frame to pulse the rose's hexagon
 * nucleus and the active "neuron" overlay nodes in sync with the
 * user's actual heartbeat. Decoupled from the HR source so any
 * ingestion path (watch Data-Layer push, Health-Connect poller,
 * future direct-sensor reader) can publish the same way:
 *
 *   LiveWallpaperPulseSink.update(bpm)
 *
 * Reader returns null when the most recent push is older than
 * [STALE_AFTER_MS] — the renderer falls back to a static calm-breath
 * rate in that case (so a screen-locked phone with no recent HR
 * still looks like a living wallpaper, just not a personalised one).
 *
 * Volatile fields + no synchronisation: writes are atomic for Int /
 * Long on the JVM and the renderer is fine with reading a slightly-
 * out-of-sync (bpm, ts) pair — the worst case is one frame using
 * stale staleness, harmless.
 */
object LiveWallpaperPulseSink {
    @Volatile private var bpmValue: Int = 0
    @Volatile private var ts: Long = 0L

    // Rolling baseline window — kept O(1) memory by tracking
    // running mean + sample count instead of the full series.
    // Used by [maybeSpikeAlert] to detect "BPM jumped above
    // baseline" events for the Dynamic Island insight push.
    @Volatile private var baselineMean: Float = 0f
    @Volatile private var baselineN: Int = 0
    @Volatile private var lastSpikeAlertTs: Long = 0L

    /** Publish a fresh BPM reading. Clamps obvious garbage. */
    fun update(bpm: Int) {
        if (bpm !in 30..240) return
        bpmValue = bpm
        ts = System.currentTimeMillis()
        maybeSpikeAlert(bpm)
        updateBaseline(bpm)
    }

    /**
     * Detect "the user's BPM just jumped well above their recent
     * baseline" and push a Dynamic Island insight when it happens
     * — so the user sees an in-app + lock-screen "❤ {bpm} bpm —
     * something just happened" pill without having to open a
     * health app.
     *
     * Threshold tuning:
     *   - Need ≥ MIN_BASELINE_SAMPLES samples in the rolling
     *     baseline before any alert can fire (so the very first
     *     sample of the day doesn't trigger).
     *   - Spike = current BPM is more than SPIKE_DELTA_PCT
     *     percent above the baseline mean (e.g. baseline 70 → 90
     *     bpm fires at +28%).
     *   - Hard floor of SPIKE_DELTA_BPM raw difference so a
     *     calm-baseline person doesn't trigger on tiny relative
     *     changes (baseline 55 → 65 = +18% but only +10 BPM —
     *     not interesting).
     *   - SPIKE_COOLDOWN_MS between alerts so a sustained
     *     high-HR period (workout, walk) doesn't spam the pill.
     */
    private fun maybeSpikeAlert(bpm: Int) {
        if (baselineN < MIN_BASELINE_SAMPLES) return
        val baseline = baselineMean
        val deltaPct = (bpm - baseline) / baseline
        val deltaBpm = (bpm - baseline).toInt()
        if (deltaPct < SPIKE_DELTA_PCT) return
        if (deltaBpm < SPIKE_DELTA_BPM) return
        val now = System.currentTimeMillis()
        if (now - lastSpikeAlertTs < SPIKE_COOLDOWN_MS) return
        lastSpikeAlertTs = now
        runCatching {
            com.mythara.ui.system.DynamicIslandSink.push(
                text = "❤ $bpm bpm",
                accent = androidx.compose.ui.graphics.Color(0xFFEB4268), // Sriracha-ish
                ttlMs = 8_000L,
            )
        }
    }

    /**
     * Streaming-mean baseline update. Caps the effective
     * sample-window at BASELINE_HORIZON so the baseline tracks
     * recent state — i.e. if the user's resting HR slowly
     * shifts upward (illness, deconditioning), the baseline
     * follows within a few hundred samples instead of being
     * pinned to the first day's number forever.
     */
    private fun updateBaseline(bpm: Int) {
        val n = baselineN.coerceAtMost(BASELINE_HORIZON - 1)
        baselineMean = (baselineMean * n + bpm) / (n + 1)
        baselineN = (baselineN + 1).coerceAtMost(BASELINE_HORIZON)
    }

    /** Latest BPM if it arrived within [maxStaleMs] of now, else
     *  null. Renderer interprets null as "no live HR — use the
     *  default breath-rate fallback". */
    fun bpm(maxStaleMs: Long = STALE_AFTER_MS): Int? {
        if (ts == 0L) return null
        return if (System.currentTimeMillis() - ts <= maxStaleMs) bpmValue else null
    }

    /** Default staleness window — 3 min. Long enough to ride out the
     *  Fitbit / Samsung Health batch cadence (typically 1-2 min)
     *  without flapping the wallpaper between live + fallback states. */
    const val STALE_AFTER_MS = 3L * 60 * 1000

    /** Spike-detection thresholds — see [maybeSpikeAlert]. */
    private const val MIN_BASELINE_SAMPLES = 12
    private const val SPIKE_DELTA_PCT = 0.20f       // ≥ 20% above baseline
    private const val SPIKE_DELTA_BPM = 12          // AND ≥ 12 raw BPM above baseline
    private const val SPIKE_COOLDOWN_MS = 5L * 60 * 1000   // 5 min between alerts
    private const val BASELINE_HORIZON = 240        // ~last 4h at 1-min cadence
}
