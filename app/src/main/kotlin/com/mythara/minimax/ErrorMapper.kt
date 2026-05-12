package com.mythara.minimax

import com.mythara.minimax.models.ErrorEnvelope
import kotlinx.serialization.json.Json

/**
 * Maps MiniMax error codes (numeric strings on the OpenAI-compat path) to
 * UX-ready strings. The error codes themselves are stable; the human
 * messages live here so we can adjust copy without touching call sites.
 *
 * Codes verified against MiniMax docs (https://platform.minimax.io/docs/api-reference/errorcode)
 * and the ones we'll hit most in practice — auth + balance + rate.
 */
object ErrorMapper {

    /**
     * A non-2xx response from MiniMax. Includes the raw HTTP status, the
     * decoded numeric code (or null), and a user-presentable message.
     */
    data class Mapped(val httpStatus: Int, val code: String?, val message: String) {
        val isRetryable: Boolean
            get() = code in RETRYABLE_CODES
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val RETRYABLE_CODES = setOf(
        "1000",     // network glitch
        "1001",     // timeout
        "1002",     // RPM rate limit
        "1024",     // transient internal
        "1033",     // transient internal
        "1039",     // TPM rate limit
        "1041",     // concurrent connection limit
    )

    fun fromHttp(status: Int, body: String?): Mapped {
        val parsed = body?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<ErrorEnvelope>(it).error }.getOrNull()
        }
        // MiniMax embeds the numeric code in the message string like
        // "invalid params, invalid tool type:  (2013)". Pull it out so we
        // can map to a useful UX. OpenAI-shaped responses set `code`
        // directly; we honour that first.
        val msg = parsed?.message
        val code = parsed?.code ?: msg?.let { CODE_IN_MESSAGE.find(it)?.groupValues?.get(1) }
        return Mapped(status, code, humanFor(status, code, msg))
    }

    /** Matches the numeric code in `… (2013)` / `… (2049)` style messages. */
    private val CODE_IN_MESSAGE = Regex("""\((\d{4,5})\)""")

    private fun humanFor(status: Int, code: String?, raw: String?): String = when (code) {
        "1004" -> "API key missing or malformed. Re-paste it in Settings → MiniMax."
        "2049" -> "API key rejected. Confirm you copied from the correct region (Global vs China)."
        "1008" -> "MiniMax account is out of credits. Top up at minimax.io and try again."
        "1002", "1039" -> "MiniMax is rate-limiting. Retrying with backoff."
        "1041" -> "Too many concurrent connections to MiniMax. One moment…"
        "2013", "1013" -> "Invalid request — Mythara sent a malformed body. (Bug — please report.)"
        "1026", "1027" -> "Message blocked by MiniMax safety filter."
        "2056" -> "Plan usage limit reached. Top up or wait for the quota to reset."
        null -> when (status) {
            401 -> "Authentication failed. Check your API key in Settings."
            403 -> "Account doesn't have access to this endpoint."
            404 -> "Endpoint not found — wrong region selected?"
            in 500..599 -> "MiniMax is having a bad day (${status}). Try again shortly."
            else -> raw ?: "Request failed (HTTP $status)."
        }
        else -> raw?.let { "MiniMax error $code: $it" } ?: "MiniMax returned error $code."
    }
}
