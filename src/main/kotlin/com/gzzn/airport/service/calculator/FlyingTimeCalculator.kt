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
import kotlin.math.abs

/**
 * 已合格历史样本的飞行时长估算与决策分支。
 * Flying-time estimation and decision branches from pre-qualified historical samples.
 *
 * 业务规则见 docs/algorithm.md §8。
 * 样本筛选（阶段 B/C、扩展扫描）由 [com.gzzn.airport.service.history.HistoryFlightProvider] 完成；
 * 本类仅接收其输出的合格列表，按样本数门槛在三种结果间选择：
 * - **HISTORY**（§8.1）：样本数 ≥ [EsttCalculationConfig.minHistoryFlight] → 飞行时长中位数
 * - **SEASONAL**（§8.2）：样本不足且季节计划时长有效（> 0）→ 采用计划时长
 * - **NONE**（§8.3）：样本不足且无有效计划时长 → 不估算
 */
@Singleton
class FlyingTimeCalculator(private val meterRegistry: MeterRegistry, private val config: EsttCalculationConfig) {

    private val log = LoggerFactory.getLogger(FlyingTimeCalculator::class.java)

    /**
     * 单次估算的内部结果，由 [EsttService] 映射为 HTTP 响应。
     * Internal estimate outcome; mapped to HTTP response by [EsttService].
     *
     * @property sampleSize 实际参与估算的合格样本数（HISTORY 时等于 qualifiedCount；SEASONAL/NONE 时为 0 或 qualifiedCount，见各分支）
     * @property qualifiedCount 传入的合格样本总数，供日志与上层判断样本是否不足
     * @property confidence HISTORY 且样本达标时为 HIGH；其余为 NONE（仅表示样本数门槛，非统计置信度）
     */
    data class Result(
        val flyingTime: Long?,
        val source: EstimateSource,
        val sampleSize: Int,
        val qualifiedCount: Int,
        val confidence: Confidence,
        val message: String,
    ) {
        /** 是否采用了历史样本估算（source == HISTORY）。Whether the estimate came from historical median. */
        val historyUsed: Boolean get() = source == EstimateSource.HISTORY
    }

    /**
     * 根据合格样本数在 HISTORY / SEASONAL / NONE 三条路径间决策。
     * Decide among HISTORY, SEASONAL, and NONE based on qualified sample count.
     *
     * @param qualifiedFlights 已由 HistoryFlightProvider 经阶段 B + C 筛选的合格样本（可为空）
     */
    fun calculate(seasonalFlight: SeasonalFlight, flightNumber: String, qualifiedFlights: List<HistoricalFlight>): Result {
        val qualifiedCount = qualifiedFlights.size
        log.debug(
            "Found {} qualified history flights (minimum required: {})",
            qualifiedCount,
            config.minHistoryFlight,
        )

        // 分支 1：样本够门槛 → 中位数估算（algorithm.md §8.1）。Branch 1: enough samples → median (§8.1).
        if (qualifiedCount >= config.minHistoryFlight) {
            val flyingTime = medianFlyingTime(qualifiedFlights)
            log.info(
                "{}: Calculated flying time {} minutes from {} historical flights",
                flightNumber,
                flyingTime,
                qualifiedCount,
            )

            meterRegistry.counter("estt.calculation.source", "source", "history").increment()
            // 与季节计划时长对比，记录偏差分布及「10 分钟内」高吻合计数（可观测性，不影响估算结果）。
            // Compare median to seasonal plan for observability; does not affect the estimate.
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
                sampleSize = qualifiedCount,
                qualifiedCount = qualifiedCount,
                confidence = Confidence.HIGH,
                message = "Calculated from $qualifiedCount historical flights",
            )
        }

        // 分支 2：样本不足 → 季节计划兜底（algorithm.md §8.2）。Branch 2: seasonal fallback (§8.2).
        val seasonalTime = seasonalFlight.flyingTime
        if (seasonalTime != null && seasonalTime > 0) {
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
                sampleSize = 0, // 未采用历史样本；No historical samples used in the estimate.
                qualifiedCount = qualifiedCount,
                confidence = Confidence.NONE,
                message = "Using seasonal flight flying time due to insufficient historical data",
            )
        }

        // 分支 3：样本不足且无有效计划时长 → 不估算（algorithm.md §8.3）。Branch 3: no estimate (§8.3).
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
            qualifiedCount = qualifiedCount,
            confidence = Confidence.NONE,
            message = NoEstimateReason.INSUFFICIENT_HISTORY_NO_SEASONAL_TIME.message,
        )
    }

    /**
     * 合格样本飞行时长的中位数（algorithm.md §8.1）。
     * Median of per-sample flying durations (§8.1).
     *
     * 单条时长 = 前站实际起飞至本站实际到港的分钟数；排序后奇数取中间值，
     * 偶数取中间两值的整数平均（截断，非四舍五入）。不截尾、不加权。
     * Per-sample duration = previous departure to actual arrival; odd → middle, even → integer average of middles.
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

    /** 两时刻之间的飞行时长（分钟）。Flying duration in whole minutes between two timestamps. */
    private fun calculateDurationMinutes(startTime: java.time.LocalDateTime, endTime: java.time.LocalDateTime): Long =
        Duration.between(startTime, endTime).toMinutes()
}
