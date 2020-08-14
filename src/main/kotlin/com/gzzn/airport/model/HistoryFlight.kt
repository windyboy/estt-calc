package com.gzzn.airport.model

import io.micronaut.data.annotation.MappedEntity
import java.util.*
@MappedEntity
data class HistoryFlight(
	val flightDate: Date,
	val preActualTime: Date,
	val actualTime: Date,
	val scheduledTime: Date) {
}
