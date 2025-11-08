package com.gzzn.airport.util

/**
 * Chains multiple Result operations, similar to flatMap in functional programming.
 * If the Result is a success, applies the transform function to its value.
 * If the Result is a failure, propagates the failure.
 *
 * @param T the type of the current Result value
 * @param R the type of the transformed Result value
 * @param transform the transformation function that produces a new Result
 * @return a Result containing the transformed value or the original failure
 *
 * @example
 * ```kotlin
 * val result = Result.success(5)
 *     .flatMap { Result.success(it * 2) }
 *     .flatMap { Result.success(it + 10) }
 * // result contains 20
 * ```
 */
inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> {
    return fold(
        onSuccess = { value -> transform(value) },
        onFailure = { exception -> Result.failure(exception) },
    )
}

/**
 * Maps a nullable Result value, handling null as a business case rather than error.
 * Useful when dealing with optional values in Result chains.
 *
 * @param T the type of the current Result value (nullable)
 * @param R the type of the transformed value
 * @param transform the transformation function applied to non-null values
 * @return a Result containing the transformed value or null if the input was null
 *
 * @example
 * ```kotlin
 * val result: Result<User?> = getUser()
 * val nameResult: Result<String?> = result.mapNotNull { user -> user.name }
 * ```
 */
inline fun <T, R> Result<T?>.mapNotNull(transform: (T) -> R): Result<R?> {
    return map { it?.let(transform) }
}
