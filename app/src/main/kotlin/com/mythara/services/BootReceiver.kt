package com.mythara.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mythara.MytharaApp
import com.mythara.voice.QuickTalkNotification
import com.mythara.voice.QuickTalkSettings
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wakes Mythara's process when the device finishes booting (or
 * after Mythara itself is updated). The mere act of being invoked
 * causes Hilt to call [MytharaApp.onCreate], which re-runs every
 * one-shot bootstrap:
 *
 *   - GrowthScheduler.start()       (idempotent WorkManager periodic)
 *   - MemorySyncScheduler.start()
 *   - SelfOrganizerScheduler.start()
 *   - PersonaScheduler — gated by toggle
 *   - QuickTalkNotification — gated by toggle, re-posted here
 *
 * Past that point, the OS-bound services (NotificationListener,
 * PhoneControlAccessibilityService, MytharaVoiceInteractionService,
 * MytharaWakeListenerService) auto-rebind based on their own grants, so
 * once the process is awake, everything that should be live is
 * live without explicit re-start.
 *
 * Why post the QuickTalk notification HERE rather than letting
 * MytharaApp.onCreate's flow collector do it: on a cold boot, the
 * collector starts AFTER the receiver returns, and we want the
 * notification to land within the first second of boot so the user
 * sees "tap to talk to Lumi" in their status bar immediately after
 * unlock. The MytharaApp flow path then re-affirms it idempotently
 * on every subsequent toggle change.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject @ApplicationContext lateinit var appCtx: Context
    @Inject lateinit var quickTalkNotification: QuickTalkNotification
    @Inject lateinit var quickTalkSettings: QuickTalkSettings
    @Inject lateinit var reminderAlarmScheduler: com.mythara.reminders.ReminderAlarmScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        Log.d(TAG, "received $action")
        if (action !in HANDLED_ACTIONS) return

        // PendingResult lets us hold the receiver alive while we do
        // a tiny suspend operation (read the toggle, post the
        // notification). Without it, the receiver returns before
        // our coroutine touches DataStore and the work is lost.
        val pending = goAsync()
        scope.launch {
            try {
                val enabled = quickTalkSettings.isEnabled()
                if (enabled) {
                    quickTalkNotification.show()
                    Log.d(TAG, "QuickTalk notification re-posted after $action")
                }
                // Re-arm every scheduled reminder. AlarmManager does
                // NOT survive a device reboot — without this, any
                // task with scheduled_for_ms set before the boot
                // would silently miss its fire time.
                runCatching { reminderAlarmScheduler.rescheduleAll() }
                    .onFailure { Log.w(TAG, "reminder reschedule on boot failed: ${it.message}") }
                // WorkManager periodic workers (Growth, MemorySync,
                // SelfOrganizer, Persona) restore themselves
                // automatically across reboots — we don't need to
                // call .start() here. MytharaApp.onCreate's hooks
                // will idempotently re-run if the process needs
                // another bootstrap pass.
            } catch (t: Throwable) {
                Log.w(TAG, "boot bootstrap failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "Mythara/Boot"

        /**
         * Boot events we react to:
         *  - BOOT_COMPLETED        — user has unlocked after a full reboot
         *  - LOCKED_BOOT_COMPLETED — direct-boot phase, before unlock
         *                            (we don't do much here yet; included
         *                            so we can react fast on the eventual
         *                            unlock)
         *  - MY_PACKAGE_REPLACED   — Mythara itself was updated; treat
         *                            like a boot so the persistent
         *                            notification + schedules re-land
         */
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
