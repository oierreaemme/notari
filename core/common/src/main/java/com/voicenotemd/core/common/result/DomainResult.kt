package com.voicenotemd.core.common.result

/**
 * A minimal sealed Result type used across the domain and data layers.
 *
 * Why not use [kotlin.Result]? Because [kotlin.Result] is hard-wired to [Throwable]
 * which leaks a heavy exception type into the domain model and makes pattern-matching
 * verbose. Our [DomainResult] carries a typed error so call sites stay declarative.
 */
sealed interface DomainResult<out T, out E> {
    data class Success<T>(val value: T) : DomainResult<T, Nothing>

    data class Failure<E>(val error: E) : DomainResult<Nothing, E>
}

inline fun <T, E, R> DomainResult<T, E>.map(transform: (T) -> R): DomainResult<R, E> =
    when (this) {
        is DomainResult.Success -> DomainResult.Success(transform(value))
        is DomainResult.Failure -> this
    }

inline fun <T, E, F> DomainResult<T, E>.mapError(transform: (E) -> F): DomainResult<T, F> =
    when (this) {
        is DomainResult.Success -> this
        is DomainResult.Failure -> DomainResult.Failure(transform(error))
    }

inline fun <T, E, R> DomainResult<T, E>.flatMap(transform: (T) -> DomainResult<R, E>): DomainResult<R, E> =
    when (this) {
        is DomainResult.Success -> transform(value)
        is DomainResult.Failure -> this
    }

fun <T, E> DomainResult<T, E>.getOrNull(): T? = (this as? DomainResult.Success)?.value
