package com.torfilx.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.torfilx.core.common.log.TorfilxLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TokenStore"
private const val ENCRYPTED_FILE = "torfilx_secure"
private const val PLAIN_FALLBACK_FILE = "torfilx_secure_fallback"
private const val KEY_TOKEN = "api_token"

/**
 * Stores the API token encrypted at rest.
 *
 * Keystore-backed encryption occasionally fails on cheap devices with a broken keystore
 * implementation. Rather than crashing at startup, the store falls back to plain preferences and
 * logs it — the token only grants access to a media server on the user's own LAN, so a hard failure
 * would be worse than the degraded storage.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences by lazy { createPreferences() }

    @Volatile
    private var cached: String? = null

    @Volatile
    private var cacheLoaded: Boolean = false

    var token: String?
        get() {
            if (!cacheLoaded) {
                cached = runCatching { prefs.getString(KEY_TOKEN, null) }.getOrNull()
                cacheLoaded = true
            }
            return cached?.takeIf { it.isNotBlank() }
        }
        set(value) {
            cached = value?.takeIf { it.isNotBlank() }
            cacheLoaded = true
            runCatching {
                prefs.edit().apply {
                    if (cached == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, cached)
                }.apply()
            }.onFailure { TorfilxLog.e(TAG, "Failed to persist API token", it) }
        }

    private fun createPreferences(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Exception) {
        TorfilxLog.e(TAG, "Encrypted storage unavailable; falling back to plain preferences", error)
        context.getSharedPreferences(PLAIN_FALLBACK_FILE, Context.MODE_PRIVATE)
    }
}
