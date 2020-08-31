package com.gzzn.airport.respository

import com.gzzn.airport.model.HistoryFlight
import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import java.util.*

@JdbcRepository(dialect = Dialect.ORACLE)
interface HistoryFlightRepository {
	@Query("select PRE_DEPT_DATETIME_ACTUAL pre_actual_time , ACTUAL_DATETIME actual_time, flight_date, SCHEDULED_DATETIME scheduled_time from FIMS_FLIGHTSCHD_HST historyflight where ACTUAL_DATETIME is not null and  ARRI_OR_DEPT='A' and flight_number = :flightNumber and flight_date between  :startDate and :endDate order by flight_date desc")
	fun getArrivalFlight(flightNumber: String, startDate: Date, endDate: Date): List<HistoryFlight>
}
