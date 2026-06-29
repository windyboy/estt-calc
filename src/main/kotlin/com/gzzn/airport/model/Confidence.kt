package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable

/** Confidence band for a flying-time estimate. */
@Serdeable
enum class Confidence {
    /** History-based estimate with sufficient qualified samples. */
    HIGH,

    /** Seasonal fallback or no estimate. */
    NONE,
}
