package com.torfilx.core.network.api

import com.torfilx.core.common.error.DataError
import com.torfilx.core.common.log.TorfilxLog
import com.torfilx.core.network.dto.ErrorEnvelopeDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val TAG = "Api"

/** A successful response plus its `ETag`, or [NotModified] when the server answered 304. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val body: T, val etag: String? = null) : ApiResult<T>
    data object NotModified : ApiResult<Nothing>
}

/**
 * Executes a Retrofit call and converts every failure mode into a typed [DataError].
 *
 * HTTP status handling matters for UX (plan.md §10): 401 sends the user to Settings, 404 refreshes
 * the library, 5xx is retryable, and an unparseable body is `Malformed` rather than a crash.
 */
suspend fun <T> apiCall(
    json: Json,
    itemId: String? = null,
    block: suspend () -> Response<T>,
): ApiResult<T> {
    val response = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (dataError: DataError) {
        // Thrown by our own interceptors (e.g. no server configured). It is already typed, and it
        // must not be re-mapped by the IOException branch below.
        throw dataError
    } catch (timeout: SocketTimeoutException) {
        throw DataError.Timeout("Server did not respond in time", timeout)
    } catch (unknownHost: UnknownHostException) {
        throw DataError.ServerUnreachable("Host not found", unknownHost)
    } catch (connect: ConnectException) {
        throw DataError.ServerUnreachable("Connection refused", connect)
    } catch (noRoute: NoRouteToHostException) {
        throw DataError.ServerUnreachable("No route to host", noRoute)
    } catch (ssl: SSLException) {
        throw DataError.ServerUnreachable("TLS failure: ${ssl.message}", ssl)
    } catch (io: IOException) {
        throw DataError.ServerUnreachable(io.message, io)
    } catch (serialization: kotlinx.serialization.SerializationException) {
        throw DataError.Malformed("Response could not be parsed", serialization)
    } catch (illegalState: IllegalStateException) {
        // Retrofit throws this for a null body on a non-nullable type.
        throw DataError.Malformed(illegalState.message, illegalState)
    }

    if (response.code() == HTTP_NOT_MODIFIED) return ApiResult.NotModified

    if (!response.isSuccessful) {
        val serverCode = response.errorBody()?.let { body ->
            runCatching { json.decodeFromString<ErrorEnvelopeDto>(body.string()).error?.code }.getOrNull()
        }
        TorfilxLog.w(TAG, "HTTP ${response.code()} for ${response.raw().request.url} (code=$serverCode)")
        throw when (response.code()) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> DataError.Unauthorized("Server rejected the API token")
            HTTP_NOT_FOUND -> DataError.NotFound(itemId, "Item not found on server")
            in HTTP_SERVER_ERROR_RANGE -> DataError.ServerError(response.code(), serverCode, response.message())
            else -> DataError.ServerError(response.code(), serverCode, response.message())
        }
    }

    val body = response.body()
    @Suppress("UNCHECKED_CAST")
    if (body == null) {
        // 204/empty body endpoints are declared as Response<Unit>.
        return ApiResult.Ok(Unit as T, response.headers()["ETag"])
    }
    return ApiResult.Ok(body, response.headers()["ETag"])
}

/** Convenience for calls whose 304 handling is irrelevant. */
suspend fun <T> apiCallBody(
    json: Json,
    itemId: String? = null,
    block: suspend () -> Response<T>,
): T = when (val result = apiCall(json, itemId, block)) {
    is ApiResult.Ok -> result.body
    ApiResult.NotModified -> throw DataError.Malformed("Unexpected 304 for a request without an ETag")
}

private const val HTTP_NOT_MODIFIED = 304
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private val HTTP_SERVER_ERROR_RANGE = 500..599
