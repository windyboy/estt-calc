package com.gzzn.airport.service

import com.gzzn.airport.model.*
import com.gzzn.airport.respository.HistoryFlightRepository
import com.gzzn.airport.respository.SeasonRepository
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import org.joda.time.DateTime
import org.joda.time.Minutes
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@Singleton
class EsttService(
    private val seasonRepository: SeasonRepository,
    private val historyFlightRepository: HistoryFlightRepository,
    @Value("\${default.maxHistoryDelay:120}") val maxHistoryDelay: Int,
    @Value("\${default.minHistoryFlight:20}") val minHistoryFlight: Int,
    @Value("\${default.dateFormat}") val dateFormat: String,
    @Value("\${default.startMinus}") val startMinus: Int
) {
    companion object {
        private val log = LoggerFactory.getLogger("EsttService")
    }

    fun getActiveSeason(): FlightSeason {
        val flightSeason = seasonRepository.getFlightSeasonByTag(true)
        log.info(" get active flight season $flightSeason")
        return flightSeason
    }

    fun getFlightDate(dateString: String): Date {
        return SimpleDateFormat(dateFormat).parse(dateString)
    }

    private fun getOperationDay(flightDate: Date): Int {
        return DateTime(flightDate).dayOfWeek
    }

    fun getSeasonalFlight(flightNumber: String, flightDate: Date): SeasonalFlight? {
        val operationDay = "%${getOperationDay(flightDate)}%"
        log.info("date: $operationDay , number: $flightNumber ")
        val seasonalFlight = seasonRepository.getSeasonalArrivalFlight(flightNumber, operationDay)
        log.info(" get seasonal flight : $seasonalFlight ")
        return seasonalFlight
    }

    fun getHistoryFlights(flightNumber: String, flightDate: Date): List<HistoricalFlight> {
        return getHistoryFlightsWithSeasonFlight(getSeasonalFlight(flightNumber, flightDate), flightDate)
    }

    private fun getHistoryFlightsWithSeasonFlight(
        seasonalFlight: SeasonalFlight?,
        flightDate: Date
    ): List<HistoricalFlight> {
        if (seasonalFlight == null) {
            log.warn("seasonal flight is null , no history flight")
            return emptyList()
        }

        val seasonStart = getHistoryStartDate(seasonalFlight.seasonStart)
        log.info("season : ${seasonalFlight.seasonStart} , historical flight from $seasonStart")
        val historyFlights = historyFlightRepository.getArrivalFlight(
            seasonalFlight.flightNumber,
            seasonStart,
            flightDate
        )
        val filtered = historyFlights.asSequence()
            .filter { historyFlight -> isHistoryFlight(seasonalFlight, historyFlight) }
            .toList()
        if (log.isDebugEnabled) {
            log.debug(" flight history with $seasonalFlight got ${historyFlights.size}, filtered : ${filtered.size}")
        }
        return filtered

    }

    private fun isHistoryFlight(seasonalFlight: SeasonalFlight?, historyFlight: HistoricalFlight): Boolean {
        if (seasonalFlight == null) {
            log.warn("seasonal flight is null !")
            return false
        }
        val operationDay = getOperationDay(historyFlight.flightDate)
        val actualFlyTime = getMinutes(historyFlight.preActualTime, historyFlight.actualTime)
        val result = seasonalFlight.operationDays.contains(operationDay.toString())
                && (historyFlight.preActualTime < historyFlight.actualTime)
                && abs(actualFlyTime - seasonalFlight.flyingTime) < maxHistoryDelay
        if (log.isDebugEnabled) {
            log.debug("history: $historyFlight , include :$result")
        }
        return result
    }


    fun calculate(flightNumber: String, flightDate: Date): FlyingTimeResponse {
        log.info("calculate flying time for $flightNumber on $flightDate")
        val seasonalFlight = getSeasonalFlight(flightNumber, flightDate)
        val context = FlyingTimeContext(
            seasonalFlight = seasonalFlight,
            historyFlights = getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
        )
        val qualifiedFlights = getQualifiedHistoryFlights(context)
        if (log.isDebugEnabled) {
            log.debug("get qualified history flight : ${qualifiedFlights.size}")
        }
        if (qualifiedFlights.size >= minHistoryFlight) {
            if (log.isDebugEnabled) {
                log.debug("get enough history flight ${qualifiedFlights.size}")
            }
            val totalFlyingTime = qualifiedFlights.asSequence()
                .take(minHistoryFlight)
                .sumOf { getMinutes(it.preActualTime, it.actualTime) }
            val average = totalFlyingTime / minHistoryFlight
            val flyingTimeResponse = FlyingTimeResponse(
                flightNumber, flightDate,
                average,
                history = true,
                seasonal = false,
                message = "calculate by $minHistoryFlight history"
            )
            log.info(" calculate by history : $flyingTimeResponse")
            return flyingTimeResponse
        }
        if (context.seasonalFlight != null) {
            if (log.isDebugEnabled) {
                log.debug("history is not enough, get flying time by seasonal flight $seasonalFlight")
            }
            val flyingTimeResponse = FlyingTimeResponse(
                flightNumber,
                flightDate, context.seasonalFlight.flyingTime,
                history = false, seasonal = true, message = "seasonal flight flying time"
            )
            log.info(" use seasonal flight flying time $flyingTimeResponse")
            return flyingTimeResponse
        }
        log.warn("history flight is not enough and can't find seasonal flight $flightNumber $flightDate")
        return FlyingTimeResponse(
            flightNumber,
            flightDate,
            0,
            history = false,
            seasonal = false,
            message = "no seasonal flight"
        )

    }


    private fun getQualifiedHistoryFlights(flyingTimeContext: FlyingTimeContext): List<HistoricalFlight> {
        return flyingTimeContext.historyFlights.asSequence()
            .sortedByDescending { it.scheduledTime }
            .filter { checkFlightTooMuchDelay(it) }
            .toList()
    }

    private fun checkFlightTooMuchDelay(historyFlight: HistoricalFlight): Boolean {
        val minutes = getMinutes(historyFlight.scheduledTime, historyFlight.actualTime)
        return minutes < maxHistoryDelay
    }

    private fun getMinutes(scheduledTime: Date, actualTime: Date): Int {
        return abs(Minutes.minutesBetween(DateTime(scheduledTime), DateTime(actualTime)).minutes)
    }

    /**
     *  use last season flight
     */
    private fun getHistoryStartDate(seasonStart: Date): Date {
        return DateTime(seasonStart).minusDays(startMinus).toDate()
    }

}
