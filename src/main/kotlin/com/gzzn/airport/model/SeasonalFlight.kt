package com.gzzn.airport.model

import io.micronaut.data.annotation.MappedEntity
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

@Serdeable
@MappedEntity
data class SeasonalFlight (
	val flightNumber: String,
	val operationDays: String,
	val flyingTime: Long,
	val seasonStart: LocalDate
) {
	init {
		require(operationDays.all { it.isDigit() && it in '1'..'7' }) {
			"Invalid operationDays: $operationDays. Must contain only digits 1-7."
		}
	}
}
