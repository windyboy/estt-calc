package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable

/** Origin of a flying-time estimate in [FlyingTimeResponse]. */
@Serdeable
enum class EstimateSource {
    /** Median of qualified historical flights (≥ minimum threshold). */
    HISTORY,

    /** Seasonal schedule flying time when history is insufficient. */
    SEASONAL,

    /** No estimate available. */
    NONE,
}
