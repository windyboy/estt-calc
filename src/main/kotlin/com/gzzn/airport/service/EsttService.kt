package com.gzzn.airport.service

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.HistoricalFlight
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
        log.info("EsttService initialized with maxHistoryDelay=$maxHistoryDelay, minHistoryFlight=$minHistoryFlight, historyStartOffsetDays=$historyStartOffsetDays, maxHistoryRows=$maxHistoryRows")
    }

    /**
     * Get the active flight season (cached for 1 hour).
     * Cache provides sufficient protection; no circuit breaker needed.
     * @return the current flight season wrapped in a Result.
     */
    @Cacheable("active-season")
    open fun getActiveSeason(): Result<FlightSeason?> {
        return runCatching {
            val activeFlightSeason = seasonRepository.getFlightSeasonByTag(true)
            log.info("Active flight season: $activeFlightSeason")
            activeFlightSeason
        }.onFailure { e ->
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
    private fun isOperationDayMatch(operationDays: String, dayOfWeek: Int): Boolean {
        // Parse operation days as individual digits
        val days = operationDays.toCharArray()
            .map { it.toString().toIntOrNull() }
            .filterNotNull()
            .toSet()
        return days.contains(dayOfWeek)
    }

    /**
     * Find a seasonal flight for a given flight number and date.
     * Fast-fail circuit breaker: 10 attempts with 500ms delay, giving database 5 seconds total.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return the seasonal flight if found, null otherwise, wrapped in a Result.
     */
    @CircuitBreaker(attempts = "10", delay = "500ms", reset = "60s")
    open fun getSeasonalFlight(flightNumber: String, flightDate: LocalDate): Result<SeasonalFlight?> {
        return runCatching {
            val operationDay = getOperationDay(flightDate)
            val likeOperationDay = "%$operationDay%"
            log.debug("Finding seasonal flight for flight number $flightNumber, operation day $operationDay")
            
            val seasonalFlight = seasonRepository.getSeasonalArrivalFlight(flightNumber, likeOperationDay)
            
            // Additional validation: ensure the operation day actually matches
            // Using helper function to avoid false matches like "1" matching "12"
            val validatedFlight = seasonalFlight?.takeIf { 
                isOperationDayMatch(it.operationDays, operationDay) 
            }
            
            log.debug("Found seasonal flight: {}", validatedFlight)
            validatedFlight
        }.onFailure { e ->
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
    open fun getHistoryFlights(flightNumber: String, flightDate: LocalDate): Result<List<HistoricalFlight>> {
        return getSeasonalFlight(flightNumber, flightDate)
            .flatMap { seasonalFlight ->
                if (seasonalFlight == null) {
                    // Business case: no seasonal flight, return empty list (success)
                    Result.success(emptyList())
                } else {
                    getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
                }
            }
            .onFailure { e ->
                log.error("Error getting history flights for $flightNumber on $flightDate", e)
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
            filtered
        }.onFailure { e ->
            log.error("Error getting history flights with seasonal flight for $flightDate", e)
        }
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
     * Calculate the flying time for a given flight number and date.
     * Uses MDC for structured logging and records metrics for monitoring.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a FlyingTimeResponse object wrapped in a Result.
     */
    open fun calculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse> {
        // Defense-in-depth: validate inputs at service layer
        require(flightNumber.isNotBlank()) { "Flight number cannot be empty" }
        require(flightNumber.length in 5..6) { "Flight number must be 5-6 characters, got: ${flightNumber.length}" }
        require(flightDate.isAfter(LocalDate.of(2000, 1, 1))) { 
            "Flight date must be after 2000-01-01, got: $flightDate" 
        }
        require(flightDate.isBefore(LocalDate.now().plusYears(1))) { 
            "Flight date cannot be more than 1 year in the future, got: $flightDate" 
        }
        
        // Add structured logging context
        MDC.put("flightNumber", flightNumber)
        MDC.put("flightDate", flightDate.toString())
        
        // Start timing for metrics
        val timer = Timer.start(meterRegistry)
        
        try {
            log.info("Starting flying time calculation")

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
                .onSuccess { response ->
                    // Record successful calculation metrics
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
                .onFailure { e ->
                    // Record failure metrics
                    timer.stop(meterRegistry.timer(
                        "estt.calculation.time",
                        "result", "failure",
                        "error", e.javaClass.simpleName
                    ))
                    meterRegistry.counter(
                        "estt.calculation.failure",
                        "error", e.javaClass.simpleName
                    ).increment()
                    log.error("Flying time calculation failed", e)
                }
        } finally {
            // Remove only the keys we added to avoid clearing context from other threads
            MDC.remove("flightNumber")
            MDC.remove("flightDate")
        }
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
        require(seasonalFlight.flyingTime > 0) { 
            "Invalid seasonal flight time: ${seasonalFlight.flyingTime} for flight $flightNumber" 
        }
        
        return getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
            .map { historyFlights ->
                val qualifiedFlights = getQualifiedHistoryFlights(historyFlights)
                val shouldUseHistoricalAverage = qualifiedFlights.size >= minHistoryFlight
                
                log.debug("Found ${qualifiedFlights.size} qualified history flights (minimum required: $minHistoryFlight)")

                // Determine the flying time based on whether there are enough historical flights
                val flyingTime: Long
                val message: String
                
                if (shouldUseHistoricalAverage) {
                    // Calculate the average flight time from the qualified historical flights
                    val flightsUsed = minOf(qualifiedFlights.size, minHistoryFlight)
                    flyingTime = calculateAverageFlightTime(qualifiedFlights)
                    message = "Calculated from $flightsUsed historical flights (${qualifiedFlights.size} available)"
                    log.info("$flightNumber: Calculated flying time $flyingTime minutes from $flightsUsed of ${qualifiedFlights.size} historical flights")
                } else {
                    flyingTime = seasonalFlight.flyingTime
                    message = "Using seasonal flight flying time due to insufficient historical data"
                    log.warn("$flightNumber: Insufficient history (${qualifiedFlights.size}/${minHistoryFlight}). Using seasonal time: $flyingTime minutes")
                }

                val historyExists = qualifiedFlights.isNotEmpty()

                FlyingTimeResponse(
                    flightNumber, flightDate, flyingTime, historyExists, true, message
                )
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
        val minutes = calculateDurationMinutes(historyFlight.scheduledTime, historyFlight.actualTime)
        return minutes < maxHistoryDelay
    }

    /**
     * Calculate the difference in minutes between two LocalDateTime objects.
     * @param startTime the start time.
     * @param endTime the end time.
     * @return the difference in minutes.
     */
    private fun calculateDurationMinutes(startTime: LocalDateTime, endTime: LocalDateTime): Long {
        return abs(Duration.between(startTime, endTime).toMinutes())
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