package com.torfilx.core.data.settings

import com.torfilx.core.common.di.ApplicationScope
import com.torfilx.core.model.ServerUrlNormalizer
import com.torfilx.core.network.ServerConfig
import com.torfilx.core.network.ServerConfigProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges user settings to the OkHttp interceptors.
 *
 * Interceptors cannot suspend, so the current configuration is mirrored into a volatile field that
 * is updated whenever settings change.
 */
@Singleton
class ServerConfigProviderImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : ServerConfigProvider {

    @Volatile
    private var baseUrl: String? = null

    init {
        settingsRepository.settings
            .onEach { settings ->
                baseUrl = ServerUrlNormalizer.normalize(settings.serverUrl)
            }
            .launchIn(scope)
    }

    override fun current(): ServerConfig? {
        val url = baseUrl ?: return null
        return ServerConfig(baseUrl = url, token = settingsRepository.apiToken())
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerConfigModule {
    @Binds
    @Singleton
    abstract fun bindsServerConfigProvider(impl: ServerConfigProviderImpl): ServerConfigProvider
}
