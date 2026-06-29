package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

/**
 * Result of the flying-time calculation served by the API.
 *
 * @property flyingTime Estimated minutes; `null` when [source] is [EstimateSource.NONE].
 * @property history Deprecated legacy flag; `true` when [source] is [EstimateSource.HISTORY].
 * @property seasonal Deprecated legacy flag; `true` when [source] is [EstimateSource.SEASONAL].
 * @property message Human-readable detail for operators and logs; not a stable API contract.
 * @property source Authoritative estimate origin. Use `NONE` to detect no estimate.
 * @property sampleSize Count of qualified historical flights used in the estimate; `0` for seasonal or none.
 * @property confidence `HIGH` only when [source] is [EstimateSource.HISTORY]; otherwise `NONE`.
 */
@Serdeable
data class FlyingTimeResponse(
    val flightNumber: String,
    val flightDate: LocalDate,
    val flyingTime: Long?,
    @Deprecated("Use source == EstimateSource.HISTORY instead")
    val history: Boolean,
    @Deprecated("Use source == EstimateSource.SEASONAL instead")
    val seasonal: Boolean,
    val message: String,
    val source: EstimateSource,
    val sampleSize: Int,
    val confidence: Confidence,
)
