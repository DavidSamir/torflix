package com.torfilx.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.data.torrent.TorrentCoordinator
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.QualityPreference
import com.torfilx.core.torrent.SharingStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "SettingsVM"

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val sharingConsent: Boolean = false,
    val seedingEnabled: Boolean = true,
    val storageFraction: Float = 0.5f,
    val sharingStats: SharingStats = SharingStats(),
    val torrentAvailable: Boolean = false,
    val message: String? = null,
)

/**
 * Settings for a server-less app: playback preferences, language, and everything about sharing.
 *
 * There is no server address, token or connection test any more — the catalogue ships with the app
 * and playback happens over BitTorrent.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository,
    private val torrentCoordinator: TorrentCoordinator,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        combine(
            settingsRepository.sharingConsent,
            settingsRepository.seedingEnabled,
            settingsRepository.storageFraction,
            torrentCoordinator.stats,
        ) { consent, seeding, fraction, stats -> SharingSnapshot(consent, seeding, fraction, stats) },
        message,
    ) { settings, sharing, msg ->
        SettingsUiState(
            settings = settings,
            sharingConsent = sharing.consent,
            seedingEnabled = sharing.seeding,
            storageFraction = sharing.fraction,
            sharingStats = sharing.stats,
            torrentAvailable = torrentCoordinator.isAvailable(),
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    private data class SharingSnapshot(
        val consent: Boolean,
        val seeding: Boolean,
        val fraction: Float,
        val stats: SharingStats,
    )

    /** Turning sharing off stops uploading immediately and makes torrent playback unavailable. */
    fun setSharingConsent(consented: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSharingConsent(consented)
            message.value = if (consented) {
                "Sharing is on. You upload what you are watching, within the storage limit."
            } else {
                "Sharing is off. Playback is unavailable until you turn it back on."
            }
        }
    }

    fun setSeedingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSeedingEnabled(enabled) }
    }

    /** Cycles the share of free disk the torrent cache may use. */
    fun cycleStorageFraction() {
        viewModelScope.launch {
            val next = when (uiState.value.storageFraction) {
                0.25f -> 0.5f
                0.5f -> 0.75f
                else -> 0.25f
            }
            settingsRepository.setStorageFraction(next)
        }
    }

    fun setAudioLanguage(language: String?) = launchSetting { settingsRepository.setAudioLanguage(language) }
    fun setSubtitleLanguage(language: String?) = launchSetting { settingsRepository.setSubtitleLanguage(language) }
    fun setSubtitlesEnabled(enabled: Boolean) = launchSetting { settingsRepository.setSubtitlesEnabled(enabled) }
    fun setAutoplayNext(enabled: Boolean) = launchSetting { settingsRepository.setAutoplayNext(enabled) }
    fun setQuality(preference: QualityPreference) = launchSetting { settingsRepository.setQuality(preference) }
    fun setFrameRateMatching(enabled: Boolean) = launchSetting { settingsRepository.setFrameRateMatching(enabled) }
    fun setTunneledPlayback(enabled: Boolean) = launchSetting { settingsRepository.setTunneledPlayback(enabled) }
    fun setSkipIntroAutomatically(enabled: Boolean) =
        launchSetting { settingsRepository.setSkipIntroAutomatically(enabled) }

    /** Clears downloaded torrent data; watch progress and My List are kept. */
    fun clearDownloadedData() {
        viewModelScope.launch {
            runCatching { torrentCoordinator.clearAllData() }
                .onSuccess { message.value = "Downloaded data cleared. Watch progress was kept." }
                .onFailure { message.value = "Could not clear downloaded data: ${it.message}" }
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            mediaRepository.clearSearchHistory()
            message.value = "Search history cleared."
        }
    }

    /** Writes the in-memory log ring buffer to a file the user can pull with adb. */
    fun exportLogs() {
        viewModelScope.launch {
            message.value = runCatching {
                val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "torfilx-log.txt")
                file.writeText(TorfilxLog.dump())
                "Logs written to ${file.absolutePath}"
            }.getOrElse {
                TorfilxLog.w(TAG, "Log export failed", it)
                "Could not write logs: ${it.message}"
            }
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
