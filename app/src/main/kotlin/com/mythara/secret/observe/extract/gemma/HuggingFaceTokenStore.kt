package com.mythara.secret.observe.extract.gemma

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted persistence for the user's Hugging Face access token.
 * Used by [GemmaModelStore] to set `Authorization: Bearer <token>`
 * on direct-URL downloads — bypasses HF's license-gating 401 wall
 * for Gemma without forcing the manual-import workflow.
 *
 * Same posture as the MiniMax key + GitHub PAT stores:
 *   - DataStore preferences hold base64(AEAD-encrypted-token)
 *   - Wrapping key lives in Android Keystore via Tink
 *   - Token never appears in plain form on disk or in logs
 *   - Token is never sent anywhere except the `Authorization` header
 *     on requests to huggingface.co
 *
 * Optional — when not set, GemmaModelStore falls back to anonymous
 * HTTP (works for non-gated mirrors / models). When set, HF accepts
 * the request regardless of license-acceptance status as long as the
 * authenticated user has accepted the Gemma license once on the
 * model's web page.
 */
@Singleton
class HuggingFaceTokenStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_huggingface_settings")

    private val keyEncrypted = stringPreferencesKey("token.encrypted")

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, "mythara_hf_keyset", "mythara_hf_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://mythara_hf_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    suspend fun token(): String? = ctx.dataStore.data.first()[keyEncrypted]?.let { tryDecrypt(it) }

    fun tokenFlow(): Flow<String?> = ctx.dataStore.data.map { prefs ->
        prefs[keyEncrypted]?.let { tryDecrypt(it) }
    }

    suspend fun setToken(plain: String) {
        val ct = aead.encrypt(plain.trim().toByteArray(Charsets.UTF_8), null)
        ctx.dataStore.edit { it[keyEncrypted] = Base64.encodeToString(ct, Base64.NO_WRAP) }
    }

    suspend fun clear() {
        ctx.dataStore.edit { it.remove(keyEncrypted) }
    }

    private fun tryDecrypt(b64: String): String? = runCatching {
        val pt = aead.decrypt(Base64.decode(b64, Base64.NO_WRAP), null)
        String(pt, Charsets.UTF_8)
    }.getOrNull()
}
