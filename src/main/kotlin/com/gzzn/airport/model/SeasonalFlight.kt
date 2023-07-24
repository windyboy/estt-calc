package com.gzzn.airport.model

import io.micronaut.data.annotation.MappedEntity
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

@Serdeable
@MappedEntity
data class SeasonalFlight (
	val flightNumber: String,
	val operationDays: String,
	val flyingTime: Long? = 0,
	val seasonStart: LocalDate
)
