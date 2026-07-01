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
 * 将已过滤历史样本转换为飞行时长估算结果，集中处理历史中位数、航季回退和无估算分支。
 * Turns filtered historical samples into a flying-time decision, centralizing the history median,
 * seasonal fallback, and no-estimate branches.
 */
@Singleton
class FlyingTimeCalculator(private val meterRegistry: MeterRegistry, private val config: EsttCalculationConfig) {

    private val log = LoggerFactory.getLogger(FlyingTimeCalculator::class.java)

    /**
     * 单次计算决策的内部结果，随后由服务层映射为 API 响应。
     * Internal result of one calculation decision, later mapped by the service layer to the API response.
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
     * 从预过滤的历史样本计算飞行时长。
     * Calculates flying time from pre-filtered historical samples.
     *
     * 达到最小样本数时使用全部合格样本的整数中位数；否则正数航季时长回退为 `SEASONAL`，
     * 未配置可用航季时长则返回 `NONE`。
     * Uses the integer median of all qualified samples when the minimum sample count is met; otherwise
     * falls back to a positive seasonal time as `SEASONAL`, or returns `NONE` when unavailable.
     */
    fun calculate(seasonalFlight: SeasonalFlight, flightNumber: String, historyFlights: List<HistoricalFlight>): Result {
        val qualifiedFlights = getQualifiedHistoryFlights(historyFlights)
        log.debug(
            "Found {} qualified history flights (minimum required: {})",
            qualifiedFlights.size,
            config.minHistoryFlight,
        )

        if (qualifiedFlights.size >= config.minHistoryFlight) {
            return historyResult(seasonalFlight, flightNumber, qualifiedFlights)
        }

        val seasonalTime = seasonalFlight.flyingTime
        if (seasonalTime != null && seasonalTime > 0) {
            return seasonalFallbackResult(flightNumber, qualifiedFlights.size, seasonalTime)
        }

        return noEstimateResult(flightNumber, qualifiedFlights.size)
    }

    private fun historyResult(seasonalFlight: SeasonalFlight, flightNumber: String, qualifiedFlights: List<HistoricalFlight>): Result {
        val flyingTime = medianFlyingTime(qualifiedFlights)
        log.info(
            "{}: Calculated flying time {} minutes from {} historical flights",
            flightNumber,
            flyingTime,
            qualifiedFlights.size,
        )

        meterRegistry.counter("estt.calculation.source", "source", "history").increment()
        recordHistoryAccuracyMetrics(seasonalFlight.flyingTime, flyingTime)

        return Result(
            flyingTime = flyingTime,
            source = EstimateSource.HISTORY,
            sampleSize = qualifiedFlights.size,
            confidence = Confidence.HIGH,
            message = "Calculated from ${qualifiedFlights.size} historical flights",
        )
    }

    private fun seasonalFallbackResult(flightNumber: String, qualifiedCount: Int, seasonalTime: Long): Result {
        log.warn(
            "{}: Insufficient history ({}/{}). Using seasonal time: {} minutes",
            flightNumber,
            qualifiedCount,
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

    private fun noEstimateResult(flightNumber: String, qualifiedCount: Int): Result {
        log.warn(
            "{}: Insufficient history ({}/{}) and no seasonal flying time configured",
            flightNumber,
            qualifiedCount,
            config.minHistoryFlight,
        )
        return Result(
            flyingTime = null,
            source = EstimateSource.NONE,
            sampleSize = qualifiedCount,
            confidence = Confidence.NONE,
            message = NoEstimateReason.INSUFFICIENT_HISTORY_NO_SEASONAL_TIME.message,
        )
    }

    private fun recordHistoryAccuracyMetrics(seasonalFlyingTime: Long?, computedFlyingTime: Long) {
        seasonalFlyingTime ?: return
        val accuracy = abs(computedFlyingTime - seasonalFlyingTime)
        meterRegistry.counter("estt.calculation.accuracy", "accuracy", accuracy.toString()).increment()
        if (accuracy <= 10) {
            meterRegistry.counter("estt.calculation.high_accuracy", "source", "history").increment()
        }
    }

    /**
     * 应用计划落地偏差规则：早到始终保留，晚到在阈值内（含等于）保留，超过阈值剔除。
     * Applies the schedule-deviation rule: early arrivals are always accepted, late arrivals are accepted
     * through the inclusive threshold, and later arrivals are rejected.
     */
    private fun getQualifiedHistoryFlights(historyFlights: List<HistoricalFlight>): List<HistoricalFlight> =
        historyFlights.filter { isScheduleDeviationAcceptable(it.scheduledTime, it.actualTime) }

    private fun isScheduleDeviationAcceptable(scheduledTime: LocalDateTime, actualTime: LocalDateTime): Boolean {
        val deviationMinutes = Duration.between(scheduledTime, actualTime).toMinutes()
        return deviationMinutes <= config.maxScheduleDeviation
    }

    /**
     * 返回合格样本飞行时长的整数中位数；偶数样本取中间两值的整数平均。
     * Returns the integer median of qualified flying durations; for even counts, uses the integer average
     * of the two middle values.
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
