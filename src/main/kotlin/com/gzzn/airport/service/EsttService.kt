package com.gzzn.airport.service

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.PaginatedHistoryResponse
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.repository.SeasonRepository
import com.gzzn.airport.util.flatMap
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micronaut.cache.annotation.Cacheable
import io.micronaut.context.annotation.Value
import io.micronaut.retry.annotation.CircuitBreaker
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.roundToLong

@Singleton
open class EsttService(
    private val seasonRepository: SeasonRepository,
    private val historyFlightRepository: HistoryFlightRepository,
    private val meterRegistry: MeterRegistry,
    @Value("\${estt.calculation.max-history-delay:120}") val maxHistoryDelay: Int,
    @Value("\${estt.calculation.min-history-flight:20}") val minHistoryFlight: Int,
    @Value("\${estt.calculation.date-format}") val dateFormat: String,
    @Value("\${estt.calculation.history-start-offset-days}") val historyStartOffsetDays: Long,
    @Value("\${estt.calculation.max-history-rows:300}") val maxHistoryRows: Int
) {
    companion object {
        private val log = LoggerFactory.getLogger(EsttService::class.java)
    }

    @PostConstruct
    fun init() {
        // Validate configuration values
        require(minHistoryFlight > 0) {
            "Configuration error: estt.calculation.min-history-flight must be positive, got: $minHistoryFlight"
        }
        require(maxHistoryDelay > 0) {
            "Configuration error: estt.calculation.max-history-delay must be positive, got: $maxHistoryDelay"
        }
        require(maxHistoryRows > 0) {
            "Configuration error: estt.calculation.max-history-rows must be positive, got: $maxHistoryRows"
        }
        require(historyStartOffsetDays >= 0) {
            "Configuration error: estt.calculation.history-start-offset-days must be non-negative, got: $historyStartOffsetDays"
        }
        require(dateFormat.isNotBlank()) {
            "Configuration error: estt.calculation.date-format must not be blank"
        }
        
        log.info("✅ EsttService initialized successfully")
        log.info("   - maxHistoryDelay: $maxHistoryDelay minutes")
        log.info("   - minHistoryFlight: $minHistoryFlight flights")
        log.info("   - historyStartOffsetDays: $historyStartOffsetDays days")
        log.info("   - maxHistoryRows: $maxHistoryRows rows")
        log.info("   - dateFormat: $dateFormat")
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

    open fun getActiveSeason(): Result<FlightSeason?> {
        return runCatching { cachedActiveSeason() }
            .onFailure { e ->
                log.error("Error getting active flight season", e)
            }
    }

    /**
     * Parse a date string into a LocalDate object using the configured date format.
     * @param dateString the date string to parse.
     * @return the parsed LocalDate object.
     */
    open fun parseFlightDate(dateString: String): LocalDate {
        return try {
            LocalDate.parse(dateString, DateTimeFormatter.ofPattern(dateFormat))
        } catch (e: DateTimeParseException) {
            log.error("Error parsing flight date: $dateString", e)
            throw IllegalArgumentException("Invalid date format: $dateString. Expected: $dateFormat", e)
        }
    }

    /**
     * Get the day of the week for a given flight date.
     * @param flightDate the flight date.
     * @return the day of the week as an integer.
     */
    private fun getOperationDay(flightDate: LocalDate): Int {
        return flightDate.dayOfWeek.value
    }

    /**
     * Check if a specific day of week matches the operation days string.
     * Parse operation days as individual digits to avoid false matches (e.g., "1" matching "12").
     * @param operationDays the operation days string from seasonal flight (e.g., "1234567")
     * @param dayOfWeek the day of week to check (1-7, where 1 is Monday)
     * @return true if the day of week is included in the operation days
     */
    internal fun isOperationDayMatch(operationDays: String, dayOfWeek: Int): Boolean {
        return operationDays.any { it.toString().toIntOrNull() == dayOfWeek }
    }

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
         val validatedFlight = seasonalFlight?.takeIf {
             isOperationDayMatch(it.operationDays, operationDay)
         }

         if (seasonalFlight != null && validatedFlight == null) {
             log.warn("Seasonal flight found but operation day validation failed: flight=$flightNumber, day=$operationDay, operationDays=${seasonalFlight.operationDays}")
         }

         log.debug("Validated seasonal flight: {}", validatedFlight)
         return validatedFlight
     }

     open fun getSeasonalFlight(flightNumber: String, flightDate: LocalDate): Result<SeasonalFlight?> {
         return runCatching { cachedSeasonalFlight(flightNumber, flightDate) }
             .onFailure { e ->
                 log.error("Error finding seasonal flight for $flightNumber on $flightDate", e)
             }
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
        return loadHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
    }

    open fun getHistoryFlights(flightNumber: String, flightDate: LocalDate): Result<List<HistoricalFlight>> {
        return runCatching { cachedHistoryFlights(flightNumber, flightDate) }
            .onFailure { e ->
                log.error("Error getting history flights for $flightNumber on $flightDate", e)
            }
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
    open fun getPaginatedHistoryFlights(flightNumber: String, flightDate: LocalDate, offset: Int, limit: Int): Result<PaginatedHistoryResponse> {
        return runCatching {
            val seasonalFlight = cachedSeasonalFlight(flightNumber, flightDate)
            if (seasonalFlight == null) {
                return@runCatching PaginatedHistoryResponse(
                    items = emptyList(),
                    totalFiltered = 0,
                    offset = offset,
                    limit = limit,
                    hasMore = false
                )
            }

            val paginated = loadPaginatedHistory(seasonalFlight, flightDate, offset, limit)
            log.debug(
                "Paginated history for ${seasonalFlight.flightNumber}: returned=${paginated.items.size}, totalFiltered=${paginated.totalFiltered}, hasMore=${paginated.hasMore}"
            )
            paginated
        }.onFailure { e ->
            log.error("Error getting paginated history flights for $flightNumber on $flightDate", e)
        }
    }

    /**
     * Get historical flights that match a given seasonal flight and date.
     * @param seasonalFlight the seasonal flight.
     * @param flightDate the flight date.
     * @return a list of historical flights wrapped in a Result.
     */
    private fun getHistoryFlightsWithSeasonFlight(
        seasonalFlight: SeasonalFlight?,
        flightDate: LocalDate
    ): Result<List<HistoricalFlight>> {
        return runCatching {
            if (seasonalFlight == null) {
                log.warn("seasonal flight is null, no history flight")
                return@runCatching emptyList()
            }
            loadHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
        }.onFailure { e ->
            log.error("Error getting history flights with seasonal flight for $flightDate", e)
        }
    }

    private fun loadHistoryFlightsWithSeasonFlight(
        seasonalFlight: SeasonalFlight,
        flightDate: LocalDate
    ): List<HistoricalFlight> {
        val seasonStart = calculateHistoryStartDate(seasonalFlight.seasonStart)
        log.debug("Querying historical flights for ${seasonalFlight.flightNumber} from $seasonStart to $flightDate")
        val historyFlights = historyFlightRepository.getArrivalFlight(
            seasonalFlight.flightNumber,
            seasonStart,
            flightDate,
            maxHistoryRows  // Fetch max records from configuration
        )
        // Filter the historical flights to include only those that match the criteria
        val filtered = historyFlights.asSequence()
            .filter { historyFlight -> isHistoryFlight(seasonalFlight, historyFlight) }
            .toList()
        log.debug("Retrieved ${historyFlights.size} historical flights, filtered to ${filtered.size} valid flights")
        return filtered
    }

    /**
     * Fetch paginated history flights by iterating over repository pages until either the requested
     * window is satisfied or the configured `maxHistoryRows` cap is reached.
     *
     * @return a [PaginatedHistoryResponse] whose total/hasMore are computed against the scanned window.
     */
    private fun loadPaginatedHistory(
        seasonalFlight: SeasonalFlight,
        flightDate: LocalDate,
        offset: Int,
        limit: Int
    ): PaginatedHistoryResponse {
        val seasonStart = calculateHistoryStartDate(seasonalFlight.seasonStart)
        val items = mutableListOf<HistoricalFlight>()
        var totalFiltered = 0
        var rawOffset = 0
        val chunkSize = maxOf(limit, 100)

        while (rawOffset < maxHistoryRows) {
            val fetchSize = minOf(chunkSize, maxHistoryRows - rawOffset)
            val batch = historyFlightRepository.getArrivalFlightPage(
                seasonalFlight.flightNumber,
                seasonStart,
                flightDate,
                rawOffset,
                fetchSize
            )
            if (batch.isEmpty()) {
                break
            }

            rawOffset += batch.size

            val filteredBatch = batch.filter { isHistoryFlight(seasonalFlight, it) }
            for (flight in filteredBatch) {
                when {
                    totalFiltered < offset -> totalFiltered++
                    items.size < limit -> {
                        items += flight
                        totalFiltered++
                    }
                    else -> {
                        totalFiltered++
                    }
                }
            }

            if (batch.size < fetchSize) {
                break
            }
        }

        return PaginatedHistoryResponse(
            items = items,
            totalFiltered = totalFiltered,
            offset = offset,
            limit = limit,
            hasMore = totalFiltered > offset + items.size
        )
    }

    /**
     * Validates if a historical flight should be included in flying time calculation.
     * 
     * This method implements critical business logic for filtering flights. A flight is valid
     * if ALL of the following conditions are met:
     * 
     * 1. **Operation day matches the seasonal schedule**: Ensures we only use flights that operated
     *    on the same day of week as the target flight. This prevents using data from different
     *    flight plans (e.g., weekday vs. weekend schedules may have different routes/times).
     * 
     * 2. **Flight time order is correct**: Validates that previousDepartureTime < actualTime.
     *    This filters out data corruption or incorrect records in the database.
     * 
     * 3. **Flying time is within maxHistoryDelay of seasonal time**: Excludes extreme delays,
     *    diversions, or abnormal flights. Only flights with flying times reasonably close to
     *    the scheduled time are used for calculation (default: within 120 minutes).
     * 
     * @param seasonalFlight the seasonal schedule flight to compare against (contains operation days and expected flying time)
     * @param historyFlight the historical flight to validate
     * @return true if the flight should be included in calculations, false if it should be excluded
     */
    private fun isHistoryFlight(seasonalFlight: SeasonalFlight?, historyFlight: HistoricalFlight): Boolean {
        if (seasonalFlight == null) {
            log.warn("seasonal flight is null!")
            return false
        }

        val seasonalFlyingTime = seasonalFlight.flyingTime
        val operationDay = getOperationDay(historyFlight.flightDate)
        val actualFlyTime = calculateDurationMinutes(historyFlight.previousDepartureTime, historyFlight.actualTime)

        // Check if the operation day matches, the flight time order is correct, and the delay is within the maximum allowed delay
        val operationDayMatches = isOperationDayMatch(seasonalFlight.operationDays, operationDay)
        val flightTimeOrderCorrect = historyFlight.previousDepartureTime < historyFlight.actualTime
        val scheduledDateMatches = historyFlight.scheduledTime.toLocalDate() == historyFlight.flightDate
        val withinMaxDelay = abs(actualFlyTime - seasonalFlyingTime) < maxHistoryDelay

        val result = operationDayMatches && flightTimeOrderCorrect && scheduledDateMatches && withinMaxDelay

        // Only log excluded flights to reduce log volume
        if (!result && log.isDebugEnabled) {
            log.debug("Excluded history flight on ${historyFlight.flightDate}: " +
                     "dayMatch=$operationDayMatches, timeOrder=$flightTimeOrderCorrect, " +
                     "dateMatch=$scheduledDateMatches, withinDelay=$withinMaxDelay")
        }
        
        return result
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
                .onSuccess { response -> recordSuccessMetrics(timer, flightNumber, response) }
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
    private fun validateInputs(flightNumber: String, flightDate: LocalDate) {
        require(flightNumber.isNotBlank()) { "Flight number cannot be empty" }
        require(flightNumber.length in 5..6) { "Flight number must be 5-6 characters, got: ${flightNumber.length}" }
        require(flightDate.isAfter(LocalDate.of(2000, 1, 1))) {
            "Flight date must be after 2000-01-01, got: $flightDate"
        }
        require(flightDate.isBefore(LocalDate.now().plusYears(1))) {
            "Flight date cannot be more than 1 year in the future, got: $flightDate"
        }
    }

    /**
     * Fetch data and perform the calculation.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a FlyingTimeResponse wrapped in a Result.
     */
    private fun fetchAndCalculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse> {
        return getSeasonalFlight(flightNumber, flightDate)
            .flatMap { seasonalFlight ->
                if (seasonalFlight == null) {
                    // Business case: no seasonal flight found
                    log.warn("No seasonal flight found")
                    Result.success(FlyingTimeResponse(
                        flightNumber, flightDate, 0, false,
                        seasonal = false,
                        message = "No seasonal flight found"
                    ))
                } else {
                    calculateWithSeasonalFlight(seasonalFlight, flightNumber, flightDate)
                }
            }
    }

    /**
     * Record metrics for successful calculation.
     * @param timer the timer to stop.
     * @param flightNumber the flight number.
     * @param response the response.
     */
    private fun recordSuccessMetrics(timer: Timer.Sample, flightNumber: String, response: FlyingTimeResponse) {
        timer.stop(meterRegistry.timer(
            "estt.calculation.time",
            "flight", flightNumber,
            "source", if (response.history) "history" else "schedule",
            "result", "success"
        ))
        meterRegistry.counter(
            "estt.calculation.success",
            "source", if (response.history) "history" else "schedule"
        ).increment()
    }

    /**
     * Record metrics for failed calculation.
     * @param timer the timer to stop.
     * @param exception the exception.
     */
    private fun recordFailureMetrics(timer: Timer.Sample, exception: Throwable) {
        timer.stop(meterRegistry.timer(
            "estt.calculation.time",
            "result", "failure",
            "error", exception.javaClass.simpleName
        ))
        meterRegistry.counter(
            "estt.calculation.failure",
            "error", exception.javaClass.simpleName
        ).increment()
        log.error("Flying time calculation failed", exception)
    }

    /**
     * Calculate flying time with a known seasonal flight.
     * @param seasonalFlight the seasonal flight to use for calculation.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a FlyingTimeResponse object wrapped in a Result.
     */
    private fun calculateWithSeasonalFlight(
        seasonalFlight: SeasonalFlight,
        flightNumber: String,
        flightDate: LocalDate
    ): Result<FlyingTimeResponse> {
        // Validate seasonal flight has valid flying time
        validateSeasonalFlight(seasonalFlight, flightNumber)

        return getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
            .map { historyFlights ->
                val qualifiedFlights = getQualifiedHistoryFlights(historyFlights)
                val (flyingTime, message) = determineFlyingTimeAndMessage(qualifiedFlights, seasonalFlight, flightNumber)
                val historyExists = qualifiedFlights.isNotEmpty()

                FlyingTimeResponse(
                    flightNumber, flightDate, flyingTime, historyExists, true, message
                )
            }
    }

    /**
     * Validate that the seasonal flight has a valid flying time.
     * @param seasonalFlight the seasonal flight to validate.
     * @param flightNumber the flight number for error messages.
     */
    private fun validateSeasonalFlight(seasonalFlight: SeasonalFlight, flightNumber: String) {
        require(seasonalFlight.flyingTime > 0) {
            "Invalid seasonal flight time: ${seasonalFlight.flyingTime} for flight $flightNumber"
        }
    }

    /**
     * Determine the flying time and message based on qualified historical flights.
     * @param qualifiedFlights the list of qualified historical flights.
     * @param seasonalFlight the seasonal flight.
     * @param flightNumber the flight number.
     * @return a pair of flying time and message.
     */
    private fun determineFlyingTimeAndMessage(
        qualifiedFlights: List<HistoricalFlight>,
        seasonalFlight: SeasonalFlight,
        flightNumber: String
    ): Pair<Long, String> {
        val shouldUseHistoricalAverage = qualifiedFlights.size >= minHistoryFlight

        log.debug("Found ${qualifiedFlights.size} qualified history flights (minimum required: $minHistoryFlight)")

        return if (shouldUseHistoricalAverage) {
            val flightsUsed = minOf(qualifiedFlights.size, minHistoryFlight)
            val flyingTime = calculateAverageFlightTime(qualifiedFlights)
            val message = "Calculated from $flightsUsed historical flights (${qualifiedFlights.size} available)"
            log.info("$flightNumber: Calculated flying time $flyingTime minutes from $flightsUsed of ${qualifiedFlights.size} historical flights")

            // Record business metrics
            meterRegistry.counter("estt.calculation.source", "source", "history").increment()
            val accuracy = abs(flyingTime - seasonalFlight.flyingTime)
            meterRegistry.counter("estt.calculation.accuracy", "accuracy", accuracy.toString()).increment()
            if (accuracy <= 10) {
                meterRegistry.counter("estt.calculation.high_accuracy", "source", "history").increment()
            }

            Pair(flyingTime, message)
        } else {
            val flyingTime = seasonalFlight.flyingTime
            val message = "Using seasonal flight flying time due to insufficient historical data"
            log.warn("$flightNumber: Insufficient history (${qualifiedFlights.size}/${minHistoryFlight}). Using seasonal time: $flyingTime minutes")

            // Record business metrics
            meterRegistry.counter("estt.calculation.source", "source", "schedule").increment()

            Pair(flyingTime, message)
        }
    }

    /**
     * Calculate the average flight time from a list of qualified historical flights.
     * Uses double division with rounding for accurate results.
     * @param qualifiedFlights the list of qualified historical flights.
     * @return the average flight time rounded to the nearest minute.
     */
    private fun calculateAverageFlightTime(qualifiedFlights: List<HistoricalFlight>): Long {
        if (qualifiedFlights.isEmpty()) return 0
        val flightsToUse = minOf(qualifiedFlights.size, minHistoryFlight)
        val totalMinutes = qualifiedFlights.take(flightsToUse)
            .sumOf { calculateDurationMinutes(it.previousDepartureTime, it.actualTime) }
        return (totalMinutes.toDouble() / flightsToUse).roundToLong()
    }

    /**
     * Get qualified historical flights by filtering and sorting the given list.
     * @param historyFlights the list of historical flights.
     * @return a list of qualified historical flights.
     */
    private fun getQualifiedHistoryFlights(historyFlights: List<HistoricalFlight>): List<HistoricalFlight> {
        return historyFlights.asSequence()
            .filter { isFlightDelayAcceptable(it) }
            .sortedByDescending { it.scheduledTime }
            .toList()
    }

    /**
     * Check if a historical flight has a delay within the maximum allowed delay.
     * @param historyFlight the historical flight.
     * @return true if the delay is within the maximum allowed delay, false otherwise.
     */
    private fun isFlightDelayAcceptable(historyFlight: HistoricalFlight): Boolean {
        val delayMinutes = calculateDurationMinutes(historyFlight.scheduledTime, historyFlight.actualTime)
        return abs(delayMinutes) < maxHistoryDelay
    }

    /**
     * Calculate the difference in minutes between two LocalDateTime objects.
     * @param startTime the start time.
     * @param endTime the end time.
     * @return the difference in minutes.
     */
    private fun calculateDurationMinutes(startTime: LocalDateTime, endTime: LocalDateTime): Long {
        return Duration.between(startTime, endTime).toMinutes()
    }

    /**
     * Get the start date for historical flights based on the season start date.
     * @param seasonStart the season start date.
     * @return the start date for historical flights.
     */
    private fun calculateHistoryStartDate(seasonStart: LocalDate): LocalDate {
        return seasonStart.minusDays(historyStartOffsetDays)
    }
}