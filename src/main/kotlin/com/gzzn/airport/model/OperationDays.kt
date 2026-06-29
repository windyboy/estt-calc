package com.gzzn.airport.model

/**
 * Parses and matches seasonal operation-day encodings.
 *
 * Operation days are encoded as a string of digits 1–7 (1 = Monday … 7 = Sunday).
 * Example: `"135"` means Monday, Wednesday, and Friday. Invalid characters are
 * ignored; duplicate digits are deduplicated.
 */
object OperationDays {
    fun parse(operationDays: String): Set<Int> = operationDays
        .filter { it in '1'..'7' }
        .map { it.digitToInt() }
        .toSet()

    fun matches(operationDays: String, day: Int): Boolean = day in parse(operationDays)
}
