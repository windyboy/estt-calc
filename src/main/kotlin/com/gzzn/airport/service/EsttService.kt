package com.gzzn.airport.service

import com.gzzn.airport.model.*
import com.gzzn.airport.respository.HistoryFlightRepository
import com.gzzn.airport.respository.SeasonRepository
import io.micronaut.context.annotation.Value
import org.joda.time.DateTime
import org.joda.time.Minutes
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class EsttService(
	private val seasonRepository: SeasonRepository,
	private val historyFlightRepository: HistoryFlightRepository,
	@Value("\${default.maxHistoryDelay:120}") val maxHistoryDelay: Int,
	@Value("\${default.minHistoryFlight:20}") val minHistoryFlight: Int,
	@Value("\${default.dateFormat}") val dateFormat: String
) {
	companion object {
		private val log = LoggerFactory.getLogger("EsttService")
	}

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
		val seasonalFlight = seasonRepository.getSeasonalArrivalFlight(flightNumber, operationDay)
		log.info(" get seasonal flight : $seasonalFlight ")
		return seasonalFlight
	}

	fun getHistoryFlights(flightNumber: String, flightDate: Date): List<HistoryFlight> {
		return getHistoryFlightsWithSeasonFlight(getSeasonalFlight(flightNumber, flightDate), flightDate)
	}

	private fun getHistoryFlightsWithSeasonFlight(seasonalFlight: SeasonalFlight?, flightDate: Date): List<HistoryFlight> {
		if (seasonalFlight != null) {
			val historyFlights = historyFlightRepository.getArrivalFlight(seasonalFlight.flightNumber, seasonalFlight.seasonStart, flightDate)
			val filtered = historyFlights.asSequence()
				.filter { historyFlight -> isHistoryFlight(seasonalFlight, historyFlight) }
				.toList()
			if (log.isDebugEnabled) {
				log.debug(" flight history with $seasonalFlight got ${historyFlights.size}, filtered : ${filtered.size}")
			}
			return filtered
		}
		log.warn("seasonal flight is null , no history flight")
		return emptyList()
	}

	private fun isHistoryFlight(seasonalFlight: SeasonalFlight?, historyFlight: HistoryFlight): Boolean {
		val operationDay = getOperationDay(historyFlight.flightDate)
		val actualFlyTime = getMinutes(historyFlight.preActualTime, historyFlight.actualTime)
		if (seasonalFlight != null) {
			val result = seasonalFlight.operationDays.contains(operationDay.toString())
				&& (historyFlight.preActualTime < historyFlight.actualTime)
				&& abs(actualFlyTime - seasonalFlight.flyingTime) < maxHistoryDelay
			if (log.isDebugEnabled) {
				log.debug("history: $historyFlight , include :$result")
			}
			return result
		}
		log.warn("seasonal flight is null !")
		return false
	}


	fun calculate(flightNumber: String, flightDate: Date): FlyingTimeResponse {
		log.info("calculate flying time for $flightNumber on $flightDate")
		val seasonalFlight = getSeasonalFlight(flightNumber, flightDate)
		val context = FlyingTimeContext(
			seasonalFlight = seasonalFlight,
			historyFlights = getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate))
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
				.sumBy { getMinutes(it.preActualTime, it.actualTime) }
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
			val flyingTimeResponse = FlyingTimeResponse(flightNumber,
				flightDate, context.seasonalFlight.flyingTime,
				history = false, seasonal = true, message = "seasonal flight flying time")
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


	private fun getQualifiedHistoryFlights(flyingTimeContext: FlyingTimeContext): List<HistoryFlight> {
		return flyingTimeContext.historyFlights.asSequence()
			.sortedByDescending { it.scheduledTime }
			.filter { checkFlightTooMuchDelay(it, flyingTimeContext.seasonalFlight) }
			.toList()
	}

	private fun checkFlightTooMuchDelay(historyFlight: HistoryFlight, seasonalFlight: SeasonalFlight?): Boolean {
		val minutes = getMinutes(historyFlight.scheduledTime, historyFlight.actualTime)
		if (seasonalFlight != null) {
			return abs(seasonalFlight.flyingTime - minutes) < minHistoryFlight && minutes < maxHistoryDelay
		}
		return minutes < maxHistoryDelay
	}

	private fun getMinutes(scheduledTime: Date, actualTime: Date): Int {
		return abs(Minutes.minutesBetween(DateTime(scheduledTime), DateTime(actualTime)).minutes)
	}

}
