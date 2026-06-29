package com.gzzn.airport.repository

import com.gzzn.airport.model.HistoricalFlight
import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import java.time.LocalDate

/**
 * Low-level access to historical flight facts stored in `FIMS_FLIGHTSCHD_HST`.
 * Queries intentionally constrain rows to recent data and rely on the service layer
 * for additional business filtering.
 */
@JdbcRepository(dialect = Dialect.ORACLE)
interface HistoryFlightRepository {
    companion object {
        const val ARRI_OR_DEPT_ARRIVAL = 'A'
    }

    /**
     * Fetches arrival history capped by `maxRows`, ordered by most recent flight date.
     *
     * `flight_date BETWEEN :startDate AND :endDate` is inclusive on both bounds; callers pass
     * the day before the target operation date as `endDate` to exclude the flight being estimated.
     */
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
  """,
    )
    fun getArrivalFlight(
        flightNumber: String,
        startDate: LocalDate,
        endDate: LocalDate,
        maxRows: Int = 300,
        arriOrDept: Char = ARRI_OR_DEPT_ARRIVAL,
    ): List<HistoricalFlight>

    /**
     * Fetches a page of arrival history using offset/limit for iterative scans.
     */
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
OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
  """,
    )
    fun getArrivalFlightPage(
        flightNumber: String,
        startDate: LocalDate,
        endDate: LocalDate,
        offset: Int,
        limit: Int,
        arriOrDept: Char = ARRI_OR_DEPT_ARRIVAL,
    ): List<HistoricalFlight>
}
