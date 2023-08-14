package com.gzzn.airport.respository

import com.gzzn.airport.model.HistoricalFlight
import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import java.time.LocalDate


@JdbcRepository(dialect = Dialect.ORACLE)
interface HistoryFlightRepository {
    @Query(
        """
select
  PRE_DEPT_DATETIME_ACTUAL pre_actual_time ,
  ACTUAL_DATETIME actual_time ,
  flight_date ,
  SCHEDULED_DATETIME scheduled_time
from
  FIMS_FLIGHTSCHD_HST historicalFlight
where
  ACTUAL_DATETIME is not null
  and PRE_DEPT_DATETIME_ACTUAL is not null
  and  ARRI_OR_DEPT='A' 
  and flight_number = :flightNumber
  and flight_date between  :startDate
  and :endDate 
  order by flight_date desc
  """
    )
    fun getArrivalFlight(flightNumber: String, startDate: LocalDate, endDate: LocalDate): List<HistoricalFlight>
}
