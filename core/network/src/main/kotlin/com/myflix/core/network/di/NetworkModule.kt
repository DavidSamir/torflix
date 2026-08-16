package com.myflix.core.network.di

import com.myflix.core.common.error.DataError
import com.myflix.core.common.time.TimeProvider
import com.myflix.core.network.ServerConfigProvider
import com.myflix.core.network.api.MyflixApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Placeholder host: every request is rewritten by [BaseUrlInterceptor] to the real server. */
    private const val PLACEHOLDER_BASE_URL = "http://myflix.invalid/"

    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 15L
    private const val WRITE_TIMEOUT_SECONDS = 15L

    @Provides
    @Singleton
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true // a server that adds fields must not break the app
        coerceInputValues = true // null for a non-null field falls back to the default
        explicitNulls = false
        isLenient = true
    }

    @Provides
    @Singleton
    fun providesOkHttpClient(
        configProvider: ServerConfigProvider,
        timeProvider: TimeProvider,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(BaseUrlInterceptor(configProvider))
        .addInterceptor(AuthInterceptor(configProvider))
        .addInterceptor(ServerClockInterceptor(timeProvider))
        .build()

    /**
     * A separate client for media and images: long reads (a 4 GB file streams for hours) must not be
     * killed by the API read timeout, and media requests carry no JSON handling.
     */
    @Provides
    @Singleton
    @MediaHttpClient
    fun providesMediaOkHttpClient(
        configProvider: ServerConfigProvider,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout while streaming
        .retryOnConnectionFailure(true)
        .addInterceptor(AuthInterceptor(configProvider))
        .build()

    @Provides
    @Singleton
    fun providesRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .callFactory(Call.Factory { request -> client.newCall(request) })
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun providesApiService(retrofit: Retrofit): MyflixApiService =
        retrofit.create(MyflixApiService::class.java)
}

/**
 * Rewrites the placeholder host to the currently configured server.
 *
 * The user can change the server URL at runtime, so the Retrofit instance is built once with a
 * placeholder and every request is retargeted here. A request made with no server configured fails
 * fast with [DataError.NotConfigured] instead of hanging on a bogus DNS lookup.
 */
internal class BaseUrlInterceptor(
    private val configProvider: ServerConfigProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val config = configProvider.current()
            ?: throw DataError.NotConfigured("No media server configured")

        val base: HttpUrl = config.baseUrl.toHttpUrlOrNull()
            ?: throw DataError.NotConfigured("Configured server URL is not a valid URL")

        val originalUrl = request.url
        val newUrl = originalUrl.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(base.encodedPath.trimEnd('/') + originalUrl.encodedPath)
            .build()

        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}

/**
 * Adds the bearer token, when one is configured.
 *
 * The token is only attached to requests aimed at the configured server's host. Media and image URLs
 * are supplied by the server and could in principle point anywhere; sending the credential to a
 * third-party host would leak it.
 */
internal class AuthInterceptor(
    private val configProvider: ServerConfigProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val config = configProvider.current()
        val token = config?.token
        val serverHost = config?.baseUrl?.toHttpUrlOrNull()?.host

        val request = chain.request()
        val shouldAuthenticate = !token.isNullOrBlank() &&
            serverHost != null &&
            request.url.host.equals(serverHost, ignoreCase = true)

        return chain.proceed(
            if (shouldAuthenticate) {
                request.newBuilder().header("Authorization", "Bearer $token").build()
            } else {
                request
            },
        )
    }
}

/**
 * Records the offset between the server clock and this device's clock.
 *
 * Fire TV sticks have no battery-backed clock; after a power cut the time can be wildly wrong, which
 * would make every local write win or lose sync conflicts incorrectly (plan.md §10).
 */
internal class ServerClockInterceptor(
    private val timeProvider: TimeProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val serverDate: Date? = response.headers.getDate("Date")
        if (serverDate != null) {
            timeProvider.serverClockOffsetMs = serverDate.time - timeProvider.nowMs()
        }
        return response
    }
}
