package com.voicenotemd.core.common.result

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DomainResultTest {
    @Test
    fun `map should transform value when given Success`() {
        val result: DomainResult<Int, String> = DomainResult.Success(2)
        val mapped = result.map { it * 3 }
        assertThat(mapped).isEqualTo(DomainResult.Success(6))
    }

    @Test
    fun `map should pass through error when given Failure`() {
        val result: DomainResult<Int, String> = DomainResult.Failure("boom")
        val mapped = result.map { it * 3 }
        assertThat(mapped).isEqualTo(DomainResult.Failure("boom"))
    }

    @Test
    fun `mapError should transform error when given Failure`() {
        val result: DomainResult<Int, String> = DomainResult.Failure("nope")
        val mapped = result.mapError { it.length }
        assertThat(mapped).isEqualTo(DomainResult.Failure(4))
    }

    @Test
    fun `flatMap should chain when given Success`() {
        val result: DomainResult<Int, String> = DomainResult.Success(10)
        val chained = result.flatMap { v -> DomainResult.Success(v + 5) }
        assertThat(chained).isEqualTo(DomainResult.Success(15))
    }

    @Test
    fun `flatMap should short-circuit when given Failure`() {
        val result: DomainResult<Int, String> = DomainResult.Failure("err")
        val chained = result.flatMap { DomainResult.Success(it + 1) }
        assertThat(chained).isEqualTo(DomainResult.Failure("err"))
    }

    @Test
    fun `getOrNull should return value when given Success`() {
        val result: DomainResult<String, Throwable> = DomainResult.Success("hi")
        assertThat(result.getOrNull()).isEqualTo("hi")
    }

    @Test
    fun `getOrNull should return null when given Failure`() {
        val result: DomainResult<String, Throwable> = DomainResult.Failure(RuntimeException())
        assertThat(result.getOrNull()).isNull()
    }
}
