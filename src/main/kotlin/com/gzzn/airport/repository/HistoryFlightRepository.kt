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
        const val ARRIVAL_FLIGHT_PAGE_SQL = """
SELECT
  previous_departure_time,
  actual_time,
  flight_date,
  scheduled_time
FROM (
  SELECT
    PRE_DEPT_DATETIME_ACTUAL previous_departure_time,
    ACTUAL_DATETIME actual_time,
    flight_date,
    SCHEDULED_DATETIME scheduled_time,
    ROW_NUMBER() OVER (
      ORDER BY flight_date DESC, SCHEDULED_DATETIME DESC, ACTUAL_DATETIME DESC
    ) rn
  FROM
    FIMS_FLIGHTSCHD_HST
  WHERE
    ACTUAL_DATETIME IS NOT NULL
    AND PRE_DEPT_DATETIME_ACTUAL IS NOT NULL
    AND PRE_DEPT_DATETIME_ACTUAL < ACTUAL_DATETIME
    AND ARRI_OR_DEPT = :arriOrDept
    AND flight_number = :flightNumber
    AND flight_date BETWEEN :startDate AND :endDate
) paged
WHERE rn > :offset
  AND rn <= (:offset + :limit)
ORDER BY rn
  """
    }

    // BETWEEN 两端包含；调用方以目标日前一天为 endDate，排除自身样本。
    // BETWEEN is inclusive; callers pass flightDate - 1 as endDate to exclude the target flight.
    @Query(ARRIVAL_FLIGHT_PAGE_SQL)
    fun getArrivalFlightPage(
        flightNumber: String,
        startDate: LocalDate,
        endDate: LocalDate,
        offset: Int,
        limit: Int,
        arriOrDept: Char = ARRI_OR_DEPT_ARRIVAL,
    ): List<HistoricalFlight>
}
