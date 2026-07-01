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

/** 将已过滤历史样本转换为飞行时长估算结果。Turns filtered historical samples into a flying-time decision. */
@Singleton
class FlyingTimeCalculator(private val meterRegistry: MeterRegistry, private val config: EsttCalculationConfig) {

    private val log = LoggerFactory.getLogger(FlyingTimeCalculator::class.java)

    data class Result(
        val flyingTime: Long?,
        val source: EstimateSource,
        val sampleSize: Int,
        val qualifiedCount: Int,
        val confidence: Confidence,
        val message: String,
    ) {
        val historyUsed: Boolean get() = source == EstimateSource.HISTORY
    }

    /** 时刻偏差过滤；供历史扫描判断合格样本是否足够。Schedule-deviation filter for scan stop conditions. */
    fun filterByScheduleDeviation(historyFlights: List<HistoricalFlight>): List<HistoricalFlight> =
        getQualifiedHistoryFlights(historyFlights)

    fun calculate(seasonalFlight: SeasonalFlight, flightNumber: String, historyFlights: List<HistoricalFlight>): Result {
        val qualifiedFlights = getQualifiedHistoryFlights(historyFlights)
        recordQualifiedScanMetrics(qualifiedFlights.size)
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
                qualifiedCount = qualifiedFlights.size,
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
                qualifiedCount = qualifiedFlights.size,
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
            qualifiedCount = qualifiedFlights.size,
            confidence = Confidence.NONE,
            message = NoEstimateReason.INSUFFICIENT_HISTORY_NO_SEASONAL_TIME.message,
        )
    }

    private fun recordQualifiedScanMetrics(qualifiedCount: Int) {
        meterRegistry.summary("estt.history.calc.scan.qualified_rows").record(qualifiedCount.toDouble())
        val sufficient = qualifiedCount >= config.minHistoryFlight
        meterRegistry.counter(
            "estt.history.calc.scan.calls",
            "qualified_sufficient",
            sufficient.toString(),
        ).increment()
    }

    /** 早到始终保留，晚到在阈值内（含等于）保留。Early arrivals accepted; late within threshold accepted. */
    private fun getQualifiedHistoryFlights(historyFlights: List<HistoricalFlight>): List<HistoricalFlight> =
        historyFlights.filter { isScheduleDeviationAcceptable(it.scheduledTime, it.actualTime) }

    private fun isScheduleDeviationAcceptable(scheduledTime: LocalDateTime, actualTime: LocalDateTime): Boolean {
        val deviationMinutes = Duration.between(scheduledTime, actualTime).toMinutes()
        return deviationMinutes <= config.maxScheduleDeviation
    }

    /** 整数中位数；偶数样本取中间两值的整数平均。Integer median; even count uses integer average of middles. */
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
