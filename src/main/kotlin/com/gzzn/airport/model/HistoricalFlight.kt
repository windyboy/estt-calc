package com.gzzn.airport.model

import io.micronaut.data.annotation.MappedEntity
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate
import java.time.LocalDateTime

@Serdeable
@MappedEntity
data class HistoricalFlight(
    val flightDate: LocalDate,
    val previousDepartureTime: LocalDateTime,
    val actualTime: LocalDateTime,
    val scheduledTime: LocalDateTime,
)
