package com.gzzn.airport.repository

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.SeasonalFlight
import io.micronaut.data.annotation.Query
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect

/**
 * 读取 FIMS 航季和航季计划主数据；查询结果仍需由服务层复核运营日和航季日期边界。
 * Reads FIMS season and seasonal schedule master data; service code still revalidates
 * operation-day and season-window boundaries.
 */
@JdbcRepository(dialect = Dialect.ORACLE)
interface SeasonRepository {
    companion object {
        const val ACTIVE_SEASON_FLAG = 1
        const val ARRI_OR_DEPT_ARRIVAL = 'A'
    }

    /**
     * 根据激活标记返回航季主记录。
     * Returns the flight-season master record for the requested active flag.
     */
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

    /**
     * 使用 `INSTR(OPERATION_DAYS, :operationDay)` 作为数据库预筛选；服务层会再次逐位校验，
     * 防止运营日编码匹配出现误判。
     * Uses `INSTR(OPERATION_DAYS, :operationDay)` as a database prefilter; service code
     * validates digit-wise again to avoid operation-day false matches.
     */
    @Query(
        """
            select
                seasonal_flight.FLIGHT_NUMBER,
                seasonal_flight.OPERATION_DAYS,
                seasonal_flight.FLYING_TIME,
                seasonal_flight.START_DATE season_start,
                flightseason.END_DATE season_end
            from
                FIMS_FLIGHTSCHD_SEASON seasonal_flight
                left join FIMS_FLIGHTSEASON flightseason
                    on seasonal_flight.SEASON_REC_ID = flightseason.SEASON_REC_ID
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
