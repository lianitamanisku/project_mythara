package com.mythara.minimax.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MiniMax error envelope. In practice the OpenAI-compat endpoint returns
 * an Anthropic-shaped error body:
 *
 *   {
 *     "type": "error",
 *     "error": {
 *       "type": "bad_request_error",
 *       "message": "invalid params, invalid tool type: (2013)",
 *       "http_code": "400"
 *     },
 *     "request_id": "..."
 *   }
 *
 * The numeric MiniMax code (e.g. `2013`, `2049`) is embedded inside the
 * `message` string in parentheses — there's no dedicated `code` field.
 * `ErrorMapper` extracts it via regex so we can map to a useful UX string.
 *
 * Native endpoints (T2A non-stream, STT, image gen) use a different
 * shape: `{"base_resp":{"status_code":2049,"status_msg":"..."}}`. Add a
 * parallel decoder if we ever call those.
 */
@Serializable
data class ErrorEnvelope(
    val type: String? = null,
    val error: ErrorBody? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
data class ErrorBody(
    val message: String? = null,
    val type: String? = null,
    /** Present on OpenAI-shaped error responses. MiniMax usually omits it. */
    val code: String? = null,
    @SerialName("http_code") val httpCode: String? = null,
    @SerialName("param") val param: String? = null,
)
