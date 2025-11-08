package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

/**
 * Result of the flying-time calculation served by the API.
 *
 * @property flightNumber normalized flight identifier.
 * @property flightDate target date of operation.
 * @property flyingTime computed flying time in minutes.
 * @property history whether historical flights contributed to the result.
 * @property seasonal whether seasonal schedule data was available.
 * @property message human-readable explanation of the data source.
 */
@Serdeable
data class FlyingTimeResponse(
    val flightNumber: String,
    val flightDate: LocalDate,
    val flyingTime: Long,
    val history: Boolean,
    val seasonal: Boolean,
    val message: String,
)
