package com.gzzn.airport.model

import java.util.*

class FlyingTimeResponse(
	val flightNumber : String,
	val flightDate : Date,
	val flyingTime : Int,
	val history : Boolean,
	val seasonal : Boolean,
	val message : String
) {
}
