package com.gzzn.airport.service.history

import com.gzzn.airport.model.HistoricalFlight

/** 主计算路径历史扫描结果及指标元数据。Calculation-path scan result with metric metadata. */
data class HistoryFlightScan(
    /** 阶段 C 合格样本，用于中位数计算。Stage-C qualified samples used for median. */
    val qualifiedFlights: List<HistoricalFlight>,
    /** 已扫描的原始行数（含被筛掉的）。Raw rows scanned from DB (including rejected). */
    val rawRows: Int,
    /** 通过阶段 B 的行数（可比样本，未必通过到港时刻筛选）。Rows passing stage B (comparable, not necessarily stage C). */
    val stageBRows: Int,
    /** 已达扫描上限后仍不足最低样本数。Still below minimum after bounded scan reaches cap. */
    val insufficientAfterCap: Boolean = false,
) {
    /** 通过阶段 C 的合格行数；始终等于 [qualifiedFlights].size。 */
    val qualifiedRows: Int get() = qualifiedFlights.size
}
