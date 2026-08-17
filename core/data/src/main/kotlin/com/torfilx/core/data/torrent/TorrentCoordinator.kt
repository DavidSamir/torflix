package com.torfilx.core.data.torrent

import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.torrent.LibTorrentEngine
import com.torfilx.core.torrent.SharingConsentProvider
import com.torfilx.core.torrent.SharingStats
import com.torfilx.core.torrent.StorageBudget
import com.torfilx.core.torrent.TorrentConfigProvider
import com.torfilx.core.torrent.TorrentEngine
import com.torfilx.core.torrent.TorrentStream
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TorrentCoord"

/**
 * Applies user settings to the torrent engine and exposes it to the rest of the app.
 *
 * The engine deliberately knows nothing about DataStore; this is the only place where the sharing
 * consent, the seeding switch and the storage budget are translated into engine behaviour.
 */
@Singleton
class TorrentCoordinator @Inject constructor(
    private val engine: TorrentEngine,
    private val libTorrentEngine: LibTorrentEngine,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    val stats: Flow<SharingStats> = engine.stats

    init {
        settingsRepository.sharingConsent
            .onEach { consented ->
                settingsRepository.cachedSharingConsent = consented
                libTorrentEngine.onConsentChanged(consented)
                if (!consented) {
                    // Withdrawing consent stops sharing immediately and gives the disk back.
                    runCatching { engine.stop() }
                        .onFailure { TorfilxLog.w(TAG, "Could not stop engine after consent removal", it) }
                }
            }
            .launchIn(scope)

        settingsRepository.storageFraction
            .onEach { fraction ->
                libTorrentEngine.storageBudget = StorageBudget(fractionOfFree = fraction)
                runCatching { engine.enforceStorageBudget() }
            }
            .launchIn(scope)

        // Keep the engine's synchronous config snapshot current. These take effect on the next play;
        // they are not applied mid-stream because they change how a session is built.
        settingsRepository.settings
            .onEach { s ->
                settingsRepository.cachedUseDht = s.useDht
                settingsRepository.cachedUseExtraTrackers = s.useExtraTrackers
                settingsRepository.cachedMetadataTimeoutSeconds = s.metadataTimeout.seconds
            }
            .launchIn(scope)
    }

    fun isAvailable(): Boolean = engine.isAvailable()

    /** Resolves a magnet into a locally-served URL the player can open. */
    suspend fun stream(magnet: String): TorrentStream {
        settingsRepository.cachedSharingConsent = settingsRepository.sharingConsent.first()
        return engine.stream(magnet)
    }

    suspend fun stopStreaming(infoHash: String) {
        // With seeding off, leaving the player also stops uploading that title.
        if (!settingsRepository.seedingEnabled.first()) {
            engine.remove(infoHash, deleteData = false)
        } else {
            engine.stopStreaming(infoHash)
        }
    }

    /** Removes every torrent and its data; watch history is untouched. */
    suspend fun clearAllData() {
        engine.stop()
        engine.enforceStorageBudget()
    }

    suspend fun shutdown() = engine.stop()
}

/** Bridges the persisted consent flag into the engine without a dependency cycle. */
@Singleton
class SettingsSharingConsentProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : SharingConsentProvider {
    override fun hasConsented(): Boolean = settingsRepository.cachedSharingConsent
}

/** Bridges the persisted engine preferences into the engine, synchronously. */
@Singleton
class SettingsTorrentConfigProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : TorrentConfigProvider {
    override fun useDht(): Boolean = settingsRepository.cachedUseDht
    override fun useExtraTrackers(): Boolean = settingsRepository.cachedUseExtraTrackers
    override fun metadataTimeoutMs(): Long = settingsRepository.cachedMetadataTimeoutSeconds * 1000L
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TorrentCoordinatorModule {
    @Binds
    @Singleton
    abstract fun bindsSharingConsentProvider(
        impl: SettingsSharingConsentProvider,
    ): SharingConsentProvider

    @Binds
    @Singleton
    abstract fun bindsTorrentConfigProvider(
        impl: SettingsTorrentConfigProvider,
    ): TorrentConfigProvider
}
