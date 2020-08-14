package com.gzzn.airport.model

import io.micronaut.data.annotation.MappedEntity
import java.util.*

@MappedEntity
data class SeasonalFlight (
	val flightNumber: String,
	val operationDays: String,
	val flyingTime: Int,
	val seasonStart: Date
) {
}
