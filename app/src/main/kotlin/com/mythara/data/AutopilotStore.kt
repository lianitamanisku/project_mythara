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
 * Master toggle for Mythara's autopilot — i.e. every code path where
 * Lumi acts WITHOUT an explicit, in-the-moment tap from the user.
 *
 * Auto-paths gated by this flag:
 *   - Wake-word ("hey lumi") triggered queries from MytharaWakeListenerService
 *   - Notification auto-process (the "[notif] …" → agent turn flow)
 *   - Future passive triggers (silence-detected ambient questions, etc.)
 *
 * Explicit user actions are NEVER gated by this flag:
 *   - Tap-and-hold mic in the chat screen → always works
 *   - Typing a message in the composer → always works
 *   - Tapping a tool/control button → always works
 *
 * Default = TRUE. The product's centre of gravity is voice-first
 * always-on assistance; turning autopilot off is the "pause everything"
 * escape hatch, not the starting state.
 *
 * When OFF the foreground services keep running (wake-word service can
 * still be active so the keyword detector stays primed for the next
 * flip-back-on), but the agent loop refuses to fire on auto-triggers.
 * The flag is checked at the *point of dispatch*, not at startup —
 * flipping it OFF stops the very next would-be auto-turn.
 */
@Singleton
class AutopilotStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_autopilot")

    private val keyEnabled = booleanPreferencesKey("autopilot.enabled")

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: DEFAULT_ENABLED }

    suspend fun isEnabled(): Boolean = enabledFlow().first()

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }

    companion object {
        /** Default ON — autopilot is the product's default behaviour. */
        const val DEFAULT_ENABLED = true
    }
}
