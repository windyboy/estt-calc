package com.gzzn.airport.service

import java.time.LocalDate

/**
 * Keeps calculation input validation rules separate from [EsttService] orchestration.
 *
 * The validation messages are part of the existing observable behavior and must remain unchanged.
 */
internal object EsttInputValidator {
    fun validateCalculationInputs(flightNumber: String, flightDate: LocalDate) {
        require(flightNumber.isNotBlank()) { "Flight number cannot be empty" }
        require(flightNumber.length in 5..6) { "Flight number must be 5-6 characters, got: ${flightNumber.length}" }
        require(flightDate.isAfter(LocalDate.of(2000, 1, 1))) {
            "Flight date must be after 2000-01-01, got: $flightDate"
        }
        require(flightDate.isBefore(LocalDate.now().plusYears(1))) {
            "Flight date cannot be more than 1 year in the future, got: $flightDate"
        }
    }
}
