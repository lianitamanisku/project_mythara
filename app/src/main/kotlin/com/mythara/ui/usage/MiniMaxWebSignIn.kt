package com.mythara.ui.usage

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.SettingsStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * MiniMax web sign-in screen. The platform's `coding_plan/remains`
 * endpoint can be authenticated either by a long-lived API Bearer
 * key (limited scope) OR by the user's signed-in browser session
 * cookies (full token-plan view, same data the
 * platform.minimax.io/user-center/payment/token-plan dashboard
 * shows).
 *
 * The Bearer path is wired in [MiniMaxUsageClient] from day one.
 * This screen captures the SECOND auth path:
 *
 *   1. Embed a Compose [WebView] pointing at platform.minimax.io
 *   2. User signs in with their normal credentials inside the WebView
 *   3. Once the page redirects to /user-center/... (= signed in),
 *      we extract the session cookies via [CookieManager]
 *   4. Persist `_token` (JWT) + `minimax_group_id_v2` encrypted
 *      to [SettingsStore.setMiniMaxWebSession]
 *   5. Caller pops back to the Usage screen, which now uses the
 *      cookie auth path on the next refresh
 *
 * Caveat called out to the user before launching: this routes the
 * user's own web session through Mythara — not a TOS-clean path
 * for a published app, but fine for personal-use launchers like
 * this one. We do NOT capture credentials, only the cookies the
 * platform ITSELF set after a successful normal login.
 *
 * The JWT is decoded client-side just to extract `exp` (expiry
 * timestamp) so we can persist + later show "session expires in
 * Xd". HS256 signature verification is not attempted — that
 * requires MiniMax's secret which we don't have. Server-side the
 * cookie is rejected anyway if tampered with.
 */
@HiltViewModel
class MiniMaxAuthViewModel @Inject constructor(
    private val settings: SettingsStore,
) : ViewModel() {

    fun saveSession(token: String, groupId: String, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val expiresAt = parseJwtExpiryMs(token) ?: (System.currentTimeMillis() + DEFAULT_EXPIRY_MS)
            settings.setMiniMaxWebSession(
                SettingsStore.MiniMaxWebSession(
                    token = token,
                    groupId = groupId,
                    expiresAtMs = expiresAt,
                ),
            )
            onDone(expiresAt)
        }
    }

    fun clear(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.clearMiniMaxWebSession()
            onDone()
        }
    }

    companion object {
        /** Fallback expiry when the JWT can't be decoded (unsigned
         *  / malformed / library can't parse). 30 days matches the
         *  observed MiniMax JWT lifetime as of this writing. */
        const val DEFAULT_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000

        /** Decode a JWT's `exp` claim (in seconds since epoch) and
         *  return millis since epoch. Returns null if the JWT is
         *  malformed. We only trust this for UX purposes (the
         *  "expires in Xd" copy); the server is the actual auth
         *  authority. */
        fun parseJwtExpiryMs(jwt: String): Long? = runCatching {
            val parts = jwt.split('.')
            if (parts.size < 2) return@runCatching null
            // Standard base64url padded → URL_SAFE | NO_PADDING |
            // NO_WRAP. Re-pad before decoding.
            val payloadB64 = parts[1].padEnd((parts[1].length + 3) / 4 * 4, '=')
            val bytes = Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_WRAP)
            val payload = String(bytes, Charsets.UTF_8)
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(payload).jsonObject
            val expSec = obj["exp"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@runCatching null
            expSec * 1000L
        }.getOrNull()
    }
}

@Composable
fun MiniMaxWebSignInScreen(
    onBack: () -> Unit,
    vm: MiniMaxAuthViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    var capturedStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            TextButton(onClick = {
                vm.clear { capturedStatus = "session cleared" }
            }) {
                Text("clear session", color = MytharaColors.Charple)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "MINIMAX SIGN-IN",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = MytharaColors.Fg, letterSpacing = 3.sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${Glyph.AccentBar} Sign in to your MiniMax web account below. " +
                    "Mythara captures the session cookies after login (we do NOT see " +
                    "your credentials) so the Usage screen can show the same data " +
                    "your platform dashboard does.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
            )
            capturedStatus?.let { status ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${Glyph.DiamondFilled} $status",
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(MytharaColors.Surface)
                .padding(2.dp),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    @SuppressLint("SetJavaScriptEnabled")
                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 10 Pro) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = false  // let the WebView handle navigation

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                Log.d(TAG, "page finished: $url")
                                tryCaptureCookies(url) { token, groupId ->
                                    vm.saveSession(token, groupId) { expiresAtMs ->
                                        val daysLeft =
                                            (expiresAtMs - System.currentTimeMillis()) /
                                                (24L * 60 * 60 * 1000)
                                        capturedStatus =
                                            "session captured — expires in ~${daysLeft}d"
                                    }
                                }
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                    webView
                },
            )
        }
    }
}

/**
 * Inspect the cookie jar after a navigation completes; if we see
 * a `_token` and `minimax_group_id_v2` for platform.minimax.io,
 * pass them up so the ViewModel can persist them.
 *
 * We re-evaluate on every onPageFinished because the cookies are
 * set during the OAuth-style login redirect chain, not the final
 * landing page itself.
 */
private fun tryCaptureCookies(
    currentUrl: String,
    onCaptured: (token: String, groupId: String) -> Unit,
) {
    if (!currentUrl.contains("platform.minimax.io")) return
    val jar = CookieManager.getInstance().getCookie("https://platform.minimax.io") ?: return
    val tokens = jar.split(';').mapNotNull {
        val eq = it.indexOf('=')
        if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
    }.toMap()
    val token = tokens["_token"] ?: return
    val groupId = tokens["minimax_group_id_v2"] ?: return
    if (token.isBlank() || groupId.isBlank()) return
    Log.i(TAG, "captured MiniMax web session — token=${token.take(20)}… groupId=$groupId")
    onCaptured(token, groupId)
}

private const val LOGIN_URL = "https://platform.minimax.io/user-center/payment/token-plan"
private const val TAG = "Mythara/MiniMaxAuth"
