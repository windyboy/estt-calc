package com.gzzn.airport.resource

import com.gzzn.airport.exception.ErrorCode
import com.gzzn.airport.exception.ErrorResponse
import com.gzzn.airport.model.*
import com.gzzn.airport.service.EsttService
import io.micrometer.core.annotation.Counted
import io.micrometer.core.annotation.Timed
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory

@Controller(value = "/estt")
@Tag(name = "Flight Estimation", description = "APIs for calculating estimated flight arrival times")
open class EsttController(
	private val esttService: EsttService,
	@Value("\${estt.validation.flight-number-pattern:^[A-Z]{2}[0-9]{3,4}$}") flightNumberPattern: String
) {
	companion object {
		private val log = LoggerFactory.getLogger(EsttController::class.java)
	}
	
	private val FLIGHT_NUMBER_REGEX = Regex(flightNumberPattern)

	@Get(uri = "/season")
	@Timed(value = "estt.api.season", description = "Get active season API")
	@Counted(value = "estt.api.season.calls", description = "Number of season queries")
	@Operation(
		summary = "Get active flight season",
		description = "Returns the currently active flight season information"
	)
	@ApiResponses(
		ApiResponse(responseCode = "200", description = "Active season found", 
			content = [Content(schema = Schema(implementation = FlightSeason::class))]),
		ApiResponse(responseCode = "404", description = "No active season found"),
		ApiResponse(responseCode = "500", description = "Database error",
			content = [Content(schema = Schema(implementation = ErrorResponse::class))])
	)
	open fun getFlightSeason(): HttpResponse<*> {
		return esttService.getActiveSeason().fold(
			onSuccess = { season -> 
				if (season != null) HttpResponse.ok(season) 
				else HttpResponse.notFound<FlightSeason>() 
			},
			onFailure = { e -> 
				log.error("Failed to get active season", e)
				HttpResponse.serverError(
					ErrorCode.DATABASE_ERROR.toErrorResponse(e.message ?: "Database access failed")
				)
			}
		)
	}

	@Get(uri = "/seasonal/{flightNumber}/{flightDateString}")
	@Timed(value = "estt.api.seasonal", description = "Get seasonal flight API")
	@Counted(value = "estt.api.seasonal.calls", description = "Number of seasonal flight queries")
	@Operation(
		summary = "Get seasonal flight schedule",
		description = "Returns the seasonal schedule for a specific flight number and date"
	)
	@ApiResponses(
		ApiResponse(responseCode = "200", description = "Seasonal flight found",
			content = [Content(schema = Schema(implementation = SeasonalFlight::class))]),
		ApiResponse(responseCode = "400", description = "Invalid flight number or date format"),
		ApiResponse(responseCode = "404", description = "No seasonal flight found for this date/flight"),
		ApiResponse(responseCode = "500", description = "Database error")
	)
	open fun getSeasonalFlight(
		@Parameter(description = "Flight number (e.g., MU9941)", example = "MU9941", required = true) 
		flightNumber: String, 
		@Parameter(description = "Flight date in format yyMMdd (e.g., 211231)", example = "211231", required = true)
		flightDateString: String
	): HttpResponse<*> {
		val normalizedFlightNumber = flightNumber.uppercase()
		validateFlightNumber(normalizedFlightNumber)
		
		val flightDate = esttService.parseFlightDate(flightDateString)
		log.debug("Getting seasonal flight for $normalizedFlightNumber on $flightDate")
		
		return esttService.getSeasonalFlight(normalizedFlightNumber, flightDate).fold(
			onSuccess = { flight -> 
				if (flight != null) HttpResponse.ok(flight) 
				else HttpResponse.notFound<SeasonalFlight>() 
			},
			onFailure = { e -> 
				log.error("Failed to get seasonal flight for $normalizedFlightNumber", e)
				HttpResponse.serverError(
					ErrorCode.DATABASE_ERROR.toErrorResponse(e.message ?: "Database access failed")
				)
			}
		)
	}

	@Get(uri = "/history/{flightNumber}/{flightDateString}")
	@Timed(value = "estt.api.history", description = "Get flight history API")
	@Counted(value = "estt.api.history.calls", description = "Number of history queries")
	@Operation(
		summary = "Get historical flights",
		description = "Returns historical flight records matching the operational day and date criteria"
	)
	@ApiResponses(
		ApiResponse(responseCode = "200", description = "History retrieved successfully"),
		ApiResponse(responseCode = "400", description = "Invalid flight number or date format"),
		ApiResponse(responseCode = "500", description = "Database error")
	)
	open fun getHistoryFlights(
		@Parameter(description = "Flight number (e.g., MU9941)", example = "MU9941", required = true)
		flightNumber: String, 
		@Parameter(description = "Flight date in format yyMMdd (e.g., 211231)", example = "211231", required = true)
		flightDateString: String
	): HttpResponse<*> {
		val normalizedFlightNumber = flightNumber.uppercase()
		validateFlightNumber(normalizedFlightNumber)
		
		log.debug("Getting history of $normalizedFlightNumber, $flightDateString")
		val flightDate = esttService.parseFlightDate(flightDateString)
		
		return esttService.getHistoryFlights(normalizedFlightNumber, flightDate).fold(
			onSuccess = { history -> 
				log.debug("history size: ${history.size}")
				HttpResponse.ok(history)
			},
			onFailure = { e -> 
				log.error("Failed to get history flights for $normalizedFlightNumber", e)
				HttpResponse.serverError(
					ErrorCode.DATABASE_ERROR.toErrorResponse(e.message ?: "Database access failed")
				)
			}
		)
	}

	@Get(uri = "/flyTime/{flightNumber}/{flightDateString}")
	@Timed(value = "estt.api.calculate", description = "Flying time calculation API")
	@Counted(value = "estt.api.calculate.calls", description = "Number of calculation requests")
	@Operation(
		summary = "Calculate flying time",
		description = "Calculates estimated flying time based on historical data or seasonal schedule. " +
			"Uses average of recent flights if sufficient history exists (min 20 flights), " +
			"otherwise uses the seasonal schedule flying time."
	)
	@ApiResponses(
		ApiResponse(responseCode = "200", description = "Calculation successful",
			content = [Content(schema = Schema(implementation = FlyingTimeResponse::class))]),
		ApiResponse(responseCode = "400", description = "Invalid flight number or date format"),
		ApiResponse(responseCode = "500", description = "Calculation error")
	)
	open fun calculate(
		@Parameter(description = "Flight number (e.g., MU9941)", example = "MU9941", required = true)
		flightNumber: String, 
		@Parameter(description = "Flight date in format yyMMdd (e.g., 211231)", example = "211231", required = true)
		flightDateString: String
	): HttpResponse<*> {
		val normalizedFlightNumber = flightNumber.uppercase()
		validateFlightNumber(normalizedFlightNumber)
		
		val flightDate = esttService.parseFlightDate(flightDateString)
		
		return esttService.calculate(normalizedFlightNumber, flightDate).fold(
			onSuccess = { result -> HttpResponse.ok(result) },
			onFailure = { e -> 
				log.error("Calculation failed for $normalizedFlightNumber", e)
				HttpResponse.serverError(
					ErrorCode.CALCULATION_ERROR.toErrorResponse(e.message ?: "Calculation failed")
				)
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
