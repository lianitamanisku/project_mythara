package com.mythara.data

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
import com.mythara.minimax.Region
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted persistence for the user's MiniMax API key + chosen region +
 * model. Uses Jetpack DataStore (preferences flavour) plus Google Tink
 * AEAD with the wrapping key in the Android Keystore — the modern
 * replacement for the now-deprecated EncryptedSharedPreferences.
 *
 * - The API key is base64(AEAD-encrypted-with-Tink) stored under
 *   `apiKey.encrypted`. Tink's [AndroidKeysetManager] handles key
 *   generation, rotation, and Keystore-backed wrapping.
 * - Region and model are stored in plaintext — not sensitive, and
 *   needed pre-decryption to render Settings.
 * - The Tink keyset itself lives in `mythara_master_keyset` SharedPreferences,
 *   wrapped by an Android Keystore key with alias `mythara_master_key`.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mythara_settings")

    private val keyApiKeyEncrypted = stringPreferencesKey("apiKey.encrypted")
    private val keyRegion          = stringPreferencesKey("region")
    private val keyModel           = stringPreferencesKey("model")
    // Gemini Developer API key — optional secondary credential used
    // exclusively for the take_photo vision route. When set, VisionService
    // routes captured images through Gemini instead of MiniMax-VL-01.
    // Lives in the same Tink-encrypted DataStore as the MiniMax key so
    // it inherits the same Keystore-backed at-rest protection.
    private val keyGeminiKeyEncrypted = stringPreferencesKey("geminiKey.encrypted")

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, "mythara_master_keyset", "mythara_master_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://mythara_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    fun apiKeyFlow(): Flow<String?> = ctx.dataStore.data.map { prefs ->
        prefs[keyApiKeyEncrypted]?.let { tryDecrypt(it) }
    }

    fun regionFlow(): Flow<Region> = ctx.dataStore.data.map { prefs ->
        Region.fromId(prefs[keyRegion])
    }

    fun modelFlow(): Flow<String> = ctx.dataStore.data.map { prefs ->
        prefs[keyModel] ?: DEFAULT_MODEL
    }

    suspend fun setApiKey(plain: String) {
        val ct = aead.encrypt(plain.toByteArray(Charsets.UTF_8), null)
        ctx.dataStore.edit { it[keyApiKeyEncrypted] = Base64.encodeToString(ct, Base64.NO_WRAP) }
    }

    /**
     * Read the Gemini vision key. Same Tink AEAD path as the MiniMax key.
     * Returns null if not set.
     */
    fun geminiKeyFlow(): Flow<String?> = ctx.dataStore.data.map { prefs ->
        prefs[keyGeminiKeyEncrypted]?.let { tryDecrypt(it) }
    }

    suspend fun setGeminiKey(plain: String) {
        if (plain.isBlank()) {
            ctx.dataStore.edit { it.remove(keyGeminiKeyEncrypted) }
            return
        }
        val ct = aead.encrypt(plain.toByteArray(Charsets.UTF_8), null)
        ctx.dataStore.edit { it[keyGeminiKeyEncrypted] = Base64.encodeToString(ct, Base64.NO_WRAP) }
    }

    suspend fun clearGeminiKey() {
        ctx.dataStore.edit { it.remove(keyGeminiKeyEncrypted) }
    }

    suspend fun setRegion(region: Region) {
        ctx.dataStore.edit { it[keyRegion] = region.name }
    }

    suspend fun setModel(model: String) {
        ctx.dataStore.edit { it[keyModel] = model }
    }

    /** Convenience: a snapshot of the trio for the network layer. */
    suspend fun snapshot(): Snapshot {
        val prefs = ctx.dataStore.data.first()
        return Snapshot(
            apiKey = prefs[keyApiKeyEncrypted]?.let { tryDecrypt(it) },
            region = Region.fromId(prefs[keyRegion]),
            model = prefs[keyModel] ?: DEFAULT_MODEL,
            geminiKey = prefs[keyGeminiKeyEncrypted]?.let { tryDecrypt(it) },
        )
    }

    private fun tryDecrypt(b64: String): String? = runCatching {
        val pt = aead.decrypt(Base64.decode(b64, Base64.NO_WRAP), null)
        String(pt, Charsets.UTF_8)
    }.getOrNull()

    data class Snapshot(
        val apiKey: String?,
        val region: Region,
        val model: String,
        /** Optional Gemini vision key. Null means we fall back to MiniMax-VL-01. */
        val geminiKey: String? = null,
    )

    companion object {
        /**
         * Default = M2.7. The function-calling guide
         * (platform.minimax.io/docs/guides/text-m2-function-call) explicitly
         * cites M2.7 for "exceptional Tool Use capabilities" — the right
         * default for an agentic runtime. Users who want faster/cheaper
         * can pick a highspeed or older variant in Settings.
         */
        const val DEFAULT_MODEL: String = "MiniMax-M2.7"

        /**
         * Models documented on /v1/chat/completions per the OpenAI-compat
         * spec page (text-chat-openai.md). M1 and VL-01 aren't listed on
         * this endpoint and are intentionally excluded.
         */
        val SUPPORTED_MODELS: List<String> = listOf(
            "MiniMax-M2.7",
            "MiniMax-M2.7-highspeed",
            "MiniMax-M2.5",
            "MiniMax-M2.5-highspeed",
            "MiniMax-M2.1",
            "MiniMax-M2.1-highspeed",
        )
    }
}
