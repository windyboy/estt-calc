package com.gzzn.airport.model

import io.micronaut.data.annotation.MappedEntity
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

/**
 * Represents a flight season master record.
 *
 * @property seasonId database identifier of the flight season.
 * @property seasonName human-readable season description (e.g. "2024 Summer").
 * @property seasonStart inclusive start date of the season.
 * @property seasonEnd inclusive end date of the season.
 */
@Serdeable
@MappedEntity
data class FlightSeason(
    val seasonId: Long,
    val seasonName: String,
    val seasonStart: LocalDate,
    val seasonEnd: LocalDate,
)
