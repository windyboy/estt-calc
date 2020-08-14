package com.gzzn.airport.model

import java.util.*

class FlyingTimeContext(
	val flightNumber: String,
	val flightDate : Date,
	val flightSeason: FlightSeason?,
	val seasonalFlight : SeasonalFlight?,
	val historyFlights: List<HistoryFlight>
) {
}
