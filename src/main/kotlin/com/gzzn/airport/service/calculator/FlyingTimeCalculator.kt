package com.gzzn.airport.service.calculator

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.Confidence
import com.gzzn.airport.model.EstimateSource
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.NoEstimateReason
import com.gzzn.airport.model.SeasonalFlight
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Encapsulates the business rules for turning historical flights into a flying-time decision.
 *
 * Responsibilities:
 * - Filter raw history so only business-approved samples contribute to the calculation.
 * - Compute the median flying time when enough qualified samples exist.
 * - Fall back to seasonal schedule time or return a no-estimate outcome.
 */
@Singleton
class FlyingTimeCalculator(private val meterRegistry: MeterRegistry, private val config: EsttCalculationConfig) {

    private val log = LoggerFactory.getLogger(FlyingTimeCalculator::class.java)

    /**
     * Outcome of the calculation step.
     */
    data class Result(
        val flyingTime: Long?,
        val source: EstimateSource,
        val sampleSize: Int,
        val confidence: Confidence,
        val message: String,
    ) {
        val historyUsed: Boolean get() = source == EstimateSource.HISTORY
    }

    /**
     * Validates seasonal flight metadata when the seasonal fallback path is used.
     *
     * @throws IllegalArgumentException when the seasonal flying time is zero or negative.
     */
    fun ensureValidSeasonalFlight(seasonalFlight: SeasonalFlight, flightNumber: String) {
        val flyingTime = seasonalFlight.flyingTime
        require(flyingTime != null && flyingTime > 0) {
            "Invalid seasonal flight time: $flyingTime for flight $flightNumber"
        }
    }

    /**
     * Calculate flying time from pre-filtered historical flights.
     *
     * When at least [EsttCalculationConfig.minHistoryFlight] qualified samples remain after the
     * schedule-deviation filter, returns the integer median of all qualified flying durations.
     * Otherwise falls back to [SeasonalFlight.flyingTime] when configured, or [EstimateSource.NONE].
     */
    fun calculate(seasonalFlight: SeasonalFlight, flightNumber: String, historyFlights: List<HistoricalFlight>): Result {
        val qualifiedFlights = getQualifiedHistoryFlights(historyFlights)
        log.debug(
            "Found {} qualified history flights (minimum required: {})",
            qualifiedFlights.size,
            config.minHistoryFlight,
        )

        if (qualifiedFlights.size >= config.minHistoryFlight) {
            val flyingTime = medianFlyingTime(qualifiedFlights)
            log.info(
                "{}: Calculated flying time {} minutes from {} historical flights",
                flightNumber,
                flyingTime,
                qualifiedFlights.size,
            )

            meterRegistry.counter("estt.calculation.source", "source", "history").increment()
            seasonalFlight.flyingTime?.let { seasonalTime ->
                val accuracy = abs(flyingTime - seasonalTime)
                meterRegistry.counter("estt.calculation.accuracy", "accuracy", accuracy.toString()).increment()
                if (accuracy <= 10) {
                    meterRegistry.counter("estt.calculation.high_accuracy", "source", "history").increment()
                }
            }

            return Result(
                flyingTime = flyingTime,
                source = EstimateSource.HISTORY,
                sampleSize = qualifiedFlights.size,
                confidence = Confidence.HIGH,
                message = "Calculated from ${qualifiedFlights.size} historical flights",
            )
        }

        val seasonalTime = seasonalFlight.flyingTime
        if (seasonalTime != null && seasonalTime > 0) {
            log.warn(
                "{}: Insufficient history ({}/{}). Using seasonal time: {} minutes",
                flightNumber,
                qualifiedFlights.size,
                config.minHistoryFlight,
                seasonalTime,
            )
            meterRegistry.counter("estt.calculation.source", "source", "schedule").increment()
            return Result(
                flyingTime = seasonalTime,
                source = EstimateSource.SEASONAL,
                sampleSize = 0,
                confidence = Confidence.NONE,
                message = "Using seasonal flight flying time due to insufficient historical data",
            )
        }

        log.warn(
            "{}: Insufficient history ({}/{}) and no seasonal flying time configured",
            flightNumber,
            qualifiedFlights.size,
            config.minHistoryFlight,
        )
        return Result(
            flyingTime = null,
            source = EstimateSource.NONE,
            sampleSize = qualifiedFlights.size,
            confidence = Confidence.NONE,
            message = NoEstimateReason.INSUFFICIENT_HISTORY_NO_SEASONAL_TIME.message,
        )
    }

    /**
     * Applies the schedule-delay qualification rule used after repository filtering.
     *
     * Early arrivals are always accepted. Only late arrivals beyond [EsttCalculationConfig.maxScheduleDeviation]
     * minutes are rejected (inclusive at the threshold).
     */
    private fun getQualifiedHistoryFlights(historyFlights: List<HistoricalFlight>): List<HistoricalFlight> =
        historyFlights.filter { isScheduleDeviationAcceptable(it.scheduledTime, it.actualTime) }

    private fun isScheduleDeviationAcceptable(scheduledTime: LocalDateTime, actualTime: LocalDateTime): Boolean {
        val deviationMinutes = Duration.between(scheduledTime, actualTime).toMinutes()
        return deviationMinutes <= config.maxScheduleDeviation
    }

    /**
     * Returns the integer median of qualified flying durations, or `0` when empty.
     *
     * For an even number of values, returns the integer average of the two middle elements.
     */
    private fun medianFlyingTime(qualifiedFlights: List<HistoricalFlight>): Long {
        val values = qualifiedFlights
            .map { calculateDurationMinutes(it.previousDepartureTime, it.actualTime) }
            .sorted()
        if (values.isEmpty()) {
            return 0
        }
        val mid = values.size / 2
        return if (values.size % 2 == 0) {
            (values[mid - 1] + values[mid]) / 2
        } else {
            values[mid]
        }
    }

    private fun calculateDurationMinutes(startTime: LocalDateTime, endTime: LocalDateTime): Long =
        Duration.between(startTime, endTime).toMinutes()
}
