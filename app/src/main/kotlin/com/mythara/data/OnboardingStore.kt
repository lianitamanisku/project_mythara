package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny "have we shown the first-run onboarding splash yet?" flag.
 *
 * Mythara has a lot of permissions and special-access toggles that the
 * user MUST grant for the assistant to be useful — Accessibility (for
 * screen-read + tap/swipe), Notification access (for read_notifications),
 * Usage access (for app analytics), the runtime perms (mic / cam /
 * contacts / sms / call / calendar / location / media / sms-read /
 * post-notifications), plus a handful of lazy-downloaded models if the
 * user wants Observe mode and on-device extraction.
 *
 * Without a guided walkthrough on first launch, a fresh install lands
 * the user in a partly-broken state where most tools error with
 * "permission not granted" and the only fix is digging through the
 * Settings screen panel by panel. [OnboardingStore] gates a one-time
 * splash that walks through every step in order, so a new device is
 * ready-to-go from the very first launch.
 *
 * Default = NOT completed → onboarding shows on first launch. The
 * user can also re-launch it from Settings via the "re-run onboarding"
 * button (useful if they skipped a step or want to grant something
 * later).
 */
@Singleton
class OnboardingStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_onboarding")

    private val keyCompleted = booleanPreferencesKey("onboarding.completed")
    // Phase E — Mythara Phone UI Overhaul. Independent from the
    // big onboarding gate above: this is just the 3-second "press
    // & hold anywhere" ghosted-ring coach mark that fades in under
    // Chat on the first launch after the redesigned amulet ships.
    // Dismissed by tap; never shown again. Re-run by clearing app
    // data or by the "re-run onboarding" button which resets BOTH
    // flags so the user gets the full new-install experience.
    private val keyAmuletHintSeen = booleanPreferencesKey("amulet.hintSeen")

    /** Reactive flow — UI observes to switch out of the onboarding screen. */
    fun completedFlow(): Flow<Boolean> =
        ctx.dataStore.data.map { it[keyCompleted] ?: false }

    suspend fun isCompleted(): Boolean = completedFlow().first()

    suspend fun markCompleted() {
        ctx.dataStore.edit { it[keyCompleted] = true }
    }

    /** Reset — used by the "re-run onboarding" button in Settings. */
    suspend fun reset() {
        ctx.dataStore.edit {
            it[keyCompleted] = false
            it[keyAmuletHintSeen] = false
        }
    }

    /** "Has the user seen the press-and-hold ghosted-ring coach
     *  mark yet?" — gates the Phase E first-launch hint that
     *  teaches the new amulet gesture. */
    suspend fun isAmuletHintSeen(): Boolean =
        ctx.dataStore.data.first()[keyAmuletHintSeen] ?: false

    suspend fun markAmuletHintSeen() {
        ctx.dataStore.edit { it[keyAmuletHintSeen] = true }
    }
}
