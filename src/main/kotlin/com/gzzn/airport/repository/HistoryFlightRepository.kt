package com.gzzn.airport.repository

import com.gzzn.airport.model.HistoricalFlight
import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import java.time.LocalDate

/**
 * 访问 `FIMS_FLIGHTSCHD_HST` 历史航班事实表；SQL 只做稳定的数据边界过滤，
 * 运营日、容差等业务规则仍由服务层统一判断。
 * Accesses historical facts in `FIMS_FLIGHTSCHD_HST`; SQL applies stable data-boundary filters,
 * while operation-day and tolerance business rules remain centralized in the service layer.
 */
@JdbcRepository(dialect = Dialect.ORACLE)
interface HistoryFlightRepository {
    companion object {
        const val ARRI_OR_DEPT_ARRIVAL = 'A'
    }

    /**
     * 按最近航班日期倒序获取到港历史，并通过 `maxRows` 控制扫描上限。
     * Fetches arrival history capped by `maxRows`, ordered by most recent flight date.
     *
     * `flight_date BETWEEN :startDate AND :endDate` 两端都包含；调用方传入目标日前一天
     * 作为 `endDate`，避免把正在估算的航班纳入自身样本。
     * `flight_date BETWEEN :startDate AND :endDate` is inclusive on both bounds; callers pass
     * the day before the target operation date as `endDate` so the estimated flight is not self-sampled.
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
     * 按 offset/limit 分页读取原始历史数据，供服务层边扫描边做业务过滤。
     * Fetches a raw arrival-history page using offset/limit so the service layer can scan
     * iteratively while applying business filters.
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
