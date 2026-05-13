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
 * Toggle for "smart triage" of non-favorite incoming messages.
 *
 * When ON (default): autopilot's auto-reply dispatcher also looks
 * at messages from senders the user hasn't added to favorites. For
 * those, it fires a triage-mode agent turn that:
 *   - decides whether the message is worth a reply at all
 *   - drops marketing / OTP / shipping update / one-way info
 *   - skips obvious group conversations
 *   - never opens URLs in the message
 *   - mirrors the sender's tone when it does reply
 *   - keeps the conversation isolated like favorite auto-replies
 *
 * When OFF: only favorites get auto-replies; everyone else hits the
 * normal surface-and-decide notification path.
 *
 * Layered on top of [AutopilotStore] — that's still the master gate.
 * If autopilot is off, triage doesn't run.
 */
@Singleton
class AutoTriageStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_auto_triage")

    private val keyEnabled = booleanPreferencesKey("triage.enabled")

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: DEFAULT_ENABLED }

    suspend fun isEnabled(): Boolean = enabledFlow().first()

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }

    companion object {
        /** Default ON — the user explicitly asked for this behaviour. */
        const val DEFAULT_ENABLED = true
    }
}
