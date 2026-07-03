package com.gzzn.airport.service.history

import com.gzzn.airport.model.HistoricalFlight

/** Result of a calculation-path history fetch, including scan metadata for metrics. */
data class HistoryFlightScan(
    val qualifiedFlights: List<HistoricalFlight>,
    val rawRows: Int,
    val stageBRows: Int,
    val qualifiedRows: Int,
    val extendedScanUsed: Boolean = false,
    val insufficientAfterBudget: Boolean = false,
)
