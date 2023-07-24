package com.gzzn.airport.resource

import com.gzzn.airport.model.*
import com.gzzn.airport.service.EsttService
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import org.slf4j.LoggerFactory

@Controller(value = "/estt")
class EsttController(private val esttService: EsttService) {
	companion object {
		private val log = LoggerFactory.getLogger("EsttController")
	}

	/**
	 * query active season
	 */
	@Get(uri = "/season")
	fun getFlightSeason(): FlightSeason? {
		val activeSeason = esttService.getActiveSeason()
//		if (activeSeason != null) {
//			val seasonStart = toLocalDate(activeSeason.seasonStart)
//			val seasonEnd = toLocalDate(activeSeason.seasonEnd)
//			return Season(activeSeason.seasonId, activeSeason.seasonName, seasonStart, seasonEnd)
//		}
		return activeSeason
	}
//	fun toLocalDate(dateToConvert: Date): LocalDate {
//		log.info("dateToConvert: ${dateToConvert.toInstant()}")
//		return dateToConvert.toInstant()
//			.atZone(ZoneId.systemDefault())// need to check default zone
//			.toLocalDate();
//	}

//	@Get(uri = "/date")
//	fun date(): FlightSeason {
//		return FlightSeason(1,"test-season", java.sql.Date(), Date())
//	}

	@Get(uri = "/seasonal/{flightNumber}/{flightDateString}")
	fun getSeasonalFlight(flightNumber: String, flightDateString: String): SeasonalFlight? {
		val flightDate = esttService.getFlightDate(flightDateString)
		log.info("flightDate: $flightDate")
		return esttService.getSeasonalFlight(flightNumber.uppercase(), flightDate)
	}

	@Get(uri = "/history/{flightNumber}/{flightDateString}")
	fun getHistoryFlights(flightNumber: String, flightDateString: String): List<HistoricalFlight> {
		log.info("get history of $flightNumber, $flightDateString")
		val flightDate = esttService.getFlightDate(flightDateString)
		val history = esttService.getHistoryFlights(flightNumber.uppercase(), flightDate)
		log.info("history size: ${history.size}")
		return history
	}

	@Get(uri = "/flyTime/{flightNumber}/{flightDateString}")
	fun calculate(flightNumber: String, flightDateString: String) : FlyingTimeResponse {
		val flightDate = esttService.getFlightDate(flightDateString)
		return esttService.calculate(flightNumber.uppercase(), flightDate)
	}
}
