package com.gzzn.airport.service.calculator

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Encapsulates the business rules for turning historical flights into a flying-time decision.
 *
 * Responsibilities:
 * - Validate that a seasonal flight provides a usable reference flying time.
 * - Filter raw history so only business-approved samples contribute to the calculation.
 * - Decide whether to rely on history or fall back to the schedule and record the decision in Micrometer.
 */
@Singleton
class FlyingTimeCalculator(
    private val meterRegistry: MeterRegistry,
    private val config: EsttCalculationConfig,
) {

    private val log = LoggerFactory.getLogger(FlyingTimeCalculator::class.java)

    /**
     * Outcome of the calculation step.
     *
     * @property flyingTime flying time (minutes) returned to the API caller.
     * @property historyUsed `true` when historical samples met the minimum requirement.
     * @property message human-readable explanation of the chosen data source.
     */
    data class Result(
        val flyingTime: Long,
        val historyUsed: Boolean,
        val message: String,
    )

    /**
     * Validates seasonal flight metadata before performing any calculation.
     * Kept public so that callers can fail fast prior to executing repository calls.
     *
     * @param seasonalFlight schedule record fetched from the repository.
     * @param flightNumber identifier used purely for more descriptive error messages.
     * @throws IllegalArgumentException when the seasonal flying time is zero or negative.
     */
    fun ensureValidSeasonalFlight(seasonalFlight: SeasonalFlight, flightNumber: String) {
        validateSeasonalFlight(seasonalFlight, flightNumber)
    }

    /**
     * Validate seasonal flight data and calculate flying time based on provided history.
     *
     * Algorithm:
     * 1. Reject the seasonal record if it lacks a positive flying time.
     * 2. Filter the raw history according to business rules (delay threshold, operation day match, etc.).
     * 3. Use the most recent qualifying samples (up to `minHistoryFlight`) to compute an average. If
     *    not enough samples exist, fall back to the seasonal value.
     * 4. Record Micrometer counters to track data source choice and result accuracy.
     *
     * @param seasonalFlight validated seasonal schedule.
     * @param flightNumber normalized flight identifier (for logging and metrics tags).
     * @param historyFlights raw historical records already scoped by the caller.
     * @return [Result] describing the flying time and the reasoning behind it.
     */
    fun calculate(seasonalFlight: SeasonalFlight, flightNumber: String, historyFlights: List<HistoricalFlight>): Result {
        ensureValidSeasonalFlight(seasonalFlight, flightNumber)
        val qualifiedFlights = getQualifiedHistoryFlights(historyFlights)
        log.debug(
            "Found {} qualified history flights (minimum required: {})",
            qualifiedFlights.size,
            config.minHistoryFlight,
        )

        return if (qualifiedFlights.size >= config.minHistoryFlight) {
            val flightsUsed = minOf(qualifiedFlights.size, config.minHistoryFlight)
            val flyingTime = calculateAverageFlightTime(qualifiedFlights)
            log.info(
                "{}: Calculated flying time {} minutes from {} of {} historical flights",
                flightNumber,
                flyingTime,
                flightsUsed,
                qualifiedFlights.size,
            )

            meterRegistry.counter("estt.calculation.source", "source", "history").increment()
            val accuracy = abs(flyingTime - seasonalFlight.flyingTime)
            meterRegistry.counter("estt.calculation.accuracy", "accuracy", accuracy.toString()).increment()
            if (accuracy <= 10) {
                meterRegistry.counter("estt.calculation.high_accuracy", "source", "history").increment()
            }

            Result(
                flyingTime = flyingTime,
                historyUsed = true,
                message = "Calculated from $flightsUsed historical flights (${qualifiedFlights.size} available)",
            )
        } else {
            log.warn(
                "{}: Insufficient history ({}/{}). Using seasonal time: {} minutes",
                flightNumber,
                qualifiedFlights.size,
                config.minHistoryFlight,
                seasonalFlight.flyingTime,
            )

            meterRegistry.counter("estt.calculation.source", "source", "schedule").increment()

            Result(
                flyingTime = seasonalFlight.flyingTime,
                historyUsed = false,
                message = "Using seasonal flight flying time due to insufficient historical data",
            )
        }
    }

    /**
     * Ensure schedule flights provide a strictly positive reference flying time.
     */
    private fun validateSeasonalFlight(seasonalFlight: SeasonalFlight, flightNumber: String) {
        require(seasonalFlight.flyingTime > 0) {
            "Invalid seasonal flight time: ${seasonalFlight.flyingTime} for flight $flightNumber"
        }
    }

    /**
     * Apply business filters to raw history and order the remaining flights from newest to oldest.
     */
    private fun getQualifiedHistoryFlights(historyFlights: List<HistoricalFlight>): List<HistoricalFlight> {
        return historyFlights.asSequence()
            .filter { isFlightDelayAcceptable(it) }
            .sortedByDescending { it.scheduledTime }
            .toList()
    }

    /**
     * A history record is acceptable when its arrival delay is within the configured tolerance.
     */
    private fun isFlightDelayAcceptable(historyFlight: HistoricalFlight): Boolean {
        val delayMinutes = calculateDurationMinutes(historyFlight.scheduledTime, historyFlight.actualTime)
        return abs(delayMinutes) < config.maxHistoryDelay
    }

    /**
     * Average the first `minHistoryFlight` valid samples using double precision before rounding.
     */
    private fun calculateAverageFlightTime(qualifiedFlights: List<HistoricalFlight>): Long {
        if (qualifiedFlights.isEmpty()) return 0
        val flightsToUse = minOf(qualifiedFlights.size, config.minHistoryFlight)
        val totalMinutes = qualifiedFlights.take(flightsToUse)
            .sumOf { calculateDurationMinutes(it.previousDepartureTime, it.actualTime) }
        return (totalMinutes.toDouble() / flightsToUse).roundToLong()
    }

    /**
     * Utility to obtain duration in minutes between two timestamps.
     */
    private fun calculateDurationMinutes(startTime: LocalDateTime, endTime: LocalDateTime): Long {
        return Duration.between(startTime, endTime).toMinutes()
    }
}
