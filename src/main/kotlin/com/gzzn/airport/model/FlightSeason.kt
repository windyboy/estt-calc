package com.gzzn.airport.model

import io.micronaut.data.annotation.MappedEntity
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

@Serdeable
@MappedEntity
data class FlightSeason(
    val seasonId: Long,
    val seasonName: String,
    val seasonStart: LocalDate,
    val seasonEnd: LocalDate,
)
