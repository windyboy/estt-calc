package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable

/**
 * Response payload returned by the history endpoint.
 *
 * @property items filtered history records for the current window.
 * @property totalFiltered total count observed within the scan window (capped by configuration).
 * @property offset client requested offset applied after filtering.
 * @property limit client requested limit applied after filtering.
 * @property hasMore indicates whether additional data is available beyond this page.
 */
@Serdeable
data class PaginatedHistoryResponse(
    val items: List<HistoricalFlight>,
    val totalFiltered: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)
