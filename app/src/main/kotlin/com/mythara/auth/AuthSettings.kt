package com.mythara.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-configurable auto-lock timeout. AuthManager records the
 * backgrounded-at timestamp when the app loses foreground; on
 * re-foreground, if the elapsed time exceeds this value, the
 * session locks and BiometricPrompt is required again.
 *
 * Default is [DEFAULT_TIMEOUT_MS] (5 minutes) — long enough that
 * checking a notification or answering a call doesn't force a
 * re-auth dance, short enough that an unattended phone re-locks
 * before someone else grabs it.
 *
 * Special values:
 *   - [IMMEDIATE_MS] (0) — lock the instant the app loses
 *     foreground. Matches the pre-this-commit behaviour.
 *   - [NEVER_MS] (-1) — never auto-lock during a session.
 *     Process death still re-locks because the AuthManager
 *     singleton resets to Locked on construction.
 */
@Singleton
class AuthSettings @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_auth")

    private val keyTimeoutMs = longPreferencesKey("auto_lock_timeout_ms")
    private val keyLockEnabled = booleanPreferencesKey("lock_enabled")

    fun timeoutFlow(): Flow<Long> = ctx.dataStore.data.map { it[keyTimeoutMs] ?: DEFAULT_TIMEOUT_MS }

    suspend fun timeoutMs(): Long =
        ctx.dataStore.data.first()[keyTimeoutMs] ?: DEFAULT_TIMEOUT_MS

    suspend fun setTimeoutMs(ms: Long) {
        ctx.dataStore.edit { it[keyTimeoutMs] = ms }
    }

    /**
     * Master switch for the biometric lock. When false, the app skips
     * the AuthGate entirely — including on cold start. Default true.
     */
    fun lockEnabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyLockEnabled] ?: true }

    suspend fun isLockEnabled(): Boolean = ctx.dataStore.data.first()[keyLockEnabled] ?: true

    suspend fun setLockEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyLockEnabled] = value }
    }

    companion object {
        const val IMMEDIATE_MS = 0L
        const val THIRTY_SECONDS_MS = 30L * 1000
        const val ONE_MINUTE_MS = 60L * 1000
        const val FIVE_MINUTES_MS = 5L * 60 * 1000
        const val FIFTEEN_MINUTES_MS = 15L * 60 * 1000
        const val ONE_HOUR_MS = 60L * 60 * 1000
        const val NEVER_MS = -1L

        /** Default 5 minutes — re-checking notifications / replying to
         *  texts shouldn't force a re-auth, but unattended phone does. */
        const val DEFAULT_TIMEOUT_MS = FIVE_MINUTES_MS

        /** Options for the Settings dropdown, in display order. */
        val PRESETS: List<Pair<Long, String>> = listOf(
            IMMEDIATE_MS to "immediate (always re-auth)",
            THIRTY_SECONDS_MS to "30 seconds",
            ONE_MINUTE_MS to "1 minute",
            FIVE_MINUTES_MS to "5 minutes",
            FIFTEEN_MINUTES_MS to "15 minutes",
            ONE_HOUR_MS to "1 hour",
            NEVER_MS to "never (until app is killed)",
        )
    }
}
