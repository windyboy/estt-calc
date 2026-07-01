package com.gzzn.airport.model

/**
 * 运营日编码为 1..7（周一..周日），逐位匹配，非子串包含。
 * Operation days encoded as digits 1..7 (Mon..Sun); digit-wise match, not substring.
 */
object OperationDays {
    fun parse(operationDays: String): Set<Int> = operationDays
        .filter { it in '1'..'7' }
        .map { it.digitToInt() }
        .toSet()

    fun matches(operationDays: String, day: Int): Boolean = day in parse(operationDays)
}
