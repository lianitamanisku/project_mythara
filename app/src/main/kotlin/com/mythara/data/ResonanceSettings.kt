package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent settings for Resonance Mode — opt-in, hidden behind the
 * secret menu. Off by default; until [enabled] is true the controller
 * ignores combo / toggle / HR messages from the watch entirely, and
 * the watch's toggle-dot is hidden (gated by [enabled] being mirrored
 * onto the watch via `ResonanceWatchNotifier`).
 *
 * Same SharedPreferences-via-DataStore pattern the rest of `:app` uses
 * (mirrors [AutoReplyPrefixStore], [SpeechMuteStore], [AuthSettings]).
 *
 * The setters are deliberately gentle — they coerce inputs into the
 * safe ranges enforced by [com.mythara.resonance.ResonanceAudioEngine]
 * so a misbehaving caller can't slip an out-of-band carrier or volume
 * past the audio engine's per-buffer clamp.
 */
@Singleton
class ResonanceSettings @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_resonance")

    private val keyEnabled = booleanPreferencesKey("enabled")
    private val keyDefaultProtocol = stringPreferencesKey("default_protocol")
    private val keyVolumeCapPercent = intPreferencesKey("volume_cap_percent")
    private val keyDefaultCarrierHz = intPreferencesKey("default_carrier_hz")
    private val keyMaskingEnabled = booleanPreferencesKey("masking_enabled")
    private val keySessionCapMinutes = intPreferencesKey("session_cap_minutes")

    fun enabledFlow(): Flow<Boolean> =
        ctx.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun isEnabled(): Boolean = enabledFlow().first()

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }

    fun defaultProtocolFlow(): Flow<String> =
        ctx.dataStore.data.map { it[keyDefaultProtocol] ?: DEFAULT_PROTOCOL }

    suspend fun setDefaultProtocol(value: String) {
        ctx.dataStore.edit { it[keyDefaultProtocol] = value }
    }

    fun volumeCapPercentFlow(): Flow<Int> =
        ctx.dataStore.data.map { it[keyVolumeCapPercent] ?: DEFAULT_VOLUME_CAP_PERCENT }

    suspend fun setVolumeCapPercent(value: Int) {
        ctx.dataStore.edit {
            it[keyVolumeCapPercent] = value.coerceIn(VOLUME_CAP_MIN_PERCENT, VOLUME_CAP_MAX_PERCENT)
        }
    }

    fun defaultCarrierHzFlow(): Flow<Int> =
        ctx.dataStore.data.map { it[keyDefaultCarrierHz] ?: DEFAULT_CARRIER_HZ }

    suspend fun setDefaultCarrierHz(value: Int) {
        ctx.dataStore.edit {
            it[keyDefaultCarrierHz] = value.coerceIn(CARRIER_MIN_HZ, CARRIER_MAX_HZ)
        }
    }

    fun maskingEnabledFlow(): Flow<Boolean> =
        ctx.dataStore.data.map { it[keyMaskingEnabled] ?: true }

    suspend fun setMaskingEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyMaskingEnabled] = value }
    }

    fun sessionCapMinutesFlow(): Flow<Int> =
        ctx.dataStore.data.map { it[keySessionCapMinutes] ?: DEFAULT_SESSION_CAP_MINUTES }

    suspend fun setSessionCapMinutes(value: Int) {
        ctx.dataStore.edit {
            it[keySessionCapMinutes] = value.coerceIn(SESSION_CAP_MIN_MINUTES, SESSION_CAP_MAX_MINUTES)
        }
    }

    companion object {
        const val DEFAULT_PROTOCOL = "Calm"

        // Match ResonanceAudioEngine's safe ranges so the UI can't
        // offer a slider that would just be clamped at draw time.
        const val DEFAULT_VOLUME_CAP_PERCENT = 35
        const val VOLUME_CAP_MIN_PERCENT = 10
        const val VOLUME_CAP_MAX_PERCENT = 70

        const val DEFAULT_CARRIER_HZ = 200
        const val CARRIER_MIN_HZ = 100
        const val CARRIER_MAX_HZ = 400

        const val DEFAULT_SESSION_CAP_MINUTES = 25
        const val SESSION_CAP_MIN_MINUTES = 5
        const val SESSION_CAP_MAX_MINUTES = 30
    }
}
