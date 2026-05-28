package com.mythara.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wake-on-pickup gate for the front-camera face tracker.
 *
 * The camera is the most expensive sensor we run; keeping it
 * bound continuously while the user is just glancing at the phone
 * drains battery for nothing. This detector uses
 * [Sensor.TYPE_SIGNIFICANT_MOTION] — a hardware-batched, ultra-low-
 * power wake sensor Android specifically designed for "the phone
 * has been picked up / put in a pocket / moved" cases — to fire
 * an "active window" only when the user actually moved the phone.
 *
 * Why TYPE_SIGNIFICANT_MOTION over a continuous accelerometer
 * stream:
 *  - It's a ONE-SHOT trigger: register → wait → fire once →
 *    auto-deregister. We re-register after each fire.
 *  - It runs on a dedicated low-power core and doesn't wake the
 *    main CPU while idle.
 *  - It coalesces all the "is this a pickup?" heuristics in
 *    silicon (typically peak acceleration > ~1.2 g with rotational
 *    component); we don't have to reinvent them.
 *
 * Flow:
 *   1. enable() registers TYPE_SIGNIFICANT_MOTION.
 *   2. User picks up the phone → sensor fires → openWindow().
 *   3. activeWindow = true for [WINDOW_MS] (8 s).
 *   4. FaceTracker can bind/run within that window.
 *   5. Every face detection refreshes the window via
 *      [extendWindow] — keep the camera running while the user is
 *      actually looking at the phone.
 *   6. Window expires → activeWindow = false → camera unbinds
 *      → trigger sensor re-registers.
 *
 * Falls back to "always-on" when the device has no significant-
 * motion sensor (rare on modern hardware; old emulators sometimes
 * lack it). Better to keep the existing behaviour than to leave
 * the user looking at a permanently empty face.
 */
@Singleton
class PhonePickupDetector @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val sensorManager: SensorManager? =
        ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val triggerSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val supportsPickup: Boolean = triggerSensor != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var closeJob: Job? = null
    @Volatile private var enabled = false

    private val _activeWindow = MutableStateFlow(false)
    /** True while the camera should be allowed to run. */
    val activeWindow: StateFlow<Boolean> = _activeWindow.asStateFlow()

    /** Number of times the wake sensor has fired since enable() —
     *  surfaced as a debug counter, not used for control. */
    @Volatile private var triggerCount: Int = 0

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            triggerCount++
            Log.d(TAG, "significant motion trigger #$triggerCount — opening window")
            openWindow()
            // Significant-motion is one-shot. Re-arm so the NEXT
            // pickup also opens a window. Re-arm AFTER opening so
            // the same gesture doesn't immediately re-trigger.
            registerTrigger()
        }
    }

    /** Start listening for pickup events. Idempotent. When the
     *  hardware sensor is unavailable, opens the window
     *  immediately so existing camera flows keep working. */
    fun enable() {
        if (enabled) return
        enabled = true
        if (!supportsPickup) {
            Log.w(TAG, "no TYPE_SIGNIFICANT_MOTION sensor — falling back to always-on")
            _activeWindow.value = true
            return
        }
        Log.d(TAG, "enabling pickup detector — opening initial window so the user gets a face on first launch")
        // Open one window on enable so the user doesn't have to
        // shake the phone after launching Home for the camera to
        // engage. First view of the screen = first window.
        openWindow()
        registerTrigger()
    }

    /** Stop listening. Idempotent. */
    fun disable() {
        if (!enabled) return
        enabled = false
        closeJob?.cancel()
        closeJob = null
        if (supportsPickup) {
            runCatching { sensorManager?.cancelTriggerSensor(triggerListener, triggerSensor) }
        }
        _activeWindow.value = false
    }

    /** Refresh the active-window timer. Called by FaceTracker every
     *  successful face detection so the camera keeps running while
     *  someone is actually using the phone. */
    fun extendWindow() {
        if (!enabled || !supportsPickup) return
        if (_activeWindow.value) {
            // Reset the close-job timer.
            startCloseTimer()
        }
    }

    private fun openWindow() {
        if (!enabled) return
        _activeWindow.value = true
        startCloseTimer()
    }

    private fun startCloseTimer() {
        closeJob?.cancel()
        closeJob = scope.launch {
            delay(WINDOW_MS)
            _activeWindow.value = false
            Log.d(TAG, "active window closed after ${WINDOW_MS}ms — camera unbinding")
        }
    }

    private fun registerTrigger() {
        if (!supportsPickup) return
        runCatching {
            sensorManager?.requestTriggerSensor(triggerListener, triggerSensor)
        }.onFailure {
            Log.w(TAG, "requestTriggerSensor failed: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "Mythara/Pickup"

        /** How long the camera stays bound after a pickup event.
         *  8 s is enough for: glance → face mesh forms (~1.8 s
         *  gather) → user reads → looks away. Each face detection
         *  resets the timer via [extendWindow] so a sustained
         *  look-at extends the window indefinitely. */
        private const val WINDOW_MS = 8_000L
    }
}
