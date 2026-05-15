package com.mythara.wear.resonance

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Tiny SharedPreferences cache for Resonance Mode flags on the watch:
 *
 *  - [available]  — pushed by the phone on `WearPaths.RESONANCE_AVAIL`
 *                   when the user enables the feature in the secret
 *                   menu. While false, the watch hides the toggle dot
 *                   and ignores combo taps entirely.
 *  - [active]     — local on/off for the current session, flipped by
 *                   the discreet toggle next to the mic button. Echoed
 *                   to the phone on `WearPaths.RESONANCE_TOGGLE`.
 *
 * Mirrors the singleton-with-SharedPreferences pattern used by
 * [com.mythara.wear.PhoneBatteryStore] / [com.mythara.wear.InsightStore]
 * — `:wear` deliberately stays Hilt-free, no DataStore dependency.
 *
 * UI reads via [observeAvailable] / [observeActive], which return a
 * Compose [State] backed by a `SharedPreferences.OnSharedPreferenceChangeListener`
 * so the PttScreen recomposes when the phone flips availability or the
 * user toggles the dot.
 */
object ResonanceStore {
    private const val PREFS = "mythara_resonance"
    private const val KEY_AVAILABLE = "available"
    private const val KEY_ACTIVE = "active"

    fun isAvailable(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AVAILABLE, false)

    fun isActive(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

    fun setAvailable(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AVAILABLE, value).apply()
    }

    fun setActive(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, value).apply()
    }

    @Composable
    fun observeAvailable(ctx: Context): State<Boolean> = observeBool(ctx, KEY_AVAILABLE, false)

    @Composable
    fun observeActive(ctx: Context): State<Boolean> = observeBool(ctx, KEY_ACTIVE, false)

    @Composable
    private fun observeBool(ctx: Context, key: String, default: Boolean): State<Boolean> {
        val prefs: SharedPreferences = remember(ctx) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        val state = remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }
        DisposableEffect(prefs, key) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
                if (changed == key) state.value = prefs.getBoolean(key, default)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return state
    }
}
