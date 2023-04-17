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

    /**
     * get active flight season
     * @return current flight season
     */
    fun getActiveSeason(): FlightSeason? {
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
        log.info("operation day like: $operationDay , number: $flightNumber ")
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
                && abs(actualFlyTime - seasonalFlight.flyingTime!!) < maxHistoryDelay
        if (log.isDebugEnabled) {
            log.debug("history: $historyFlight , include :$result")
        }
        return result
    }


    /**
     * 根据给定的航班号和日期计算飞行时间。飞行时间是根据历史航班和季节性航班数据计算的。
     *
     * @param flightNumber 表示航班号的字符串。
     * @param flightDate 表示航班日期的 Date 对象。
     * @return FlyingTimeResponse 对象，包含以下属性：
     *          - flightNumber：航班号，String 类型。
     *          - flightDate：航班日期，Date 类型。
     *          - flyingTime：计算出的飞行时间，Int 类型。
     *          - history：一个布尔值，表示飞行时间是否使用历史航班数据计算。
     *          - seasonal：一个布尔值，表示飞行时间是否使用季节性航班数据计算。
     *          - message：一个字符串，包含关于如何计算飞行时间的描述性消息。
     *
     * 算法：
     * 1. 获取给定航班号和日期的季节性航班数据。
     * 2. 如果没有季节性航班数据，返回一个 FlyingTimeResponse，其中 seasonal 设置为 false，history 设置为 false，flyingTime 设置为 0。
     * 3. 如果有季节性航班数据，检索与季节性航班匹配的历史航班数据。
     * 4. 过滤历史航班数据以获得合格的航班。
     * 5. 如果合格航班的数量大于或等于 minHistoryFlight，则使用历史航班数据计算飞行时间。
     * 6. 如果合格航班的数量小于 minHistoryFlight，则使用季节性航班数据确定飞行时间。
     * 7. 返回计算出的 FlyingTimeResponse。
     */
    fun calculate(flightNumber: String, flightDate: Date): FlyingTimeResponse {
        log.info("Calculating flying time for $flightNumber on $flightDate")
        val seasonalFlight = getSeasonalFlight(flightNumber, flightDate)
        val flyingTimeResponse: FlyingTimeResponse

        if (seasonalFlight == null) {
            log.warn("No seasonal flight found for $flightNumber on $flightDate")
            flyingTimeResponse = FlyingTimeResponse(
                flightNumber, flightDate, 0,
                history = false, seasonal = false, message = "No seasonal flight"
            )
        } else {
            val historyFlights = getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
            val qualifiedFlights = getQualifiedHistoryFlights(historyFlights)

            log.debug("Qualified history flights: ${qualifiedFlights.size}")

            if (qualifiedFlights.size >= minHistoryFlight) {
                log.debug("Enough history flights: ${qualifiedFlights.size}")
                val totalFlyingTime = qualifiedFlights.asSequence()
                    .take(minHistoryFlight)
                    .sumOf { getMinutes(it.preActualTime, it.actualTime) }
                val average = totalFlyingTime / minHistoryFlight

                flyingTimeResponse = FlyingTimeResponse(
                    flightNumber, flightDate, average,
                    history = true, seasonal = false, message = "Calculated by $minHistoryFlight history flights"
                )
                log.info("Calculated by history: $flyingTimeResponse")
            } else {
                log.warn("History flights (${qualifiedFlights.size}) are not enough and can't find seasonal flight $flightNumber $flightDate")
                flyingTimeResponse = FlyingTimeResponse(
                    flightNumber, flightDate, seasonalFlight.flyingTime ?: 0,
                    history = false, seasonal = true, message = "Seasonal flight flying time"
                )
                log.info("Using seasonal flight flying time: $flyingTimeResponse")
            }
        }
        return flyingTimeResponse
    }



//    private fun getQualifiedHistoryFlights(flyingTimeContext: FlyingTimeContext): List<HistoricalFlight> {
//        return flyingTimeContext.historyFlights.asSequence()
//            .sortedByDescending { it.scheduledTime }
//            .filter { checkFlightTooMuchDelay(it) }
//            .toList()
//    }

    private fun getQualifiedHistoryFlights(historyFlights: List<HistoricalFlight>): List<HistoricalFlight> {
        return historyFlights.asSequence()
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
