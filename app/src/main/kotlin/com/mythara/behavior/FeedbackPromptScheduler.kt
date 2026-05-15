package com.mythara.behavior

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic "how am I doing" feedback prompt scheduler.
 *
 * The Auto-Resonance system needs labelled feedback to learn from —
 * it can't tell whether a tone session HELPED unless the user
 * occasionally answers "yes that calmed me" / "no I'm still wound
 * up". This scheduler decides WHEN to ask + which question to ask,
 * and the chat surface renders the resulting prompt as a
 * `FeedbackPromptCard` ChatItem.
 *
 * Two concrete prompt families currently scheduled (more get added
 * as the agent learns which questions correlate with intervention
 * outcomes):
 *
 *   "how-are-you" — open-ended "how are you doing right now?".
 *                   Fires at most once every PROMPT_COOLDOWN_MS so
 *                   we never become an interrogation.
 *   "tone-rating" — only fires within FEEDBACK_WINDOW_MS of a
 *                   recent tone-trigger. "Did that session help?"
 *
 * State persisted via DataStore Preferences so prompt cadence
 * survives process death:
 *   - last-prompt-ts   : when we last surfaced ANY prompt
 *   - dismissed-promptIds : which prompts the user has already
 *                            dismissed in the current cadence window
 *
 * Phase: scaffolding only. The actual scheduling decision (when to
 * fire what) is intentionally simple now (cooldown + recent-trigger
 * gate); the daily-review agent loop will replace it once it lands.
 */
@Singleton
class FeedbackPromptScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_feedback_prompts")

    @Serializable
    data class Pending(
        val id: String,
        val question: String,
        val createdMs: Long,
    )

    private val keyLast = longPreferencesKey("last.prompt.ts")
    private val keyPending = stringPreferencesKey("pending.json")
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    /**
     * Returns the currently-pending prompt for the user (if any),
     * or null when no prompt is due. Called by the chat scrollback
     * renderer to decide whether to surface a [FeedbackPromptCard].
     *
     * Idempotent — the same prompt remains "pending" until the user
     * either responds (via [resolve]) or explicitly dismisses
     * (via [dismiss]).
     */
    suspend fun pending(): Pending? {
        val raw = ctx.dataStore.data.first()[keyPending] ?: return null
        return runCatching { json.decodeFromString(Pending.serializer(), raw) }.getOrNull()
    }

    /** Schedule a new prompt to surface. Replaces any current
     *  pending prompt — only one is active at a time so we never
     *  swarm the user with feedback questions. */
    suspend fun schedule(id: String, question: String) {
        val now = System.currentTimeMillis()
        val pending = Pending(id = id, question = question, createdMs = now)
        ctx.dataStore.edit { prefs ->
            prefs[keyPending] = json.encodeToString(Pending.serializer(), pending)
            prefs[keyLast] = now
        }
    }

    /** User responded — caller has already routed the response
     *  through [BehaviorEventStore.recordFeedback]; this just
     *  clears the pending slot. */
    suspend fun resolve() {
        ctx.dataStore.edit { it.remove(keyPending) }
    }

    /** User dismissed the prompt without responding. Same effect
     *  on the pending slot as [resolve]; separate method for
     *  call-site clarity + future analytics divergence. */
    suspend fun dismiss() {
        ctx.dataStore.edit { it.remove(keyPending) }
    }

    /** True when enough time has elapsed since the last prompt
     *  that we're allowed to fire another one. Caller still has
     *  to decide what question to ask. */
    suspend fun isCooldownExpired(): Boolean {
        val last = ctx.dataStore.data.first()[keyLast] ?: 0L
        return System.currentTimeMillis() - last > PROMPT_COOLDOWN_MS
    }

    companion object {
        /** Minimum gap between any two prompts. ~6 hours by default
         *  — frequent enough to learn, infrequent enough not to
         *  annoy. */
        const val PROMPT_COOLDOWN_MS = 6L * 60 * 60 * 1000

        /** Window after a tone-trigger event during which we'll ask
         *  "did that help?". Beyond this, the user's memory of the
         *  intervention is too stale for a useful answer. */
        const val FEEDBACK_WINDOW_MS = 30L * 60 * 1000
    }
}
