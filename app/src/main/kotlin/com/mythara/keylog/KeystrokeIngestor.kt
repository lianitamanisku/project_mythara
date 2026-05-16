package com.mythara.keylog

import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.mythara.behavior.BehaviorEventStore
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receives raw [AccessibilityEvent]s of type
 * `TYPE_VIEW_TEXT_CHANGED` from
 * [com.mythara.services.PhoneControlAccessibilityService] and,
 * when the user has explicitly opted in via [KeyLearnStore],
 * writes a redacted record of the typed text to the learning
 * vault for the daily-summariser to pattern-mine.
 *
 * This is the "always-learn from keystrokes across every app"
 * pipeline the user explicitly asked for. It's gated behind a
 * default-off toggle because the privacy implications are
 * larger than any other learning input Mythara has.
 *
 * Filters applied BEFORE writing to vault:
 *   1. Toggle off → drop everything (cheap fast-path, no
 *      ingestion cost when disabled).
 *   2. Self events (eventPackage == our package) → drop. Any
 *      text the user types into Mythara's own surfaces is
 *      already captured via the chat history + observe-mode
 *      paths; double-recording it here would inflate counts.
 *   3. Password-bit set on the source field's input type → drop.
 *      Per Android's contract, any field with
 *      InputType.TYPE_TEXT_VARIATION_PASSWORD or
 *      TYPE_NUMBER_VARIATION_PASSWORD must NEVER be persisted.
 *   4. Field id-name heuristic match against PASSWORDISH_NAMES
 *      → drop. Catches password fields whose input type is
 *      generic (a surprising number of older apps ship these).
 *   5. Very short text (<3 chars) → drop. No learning value
 *      in single keystrokes; dropping them slashes write
 *      volume by ~10x.
 *   6. Blocked-app list (banking / payment / health portal /
 *      etc.) — defer to [com.mythara.agent.CriticalActionGuard]'s
 *      blocked-apps list. Same allowlist that prevents
 *      automation tools from acting in those apps; same
 *      threshold for capturing text.
 *   7. Truncate to MAX_LEN chars per write — most legitimate
 *      use cases (compose box, search bar) fit in 600 chars,
 *      and capping bounds vault row size.
 *
 * Surviving rows go into the vault tagged
 * `behavior:keystroke + behavior-event` so the
 * BehaviorLearningSummarizer's daily pass picks them up + the
 * 24h prune sweep clears them. They DO NOT survive past one
 * summariser run by design — the durable artifact is the
 * synthesised summary, not the raw keystroke stream.
 */
@Singleton
class KeystrokeIngestor @Inject constructor(
    private val store: KeyLearnStore,
    private val vault: LearningVault,
    @ApplicationContext private val ctx: Context,
) {
    /**
     * Cheap fast-path called from
     * [com.mythara.services.PhoneControlAccessibilityService.onAccessibilityEvent]
     * — returns immediately when the toggle is off or the event
     * isn't text-changed. Only the survivors do any work.
     */
    suspend fun handle(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (!store.isEnabled()) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == ctx.packageName) return

        // Hardcoded sensitive-app deny list — banking, payment,
        // password managers, health portals. The user can't opt
        // any of these IN even with the global keylogging toggle
        // on; the privacy floor is non-negotiable.
        if (SENSITIVE_PACKAGES.any { pkg.startsWith(it) }) {
            Log.d(TAG, "skip sensitive-app keystrokes from $pkg")
            return
        }

        // Source-field metadata. Defensive — getSource() can
        // return null on some Android builds.
        val source = runCatching { event.source }.getOrNull()
        try {
            val sourceInputType = source?.inputType ?: 0
            val viewIdName = source?.viewIdResourceName.orEmpty()
            val isPassword = source?.isPassword == true ||
                isPasswordInputType(sourceInputType) ||
                isPasswordishViewId(viewIdName)
            if (isPassword) {
                Log.d(TAG, "skip password field keystrokes from $pkg")
                return
            }

            val text = (event.text?.joinToString(" ") ?: "").trim()
            if (text.length < MIN_LEN) return
            val capped = text.take(MAX_LEN)

            runCatching {
                vault.add(
                    content = "[$pkg${if (viewIdName.isNotBlank()) " · $viewIdName" else ""}] $capped",
                    tier = Tier.Working,
                    src = "behavior:keystroke",
                    facets = listOf(
                        BehaviorEventStore.FACET_KIND,
                        "behavior:keystroke",
                        "app:$pkg",
                    ),
                    conf = 0.7,
                )
            }.onFailure { Log.w(TAG, "vault.add keystroke failed: ${it.message}") }
        } finally {
            // AccessibilityNodeInfo is recyclable; not recycling
            // it leaks system resources (and on some OEM builds
            // can crash the service).
            runCatching { source?.recycle() }
        }
    }

    private fun isPasswordInputType(type: Int): Boolean {
        if (type == 0) return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        val klass = type and InputType.TYPE_MASK_CLASS
        return when {
            klass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                ) -> true
            klass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            else -> false
        }
    }

    private fun isPasswordishViewId(name: String): Boolean {
        if (name.isBlank()) return false
        val n = name.lowercase()
        return PASSWORDISH_NAMES.any { n.contains(it) }
    }

    companion object {
        private const val TAG = "Mythara/Keystrokes"

        /** Keystrokes shorter than this are skipped — single
         *  characters carry no learning signal and inflate
         *  vault size 10x. */
        private const val MIN_LEN = 3

        /** Per-write cap. Compose boxes + search bars fit
         *  comfortably; longer text gets clipped. */
        private const val MAX_LEN = 600

        /** Field-id substrings that almost-always indicate a
         *  password / OTP / PIN field even when the input type
         *  is generic. Conservative — false positives just drop
         *  legitimate text; false negatives could leak secrets. */
        private val PASSWORDISH_NAMES = setOf(
            "password", "passwd", "pwd", "pin", "otp",
            "secret", "passphrase", "verification_code",
            "auth_code", "security_code", "credit_card",
            "card_number", "cvv", "cvc",
        )

        /** Hardcoded sensitive-app prefixes — text typed in any
         *  of these is NEVER ingested regardless of toggle state.
         *  Covers Indian + US banking, password managers, health
         *  portals, payment apps. Add to this list as new
         *  high-sensitivity apps are identified. */
        private val SENSITIVE_PACKAGES = setOf(
            // Banking
            "com.chase", "com.wf.wellsfargomobile", "com.bofa",
            "com.citi", "com.capitalone", "com.americanexpress",
            "com.discover", "com.usaa", "com.snapwood.suprema",
            "com.icicibank", "com.hdfc", "com.axis", "com.sbi",
            "net.one97.paytm", "in.org.npci.upiapp",
            // Payment / wallet
            "com.google.android.apps.walletnfcrel", "com.venmo",
            "com.zellepay", "com.cashapp", "com.paypal",
            // Password managers
            "com.lastpass.lpandroid", "com.dashlane.frozenapp",
            "com.bitwarden.android", "com.x8bit.bitwarden",
            "com.onepassword", "com.agilebits.onepassword",
            "com.keeper", "com.duosecurity.duomobile",
            // Health portals
            "com.epic.mychart", "com.kp.consumer",
        )
    }
}
