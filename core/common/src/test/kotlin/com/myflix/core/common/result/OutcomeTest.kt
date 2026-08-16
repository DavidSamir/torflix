package com.myflix.core.common.result

import com.google.common.truth.Truth.assertThat
import com.myflix.core.common.error.DataError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutcomeTest {

    @Test
    fun `typed errors pass through unchanged`() {
        val outcome = runCatchingData { throw DataError.Unauthorized("nope") }
        assertThat(outcome.errorOrNull()).isInstanceOf(DataError.Unauthorized::class.java)
    }

    @Test
    fun `unexpected throwables are wrapped but keep their cause`() {
        val boom = IllegalStateException("boom")
        val outcome = runCatchingData { throw boom }
        val error = outcome.errorOrNull()
        assertThat(error).isInstanceOf(DataError.Unknown::class.java)
        assertThat(error!!.cause).isSameInstanceAs(boom)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is never swallowed`() {
        // Swallowing this would break structured concurrency: a cancelled coroutine would "succeed".
        runCatchingData { throw CancellationException("cancelled") }
    }

    @Test
    fun `map only transforms the success branch`() {
        assertThat(Outcome.Success(2).map { it * 3 }.dataOrNull()).isEqualTo(6)
        val failure: Outcome<Int> = Outcome.Failure(DataError.NotFound("x"))
        assertThat(failure.map { it * 3 }.errorOrNull()).isInstanceOf(DataError.NotFound::class.java)
    }

    @Test
    fun `getOrElse supplies a fallback for failures only`() {
        assertThat(Outcome.Success(5).getOrElse { 0 }).isEqualTo(5)
        val failure: Outcome<Int> = Outcome.Failure(DataError.Timeout())
        assertThat(failure.getOrElse { 42 }).isEqualTo(42)
    }

    @Test
    fun `flow failures arrive as values instead of crashing the collector`() = runTest {
        val values = flow {
            emit(1)
            throw DataError.ServerUnreachable("down")
        }.asOutcome().toList()

        assertThat(values).hasSize(2)
        assertThat(values[0]).isEqualTo(Outcome.Success(1))
        assertThat((values[1] as Outcome.Failure).error)
            .isInstanceOf(DataError.ServerUnreachable::class.java)
    }

    @Test
    fun `successful flow values are wrapped`() = runTest {
        assertThat(flow { emit("a") }.asOutcome().first()).isEqualTo(Outcome.Success("a"))
    }

    @Test
    fun `retryability distinguishes transient failures from permanent ones`() {
        assertThat(DataError.ServerUnreachable().isRetryable).isTrue()
        assertThat(DataError.Timeout().isRetryable).isTrue()
        assertThat(DataError.ServerError(503).isRetryable).isTrue()

        assertThat(DataError.Unauthorized().isRetryable).isFalse()
        assertThat(DataError.NotFound("x").isRetryable).isFalse()
        assertThat(DataError.NotConfigured().isRetryable).isFalse()
        assertThat(DataError.Malformed().isRetryable).isFalse()
    }
}
