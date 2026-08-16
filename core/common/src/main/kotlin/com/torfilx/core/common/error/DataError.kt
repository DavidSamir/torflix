package com.torfilx.core.common.error

/**
 * Every failure the data layer can produce, as a closed set. Screens switch on this to choose copy
 * and recovery actions, so a new failure mode cannot silently fall through to a generic message.
 *
 * It extends [java.io.IOException] deliberately: these errors are thrown from inside I/O layers
 * (OkHttp interceptors used by image loading, file access) which only contract to deliver
 * `IOException` to the caller — any other type escapes on a worker thread and crashes the process.
 */
sealed class DataError(
    message: String? = null,
    cause: Throwable? = null,
) : java.io.IOException(message, cause) {

    /** A network endpoint could not be reached: no route, refused, DNS, timeout, TLS handshake. */
    class Unreachable(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** The request timed out after connecting. */
    class Timeout(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** The requested item is not in the catalogue (or no longer is). */
    class NotFound(val itemId: String? = null, message: String? = null) : DataError(message)

    /** A file could not be parsed — a malformed `catalog.json`, a truncated download. */
    class Malformed(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** Local storage failure (disk full, corrupted database). */
    class Storage(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** Anything genuinely unexpected; always carries the original cause for the log export. */
    class Unknown(message: String? = null, cause: Throwable? = null) : DataError(message, cause)

    /** True when retrying the same operation unchanged could plausibly succeed. */
    val isRetryable: Boolean
        get() = when (this) {
            is Unreachable, is Timeout -> true
            is NotFound, is Malformed, is Storage, is Unknown -> false
        }
}
