package com.gzzn.airport.respository

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.SeasonalFlight
import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect

@JdbcRepository(dialect = Dialect.ORACLE)
interface SeasonRepository {
	@Query(
		"""
		select 
		SEASON_REC_ID season_id, SEASON_NAME,START_DATE season_start,END_DATE season_end 
		from 
		FIMS_FLIGHTSEASON flight_season 
		where ACTIVESEASON_FLAG = :tag
		"""
	)
	fun getFlightSeasonByTag(tag: Boolean): FlightSeason?


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
		 flightseason.ACTIVESEASON_FLAG = 1 
		 and FLIGHT_NUMBER = :flightNumber 
		 and OPERATION_DAYS 
		 like :operationDay 
		 and ARRI_OR_DEPT = 'A'
		 
		 """
	)
	fun getSeasonalArrivalFlight(flightNumber: String, operationDay: String): SeasonalFlight?

}
