package com.gzzn.airport.resource

import com.gzzn.airport.model.*
import com.gzzn.airport.service.EsttService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import org.slf4j.LoggerFactory

@Controller(value = "/estt")
class EsttController(private val esttService: EsttService) {
	companion object {
		private val log = LoggerFactory.getLogger(EsttController::class.java)
		private val FLIGHT_NUMBER_REGEX = Regex("^[A-Z]{2}[0-9]{3,4}$")
	}

	/**
	 * Query active season
	 */
	@Get(uri = "/season")
	fun getFlightSeason(): HttpResponse<FlightSeason> {
		val activeSeason = esttService.getActiveSeason()
		return if (activeSeason != null) {
			HttpResponse.ok(activeSeason)
		} else {
			HttpResponse.notFound()
		}
	}

	@Get(uri = "/seasonal/{flightNumber}/{flightDateString}")
	fun getSeasonalFlight(flightNumber: String, flightDateString: String): HttpResponse<SeasonalFlight> {
		val normalizedFlightNumber = flightNumber.uppercase()
		validateFlightNumber(normalizedFlightNumber)
		
		val flightDate = esttService.parseFlightDate(flightDateString)
		log.info("Getting seasonal flight for $normalizedFlightNumber on $flightDate")
		
		val seasonalFlight = esttService.getSeasonalFlight(normalizedFlightNumber, flightDate)
		return if (seasonalFlight != null) {
			HttpResponse.ok(seasonalFlight)
		} else {
			HttpResponse.notFound()
		}
	}

	@Get(uri = "/history/{flightNumber}/{flightDateString}")
	fun getHistoryFlights(flightNumber: String, flightDateString: String): HttpResponse<List<HistoricalFlight>> {
		val normalizedFlightNumber = flightNumber.uppercase()
		validateFlightNumber(normalizedFlightNumber)
		
		log.info("Getting history of $normalizedFlightNumber, $flightDateString")
		val flightDate = esttService.parseFlightDate(flightDateString)
		val history = esttService.getHistoryFlights(normalizedFlightNumber, flightDate)
		log.info("history size: ${history.size}")
		
		return HttpResponse.ok(history)
	}

	@Get(uri = "/flyTime/{flightNumber}/{flightDateString}")
	fun calculate(flightNumber: String, flightDateString: String): HttpResponse<FlyingTimeResponse> {
		val normalizedFlightNumber = flightNumber.uppercase()
		validateFlightNumber(normalizedFlightNumber)
		
		val flightDate = esttService.parseFlightDate(flightDateString)
		val result = esttService.calculate(normalizedFlightNumber, flightDate)
		
		return HttpResponse.ok(result)
	}

	private fun validateFlightNumber(flightNumber: String) {
		require(flightNumber.isNotBlank()) { "Flight number cannot be empty" }
		require(flightNumber.matches(FLIGHT_NUMBER_REGEX)) {
			"Invalid flight number format: $flightNumber. Expected format: AA1234 (2 letters + 3-4 digits)"
		}
	}
}
