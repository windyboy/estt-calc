package com.gzzn.airport.service.history

import com.gzzn.airport.model.HistoricalFlight

/** Result of a calculation-path history fetch, including scan metadata for metrics. */
data class HistoryFlightScan(
    val flights: List<HistoricalFlight>,
    val rawRows: Int,
    val filteredRows: Int,
    val hitScanLimit: Boolean,
    val extendedBeyondBudget: Boolean = false,
)
