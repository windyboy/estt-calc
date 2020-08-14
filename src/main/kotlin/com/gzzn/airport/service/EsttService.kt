package com.gzzn.airport.service

import com.gzzn.airport.model.*
import com.gzzn.airport.respository.HistoryFlightRepository
import com.gzzn.airport.respository.SeasonRepository
import io.micronaut.context.annotation.Value
import org.joda.time.DateTime
import org.joda.time.Minutes
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

	fun getActiveSeason(): FlightSeason? {
		return seasonRepository.getFlightSeasonByTag(true);
	}

	fun getFlightDate(dateString: String): Date {
		return SimpleDateFormat(dateFormat).parse(dateString)
	}

	private fun getOperationDay(flightDate: Date): Int {
		return DateTime(flightDate).dayOfWeek
	}

	fun getSeasonalFlight(flightNumber: String, flightDate: Date): SeasonalFlight? {
		val operationDay = "%" + getOperationDay(flightDate) + "%"
		return seasonRepository.getSeasonalArrivalFlight(flightNumber, operationDay)
	}

	fun getHistoryFlights(flightNumber: String, flightDate: Date): List<HistoryFlight> {
		val seasonalFlight = getSeasonalFlight(flightNumber, flightDate)
		val historyFlights = seasonalFlight?.let { historyFlightRepository.getArrivalFlight(flightNumber, it.seasonStart, flightDate) }
		return historyFlights?.asSequence()
			?.filter { historyFlight -> isHistoryFlight(seasonalFlight, historyFlight) }
			?.toList() ?: emptyList()

	}

	private fun isHistoryFlight(seasonalFlight: SeasonalFlight?, historyFlight: HistoryFlight): Boolean {
		val operationDay = getOperationDay(historyFlight.flightDate)
		return seasonalFlight?.operationDays!!.contains(operationDay.toString())
	}


	fun calculate(flightNumber: String, flightDate: Date): FlyingTimeResponse {
		val context = FlyingTimeContext(
			flightNumber,
			flightDate,
			flightSeason = getActiveSeason(),
			seasonalFlight = getSeasonalFlight(flightNumber, flightDate),
			historyFlights = getHistoryFlights(flightNumber, flightDate))
		val qualifiedFlights = getQualifiedHistoryFlights(context)
//		println(" ${qualifiedFlights.size} ")

		if (qualifiedFlights.size >= minHistoryFlight) {
			val totalFlyingTime = qualifiedFlights.asSequence()
				.take(minHistoryFlight)
				.sumBy { getMinutes(it.preActualTime, it.actualTime) }
			return FlyingTimeResponse(
				flightNumber, flightDate,
				totalFlyingTime / minHistoryFlight,
				history = true,
				seasonal = false,
				message = "calculate by $minHistoryFlight history"
			)
		}
		if (context.seasonalFlight != null) {
			return FlyingTimeResponse(flightNumber,
				flightDate, context.seasonalFlight.flyingTime, history = false, seasonal = true, message = "seasonal flight flying time")
		}
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
//		println(" $historyFlight, minutes : $minutes")
		return minutes < maxHistoryDelay
	}

	private fun getMinutes(scheduledTime: Date, actualTime: Date): Int {
		return abs(Minutes.minutesBetween(DateTime(scheduledTime), DateTime(actualTime)).minutes)
	}

}
