package com.mythara.memory

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
 * Encrypted persistence for the user's GitHub Memory-Sync configuration.
 * Mirrors the [com.mythara.data.SettingsStore] pattern: the PAT is held
 * AEAD-encrypted in a DataStore preferences blob, the wrapping key lives
 * in the Android Keystore via Tink's [AndroidKeysetManager].
 *
 * The PAT never appears on disk in plain form; it's never logged; it's
 * never sent anywhere except the `Authorization` header of GitHub API
 * calls. Same posture as the MiniMax key.
 */
@Singleton
class MemorySettings @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mythara_memory_settings")

    // Encrypted token; everything else is plaintext.
    private val keyPatEncrypted   = stringPreferencesKey("github.pat.encrypted")
    private val keyOwner          = stringPreferencesKey("github.owner")
    private val keyRepo           = stringPreferencesKey("github.repo")
    private val keyBranch         = stringPreferencesKey("github.branch")

    // User-controlled scopes — opt in per data category.
    private val keySyncLearnings  = booleanPreferencesKey("sync.learnings")
    private val keySyncSettings   = booleanPreferencesKey("sync.settings")
    private val keySyncChat       = booleanPreferencesKey("sync.chat")
    private val keyEnabled        = booleanPreferencesKey("sync.enabled")

    // State tracking.
    private val keyLastSyncTs     = longPreferencesKey("sync.lastTs")
    private val keyManifestJson   = stringPreferencesKey("sync.manifestCache.json")

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, "mythara_memory_keyset", "mythara_memory_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://mythara_memory_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    data class Snapshot(
        val pat: String?,
        val owner: String,
        val repo: String,
        val branch: String,
        val enabled: Boolean,
        val syncLearnings: Boolean,
        val syncSettings: Boolean,
        val syncChat: Boolean,
        val lastSyncTs: Long,
        val manifestJson: String?,
    ) {
        val configured: Boolean get() = !pat.isNullOrBlank() && owner.isNotBlank() && repo.isNotBlank()
    }

    suspend fun snapshot(): Snapshot {
        val prefs = ctx.dataStore.data.first()
        return Snapshot(
            pat = prefs[keyPatEncrypted]?.let { tryDecrypt(it) },
            owner = prefs[keyOwner] ?: DEFAULT_OWNER,
            repo = prefs[keyRepo] ?: DEFAULT_REPO,
            branch = prefs[keyBranch] ?: DEFAULT_BRANCH,
            enabled = prefs[keyEnabled] ?: false,
            syncLearnings = prefs[keySyncLearnings] ?: true,
            syncSettings = prefs[keySyncSettings] ?: true,
            // Phase F — chat transcripts sync by default so a
            // fresh install / pm clear pulls full history from
            // peers. Privacy-conscious users can flip the
            // Settings toggle off.
            syncChat = prefs[keySyncChat] ?: true,
            lastSyncTs = prefs[keyLastSyncTs] ?: 0L,
            manifestJson = prefs[keyManifestJson],
        )
    }

    fun observe(): Flow<Snapshot> = ctx.dataStore.data.map { prefs ->
        Snapshot(
            pat = prefs[keyPatEncrypted]?.let { tryDecrypt(it) },
            owner = prefs[keyOwner] ?: DEFAULT_OWNER,
            repo = prefs[keyRepo] ?: DEFAULT_REPO,
            branch = prefs[keyBranch] ?: DEFAULT_BRANCH,
            enabled = prefs[keyEnabled] ?: false,
            syncLearnings = prefs[keySyncLearnings] ?: true,
            syncSettings = prefs[keySyncSettings] ?: true,
            // Phase F — chat transcripts sync by default so a
            // fresh install / pm clear pulls full history from
            // peers. Privacy-conscious users can flip the
            // Settings toggle off.
            syncChat = prefs[keySyncChat] ?: true,
            lastSyncTs = prefs[keyLastSyncTs] ?: 0L,
            manifestJson = prefs[keyManifestJson],
        )
    }

    suspend fun setPat(plain: String) {
        val ct = aead.encrypt(plain.toByteArray(Charsets.UTF_8), null)
        ctx.dataStore.edit { it[keyPatEncrypted] = Base64.encodeToString(ct, Base64.NO_WRAP) }
    }

    suspend fun setRepo(owner: String, repo: String, branch: String = DEFAULT_BRANCH) {
        ctx.dataStore.edit {
            it[keyOwner] = owner
            it[keyRepo] = repo
            it[keyBranch] = branch
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = enabled }
    }

    suspend fun setScopes(learnings: Boolean, settings: Boolean, chat: Boolean) {
        ctx.dataStore.edit {
            it[keySyncLearnings] = learnings
            it[keySyncSettings] = settings
            it[keySyncChat] = chat
        }
    }

    suspend fun setLastSyncTs(ts: Long) {
        ctx.dataStore.edit { it[keyLastSyncTs] = ts }
    }

    suspend fun setManifestJson(json: String) {
        ctx.dataStore.edit { it[keyManifestJson] = json }
    }

    suspend fun clearPat() {
        ctx.dataStore.edit { it.remove(keyPatEncrypted) }
    }

    private fun tryDecrypt(b64: String): String? = runCatching {
        val pt = aead.decrypt(Base64.decode(b64, Base64.NO_WRAP), null)
        String(pt, Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val DEFAULT_OWNER = "ankurCES"
        const val DEFAULT_REPO = "mythara_memory"
        const val DEFAULT_BRANCH = "main"
    }
}
