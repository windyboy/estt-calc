package com.gzzn.airport.model

import java.util.*

data class FlyingTimeResponse(
	val flightNumber: String,
	val flightDate: Date,
	val flyingTime: Int,
	val history: Boolean,
	val seasonal: Boolean,
	val message: String
)
