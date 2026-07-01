package com.gzzn.airport.model

/**
 * 解析并匹配航季计划中的运营日编码，避免用字符串包含关系造成误匹配。
 * Parses and matches seasonal operation-day encodings without relying on unsafe substring checks.
 *
 * 运营日使用 `1..7` 编码（1 = 周一 … 7 = 周日）；无效字符会被忽略，重复数字会去重。
 * Operation days use digits `1..7` (1 = Monday … 7 = Sunday); invalid characters are ignored
 * and duplicate digits are deduplicated.
 */
object OperationDays {
    fun parse(operationDays: String): Set<Int> = operationDays
        .filter { it in '1'..'7' }
        .map { it.digitToInt() }
        .toSet()

    fun matches(operationDays: String, day: Int): Boolean = day in parse(operationDays)
}
