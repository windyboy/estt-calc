package com.gzzn.airport.model

import io.micronaut.data.annotation.MappedEntity
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

/**
 * Seasonal flight schedule entry supplied by the master data tables.
 *
 * @property flightNumber carrier flight identifier (already normalized to uppercase).
 * @property operationDays digits representing operating days of week (1 = Monday ... 7 = Sunday).
 * @property flyingTime scheduled flying time in minutes.
 * @property seasonStart start date of the applicable season.
 * @property seasonEnd end date of the applicable season.
 */
@Serdeable
@MappedEntity
data class SeasonalFlight(
    val flightNumber: String,
    val operationDays: String,
    val flyingTime: Long,
    val seasonStart: LocalDate,
    val seasonEnd: LocalDate,
) {
    init {
        require(operationDays.all { it.isDigit() && it in '1'..'7' }) {
            "Invalid operationDays: $operationDays. Must contain only digits 1-7."
        }
        require(!seasonEnd.isBefore(seasonStart)) {
            "Season end $seasonEnd cannot be before season start $seasonStart for flight $flightNumber"
        }
    }
}
