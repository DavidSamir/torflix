package com.myflix.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.myflix.core.common.log.MyflixLog
import com.myflix.core.model.AppSettings
import com.myflix.core.model.QualityPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Settings"

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "myflix_settings")

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
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { throwable ->
            // A corrupted preferences file must not brick the app; fall back to defaults.
            if (throwable is IOException) {
                MyflixLog.w(TAG, "Settings unreadable, using defaults", throwable)
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

    fun apiToken(): String? = tokenStore.token

    fun setApiToken(token: String?) {
        tokenStore.token = token
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
