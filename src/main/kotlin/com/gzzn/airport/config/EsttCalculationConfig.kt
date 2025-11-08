package com.gzzn.airport.config

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton

/**
 * Centralised configuration bundle for ESTT calculation parameters.
 * Validates required application settings eagerly so that misconfiguration
 * fails fast during application startup.
 */
@Singleton
class EsttCalculationConfig(
    @Value("\${estt.calculation.max-history-delay:120}") val maxHistoryDelay: Int,
    @Value("\${estt.calculation.min-history-flight:20}") val minHistoryFlight: Int,
    @Value("\${estt.calculation.date-format}") val dateFormat: String,
    @Value("\${estt.calculation.history-start-offset-days}") val historyStartOffsetDays: Long,
    @Value("\${estt.calculation.max-history-rows:300}") val maxHistoryRows: Int,
) {
    init {
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
    }
}
