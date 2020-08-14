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
		val historyFlights = seasonalFlight?.let { historyFlightRepository.getArrivalFlight(seasonalFlight.flightNumber, it.seasonStart, flightDate) }
		val filtered = historyFlights?.asSequence()
			?.filter { historyFlight -> isHistoryFlight(seasonalFlight, historyFlight) }
			?.toList() ?: emptyList()
		if (log.isDebugEnabled) {
			log.debug(" flight history with $seasonalFlight got ${historyFlights?.size}, filtered : ${filtered.size}")
		}
		return filtered
	}

	private fun isHistoryFlight(seasonalFlight: SeasonalFlight?, historyFlight: HistoryFlight): Boolean {
		val operationDay = getOperationDay(historyFlight.flightDate)
		return seasonalFlight?.operationDays!!.contains(operationDay.toString())
	}


	fun calculate(flightNumber: String, flightDate: Date): FlyingTimeResponse {
		val seasonalFlight = getSeasonalFlight(flightNumber, flightDate)
		val context = FlyingTimeContext(
			flightNumber,
			flightDate,
			flightSeason = getActiveSeason(),
			seasonalFlight = seasonalFlight,
			historyFlights = getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate))
		val qualifiedFlights = getQualifiedHistoryFlights(context)
		if (log.isDebugEnabled) {
			log.debug("get qualified history flight : ${qualifiedFlights.size}")
		}
		if (qualifiedFlights.size >= minHistoryFlight) {
			val totalFlyingTime = qualifiedFlights.asSequence()
				.take(minHistoryFlight)
				.sumBy { getMinutes(it.preActualTime, it.actualTime) }
			val flyingTimeResponse = FlyingTimeResponse(
				flightNumber, flightDate,
				totalFlyingTime / minHistoryFlight,
				history = true,
				seasonal = false,
				message = "calculate by $minHistoryFlight history"
			)
			log.info(" calculate by history : $flyingTimeResponse")
			return flyingTimeResponse
		}
		if (context.seasonalFlight != null) {
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
			.filter { checkFlightTooMuchDelay(it) }
			.toList()
	}

	private fun checkFlightTooMuchDelay(historyFlight: HistoryFlight): Boolean {
		val minutes = getMinutes(historyFlight.scheduledTime, historyFlight.actualTime)
		return minutes < maxHistoryDelay
	}

	private fun getMinutes(scheduledTime: Date, actualTime: Date): Int {
		return abs(Minutes.minutesBetween(DateTime(scheduledTime), DateTime(actualTime)).minutes)
	}

}
