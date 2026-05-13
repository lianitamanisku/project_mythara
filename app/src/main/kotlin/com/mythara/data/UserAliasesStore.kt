package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * List of name / phone pairs that all represent the user.
 *
 * Why we need this: different WhatsApp accounts / devices / locales
 * surface the user's own sender label differently in chat exports —
 * "You", their saved full name, a nickname, a phone number, an
 * emoji-only profile name. Mythara has to know all of them so the
 * importer can correctly tell "this message was from me" vs "this
 * message was from my contact" and not create a phantom contact
 * profile for the user themselves.
 *
 * Designed to be populated via the system contact picker (Settings
 * → user aliases) so the user can rapidly select the entries they
 * use on other devices without typing names that have to match
 * exactly. Manual entry is the fallback for nicknames, emoji-only
 * names, or "You" / "Me" labels.
 *
 * Stored as a JSON list in DataStore — small list (typically ≤ 5
 * entries), no need for a Room schema.
 */
@Singleton
class UserAliasesStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    @Serializable
    data class Alias(
        val name: String,
        val phone: String = "",
    ) {
        val digits: String get() = phone.filter { it.isDigit() }
    }

    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_user_aliases")

    private val keyList = stringPreferencesKey("aliases.json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(Alias.serializer())

    fun aliasesFlow(): Flow<List<Alias>> = ctx.dataStore.data.map { prefs ->
        val raw = prefs[keyList] ?: return@map emptyList()
        runCatching { json.decodeFromString(listSer, raw) }.getOrElse { emptyList() }
    }

    suspend fun list(): List<Alias> = aliasesFlow().first()

    /**
     * True when [candidate] looks like any of the user's aliases.
     * Case-insensitive; matches either exactly, or by loose
     * containment in either direction (so "Ankur" matches an alias
     * "Ankur Nair" and vice versa). Phone digits also match — useful
     * when an export uses raw phone numbers instead of contact names.
     */
    suspend fun isUserCandidate(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val aliases = list()
        if (aliases.isEmpty()) return false
        val c = candidate.trim().lowercase()
        val cDigits = candidate.filter { it.isDigit() }
        for (a in aliases) {
            val n = a.name.trim().lowercase()
            if (n.isNotEmpty()) {
                if (n == c) return true
                if (n.length >= 3 && c.contains(n)) return true
                if (c.length >= 3 && n.contains(c)) return true
            }
            val d = a.digits
            if (d.isNotEmpty() && cDigits.isNotEmpty() &&
                (d.endsWith(cDigits.takeLast(d.length.coerceAtMost(cDigits.length))) ||
                    cDigits.endsWith(d.takeLast(cDigits.length.coerceAtMost(d.length)))) &&
                minOf(d.length, cDigits.length) >= 7
            ) {
                return true
            }
        }
        return false
    }

    suspend fun upsert(alias: Alias) {
        val current = list().toMutableList()
        val incoming = alias.copy(name = alias.name.trim())
        // Dedup by (name + digits) so the same contact's mobile +
        // work numbers coexist. Pure name-keying collapsed them and
        // silently dropped the second add.
        val idx = current.indexOfFirst { sameKey(it, incoming) }
        if (idx >= 0) current[idx] = incoming else current.add(incoming)
        save(current)
    }

    /**
     * Atomic batch add. Calling upsert in a loop from the
     * multi-picker raced — each coroutine read the same stale list
     * before any save landed, so only the last write survived
     * (last-writer-wins on an N-entry list = only one alias ever
     * persisted). This variant reads once, merges everything, writes
     * once.
     */
    suspend fun upsertAll(aliases: List<Alias>) {
        if (aliases.isEmpty()) return
        val current = list().toMutableList()
        for (alias in aliases) {
            val n = alias.name.trim()
            if (n.isEmpty()) continue
            val normalized = alias.copy(name = n)
            // Same dedup as upsert — same contact's two numbers stay
            // as two separate entries.
            val idx = current.indexOfFirst { sameKey(it, normalized) }
            if (idx >= 0) current[idx] = normalized else current.add(normalized)
        }
        save(current)
    }

    /**
     * Identity for dedup: same name (case-insensitive) AND same
     * digits string. Either field differs → distinct alias.
     */
    private fun sameKey(a: Alias, b: Alias): Boolean =
        a.name.equals(b.name, ignoreCase = true) && a.digits == b.digits

    suspend fun remove(name: String) {
        save(list().filterNot { it.name.equals(name, ignoreCase = true) })
    }

    /**
     * Remove a specific alias by (name, digits) so the user can
     * delete just one of a contact's multiple registered numbers
     * without taking out the others.
     */
    suspend fun removeOne(name: String, phone: String) {
        val digits = phone.filter { it.isDigit() }
        save(list().filterNot { it.name.equals(name, ignoreCase = true) && it.digits == digits })
    }

    suspend fun clear() {
        save(emptyList())
    }

    private suspend fun save(items: List<Alias>) {
        val raw = json.encodeToString(listSer, items)
        ctx.dataStore.edit { it[keyList] = raw }
    }
}
