package com.gzzn.airport.repository

import com.gzzn.airport.model.HistoricalFlight
import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import java.time.LocalDate


@JdbcRepository(dialect = Dialect.ORACLE)
interface HistoryFlightRepository {
    companion object {
        const val ARRI_OR_DEPT_ARRIVAL = 'A'
    }
    @Query(
        """
SELECT
  PRE_DEPT_DATETIME_ACTUAL previous_departure_time,
  ACTUAL_DATETIME actual_time,
  flight_date,
  SCHEDULED_DATETIME scheduled_time
FROM
  FIMS_FLIGHTSCHD_HST
WHERE
  ACTUAL_DATETIME IS NOT NULL
  AND PRE_DEPT_DATETIME_ACTUAL IS NOT NULL
  AND PRE_DEPT_DATETIME_ACTUAL < ACTUAL_DATETIME
  AND ARRI_OR_DEPT = :arriOrDept
  AND flight_number = :flightNumber
  AND flight_date BETWEEN :startDate AND :endDate
  AND flight_date = TRUNC(SCHEDULED_DATETIME)
ORDER BY flight_date DESC
FETCH FIRST :maxRows ROWS ONLY
  """
    )
    fun getArrivalFlight(flightNumber: String, startDate: LocalDate, endDate: LocalDate, maxRows: Int = 300, arriOrDept: Char = ARRI_OR_DEPT_ARRIVAL): List<HistoricalFlight>

    @Query(
        """
SELECT
  PRE_DEPT_DATETIME_ACTUAL previous_departure_time,
  ACTUAL_DATETIME actual_time,
  flight_date,
  SCHEDULED_DATETIME scheduled_time
FROM
  FIMS_FLIGHTSCHD_HST
WHERE
  ACTUAL_DATETIME IS NOT NULL
  AND PRE_DEPT_DATETIME_ACTUAL IS NOT NULL
  AND PRE_DEPT_DATETIME_ACTUAL < ACTUAL_DATETIME
  AND ARRI_OR_DEPT = :arriOrDept
  AND flight_number = :flightNumber
  AND flight_date BETWEEN :startDate AND :endDate
  AND flight_date = TRUNC(SCHEDULED_DATETIME)
ORDER BY flight_date DESC
  """
    )
    fun getArrivalFlightUnlimited(flightNumber: String, startDate: LocalDate, endDate: LocalDate, arriOrDept: Char = ARRI_OR_DEPT_ARRIVAL): List<HistoricalFlight>
}

