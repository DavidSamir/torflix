package com.torfilx.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.QualityPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Settings"

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "torfilx_settings")

/**
 * User settings (plan.md §8.3).
 *
 * The API token is *not* stored here — it lives in [SecureTokenStore] so it is encrypted at rest.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: SecureTokenStore,
) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val SUBTITLES_ON = booleanPreferencesKey("subtitles_on")
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val QUALITY = stringPreferencesKey("quality")
        val FRAME_RATE_MATCHING = booleanPreferencesKey("frame_rate_matching")
        val TUNNELED_PLAYBACK = booleanPreferencesKey("tunneled_playback")
        val SKIP_INTRO_AUTO = booleanPreferencesKey("skip_intro_auto")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val SHARING_CONSENT = booleanPreferencesKey("sharing_consent")
        val SHARING_CONSENT_SEEN = booleanPreferencesKey("sharing_consent_seen")
        val SEEDING_ENABLED = booleanPreferencesKey("seeding_enabled")
        val STORAGE_FRACTION = stringPreferencesKey("storage_fraction")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { throwable ->
            // A corrupted preferences file must not brick the app; fall back to defaults.
            if (throwable is IOException) {
                TorfilxLog.w(TAG, "Settings unreadable, using defaults", throwable)
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs ->
            AppSettings(
                serverUrl = prefs[Keys.SERVER_URL].orEmpty(),
                preferredAudioLanguage = prefs[Keys.AUDIO_LANGUAGE],
                preferredSubtitleLanguage = prefs[Keys.SUBTITLE_LANGUAGE],
                subtitlesEnabledByDefault = prefs[Keys.SUBTITLES_ON] ?: false,
                autoplayNextEpisode = prefs[Keys.AUTOPLAY_NEXT] ?: true,
                quality = prefs[Keys.QUALITY]?.let { name ->
                    runCatching { QualityPreference.valueOf(name) }.getOrNull()
                } ?: QualityPreference.AUTO,
                frameRateMatching = prefs[Keys.FRAME_RATE_MATCHING] ?: true,
                tunneledPlayback = prefs[Keys.TUNNELED_PLAYBACK] ?: true,
                skipIntroAutomatically = prefs[Keys.SKIP_INTRO_AUTO] ?: false,
            )
        }

    /**
     * Whether the user has agreed to share (upload) while watching over BitTorrent.
     *
     * Default false, and nothing torrent-related runs until it is true: uploading redistributes
     * whatever is being watched, which is the user.s call, not the app.s.
     */
    val sharingConsent: Flow<Boolean> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.SHARING_CONSENT] ?: false }

    /** True once the first-run sharing screen has been answered either way. */
    val sharingConsentAnswered: Flow<Boolean> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.SHARING_CONSENT_SEEN] ?: false }

    /** Keep seeding after playback finishes, within the storage budget. */
    val seedingEnabled: Flow<Boolean> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.SEEDING_ENABLED] ?: true }

    /** Fraction of free space the torrent cache may use. */
    val storageFraction: Flow<Float> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.STORAGE_FRACTION]?.toFloatOrNull() ?: DEFAULT_STORAGE_FRACTION }

    val demoMode: Flow<Boolean> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.DEMO_MODE] ?: false }

    suspend fun setServerUrl(url: String) = edit { it[Keys.SERVER_URL] = url }
    suspend fun setAudioLanguage(language: String?) = editNullable(Keys.AUDIO_LANGUAGE, language)
    suspend fun setSubtitleLanguage(language: String?) = editNullable(Keys.SUBTITLE_LANGUAGE, language)
    suspend fun setSubtitlesEnabled(enabled: Boolean) = edit { it[Keys.SUBTITLES_ON] = enabled }
    suspend fun setAutoplayNext(enabled: Boolean) = edit { it[Keys.AUTOPLAY_NEXT] = enabled }
    suspend fun setQuality(preference: QualityPreference) = edit { it[Keys.QUALITY] = preference.name }
    suspend fun setFrameRateMatching(enabled: Boolean) = edit { it[Keys.FRAME_RATE_MATCHING] = enabled }
    suspend fun setTunneledPlayback(enabled: Boolean) = edit { it[Keys.TUNNELED_PLAYBACK] = enabled }
    suspend fun setSkipIntroAutomatically(enabled: Boolean) = edit { it[Keys.SKIP_INTRO_AUTO] = enabled }
    suspend fun setDemoMode(enabled: Boolean) = edit { it[Keys.DEMO_MODE] = enabled }

    suspend fun setSharingConsent(consented: Boolean) = edit {
        it[Keys.SHARING_CONSENT] = consented
        it[Keys.SHARING_CONSENT_SEEN] = true
    }

    suspend fun setSeedingEnabled(enabled: Boolean) = edit { it[Keys.SEEDING_ENABLED] = enabled }

    suspend fun setStorageFraction(fraction: Float) = edit {
        it[Keys.STORAGE_FRACTION] = fraction.coerceIn(0.1f, 0.9f).toString()
    }

    /** Synchronous read for the torrent engine, which cannot suspend inside libtorrent callbacks. */
    
    var cachedSharingConsent: Boolean = false
        internal set

    fun apiToken(): String? = tokenStore.token

    fun setApiToken(token: String?) {
        tokenStore.token = token
    }

    companion object {
        const val DEFAULT_STORAGE_FRACTION = 0.5f
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private suspend fun editNullable(key: Preferences.Key<String>, value: String?) {
        context.settingsDataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
        }
    }
}
