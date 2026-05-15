package com.mythara.wear

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.mythara.wear.complications.InsightComplicationService
import com.mythara.wear.complications.PhoneBatteryComplicationService
import com.mythara.wear.complications.ReminderComplicationService
import com.mythara.wear.resonance.ResonanceStore

/**
 * Watch-side companion to the phone's Data Layer pushes.
 *
 *  - [WearPaths.INSIGHT]        → latest Mythara agent chat message,
 *                                 cached via [InsightStore]. A short
 *                                 haptic fires when (and only when)
 *                                 the message actually changes — the
 *                                 watch face itself can't vibrate
 *                                 (WFF is resource-only), so the
 *                                 Mythara-only buzz lives here.
 *  - [WearPaths.PHONE_BATTERY]  → paired phone's battery percent,
 *                                 cached via [PhoneBatteryStore].
 *
 * In both cases we ask the complication system to refresh the matching
 * data-source service immediately, so the watch face updates in
 * near-real-time rather than waiting for the next periodic poll.
 */
class MytharaWearDataReceiver : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.INSIGHT -> {
                val text = runCatching { String(event.data, Charsets.UTF_8).trim() }.getOrNull()
                if (text.isNullOrBlank()) {
                    Log.d(TAG, "insight arrived empty; skipping")
                    return
                }
                if (text == InsightStore.latest(this)) {
                    Log.d(TAG, "insight unchanged; no buzz, no refresh")
                    return
                }
                Log.d(TAG, "insight from phone: \"${text.take(80)}\"")
                InsightStore.save(this, text)
                requestUpdate(InsightComplicationService::class.java)
                buzz()
            }

            WearPaths.PHONE_BATTERY -> {
                val pct = runCatching { String(event.data, Charsets.UTF_8).trim().toInt() }.getOrNull()
                if (pct == null) {
                    Log.d(TAG, "phone battery payload unparseable; skipping")
                    return
                }
                Log.d(TAG, "phone battery from phone: $pct%")
                PhoneBatteryStore.save(this, pct)
                requestUpdate(PhoneBatteryComplicationService::class.java)
            }

            WearPaths.TASKS -> {
                val raw = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull() ?: return
                Log.d(TAG, "tasks snapshot from phone (${raw.length}B)")
                ClusterDataStore.saveTasks(this, raw)
            }

            WearPaths.PEOPLE -> {
                val raw = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull() ?: return
                Log.d(TAG, "people snapshot from phone (${raw.length}B)")
                ClusterDataStore.savePeople(this, raw)
            }

            WearPaths.CALENDAR -> {
                val raw = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull() ?: return
                Log.d(TAG, "calendar snapshot from phone (${raw.length}B)")
                ClusterDataStore.saveCalendar(this, raw)
            }

            WearPaths.REMINDER -> {
                val raw = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull() ?: return
                Log.d(TAG, "reminder from phone (${raw.length}B)")
                ClusterDataStore.saveReminder(this, raw)
                requestUpdate(ReminderComplicationService::class.java)
            }

            WearPaths.AUDIT -> {
                val raw = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull() ?: return
                Log.d(TAG, "audit snapshot from phone (${raw.length}B)")
                ClusterDataStore.saveAudit(this, raw)
            }

            WearPaths.RESONANCE_AVAIL -> {
                // Phone-pushed feature flag — flips the toggle dot's
                // visibility on the watch. Payload "1" / "0".
                val raw = runCatching { String(event.data, Charsets.UTF_8).trim() }.getOrNull()
                val avail = raw == "1"
                Log.d(TAG, "resonance availability from phone: $avail")
                ResonanceStore.setAvailable(this, avail)
                // If the phone disabled the feature mid-session, also
                // force-clear local active state so the pad disappears
                // AND drop HR streaming back to the slow baseline.
                if (!avail) {
                    ResonanceStore.setActive(this, false)
                    HeartRateService.stopStreaming(this)
                }
            }

            WearPaths.RESONANCE_STATE -> {
                // Phone-confirmed session state — buzz so the user
                // knows their toggle / End-Session combo landed.
                val raw = runCatching { String(event.data, Charsets.UTF_8).trim() }.getOrNull()
                Log.d(TAG, "resonance state from phone: $raw")
                if (raw == "ended") {
                    // Mirror the local flag so the pad collapses if the
                    // phone ended the session for any reason (cap timer,
                    // headphone removal, hard stop from the app, etc.),
                    // and drop HR sampling back to the slow baseline.
                    ResonanceStore.setActive(this, false)
                    HeartRateService.stopStreaming(this)
                }
                buzz()
            }

            else -> Log.d(TAG, "ignored message on ${event.path}")
        }
    }

    private fun requestUpdate(service: Class<*>) {
        ComplicationDataSourceUpdateRequester
            .create(this, ComponentName(this, service))
            .requestUpdateAll()
    }

    /** A single short pulse — the Mythara-only "new message" haptic. */
    private fun buzz() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(160, VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { Log.w(TAG, "vibrate failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "Mythara/WearRecv"
    }
}
