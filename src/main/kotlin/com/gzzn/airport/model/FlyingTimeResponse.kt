package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

@Serdeable
data class FlyingTimeResponse(
    val flightNumber: String,
    val flightDate: LocalDate,
    val flyingTime: Long,
    val history: Boolean,
    val seasonal: Boolean,
    val message: String,
)
