package com.gzzn.airport.model


class FlyingTimeContext(
	val seasonalFlight: SeasonalFlight?,
	val historyFlights: List<HistoricalFlight>
)
