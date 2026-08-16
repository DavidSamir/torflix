package com.torfilx.core.network

/** The server the app is currently pointed at. */
data class ServerConfig(
    val baseUrl: String,
    val token: String?,
)

/**
 * Supplies the current server configuration to the OkHttp layer.
 *
 * The base URL is user-editable at runtime, so it cannot be baked into the Retrofit instance.
 * Implemented in `:core:data` over DataStore; read synchronously (from a cached volatile value)
 * because interceptors are not suspending.
 */
interface ServerConfigProvider {
    fun current(): ServerConfig?
}
