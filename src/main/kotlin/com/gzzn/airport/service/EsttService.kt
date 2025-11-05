package com.gzzn.airport.service

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.repository.SeasonRepository
import io.micronaut.cache.annotation.Cacheable
import io.micronaut.context.annotation.Value
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Singleton
open class EsttService(
    private val seasonRepository: SeasonRepository,
    private val historyFlightRepository: HistoryFlightRepository,
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
    fun parseFlightDate(dateString: String): LocalDate {
        return try {
            LocalDate.parse(dateString, DateTimeFormatter.ofPattern(dateFormat))
        } catch (e: Exception) {
            log.error("Error parsing flight date: $dateString", e)
            throw IllegalArgumentException("Invalid date format: $dateString")
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
     * Find a seasonal flight for a given flight number and date.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return the seasonal flight if found, null otherwise, wrapped in a Result.
     */
    fun getSeasonalFlight(flightNumber: String, flightDate: LocalDate): Result<SeasonalFlight?> {
        return runCatching {
            val operationDay = getOperationDay(flightDate).toString()
            val likeOperationDay = "%$operationDay%"
            log.debug("Finding seasonal flight for flight number $flightNumber, operation day $operationDay")
            
            val seasonalFlight = seasonRepository.getSeasonalArrivalFlight(flightNumber, likeOperationDay)
            
            // Additional validation: ensure the operation day is actually in the string
            // This prevents incorrect matches like "1" matching "21"
            val validatedFlight = seasonalFlight?.takeIf { 
                it.operationDays.contains(operationDay) 
            }
            
            log.debug("Found seasonal flight: {}", validatedFlight)
            validatedFlight
        }.onFailure { e ->
            log.error("Error finding seasonal flight for $flightNumber on $flightDate", e)
        }
    }

    /**
     * Get historical flights for a given flight number and date.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a list of historical flights wrapped in a Result.
     */
    fun getHistoryFlights(flightNumber: String, flightDate: LocalDate): Result<List<HistoricalFlight>> {
        return runCatching {
            val seasonalFlight = getSeasonalFlight(flightNumber, flightDate).getOrThrow()
            getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate).getOrThrow()
        }.onFailure { e ->
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
            log.info("flight: $seasonalFlight, historical flight from $seasonStart")
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
            if (log.isDebugEnabled) {
                log.debug("flight history with $seasonalFlight got ${historyFlights.size}, filtered: ${filtered.size}")
            }
            filtered
        }.onFailure { e ->
            log.error("Error getting history flights with seasonal flight for $flightDate", e)
        }
    }

    /**
     * Check if a historical flight matches the criteria for a given seasonal flight.
     * @param seasonalFlight the seasonal flight.
     * @param historyFlight the historical flight.
     * @return true if the historical flight matches the criteria, false otherwise.
     */
    private fun isHistoryFlight(seasonalFlight: SeasonalFlight?, historyFlight: HistoricalFlight): Boolean {
        if (seasonalFlight == null) {
            log.warn("seasonal flight is null!")
            return false
        }

        val seasonalFlyingTime = seasonalFlight.flyingTime ?: return false
        val operationDay = getOperationDay(historyFlight.flightDate)
        val actualFlyTime = calculateDurationMinutes(historyFlight.preActualTime, historyFlight.actualTime)

        // Check if the operation day matches, the flight time order is correct, and the delay is within the maximum allowed delay
        val operationDayMatches = seasonalFlight.operationDays.contains(operationDay.toString())
        val flightTimeOrderCorrect = historyFlight.preActualTime < historyFlight.actualTime
        val withinMaxDelay = abs(actualFlyTime - seasonalFlyingTime) < maxHistoryDelay

        val result = operationDayMatches && flightTimeOrderCorrect && withinMaxDelay

        return result.also {
            if (log.isDebugEnabled) {
                log.debug("history: $historyFlight, include: $it")
            }
        }
    }

    /**
     * Calculate the flying time for a given flight number and date.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a FlyingTimeResponse object wrapped in a Result.
     */
    fun calculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse> {
        require(flightNumber.isNotBlank()) { "Flight number cannot be empty" }
        log.info("Calculating flying time for $flightNumber on $flightDate")

        return runCatching {
            val seasonalFlight = getSeasonalFlight(flightNumber, flightDate).getOrThrow()
            if (seasonalFlight == null) {
                log.warn("No seasonal flight found for $flightNumber on $flightDate")
                return@runCatching FlyingTimeResponse(
                    flightNumber, flightDate, 0, false,
                    seasonal = false,
                    message = "No seasonal flight or history flights found"
                )
            }

            val historyFlights = getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate).getOrThrow()
            val qualifiedFlights = getQualifiedHistoryFlights(historyFlights)

            val enoughHistoryFlights = qualifiedFlights.size >= minHistoryFlight
            log.debug("Found ${qualifiedFlights.size} qualified history flights (minimum required: $minHistoryFlight)")

            // Determine the flying time based on whether there are enough historical flights
            val flyingTime: Long
            val message: String
            
            if (enoughHistoryFlights) {
                // Calculate the average flight time from the qualified historical flights
                flyingTime = calculateAverageFlightTime(qualifiedFlights)
                message = "Calculated from ${minOf(qualifiedFlights.size, minHistoryFlight)} historical flights"
                log.info("Using average flight time from historical data: $flyingTime minutes")
            } else {
                flyingTime = seasonalFlight.flyingTime ?: 0
                message = "Using seasonal flight flying time due to insufficient historical data"
                log.warn("Insufficient history flights (${qualifiedFlights.size}/${minHistoryFlight} required). Using seasonal flying time: $flyingTime minutes")
            }

            val historyExists = qualifiedFlights.isNotEmpty()

            FlyingTimeResponse(
                flightNumber, flightDate, flyingTime, historyExists, true, message
            )
        }.onFailure { e ->
            log.error("Error calculating flying time for $flightNumber on $flightDate", e)
        }
    }

    /**
     * Calculate the average flight time from a list of qualified historical flights.
     * @param qualifiedFlights the list of qualified historical flights.
     * @return the average flight time.
     */
    private fun calculateAverageFlightTime(qualifiedFlights: List<HistoricalFlight>): Long {
        if (qualifiedFlights.isEmpty()) return 0
        val flightsToUse = minOf(qualifiedFlights.size, minHistoryFlight)
        return qualifiedFlights.take(flightsToUse)
            .sumOf { calculateDurationMinutes(it.preActualTime, it.actualTime) } / flightsToUse
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

class SeasonalFlightNotFoundException(message: String) : RuntimeException(message)