package com.mythara.mic

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
 * Global "speech-to-text muted" switch.
 *
 * When ON, every on-device speech path is silenced — the always-on
 * "Hey Lumi" wake-word listener, Observe-mode passive learning,
 * continuous voice chat, and the push-to-talk mic button. It's the
 * single hard kill-switch for the microphone-driven STT surfaces.
 *
 * Plain DataStore preferences; persisted across restarts. [MicBroker]
 * hydrates from this on construction and mirrors live edits.
 */
@Singleton
class SpeechMuteStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_speech_mute")

    private val keyMuted = booleanPreferencesKey("muted")

    fun mutedFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyMuted] ?: false }

    suspend fun isMuted(): Boolean = ctx.dataStore.data.first()[keyMuted] ?: false

    suspend fun setMuted(value: Boolean) {
        ctx.dataStore.edit { it[keyMuted] = value }
    }
}
