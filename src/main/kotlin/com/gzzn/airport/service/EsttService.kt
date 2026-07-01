package com.gzzn.airport.service

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.exception.InvalidFlightDateException
import com.gzzn.airport.model.Confidence
import com.gzzn.airport.model.EstimateSource
import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.NoEstimateReason
import com.gzzn.airport.model.OperationDays
import com.gzzn.airport.model.PaginatedHistoryResponse
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.SeasonRepository
import com.gzzn.airport.service.calculator.FlyingTimeCalculator
import com.gzzn.airport.service.history.HistoryFlightProvider
import com.gzzn.airport.util.flatMap
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micronaut.cache.annotation.Cacheable
import io.micronaut.retry.annotation.CircuitBreaker
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Top-level orchestration service for Estimated Seasonal Turnaround Time (ESTT) calculations.
 *
 * Delegates responsibilities to collaborators:
 * - [HistoryFlightProvider] handles data access and filtering of historical flights.
 * - [FlyingTimeCalculator] applies median/fallback rules and records metrics.
 * - [EsttCalculationConfig] exposes validated configuration values shared across the workflow.
 *
 * The service keeps the controller-facing API small while remaining composable for tests.
 */
@Singleton
open class EsttService(
    private val seasonRepository: SeasonRepository,
    private val historyFlightProvider: HistoryFlightProvider,
    private val flyingTimeCalculator: FlyingTimeCalculator,
    private val meterRegistry: MeterRegistry,
    private val config: EsttCalculationConfig,
) {
    companion object {
        private val log = LoggerFactory.getLogger(EsttService::class.java)
        private val YYMMDD_PATTERN = Regex("\\d{6}")
    }

    init {
        log.info("✅ EsttService initialized successfully")
        log.info("   - maxScheduleDeviation: ${config.maxScheduleDeviation} minutes")
        log.info("   - maxFlyingTimeDeviation: ${config.maxFlyingTimeDeviation} minutes")
        log.info("   - minHistoryFlight: ${config.minHistoryFlight} flights")
        log.info("   - historyStartOffsetDays: ${config.historyStartOffsetDays} days")
        log.info("   - maxHistoryRows: ${config.maxHistoryRows} rows")
        log.info("   - dateFormat: ${config.dateFormat}")
    }

    /**
     * Get the active flight season (cached for 1 hour).
     * Cache provides sufficient protection; no circuit breaker needed.
     * @return the current flight season wrapped in a Result.
     */
    @Cacheable("active-season")
    open fun cachedActiveSeason(): FlightSeason? {
        val activeFlightSeason = seasonRepository.getFlightSeason(true)
        log.info("Active flight season: $activeFlightSeason")
        return activeFlightSeason
    }

    open fun getActiveSeason(): Result<FlightSeason?> = runCatching { cachedActiveSeason() }
        .onFailure { e ->
            log.error("Error getting active flight season", e)
        }

    /**
     * Parse a date string into a [LocalDate] using the configured [EsttCalculationConfig.dateFormat].
     *
     * Only `yyMMdd` is supported in v1. Input must be exactly six digits with no trailing
     * characters. Years 00–99 map to 2000–2099.
     */
    open fun parseFlightDate(dateString: String): LocalDate {
        if (config.dateFormat != "yyMMdd") {
            throw InvalidFlightDateException("Unsupported date format: ${config.dateFormat}")
        }
        if (!YYMMDD_PATTERN.matches(dateString)) {
            throw InvalidFlightDateException("Invalid flight date: $dateString")
        }
        val formatter = DateTimeFormatter.ofPattern("uuMMdd")
            .withResolverStyle(ResolverStyle.STRICT)
        return try {
            LocalDate.parse(dateString, formatter)
        } catch (e: DateTimeParseException) {
            throw InvalidFlightDateException("Invalid flight date: $dateString", e)
        }
    }

    /**
     * Get the day of the week for a given flight date.
     * @param flightDate the flight date.
     * @return the day of the week as an integer.
     */
    private fun getOperationDay(flightDate: LocalDate): Int = flightDate.dayOfWeek.value

    /**
     * Check if a specific day of week matches the operation days string.
     * Parse operation days as individual digits to avoid false matches (e.g., "1" matching "12").
     * @param operationDays the operation days string from seasonal flight (e.g., "1234567")
     * @param dayOfWeek the day of week to check (1-7, where 1 is Monday)
     * @return true if the day of week is included in the operation days
     */
    internal fun isOperationDayMatch(operationDays: String, dayOfWeek: Int): Boolean = OperationDays.matches(operationDays, dayOfWeek)

    /**
     * Find a seasonal flight for a given flight number and date.
     * Cached for 4 hours since seasonal schedules rarely change.
     * Fast-fail circuit breaker: 10 attempts with 500ms delay, giving database 5 seconds total.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return the seasonal flight if found, null otherwise, wrapped in a Result.
     */
    @Cacheable("seasonal-flight")
    @CircuitBreaker(attempts = "10", delay = "500ms", reset = "60s")
    open fun cachedSeasonalFlight(flightNumber: String, flightDate: LocalDate): SeasonalFlight? {
        val operationDay = getOperationDay(flightDate)
        log.debug("Finding seasonal flight for flight number $flightNumber, operation day $operationDay")

        // Pass operationDay as string without wildcards (INSTR handles the search)
        val seasonalFlight = seasonRepository.getSeasonalArrivalFlight(flightNumber, operationDay.toString())

        // Additional validation: ensure the operation day actually matches
        // Using helper function to avoid false matches like "1" matching "12"
        val validatedFlight = seasonalFlight?.let {
            val operationDayMatches = isOperationDayMatch(it.operationDays, operationDay)
            val withinSeasonBounds = !flightDate.isBefore(it.seasonStart) && !flightDate.isAfter(it.seasonEnd)

            if (!operationDayMatches) {
                log.warn(
                    "Seasonal flight found but operation day validation failed: flight={}, day={}, operationDays={}",
                    flightNumber,
                    operationDay,
                    it.operationDays,
                )
            }

            if (!withinSeasonBounds) {
                log.warn(
                    "Seasonal flight found but date {} is outside season window: start={}, end={}",
                    flightDate,
                    it.seasonStart,
                    it.seasonEnd,
                )
            }

            if (operationDayMatches && withinSeasonBounds) it else null
        }

        log.debug("Validated seasonal flight: {}", validatedFlight)
        return validatedFlight
    }

    open fun getSeasonalFlight(flightNumber: String, flightDate: LocalDate): Result<SeasonalFlight?> = runCatching {
        cachedSeasonalFlight(flightNumber, flightDate)
    }
        .onFailure { e ->
            log.error("Error finding seasonal flight for $flightNumber on $flightDate", e)
        }

    /**
     * Get historical flights for a given flight number and date.
     * Cached for 4 hours since historical data rarely changes.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a list of historical flights wrapped in a Result.
     */
    @Cacheable("history-flights")
    open fun cachedHistoryFlights(flightNumber: String, flightDate: LocalDate): List<HistoricalFlight> {
        val seasonalFlight = cachedSeasonalFlight(flightNumber, flightDate)
        if (seasonalFlight == null) {
            // Business case: no seasonal flight, cache empty list
            return emptyList()
        }
        return historyFlightProvider.getHistoryFlights(seasonalFlight, flightDate)
    }

    open fun getHistoryFlights(flightNumber: String, flightDate: LocalDate): Result<List<HistoricalFlight>> = runCatching {
        cachedHistoryFlights(flightNumber, flightDate)
    }
        .onFailure { e ->
            log.error("Error getting history flights for $flightNumber on $flightDate", e)
        }

    /**
     * Get paginated historical flights for a given flight number and date.
     * Performs chunked repository pagination (bounded by `maxHistoryRows`)
     * and applies business filtering before building the page.
     *
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @param offset the number of filtered results to skip.
     * @param limit the maximum number of filtered results to return.
     * @return paginated response with metadata wrapped in a Result.
     */
    open fun getPaginatedHistoryFlights(
        flightNumber: String,
        flightDate: LocalDate,
        offset: Int,
        limit: Int,
    ): Result<PaginatedHistoryResponse> {
        return runCatching {
            val seasonalFlight = cachedSeasonalFlight(flightNumber, flightDate)
            if (seasonalFlight == null) {
                return@runCatching PaginatedHistoryResponse(
                    items = emptyList(),
                    totalFiltered = 0,
                    offset = offset,
                    limit = limit,
                    hasMore = false,
                )
            }

            val paginated = historyFlightProvider.getPaginatedHistory(seasonalFlight, flightDate, offset, limit)
            log.debug(
                "Paginated history for {}: returned={}, totalFiltered={}, hasMore={}, offset={}, limit={}, capped={}",
                seasonalFlight.flightNumber,
                paginated.items.size,
                paginated.totalFiltered,
                paginated.hasMore,
                offset,
                limit,
                paginated.totalFiltered >= config.maxHistoryRows,
            )
            if (paginated.hasMore || paginated.totalFiltered >= config.maxHistoryRows) {
                log.info(
                    "History pagination truncated for {}: hasMore={}, filtered={}, offset={}, limit={}, maxRows={}",
                    seasonalFlight.flightNumber,
                    paginated.hasMore,
                    paginated.totalFiltered,
                    offset,
                    limit,
                    config.maxHistoryRows,
                )
            }
            paginated
        }.onFailure { e ->
            log.error("Error getting paginated history flights for $flightNumber on $flightDate", e)
        }
    }

    /**
     * Get historical flights that match a given seasonal flight and date.
     *
     * This method exists to keep the primary result pipeline ([calculateWithSeasonalFlight]) clean
     * and to reuse error handling (logging + Result failure propagation).
     */
    private fun getHistoryFlightsWithSeasonFlight(seasonalFlight: SeasonalFlight?, flightDate: LocalDate): Result<List<HistoricalFlight>> {
        return runCatching {
            if (seasonalFlight == null) {
                log.warn("seasonal flight is null, no history flight")
                return@runCatching emptyList()
            }
            historyFlightProvider.getHistoryFlights(seasonalFlight, flightDate)
        }.onFailure { e ->
            log.error("Error getting history flights with seasonal flight for $flightDate", e)
        }
    }

    /**
     * Calculate flying time for a given flight number and date.
     * Uses MDC for structured logging and records metrics for monitoring.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a FlyingTimeResponse object wrapped in a Result.
     */
    open fun calculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse> {
        validateInputs(flightNumber, flightDate)

        // Add structured logging context
        MDC.put("flightNumber", flightNumber)
        MDC.put("flightDate", flightDate.toString())

        // Start timing for metrics
        val timer = Timer.start(meterRegistry)

        try {
            log.info("Starting flying time calculation")
            return fetchAndCalculate(flightNumber, flightDate)
                .onSuccess { response -> recordSuccessMetrics(timer, response) }
                .onFailure { e -> recordFailureMetrics(timer, e) }
        } finally {
            // Remove only the keys we added to avoid clearing context from other threads
            MDC.remove("flightNumber")
            MDC.remove("flightDate")
        }
    }

    /**
     * Validate input parameters for the calculation.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     */
    private fun validateInputs(flightNumber: String, flightDate: LocalDate) =
        EsttInputValidator.validateCalculationInputs(flightNumber, flightDate)

    /**
     * Fetch data and perform the calculation.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a FlyingTimeResponse wrapped in a Result.
     */
    private fun fetchAndCalculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse> =
        getSeasonalFlight(flightNumber, flightDate)
            .flatMap { seasonalFlight ->
                if (seasonalFlight == null) {
                    log.warn(NoEstimateReason.NO_SEASONAL_FLIGHT.message)
                    Result.success(
                        buildNoEstimateResponse(flightNumber, flightDate, NoEstimateReason.NO_SEASONAL_FLIGHT),
                    )
                } else {
                    calculateWithSeasonalFlight(seasonalFlight, flightNumber, flightDate)
                }
            }

    /**
     * Record metrics for successful calculation.
     * @param timer the timer to stop.
     * @param flightNumber the flight number.
     * @param response the response.
     */
    private fun recordSuccessMetrics(timer: Timer.Sample, response: FlyingTimeResponse) {
        val sourceTag = when (response.source) {
            EstimateSource.HISTORY -> "history"
            EstimateSource.SEASONAL -> "schedule"
            EstimateSource.NONE -> "none"
        }
        timer.stop(
            meterRegistry.timer(
                "estt.calculation.time",
                "source",
                sourceTag,
                "result",
                "success",
            ),
        )
        meterRegistry.counter(
            "estt.calculation.success",
            "source",
            sourceTag,
        ).increment()
    }

    /**
     * Record metrics for failed calculation.
     * @param timer the timer to stop.
     * @param exception the exception.
     */
    private fun recordFailureMetrics(timer: Timer.Sample, exception: Throwable) {
        timer.stop(
            meterRegistry.timer(
                "estt.calculation.time",
                "result",
                "failure",
                "error",
                exception.javaClass.simpleName,
            ),
        )
        meterRegistry.counter(
            "estt.calculation.failure",
            "error",
            exception.javaClass.simpleName,
        ).increment()
        log.error("Flying time calculation failed", exception)
    }

    /**
     * Calculate flying time with a known seasonal flight using historical data.
     * @param seasonalFlight the seasonal flight to use for calculation.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a FlyingTimeResponse object wrapped in a Result.
     */
    private fun calculateWithSeasonalFlight(
        seasonalFlight: SeasonalFlight,
        flightNumber: String,
        flightDate: LocalDate,
    ): Result<FlyingTimeResponse> = getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
        .map { historyFlights ->
            val result = flyingTimeCalculator.calculate(seasonalFlight, flightNumber, historyFlights)
            if (result.source == EstimateSource.NONE) {
                buildNoEstimateResponse(
                    flightNumber,
                    flightDate,
                    NoEstimateReason.INSUFFICIENT_HISTORY_NO_SEASONAL_TIME,
                )
            } else {
                FlyingTimeResponse(
                    flightNumber,
                    flightDate,
                    result.flyingTime,
                    result.historyUsed,
                    result.source == EstimateSource.SEASONAL,
                    result.message,
                    result.source,
                    result.sampleSize,
                    result.confidence,
                )
            }
        }

    private fun buildNoEstimateResponse(flightNumber: String, flightDate: LocalDate, reason: NoEstimateReason): FlyingTimeResponse =
        FlyingTimeResponse(
            flightNumber,
            flightDate,
            null,
            false,
            false,
            reason.message,
            EstimateSource.NONE,
            0,
            Confidence.NONE,
        )
}
