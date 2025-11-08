package com.gzzn.airport.model

import io.micronaut.data.annotation.MappedEntity
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Immutable view of a previously operated flight used to derive flying-time estimates.
 *
 * @property flightDate calendar date of the operation (local airport time).
 * @property previousDepartureTime actual departure time from the previous station.
 * @property actualTime actual arrival time for the current station.
 * @property scheduledTime scheduled arrival time for comparison and delay analysis.
 */
@Serdeable
@MappedEntity
data class HistoricalFlight(
    val flightDate: LocalDate,
    val previousDepartureTime: LocalDateTime,
    val actualTime: LocalDateTime,
    val scheduledTime: LocalDateTime,
)
