package com.mythara.wake

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the always-listen toggle in main Settings. Plain
 * DataStore — the toggle isn't a secret, just user preference.
 *
 * Unlike Observe (Secret Settings), this surface is public: users see
 * it in main Settings the same way they'd see "Wi-Fi" or "Bluetooth".
 * The privacy contract is correspondingly tight: in this mode Vosk
 * runs continuously but **no transcripts are persisted** unless they
 * match "Hey Lumi <query>" and are subsequently submitted to the
 * chat agent (where they go through the normal MiniMax round-trip).
 */
@Singleton
class WakeListenerSettings @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_lumi_listener")

    private val keyEnabled = booleanPreferencesKey("listener.enabled")

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }
}
