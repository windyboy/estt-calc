package com.gzzn.airport.service.history

import com.gzzn.airport.model.HistoricalFlight
import java.time.Duration

/** 早到始终保留，晚到在阈值内（含等于）保留。Early arrivals accepted; late within threshold accepted. */
internal fun passesScheduleDeviation(historyFlight: HistoricalFlight, maxScheduleDeviation: Int): Boolean {
    val deviationMinutes = Duration.between(historyFlight.scheduledTime, historyFlight.actualTime).toMinutes()
    return deviationMinutes <= maxScheduleDeviation
}
