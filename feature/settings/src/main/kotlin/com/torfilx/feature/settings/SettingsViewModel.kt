package com.torfilx.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torfilx.core.common.error.DataError
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.data.repository.MediaRepository
import com.torfilx.core.data.settings.SettingsRepository
import com.torfilx.core.model.AppSettings
import com.torfilx.core.model.ConnectionTestResult
import com.torfilx.core.model.QualityPreference
import com.torfilx.core.model.ServerUrlNormalizer
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
    val tokenSet: Boolean = false,
    val demoMode: Boolean = false,
    val connectionTest: ConnectionTestResult? = null,
    val isTesting: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    private val connectionTest = MutableStateFlow<ConnectionTestResult?>(null)
    private val testing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val tokenSet = MutableStateFlow(settingsRepository.apiToken() != null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        settingsRepository.demoMode,
        combine(connectionTest, testing, message) { test, isTesting, msg -> Triple(test, isTesting, msg) },
        tokenSet,
    ) { settings, demo, (test, isTesting, msg), hasToken ->
        SettingsUiState(
            settings = settings,
            tokenSet = hasToken,
            demoMode = demo,
            connectionTest = test,
            isTesting = isTesting,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    fun setServerUrl(url: String) {
        viewModelScope.launch {
            val normalized = ServerUrlNormalizer.normalize(url)
            if (normalized == null && url.isNotBlank()) {
                message.value = "That doesn't look like a valid address."
                return@launch
            }
            settingsRepository.setServerUrl(normalized ?: "")
            connectionTest.value = null
            message.value = null
        }
    }

    fun setApiToken(token: String) {
        settingsRepository.setApiToken(token.takeIf { it.isNotBlank() })
        tokenSet.value = settingsRepository.apiToken() != null
        connectionTest.value = null
    }

    /**
     * "Test connection" reports what the server actually said, so a wrong port or a rejected token
     * is distinguishable from a sleeping PC (plan.md §6.7).
     */
    fun testConnection() {
        if (testing.value) return
        viewModelScope.launch {
            testing.value = true
            connectionTest.value = try {
                ConnectionTestResult.Success(mediaRepository.serverInfo())
            } catch (error: DataError) {
                TorfilxLog.w(TAG, "Connection test failed", error)
                ConnectionTestResult.Failure(
                    when (error) {
                        is DataError.NotConfigured -> "No server address set."
                        is DataError.Unauthorized -> "The server rejected the API token."
                        is DataError.Timeout -> "The server did not answer in time."
                        is DataError.Malformed -> "That address answered, but not with a Torfilx API."
                        else -> "Could not reach the server."
                    },
                )
            } finally {
                testing.value = false
            }
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

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDemoMode(enabled)
            mediaRepository.clearCache()
            message.value = if (enabled) {
                "Demo library enabled — the server is not being used."
            } else {
                "Demo library disabled."
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            mediaRepository.clearCache()
            message.value = "Cached library cleared. Watch progress was kept."
        }
    }

    /** Writes the in-memory log ring buffer to a file the user can pull with adb. */
    fun exportLogs() {
        viewModelScope.launch {
            message.value = runCatching {
                val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "torfilx-log.txt")
                file.writeText(TorfilxLog.dump())
                "Logs written to ${file.absolutePath}"
            }.getOrElse { "Could not write logs: ${it.message}" }
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
