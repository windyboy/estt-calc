package com.gzzn.airport.config

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton

/** 集中管理 ESTT 计算参数，启动时校验配置。Centralizes ESTT parameters with fail-fast validation. */
@Singleton
class EsttCalculationConfig(
    @param:Value("\${estt.calculation.max-schedule-deviation:120}") val maxScheduleDeviation: Int,
    @param:Value("\${estt.calculation.max-flying-time-deviation:120}") val maxFlyingTimeDeviation: Int,
    @param:Value("\${estt.calculation.min-history-flight:20}") val minHistoryFlight: Int,
    @param:Value("\${estt.calculation.date-format}") val dateFormat: String,
    @param:Value("\${estt.calculation.history-start-offset-days}") val historyStartOffsetDays: Long,
    @param:Value("\${estt.calculation.max-history-rows:300}") val maxHistoryRows: Int,
) {
    init {
        require(minHistoryFlight > 0) {
            "Configuration error: estt.calculation.min-history-flight must be positive, got: $minHistoryFlight"
        }
        require(maxScheduleDeviation > 0) {
            "Configuration error: estt.calculation.max-schedule-deviation must be positive, got: $maxScheduleDeviation"
        }
        require(maxFlyingTimeDeviation > 0) {
            "Configuration error: estt.calculation.max-flying-time-deviation must be positive, got: $maxFlyingTimeDeviation"
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
    }
}
