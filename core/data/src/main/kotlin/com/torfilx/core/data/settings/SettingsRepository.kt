package com.torfilx.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.torfilx.core.common.di.Dispatcher
import com.torfilx.core.common.di.TorfilxDispatcher
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.MetadataTimeout
import com.torfilx.core.model.StreamingMode
import com.torfilx.core.model.QualityPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Settings"

/**
 * User settings (plan.md §8.3).
 *
 * The API token is *not* stored here — it lives in [SecureTokenStore] so it is encrypted at rest.
 *
 * Nothing here touches disk while the object is being constructed. Hilt builds this singleton on the
 * main thread inside `MainActivity.onCreate`, and opening a DataStore there cost a Fire OS 5 stick
 * its first frame: the activity never finished `onCreate`, so the app showed a black screen forever.
 * The store is created on first *collection* instead, on [ioDispatcher].
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(TorfilxDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
            // Without this a corrupt preferences file throws on every read AND write — the reads are
            // caught below and fall back to defaults, but the writes were not, so the user could
            // never change a setting again. Replacing the corrupt file with empty preferences turns
            // a permanent lockout into a one-time reset to defaults.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("torfilx_settings") },
        )
    }

    /**
     * Cold: [dataStore] is not built until something collects, which never happens on the main
     * thread. Every flow below is derived from this one.
     */
    private val preferences: Flow<Preferences> = flow { emitAll(dataStore.data) }

    private object Keys {
        val AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        val SUBTITLES_ON = booleanPreferencesKey("subtitles_on")
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val QUALITY = stringPreferencesKey("quality")
        val FRAME_RATE_MATCHING = booleanPreferencesKey("frame_rate_matching")
        val TUNNELED_PLAYBACK = booleanPreferencesKey("tunneled_playback")
        val SKIP_INTRO_AUTO = booleanPreferencesKey("skip_intro_auto")
        val SHARING_CONSENT = booleanPreferencesKey("sharing_consent")
        val SHARING_CONSENT_SEEN = booleanPreferencesKey("sharing_consent_seen")
        val SEEDING_ENABLED = booleanPreferencesKey("seeding_enabled")
        val STORAGE_FRACTION = stringPreferencesKey("storage_fraction")
        val USE_DHT = booleanPreferencesKey("use_dht")
        val USE_EXTRA_TRACKERS = booleanPreferencesKey("use_extra_trackers")
        val METADATA_TIMEOUT = stringPreferencesKey("metadata_timeout")
        val STREAMING_MODE = stringPreferencesKey("streaming_mode")
        val FORCE_SOFTWARE_DECODER = booleanPreferencesKey("force_software_decoder")
    }

    val settings: Flow<AppSettings> = preferences
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
                useDht = prefs[Keys.USE_DHT] ?: true,
                useExtraTrackers = prefs[Keys.USE_EXTRA_TRACKERS] ?: true,
                metadataTimeout = prefs[Keys.METADATA_TIMEOUT]?.let { name ->
                    runCatching { MetadataTimeout.valueOf(name) }.getOrNull()
                } ?: MetadataTimeout.STANDARD,
                streamingMode = prefs[Keys.STREAMING_MODE]?.let { name ->
                    runCatching { StreamingMode.valueOf(name) }.getOrNull()
                } ?: StreamingMode.SEQUENTIAL,
                forceSoftwareDecoder = prefs[Keys.FORCE_SOFTWARE_DECODER] ?: false,
            )
        }

    /**
     * Whether the user has agreed to share (upload) while watching over BitTorrent.
     *
     * Default false, and nothing torrent-related runs until it is true: uploading redistributes
     * whatever is being watched, which is the user.s call, not the app.s.
     */
    val sharingConsent: Flow<Boolean> = preferences
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.SHARING_CONSENT] ?: false }

    /** True once the first-run sharing screen has been answered either way. */
    val sharingConsentAnswered: Flow<Boolean> = preferences
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.SHARING_CONSENT_SEEN] ?: false }

    /** Keep seeding after playback finishes, within the storage budget. */
    val seedingEnabled: Flow<Boolean> = preferences
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.SEEDING_ENABLED] ?: true }

    /** Fraction of free space the torrent cache may use. */
    val storageFraction: Flow<Float> = preferences
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.STORAGE_FRACTION]?.toFloatOrNull() ?: DEFAULT_STORAGE_FRACTION }

    suspend fun setAudioLanguage(language: String?) = editNullable(Keys.AUDIO_LANGUAGE, language)
    suspend fun setSubtitleLanguage(language: String?) = editNullable(Keys.SUBTITLE_LANGUAGE, language)
    suspend fun setSubtitlesEnabled(enabled: Boolean) = edit { it[Keys.SUBTITLES_ON] = enabled }
    suspend fun setAutoplayNext(enabled: Boolean) = edit { it[Keys.AUTOPLAY_NEXT] = enabled }
    suspend fun setQuality(preference: QualityPreference) = edit { it[Keys.QUALITY] = preference.name }
    suspend fun setFrameRateMatching(enabled: Boolean) = edit { it[Keys.FRAME_RATE_MATCHING] = enabled }
    suspend fun setTunneledPlayback(enabled: Boolean) = edit { it[Keys.TUNNELED_PLAYBACK] = enabled }
    suspend fun setSkipIntroAutomatically(enabled: Boolean) = edit { it[Keys.SKIP_INTRO_AUTO] = enabled }
    suspend fun setSharingConsent(consented: Boolean) = edit {
        it[Keys.SHARING_CONSENT] = consented
        it[Keys.SHARING_CONSENT_SEEN] = true
    }

    suspend fun setSeedingEnabled(enabled: Boolean) = edit { it[Keys.SEEDING_ENABLED] = enabled }

    suspend fun setStorageFraction(fraction: Float) = edit {
        it[Keys.STORAGE_FRACTION] = fraction.coerceIn(0.1f, 0.9f).toString()
    }

    suspend fun setUseDht(enabled: Boolean) = edit { it[Keys.USE_DHT] = enabled }
    suspend fun setUseExtraTrackers(enabled: Boolean) = edit { it[Keys.USE_EXTRA_TRACKERS] = enabled }
    suspend fun setMetadataTimeout(value: MetadataTimeout) = edit { it[Keys.METADATA_TIMEOUT] = value.name }
    suspend fun setStreamingMode(mode: StreamingMode) = edit { it[Keys.STREAMING_MODE] = mode.name }
    suspend fun setForceSoftwareDecoder(enabled: Boolean) =
        edit { it[Keys.FORCE_SOFTWARE_DECODER] = enabled }

    // Synchronous snapshots for the torrent engine and player factory, which read configuration from
    // inside native callbacks and construction paths that cannot suspend. Kept current by the layer
    // that observes the flows above (see TorrentCoordinator).
    var cachedSharingConsent: Boolean = false
        internal set
    var cachedUseDht: Boolean = true
        internal set
    var cachedUseExtraTrackers: Boolean = true
        internal set
    var cachedMetadataTimeoutSeconds: Int = MetadataTimeout.STANDARD.seconds
        internal set

    companion object {
        const val DEFAULT_STORAGE_FRACTION = 0.5f
    }

    // Writes are moved to the IO dispatcher explicitly: a setter called from a screen runs on the
    // main thread, and that is where the store would otherwise be opened for the first time.
    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        withContext(ioDispatcher) { dataStore.edit(block) }
    }

    private suspend fun editNullable(key: Preferences.Key<String>, value: String?) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
            }
        }
    }
}
