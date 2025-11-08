package com.gzzn.airport.repository

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.SeasonalFlight
import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect

@JdbcRepository(dialect = Dialect.ORACLE)
interface SeasonRepository {
    companion object {
        const val ACTIVE_SEASON_FLAG = 1
        const val ARRI_OR_DEPT_ARRIVAL = 'A'
    }

    @Query(
        """
	select
	SEASON_REC_ID season_id, SEASON_NAME,START_DATE season_start,END_DATE season_end
	from
	FIMS_FLIGHTSEASON flight_season
	where ACTIVESEASON_FLAG = :isActive
		""",
    )
    fun getFlightSeason(isActive: Boolean = true): FlightSeason?

    @Query(
        """
		 select
		 seasonal_flight.FLIGHT_NUMBER,seasonal_flight.OPERATION_DAYS,seasonal_flight.FLYING_TIME,seasonal_flight.START_DATE season_start
		 from
		 FIMS_FLIGHTSCHD_SEASON seasonal_flight
		 LEFT JOIN
		 FIMS_FLIGHTSEASON flightseason
		 ON
		 seasonal_flight.SEASON_REC_ID = flightseason.SEASON_REC_ID
		 where
		 flightseason.ACTIVESEASON_FLAG = :activeFlag
		 and FLIGHT_NUMBER = :flightNumber
		 and INSTR(OPERATION_DAYS, :operationDay) > 0
		 and ARRI_OR_DEPT = :arriOrDept

		 """,
    )
    fun getSeasonalArrivalFlight(
        flightNumber: String,
        operationDay: String,
        activeFlag: Int = ACTIVE_SEASON_FLAG,
        arriOrDept: Char = ARRI_OR_DEPT_ARRIVAL,
    ): SeasonalFlight?
}
