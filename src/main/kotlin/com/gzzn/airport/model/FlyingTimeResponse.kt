package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

@Serdeable
data class FlyingTimeResponse(
    val flightNumber: String,
    val flightDate: LocalDate,
    val flyingTime: Long?,
    @Deprecated("Use source == EstimateSource.HISTORY instead")
    val history: Boolean,
    @Deprecated("Use source == EstimateSource.SEASONAL instead")
    val seasonal: Boolean,
    val message: String,
    val source: EstimateSource,
    val sampleSize: Int,
    val confidence: Confidence,
)
