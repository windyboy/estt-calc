package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class PaginatedHistoryResponse(
    val items: List<HistoricalFlight>,
    val totalFiltered: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

