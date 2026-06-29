package com.gzzn.airport.resource

import com.gzzn.airport.exception.ErrorCode
import com.gzzn.airport.exception.ErrorResponse
import com.gzzn.airport.model.*
import com.gzzn.airport.model.PaginatedHistoryResponse
import com.gzzn.airport.service.EsttService
import io.micrometer.core.annotation.Counted
import io.micrometer.core.annotation.Timed
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
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
    @Value("\${estt.validation.flight-number-pattern:^[A-Z]{2}[0-9]{3,4}$}") flightNumberPattern: String,
) {
    companion object {
        private val log = LoggerFactory.getLogger(EsttController::class.java)
    }

    private val flightNumberRegex = Regex(flightNumberPattern)

    @Get(uri = "/season")
    @Timed(value = "estt.api.season", description = "Get active season API")
    @Counted(value = "estt.api.season.calls", description = "Number of season queries")
    @Operation(
        summary = "Get active flight season",
        description = "Returns the currently active flight season information",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Active season found",
            content = [Content(schema = Schema(implementation = FlightSeason::class))],
        ),
        ApiResponse(responseCode = "404", description = "No active season found"),
        ApiResponse(
            responseCode = "500",
            description = "Database error",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    open fun getFlightSeason(): HttpResponse<*> {
        log.info("Request: GET /estt/season")
        return esttService.getActiveSeason().fold(
            onSuccess = { season ->
                log.info("Response: season=${season?.seasonName ?: "not found"}")
                if (season != null) {
                    HttpResponse.ok(season)
                } else {
                    HttpResponse.notFound<FlightSeason>()
                }
            },
            onFailure = { e ->
                log.error("Failed to get active season", e)
                HttpResponse.serverError(
                    ErrorCode.DATABASE_ERROR.toErrorResponse(e.message ?: "Database access failed"),
                )
            },
        )
    }

    @Get(uri = "/seasonal/{flightNumber}/{flightDateString}")
    @Timed(value = "estt.api.seasonal", description = "Get seasonal flight API")
    @Counted(value = "estt.api.seasonal.calls", description = "Number of seasonal flight queries")
    @Operation(
        summary = "Get seasonal flight schedule",
        description = "Returns the seasonal schedule for a specific flight number and date",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Seasonal flight found",
            content = [Content(schema = Schema(implementation = SeasonalFlight::class))],
        ),
        ApiResponse(responseCode = "400", description = "Invalid flight number or date format"),
        ApiResponse(responseCode = "404", description = "No seasonal flight found for this date/flight"),
        ApiResponse(responseCode = "500", description = "Database error"),
    )
    open fun getSeasonalFlight(
        @Parameter(description = "Flight number (e.g., MU9941)", example = "MU9941", required = true)
        flightNumber: String,
        @Parameter(description = "Flight date in format yyMMdd (e.g., 211231)", example = "211231", required = true)
        flightDateString: String,
    ): HttpResponse<*> {
        val normalizedFlightNumber = flightNumber.uppercase()
        validateFlightNumber(normalizedFlightNumber)

        val flightDate = esttService.parseFlightDate(flightDateString)
        log.info("Request: GET /estt/seasonal/$normalizedFlightNumber/$flightDateString -> $flightDate")

        return esttService.getSeasonalFlight(normalizedFlightNumber, flightDate).fold(
            onSuccess = { flight ->
                log.info("Response: seasonal flight=${flight?.flightNumber ?: "not found"}")
                if (flight != null) {
                    HttpResponse.ok(flight)
                } else {
                    HttpResponse.notFound<SeasonalFlight>()
                }
            },
            onFailure = { e ->
                log.error("Failed to get seasonal flight for $normalizedFlightNumber", e)
                HttpResponse.serverError(
                    ErrorCode.DATABASE_ERROR.toErrorResponse(e.message ?: "Database access failed"),
                )
            },
        )
    }

    @Get(uri = "/history/{flightNumber}/{flightDateString}{?limit,offset}")
    @Timed(value = "estt.api.history", description = "Get flight history API")
    @Counted(value = "estt.api.history.calls", description = "Number of history queries")
    @Operation(
        summary = "Get historical flights",
        description = "Returns historical flight records matching the operational day and date criteria. " +
            "Supports optional pagination via limit and offset query parameters.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "History retrieved successfully",
            content = [Content(schema = Schema(implementation = PaginatedHistoryResponse::class))],
        ),
        ApiResponse(responseCode = "400", description = "Invalid flight number, date format, or pagination parameters"),
        ApiResponse(responseCode = "500", description = "Database error"),
    )
    open fun getHistoryFlights(
        @Parameter(description = "Flight number (e.g., MU9941)", example = "MU9941", required = true)
        flightNumber: String,
        @Parameter(description = "Flight date in format yyMMdd (e.g., 211231)", example = "211231", required = true)
        flightDateString: String,
        @Parameter(description = "Maximum number of results to return", example = "100")
        @QueryValue(defaultValue = "100") limit: Int,
        @Parameter(description = "Number of results to skip", example = "0")
        @QueryValue(defaultValue = "0") offset: Int,
    ): HttpResponse<*> {
        // Validate pagination parameters
        require(limit in 1..1000) {
            "Limit must be between 1 and 1000, got: $limit"
        }
        require(offset >= 0) {
            "Offset must be non-negative, got: $offset"
        }

        val normalizedFlightNumber = flightNumber.uppercase()
        validateFlightNumber(normalizedFlightNumber)

        val flightDate = esttService.parseFlightDate(flightDateString)
        log.info(
            "Request: GET /estt/history/{}/{} limit={} offset={}",
            normalizedFlightNumber,
            flightDateString,
            limit,
            offset,
        )

        return esttService.getPaginatedHistoryFlights(normalizedFlightNumber, flightDate, offset, limit).fold(
            onSuccess = { paginatedResponse ->
                log.info(
                    "Response: total={}, returned={}, hasMore={}",
                    paginatedResponse.totalFiltered,
                    paginatedResponse.items.size,
                    paginatedResponse.hasMore,
                )
                HttpResponse.ok(paginatedResponse)
            },
            onFailure = { e ->
                log.error("Failed to get paginated history flights for $normalizedFlightNumber", e)
                HttpResponse.serverError(
                    ErrorCode.DATABASE_ERROR.toErrorResponse(e.message ?: "Database access failed"),
                )
            },
        )
    }

    @Get(uri = "/flyTime/{flightNumber}/{flightDateString}")
    @Timed(value = "estt.api.calculate", description = "Flying time calculation API")
    @Counted(value = "estt.api.calculate.calls", description = "Number of calculation requests")
    @Operation(
        summary = "Calculate flying time",
        description = "Calculates estimated flying time based on historical data or seasonal schedule. " +
            "Uses the median of qualified flights when sufficient history exists (min 20 flights), " +
            "otherwise uses the seasonal schedule flying time.",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Calculation successful",
            content = [Content(schema = Schema(implementation = FlyingTimeResponse::class))],
        ),
        ApiResponse(responseCode = "400", description = "Invalid flight number or date format"),
        ApiResponse(responseCode = "500", description = "Calculation error"),
    )
    open fun calculate(
        @Parameter(description = "Flight number (e.g., MU9941)", example = "MU9941", required = true)
        flightNumber: String,
        @Parameter(description = "Flight date in format yyMMdd (e.g., 211231)", example = "211231", required = true)
        flightDateString: String,
    ): HttpResponse<*> {
        val normalizedFlightNumber = flightNumber.uppercase()
        validateFlightNumber(normalizedFlightNumber)

        val flightDate = esttService.parseFlightDate(flightDateString)
        log.info("Request: GET /estt/flyTime/$normalizedFlightNumber/$flightDateString")

        return esttService.calculate(normalizedFlightNumber, flightDate).fold(
            onSuccess = { result ->
                log.info("Response: flyingTime=${result.flyingTime}, history=${result.history}, seasonal=${result.seasonal}")
                HttpResponse.ok(result)
            },
            onFailure = { e ->
                log.error("Calculation failed for $normalizedFlightNumber", e)
                HttpResponse.serverError(
                    ErrorCode.CALCULATION_ERROR.toErrorResponse(e.message ?: "Calculation failed"),
                )
            },
        )
    }

    private fun validateFlightNumber(flightNumber: String) {
        require(flightNumber.isNotBlank()) { "Flight number cannot be empty" }
        require(flightNumber.matches(flightNumberRegex)) {
            "Invalid flight number format: $flightNumber. Expected format: AA1234 (2 letters + 3-4 digits)"
        }
    }
}
