package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable

@Serdeable
/** 有界历史查询响应。`totalFiltered` 等于 [items] 条数（阶段 B 合格记录）；[rawScanned] 为原始行数；[capped] 表示触达 `MAX_HISTORY_ROWS` 上限。 */
data class HistoryResponse(val items: List<HistoricalFlight>, val totalFiltered: Int, val rawScanned: Int, val capped: Boolean)
