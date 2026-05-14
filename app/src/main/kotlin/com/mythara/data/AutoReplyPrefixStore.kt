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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional prefix string that gets prepended to every message Mythara
 * sends through an app (SMS, WhatsApp) during an autopilot auto-reply
 * turn. Lets the user mark agent-sent messages so recipients can tell
 * "this came from Lumi" vs "this came from me":
 *
 *   "LUMI (autopilot): I'll be there at 5"
 *
 * Fresh installs are pre-filled with [DEFAULT_PREFIX] — a short
 * "this is an AI assistant" disclaimer — so auto-replies are marked
 * out of the box. The user can edit it in Settings, or clear it to
 * an empty string to send messages with no prefix at all.
 *
 * Scope: AUTO-REPLY ONLY. When the user explicitly asks Lumi to text
 * someone ("text mom I'm running late"), the user's intent stands and
 * the message goes through unmodified. The prefix only kicks in when
 * the auto-reply dispatcher is the originator — the case where the
 * recipient might genuinely benefit from knowing this wasn't typed
 * by the user.
 *
 * The prefix is applied at tool-execution time inside
 * [com.mythara.agent.ToolRegistry] rather than being prompted into
 * the LLM, because LLMs frequently strip leading parentheticals or
 * reflow them mid-sentence. The mechanical prepend is the only way
 * to guarantee the marker reaches the wire intact.
 */
@Singleton
class AutoReplyPrefixStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_autoreply_prefix")

    private val keyPrefix = stringPreferencesKey("autoreply.prefix")

    // `?: DEFAULT_PREFIX` only fires when the key was never written —
    // i.e. a fresh install. Once the user saves anything (including an
    // empty string to disable the prefix), their value stands.
    fun prefixFlow(): Flow<String> = ctx.dataStore.data.map { it[keyPrefix] ?: DEFAULT_PREFIX }

    suspend fun prefix(): String = prefixFlow().first()

    suspend fun setPrefix(value: String) {
        ctx.dataStore.edit { it[keyPrefix] = value }
    }

    companion object {
        /** Pre-filled on fresh installs; editable / clearable in Settings. */
        const val DEFAULT_PREFIX = "I'm Anku's personal AI assistant and I can make mistakes"
    }
}
