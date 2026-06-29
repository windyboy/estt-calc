package com.gzzn.airport.model

enum class NoEstimateReason(val message: String) {
    NO_SEASONAL_FLIGHT("no seasonal flight for operation day"),
    INSUFFICIENT_HISTORY_NO_SEASONAL_TIME(
        "insufficient qualified history; seasonal flying time not configured",
    ),
}
