package com.gzzn.airport.service

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.respository.HistoryFlightRepository
import com.gzzn.airport.respository.SeasonRepository
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Singleton
class EsttService(
    private val seasonRepository: SeasonRepository,
    private val historyFlightRepository: HistoryFlightRepository,
    @Value("\${default.maxHistoryDelay:120}") val maxHistoryDelay: Int,
    @Value("\${default.minHistoryFlight:20}") val minHistoryFlight: Int,
    @Value("\${default.dateFormat}") val dateFormat: String,
    @Value("\${default.startMinus}") val startMinus: Long
) {
    companion object {
        private val log = LoggerFactory.getLogger("EsttService")
    }

    /**
     * Get the active flight season.
     * @return the current flight season.
     */
    fun getActiveSeason(): FlightSeason? {
        return try {
            val activeFlightSeason = seasonRepository.getFlightSeasonByTag(true)
            log.info("Active flight season: $activeFlightSeason")
            activeFlightSeason
        } catch (e: Exception) {
            log.error("Error getting active flight season", e)
            null
        }
    }

    /**
     * Parse a date string into a LocalDate object using the configured date format.
     * @param dateString the date string to parse.
     * @return the parsed LocalDate object.
     */
    fun getFlightDate(dateString: String): LocalDate {
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
     * @return the seasonal flight if found, null otherwise.
     */
    fun getSeasonalFlight(flightNumber: String, flightDate: LocalDate): SeasonalFlight? {
        return try {
            val likeOperationDay = "%${getOperationDay(flightDate)}%"
            log.debug("Finding seasonal flight for flight number $flightNumber, like operation day $likeOperationDay")
            val seasonalFlight = seasonRepository.getSeasonalArrivalFlight(flightNumber, likeOperationDay)
            log.debug("Found seasonal flight: {}", seasonalFlight)
            if (seasonalFlight == null) {
                throw SeasonalFlightNotFoundException("No seasonal flight found for $flightNumber on $flightDate")
            }
            seasonalFlight
        } catch (e: Exception) {
            log.error("Error finding seasonal flight for $flightNumber on $flightDate", e)
            throw e
        }
    }

    /**
     * Get historical flights for a given flight number and date.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a list of historical flights.
     */
    fun getHistoryFlights(flightNumber: String, flightDate: LocalDate): List<HistoricalFlight> {
        return try {
            getHistoryFlightsWithSeasonFlight(getSeasonalFlight(flightNumber, flightDate), flightDate)
        } catch (e: Exception) {
            log.error("Error getting history flights for $flightNumber on $flightDate", e)
            emptyList()
        }
    }

    /**
     * Get historical flights that match a given seasonal flight and date.
     * @param seasonalFlight the seasonal flight.
     * @param flightDate the flight date.
     * @return a list of historical flights.
     */
    private fun getHistoryFlightsWithSeasonFlight(
        seasonalFlight: SeasonalFlight?,
        flightDate: LocalDate
    ): List<HistoricalFlight> {
        return try {
            if (seasonalFlight == null) {
                log.warn("seasonal flight is null, no history flight")
                return emptyList()
            }

            val seasonStart = getHistoryStartDate(seasonalFlight.seasonStart)
            log.info("flight: $seasonalFlight, historical flight from $seasonStart")
            val historyFlights = historyFlightRepository.getArrivalFlight(
                seasonalFlight.flightNumber,
                seasonStart,
                flightDate
            )
            // Filter the historical flights to include only those that match the criteria
            val filtered = historyFlights.asSequence()
                .filter { historyFlight -> isHistoryFlight(seasonalFlight, historyFlight) }
                .toList()
            if (log.isDebugEnabled) {
                log.debug("flight history with $seasonalFlight got ${historyFlights.size}, filtered: ${filtered.size}")
            }
            filtered
        } catch (e: Exception) {
            log.error("Error getting history flights with seasonal flight for $flightDate", e)
            emptyList()
        }
    }

    /**
     * Check if a historical flight matches the criteria for a given seasonal flight.
     * @param seasonalFlight the seasonal flight.
     * @param historyFlight the historical flight.
     * @return true if the historical flight matches the criteria, false otherwise.
     */
    private fun isHistoryFlight(seasonalFlight: SeasonalFlight?, historyFlight: HistoricalFlight): Boolean {
        return try {
            if (seasonalFlight == null) {
                log.warn("seasonal flight is null!")
                return false
            }

            val operationDay = getOperationDay(historyFlight.flightDate)
            val actualFlyTime = getMinutes(historyFlight.preActualTime, historyFlight.actualTime)

            // Check if the operation day matches, the flight time order is correct, and the delay is within the maximum allowed delay
            val operationDayMatches = seasonalFlight.operationDays.contains(operationDay.toString())
            val flightTimeOrderCorrect = historyFlight.preActualTime < historyFlight.actualTime
            val withinMaxDelay = abs(actualFlyTime - seasonalFlight.flyingTime!!) < maxHistoryDelay

            val result = operationDayMatches && flightTimeOrderCorrect && withinMaxDelay

            result.also {
                if (log.isDebugEnabled) {
                    log.debug("history: $historyFlight, include: $it")
                }
            }
        } catch (e: Exception) {
            log.error("Error checking if history flight matches criteria", e)
            false
        }
    }

    /**
     * Calculate the flying time for a given flight number and date.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a FlyingTimeResponse object containing the calculated flying time and related information.
     */
    fun calculate(flightNumber: String, flightDate: LocalDate): FlyingTimeResponse {
        require(flightNumber.isNotBlank()) { "Flight number cannot be empty" }
        log.info("Calculating flying time for $flightNumber on $flightDate")

        val seasonalFlight = getSeasonalFlight(flightNumber, flightDate)
        if (seasonalFlight == null) {
            log.warn("No seasonal flight found for $flightNumber on $flightDate")
            return FlyingTimeResponse(
                flightNumber, flightDate, 0, false,
                seasonal = false,
                message = "No seasonal flight or history flights found"
            )
        }

        val historyFlights = getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
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

        return FlyingTimeResponse(
            flightNumber, flightDate, flyingTime, historyExists, true, message
        )
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
            .sumOf { getMinutes(it.preActualTime, it.actualTime) } / flightsToUse
    }

    /**
     * Get qualified historical flights by filtering and sorting the given list.
     * @param historyFlights the list of historical flights.
     * @return a list of qualified historical flights.
     */
    private fun getQualifiedHistoryFlights(historyFlights: List<HistoricalFlight>): List<HistoricalFlight> {
        return try {
            historyFlights.asSequence()
                .filter { isFlightDelayAcceptable(it) }
                .sortedByDescending { it.scheduledTime }
                .toList()
        } catch (e: Exception) {
            log.error("Error getting qualified history flights", e)
            emptyList()
        }
    }

    /**
     * Check if a historical flight has a delay within the maximum allowed delay.
     * @param historyFlight the historical flight.
     * @return true if the delay is within the maximum allowed delay, false otherwise.
     */
    private fun isFlightDelayAcceptable(historyFlight: HistoricalFlight): Boolean {
        return try {
            val minutes = getMinutes(historyFlight.scheduledTime, historyFlight.actualTime)
            minutes < maxHistoryDelay
        } catch (e: Exception) {
            log.error("Error checking flight delay for $historyFlight", e)
            false
        }
    }

    /**
     * Calculate the difference in minutes between two LocalDateTime objects.
     * @param scheduledTime the scheduled time.
     * @param actualTime the actual time.
     * @return the difference in minutes.
     */
    private fun getMinutes(scheduledTime: LocalDateTime, actualTime: LocalDateTime): Long {
        return try {
            abs(Duration.between(scheduledTime, actualTime).toMinutes())
        } catch (e: Exception) {
            log.error("Error calculating minutes between $scheduledTime and $actualTime", e)
            0
        }
    }

    /**
     * Get the start date for historical flights based on the season start date.
     * @param seasonStart the season start date.
     * @return the start date for historical flights.
     */
    private fun getHistoryStartDate(seasonStart: LocalDate): LocalDate {
        return try {
            seasonStart.minusDays(startMinus)
        } catch (e: Exception) {
            log.error("Error calculating history start date from $seasonStart", e)
            seasonStart
        }
    }
}

class SeasonalFlightNotFoundException(message: String) : RuntimeException(message)