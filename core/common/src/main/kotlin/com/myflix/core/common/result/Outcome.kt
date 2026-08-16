package com.myflix.core.common.result

import com.myflix.core.common.error.DataError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** A success/failure pair with a typed error. Deliberately not `kotlin.Result` so the error type is known. */
sealed interface Outcome<out T> {
    data class Success<T>(val data: T) : Outcome<T>
    data class Failure(val error: DataError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success
    fun dataOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): DataError? = (this as? Failure)?.error
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(data))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(data)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (DataError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}

fun <T> Outcome<T>.getOrElse(fallback: (DataError) -> T): T = when (this) {
    is Outcome.Success -> data
    is Outcome.Failure -> fallback(error)
}

/**
 * Runs [block], converting any throwable into a [DataError].
 *
 * `CancellationException` is deliberately rethrown: swallowing it would break structured concurrency
 * and leave coroutines that "succeed" after their scope has been cancelled.
 */
inline fun <T> runCatchingData(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: DataError) {
    Outcome.Failure(error)
} catch (throwable: Throwable) {
    Outcome.Failure(DataError.Unknown(throwable.message, throwable))
}

/** Same as [runCatchingData] for suspending work. */
suspend inline fun <T> runCatchingDataSuspend(crossinline block: suspend () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: DataError) {
    Outcome.Failure(error)
} catch (throwable: Throwable) {
    Outcome.Failure(DataError.Unknown(throwable.message, throwable))
}

/** Wraps a flow so downstream collectors receive failures as values rather than crashes. */
fun <T> Flow<T>.asOutcome(): Flow<Outcome<T>> =
    map<T, Outcome<T>> { Outcome.Success(it) }
        .catch { throwable ->
            if (throwable is CancellationException) throw throwable
            val error = throwable as? DataError ?: DataError.Unknown(throwable.message, throwable)
            emit(Outcome.Failure(error))
        }
