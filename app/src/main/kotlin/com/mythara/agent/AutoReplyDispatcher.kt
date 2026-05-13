package com.mythara.agent

import android.content.Context
import android.util.Log
import com.mythara.audit.AuditLogger
import com.mythara.data.AutoTriageStore
import com.mythara.data.AutopilotStore
import com.mythara.data.EnterpriseAutopilotStore
import com.mythara.data.FavoritesStore
import com.mythara.services.NotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the global [NotificationListener.newNotifications] stream
 * and decides what to do with each incoming message:
 *
 *   • SENDER MATCHES A FAVORITE → fire a tone-conditioned auto-reply
 *     turn ([AUTO_REPLY_PREFIX]). Uses the user-chosen tone.
 *
 *   • SENDER IS A STRANGER, [AutoTriageStore] is ON → fire a triage
 *     turn ([AUTO_TRIAGE_PREFIX]). The agent decides whether the
 *     message is worth a reply at all, drops marketing/OTP/info/
 *     groups, mirrors the conversation tone if it does reply, and
 *     NEVER opens any URLs from the message (security).
 *
 *   • Anything else → silently ignored here, falls through to the
 *     normal surface-and-decide notification path elsewhere.
 *
 * Process-scoped — lives in [com.mythara.MytharaApp] and starts at
 * cold-boot so it works on locked screens / UI-detached.
 *
 * Group detection (cheap heuristic; the prompt also has a hard
 * "drop if group" rule as a backstop):
 *   - title contains a comma → multi-recipient SMS / MMS
 *   - body starts with "<Name>:" where Name differs from the
 *     notification title → looks like a reply in a group chat
 *   - title contains "(N messages)" / "(N)" → group summary
 *
 * Sender-class drops (also cheap, no LLM tokens spent on these):
 *   - all-digit shortcode senders (5-6 digit codes) → automated
 *   - empty body → useless ping
 */
@Singleton
class AutoReplyDispatcher @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val favorites: FavoritesStore,
    private val autopilot: AutopilotStore,
    private val entAutopilot: EnterpriseAutopilotStore,
    private val triage: AutoTriageStore,
    private val runner: AgentRunner,
    private val audit: AuditLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            NotificationListener.newNotifications.collect { r ->
                handle(r)
            }
        }
        Log.d(TAG, "AutoReplyDispatcher started")
    }

    private suspend fun handle(r: NotificationListener.Recent) {
        // Universal cheap drops first — bail before any store lookup.
        if (r.ongoing) return
        if (r.packageName == ctx.packageName) return
        val title = r.title?.trim().orEmpty()
        val body = r.text?.trim().orEmpty()
        if (title.isEmpty() || body.isEmpty()) return

        // Master gate: autopilot off → no auto-paths fire at all.
        if (!autopilot.isEnabled()) return
        // Enterprise gate: enterprise app + enterprise autopilot off → skip.
        if (EnterpriseAutopilotStore.isEnterprise(r.packageName) && !entAutopilot.isEnabled()) {
            Log.d(TAG, "enterprise autopilot off — skipping ${r.packageName}")
            return
        }
        // Don't pile turns on a busy agent — overlapping streaming +
        // TTS reads back garbled audio.
        if (runner.busy.value) {
            Log.d(TAG, "agent busy — deferring incoming on ${r.packageName}")
            return
        }

        // Route 1: favorite match — explicit per-contact tone.
        val fav = favorites.matchByName(title)
        if (fav != null) {
            if (!fav.enabled) return
            if (fav.apps.isNotEmpty() && r.packageName !in fav.apps) {
                Log.d(TAG, "favorite ${fav.name} matched but pkg=${r.packageName} not in app allowlist")
                return
            }
            fireFavoriteReply(fav, body, r.packageName)
            return
        }

        // Route 2: non-favorite triage. Only when the user has opted
        // in. The triage prompt does the heavy lifting (decides yes/
        // no, mirrors tone, refuses URLs, drops groups) but we still
        // pre-filter obvious cases here to save tokens.
        if (!triage.isEnabled()) return

        if (looksLikeGroup(title, body)) {
            Log.d(TAG, "triage skip (group): title=$title body0='${body.take(40)}'")
            return
        }
        if (looksLikeAutomatedSender(title)) {
            Log.d(TAG, "triage skip (automated sender): title=$title")
            return
        }
        if (!isMessagingApp(r.packageName)) {
            // Only triage actual messaging-app notifications. App
            // notifications from random apps (Tinder match, news
            // alert, etc.) get the normal surface path.
            return
        }

        fireTriage(title, body, r.packageName)
    }

    private suspend fun fireFavoriteReply(fav: FavoritesStore.Favorite, body: String, pkg: String) {
        Log.d(TAG, "auto-reply firing: ${fav.name} via $pkg (tone=${fav.tone.label})")
        audit.logSystem("auto-reply trigger: ${fav.name} on $pkg tone=${fav.tone.label}")
        val turnText = buildString {
            append(AUTO_REPLY_PREFIX).append(' ')
            append("contact=").append(escape(fav.name)).append(' ')
            append("phone=").append(escape(fav.digits)).append(' ')
            append("app=").append(escape(pkg)).append(' ')
            append("tone=").append(fav.tone.label).append('\n')
            append("incoming: ").append(body)
        }
        runner.submit(text = turnText, fromVoice = false)
    }

    private suspend fun fireTriage(senderTitle: String, body: String, pkg: String) {
        Log.d(TAG, "triage firing: sender=$senderTitle via $pkg")
        audit.logSystem("auto-triage trigger: $senderTitle on $pkg")
        // No phone in the header — for non-favorites we don't have
        // the digits, and the model is allowed to call read_contact
        // to resolve them when composing a reply (if it decides to
        // reply at all).
        val turnText = buildString {
            append(AUTO_TRIAGE_PREFIX).append(' ')
            append("sender=").append(escape(senderTitle)).append(' ')
            append("app=").append(escape(pkg)).append('\n')
            append("incoming: ").append(body)
        }
        runner.submit(text = turnText, fromVoice = false)
    }

    // ── Heuristics ──────────────────────────────────────────────────────

    /**
     * Cheap group detector. Caches no state — pure on the title+body.
     * Conservative: we'd rather drop a genuine 1-on-1 than risk
     * auto-replying into a 5-person group thread.
     */
    private fun looksLikeGroup(title: String, body: String): Boolean {
        // Multi-recipient title — MMS group, or named WhatsApp group
        // with comma-separated participants.
        if (title.contains(',')) return true
        // "(N messages)" / "(N)" → notification summary covering many
        // unread items, almost always a group catch-up.
        if (Regex("""\((\d+)\)|\((\d+)\s*messages?\)""", RegexOption.IGNORE_CASE).containsMatchIn(title)) return true
        // Body prefixed with "<Name>:" where Name != title → message
        // from a member inside a group chat (WhatsApp groups always
        // surface as "GroupName" title + "Member: msg" body).
        val m = Regex("""^([A-Za-z][A-Za-z0-9 ._'-]{0,30}):\s""").find(body)
        if (m != null) {
            val senderInBody = m.groupValues[1].trim()
            if (!title.equals(senderInBody, ignoreCase = true) &&
                !senderInBody.equals("You", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Numeric shortcodes (5-6 digit "senders") and obvious bot
     * patterns. Examples: "62951" (banking OTP), "AMAZON",
     * "VERIFY-MTM". When the title carries no letters or is uppercase
     * shouty, almost certainly automated.
     */
    private fun looksLikeAutomatedSender(title: String): Boolean {
        if (title.isBlank()) return true
        // Pure-digit short sender → carrier shortcode.
        if (title.all { it.isDigit() } && title.length in 4..7) return true
        // All-caps with no spaces or only special separators → brand
        // tag like "AMAZON" / "VERIFY-NOW".
        val allUpperNoSpace = title.uppercase() == title && !title.contains(' ')
        if (allUpperNoSpace && title.length in 3..20 && title.any { it.isLetter() }) return true
        return false
    }

    /**
     * Restricted to known messaging apps so the triage path doesn't
     * try to "reply" to a Tinder match notification or a YouTube
     * comment alert. Conservative allowlist — extend as needed.
     */
    private fun isMessagingApp(pkg: String): Boolean = pkg in MESSAGING_APPS

    private fun escape(s: String): String = s.replace(' ', '_').replace('=', '_')

    companion object {
        private const val TAG = "Mythara/AutoReply"

        /** Header tag for favorite-matched auto-replies. */
        const val AUTO_REPLY_PREFIX = "[auto-reply]"

        /** Header tag for non-favorite smart-triage turns. */
        const val AUTO_TRIAGE_PREFIX = "[auto-triage]"

        /** Messaging-app allowlist for the triage route. */
        private val MESSAGING_APPS: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "org.thoughtcrime.securesms", // Signal
            "org.telegram.messenger",
            "com.facebook.orca", // Messenger
            // Enterprise messengers covered by the enterprise gate
            // upstream, listed here so they're still reachable when
            // enterprise autopilot is on.
            "com.microsoft.teams",
            "com.microsoft.teams2",
            "com.Slack",
            "com.slack",
            "com.google.android.apps.dynamite",
        )
    }
}
