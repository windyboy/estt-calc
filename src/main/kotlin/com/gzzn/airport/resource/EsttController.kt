package com.gzzn.airport.resource

import com.gzzn.airport.exception.ErrorResponse
import com.gzzn.airport.model.*
import com.gzzn.airport.service.EsttService
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import org.slf4j.LoggerFactory

@Controller(value = "/estt")
class EsttController(
	private val esttService: EsttService,
	@Value("\${estt.validation.flight-number-pattern:^[A-Z]{2}[0-9]{3,4}$}") flightNumberPattern: String
) {
	companion object {
		private val log = LoggerFactory.getLogger(EsttController::class.java)
	}
	
	private val FLIGHT_NUMBER_REGEX = Regex(flightNumberPattern)

	/**
	 * Query active season
	 */
	@Get(uri = "/season")
	fun getFlightSeason(): HttpResponse<*> {
		return esttService.getActiveSeason().fold(
			onSuccess = { season -> 
				if (season != null) HttpResponse.ok(season) 
				else HttpResponse.notFound<FlightSeason>() 
			},
			onFailure = { e -> 
				HttpResponse.serverError(ErrorResponse(500, "SYSTEM_ERROR", e.message ?: "Unknown error"))
			}
		)
	}

	@Get(uri = "/seasonal/{flightNumber}/{flightDateString}")
	fun getSeasonalFlight(flightNumber: String, flightDateString: String): HttpResponse<*> {
		val normalizedFlightNumber = flightNumber.uppercase()
		validateFlightNumber(normalizedFlightNumber)
		
		val flightDate = esttService.parseFlightDate(flightDateString)
		log.info("Getting seasonal flight for $normalizedFlightNumber on $flightDate")
		
		return esttService.getSeasonalFlight(normalizedFlightNumber, flightDate).fold(
			onSuccess = { flight -> 
				if (flight != null) HttpResponse.ok(flight) 
				else HttpResponse.notFound<SeasonalFlight>() 
			},
			onFailure = { e -> 
				HttpResponse.serverError(ErrorResponse(500, "SYSTEM_ERROR", e.message ?: "Unknown error"))
			}
		)
	}

	@Get(uri = "/history/{flightNumber}/{flightDateString}")
	fun getHistoryFlights(flightNumber: String, flightDateString: String): HttpResponse<*> {
		val normalizedFlightNumber = flightNumber.uppercase()
		validateFlightNumber(normalizedFlightNumber)
		
		log.info("Getting history of $normalizedFlightNumber, $flightDateString")
		val flightDate = esttService.parseFlightDate(flightDateString)
		
		return esttService.getHistoryFlights(normalizedFlightNumber, flightDate).fold(
			onSuccess = { history -> 
				log.info("history size: ${history.size}")
				HttpResponse.ok(history)
			},
			onFailure = { e -> 
				HttpResponse.serverError(ErrorResponse(500, "SYSTEM_ERROR", e.message ?: "Unknown error"))
			}
		)
	}

	@Get(uri = "/flyTime/{flightNumber}/{flightDateString}")
	fun calculate(flightNumber: String, flightDateString: String): HttpResponse<*> {
		val normalizedFlightNumber = flightNumber.uppercase()
		validateFlightNumber(normalizedFlightNumber)
		
		val flightDate = esttService.parseFlightDate(flightDateString)
		
		return esttService.calculate(normalizedFlightNumber, flightDate).fold(
			onSuccess = { result -> HttpResponse.ok(result) },
			onFailure = { e -> 
				HttpResponse.serverError(ErrorResponse(500, "CALCULATION_ERROR", e.message ?: "Unknown error"))
			}
		)
	}

	private fun validateFlightNumber(flightNumber: String) {
		require(flightNumber.isNotBlank()) { "Flight number cannot be empty" }
		require(flightNumber.matches(FLIGHT_NUMBER_REGEX)) {
			"Invalid flight number format: $flightNumber. Expected format: AA1234 (2 letters + 3-4 digits)"
		}
	}
}
