package com.gzzn.airport.resource

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.HistoryFlight
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.service.EsttService
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import org.slf4j.LoggerFactory

@Controller(value = "/estt")
class EsttController(private val esttService: EsttService) {
	companion object {
		private val log = LoggerFactory.getLogger("EsttController")
	}

	/**
	 * query active season
	 */
	@Get(uri = "/season")
	fun getFlightSeason(): FlightSeason? {
		return esttService.getActiveSeason()
	}

	@Get(uri = "/seasonal/{flightNumber}/{flightDateString}")
	fun getSeasonalFlight(flightNumber: String, flightDateString: String): SeasonalFlight? {
		val flightDate = esttService.getFlightDate(flightDateString)
		log.info("flightDate ", flightDate)
		return esttService.getSeasonalFlight(flightNumber.toUpperCase(), flightDate)
	}

	@Get(uri = "/history/{flightNumber}/{flightDateString}")
	fun getHistoryFlights(flightNumber: String, flightDateString: String) : List<HistoryFlight> {
		val flightDate = esttService.getFlightDate(flightDateString)
		return esttService.getHistoryFlights(flightNumber.toUpperCase(), flightDate)
	}

	@Get(uri = "/flyTime/{flightNumber}/{flightDateString}")
	fun calculate(flightNumber: String, flightDateString: String) : FlyingTimeResponse {
		val flightDate = esttService.getFlightDate(flightDateString)
		return esttService.calculate(flightNumber.toUpperCase(), flightDate)
	}
}
