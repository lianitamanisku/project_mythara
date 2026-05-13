package com.mythara.persona

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
 * User toggle for the persona-from-usage-stats feature. Plain
 * DataStore preferences — the toggle isn't a secret, just user
 * preference.
 *
 * Default OFF. This is the most surveillance-heavy single feature
 * in the app; the user has to flip both this AND the
 * `PACKAGE_USAGE_STATS` special access in system Settings before
 * any data is collected.
 */
@Singleton
class PersonaSettings @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_persona")

    private val keyEnabled = booleanPreferencesKey("enabled")

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }
}
