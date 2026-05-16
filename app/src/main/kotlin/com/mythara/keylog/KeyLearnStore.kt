package com.mythara.keylog

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
 * The user-controlled toggle for "always learn from keystrokes
 * across every app via the Accessibility Service".
 *
 * **Default OFF.** Keystroke capture is the most privacy-sensitive
 * input source Mythara has, even more than notification access —
 * every text field the user types into across every app gets
 * written to the local vault. We DO NOT enable this by default;
 * the user must explicitly flip it in the Permissions screen
 * after reading what it does.
 *
 * Once on:
 *   - The PhoneControlAccessibilityService listens for
 *     TYPE_VIEW_TEXT_CHANGED events from any focused text field.
 *   - The KeystrokeIngestor strips passwords (by inputType bit-
 *     check, isPassword(), and id-name heuristics like "password",
 *     "pin", "otp"), strips Mythara-self events, and skips events
 *     from the user's blocked-apps list (banking, payment).
 *   - Surviving events are written to the vault as Tier.Working
 *     rows with src=`behavior:keystroke` so the daily summariser
 *     can derive patterns ("user types about $topic at $time").
 *
 * Privacy posture:
 *   - All keystroke ingestion is purely local (vault), never
 *     synced to GitHub backup unless the user has the standard
 *     vault sync turned on AND the keystroke rows haven't been
 *     pruned by the daily summariser yet (which happens within
 *     24h).
 *   - The toggle is at the top of the Permissions screen with
 *     red copy describing what it captures, so it's not buried
 *     under more innocuous toggles.
 */
@Singleton
class KeyLearnStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_keylearn")

    private val keyEnabled = booleanPreferencesKey("enabled")

    fun observe(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun isEnabled(): Boolean =
        ctx.dataStore.data.first()[keyEnabled] ?: false

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }
}
