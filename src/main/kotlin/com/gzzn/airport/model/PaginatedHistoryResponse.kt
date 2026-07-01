package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable

/**
 * 历史航班接口的分页响应；分页在业务过滤之后应用。
 * Paginated response for the history endpoint; paging is applied after business filtering.
 *
 * @property items 当前窗口内返回的已过滤历史记录。
 * Filtered history records for the current window.
 * @property totalFiltered 扫描窗口内观察到的已过滤总数，受配置上限约束。
 * Total count observed within the scan window, capped by configuration.
 * @property offset 客户端请求的偏移量，在过滤后应用。
 * Client requested offset applied after filtering.
 * @property limit 客户端请求的返回数量上限，在过滤后应用。
 * Client requested limit applied after filtering.
 * @property hasMore 表示当前页之后是否可能还有更多数据。
 * Indicates whether additional data is available beyond this page.
 */
@Serdeable
data class PaginatedHistoryResponse(
    val items: List<HistoricalFlight>,
    val totalFiltered: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)
