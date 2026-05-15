package com.mythara.behavior

import android.util.Log
import com.mythara.branding.LiveWallpaperPulseSink
import com.mythara.branding.MoodSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **PHASE: Scaffolding.** The decision-making engine for autonomous
 * Resonance interventions hasn't shipped yet — this class is the
 * substrate the future agent loop will plug into.
 *
 * Vision (from product asks):
 *   - Agent observes the user continuously: chat-mood scores from
 *     [com.mythara.agent.mood.ChatMoodTracker], live HR from
 *     [LiveWallpaperPulseSink], acoustic prosody from the existing
 *     [com.mythara.secret.observe.acoustic.AcousticAnalyzer], and
 *     reminder-miss patterns from [BehaviorEventStore].
 *   - When patterns indicate a behavioural intervention would help
 *     ("anxious + elevated HR for >5 min", "third missed reminder
 *     today with 'tired' reason", "voice prosody flagged elevated
 *     stress arc over a 90s window"), the agent picks a Resonance
 *     protocol (calm / focus / wind-down / OM) and calls
 *     [trigger] to start a low-volume tone session.
 *   - Post-session, the agent records the intervention's effect via
 *     [BehaviorEventStore.recordToneEffect] so future decisions
 *     learn what's actually working for THIS user.
 *
 * What's here today:
 *   - The data sources are all wired (sinks read in [snapshot]).
 *   - The vault facets the agent will query are formalised in
 *     [BehaviorEventStore].
 *   - [trigger] writes the intervention row but does NOT actually
 *     start the audio engine yet — the existing
 *     [com.mythara.resonance.ResonanceController] already has the
 *     full audio pipeline (binaural / isochronic / OM-style
 *     carriers); wiring trigger → controller is the obvious next
 *     step.
 *
 * Daily-review hook (also future): a worker reads the day's
 * [BehaviorEventStore] rows + this observer's snapshots + the
 * vault's mood / HR / reminder-miss facets, asks the LLM to
 * surface patterns, and writes a [BehaviorEventStore.recordDailyReview]
 * row capturing tomorrow's intervention plan.
 */
@Singleton
class AutoResonanceObserver @Inject constructor(
    private val behaviorEvents: BehaviorEventStore,
) {

    /** Snapshot of every input the future decision engine will read
     *  per evaluation tick. Captured atomically so a slow source
     *  (e.g. acoustic prosody from a 90s rolling buffer) doesn't
     *  cause the snapshot to interleave with a fresher one. */
    data class Snapshot(
        /** Most recent BPM if fresh, else null. */
        val hr: Int?,
        /** Most recent detected mood label if fresh, else null. */
        val mood: String?,
        /** Wall-clock millis when the snapshot was taken. */
        val tsMs: Long,
    )

    /** Read every observation source. Synchronous + cheap — the
     *  decision loop calls this every ~30s. */
    fun snapshot(): Snapshot = Snapshot(
        hr = LiveWallpaperPulseSink.bpm(),
        mood = MoodSink.current(),
        tsMs = System.currentTimeMillis(),
    )

    /**
     * Schedule a tone intervention. Today this is a logging stub
     * that records the trigger to the behaviour vault so the daily-
     * review agent has training data; the audio path itself is not
     * yet wired. Wiring point: replace the `Log.i` below with a
     * call into [com.mythara.resonance.ResonanceController.start]
     * passing the chosen protocol.
     *
     * Tone-pattern memory (from product ask):
     *   When the LLM-driven decision engine picks WHAT to play, it
     *   should consult the vault for past `behavior:tone-effect`
     *   rows correlated with similar trigger contexts (mood / HR /
     *   reason) — that's the "decoded patterns stored in memory"
     *   substrate. The engine itself isn't here yet but the data
     *   substrate is.
     */
    suspend fun trigger(
        reason: String,
        protocol: String,
    ) {
        val snap = snapshot()
        behaviorEvents.recordToneTrigger(
            reason = reason,
            protocol = protocol,
            triggerHrBpm = snap.hr,
            triggerMood = snap.mood,
        )
        Log.i(
            TAG,
            "tone-trigger reason=$reason protocol=$protocol " +
                "hr=${snap.hr ?: "--"} mood=${snap.mood ?: "--"} " +
                "(audio wiring pending — vault row only)",
        )
        // FUTURE: ResonanceController.start(protocol) here. When
        // the controller actually runs, also schedule a follow-up
        // recordToneEffect a few minutes after the session ends so
        // the learning loop closes.
    }

    companion object {
        private const val TAG = "Mythara/AutoResonance"
    }
}
