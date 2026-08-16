package com.myflix.core.common.error

/**
 * Every failure the data layer can produce, as a closed set. Screens switch on this to choose copy
 * and recovery actions, so a new failure mode cannot silently fall through to a generic message
 * (plan.md §3, §10).
 */
sealed class DataError(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The server could not be reached at all: no route, refused, DNS, timeout, TLS handshake. */
    class ServerUnreachable(message: String? = null, cause: Throwable? = null) :
        DataError(message, cause)

    /** The request timed out after connecting. */
    class Timeout(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** 401/403 — the API token is missing, wrong, or has been revoked. */
    class Unauthorized(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** 404 — the item no longer exists on the server (deleted since the library was cached). */
    class NotFound(val itemId: String? = null, message: String? = null) : DataError(message)

    /** 5xx or an error envelope from the server. */
    class ServerError(val code: Int, val serverCode: String? = null, message: String? = null) :
        DataError(message)

    /** The response could not be parsed — a schema mismatch or a proxy returning HTML. */
    class Malformed(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** No server URL has been configured yet. */
    class NotConfigured(message: String? = null) : DataError(message)

    /** Local storage failure (disk full, corrupted database). */
    class Storage(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** Anything genuinely unexpected; always carries the original cause for the log export. */
    class Unknown(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** True when retrying the same request unchanged could plausibly succeed. */
    val isRetryable: Boolean
        get() = when (this) {
            is ServerUnreachable, is Timeout, is ServerError -> true
            is Unauthorized, is NotFound, is Malformed, is NotConfigured, is Storage, is Unknown -> false
        }
}
