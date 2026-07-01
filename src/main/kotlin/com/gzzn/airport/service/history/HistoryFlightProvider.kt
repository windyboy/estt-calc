package com.gzzn.airport.service.history

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.OperationDays
import com.gzzn.airport.model.PaginatedHistoryResponse
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * 封装历史航班读取、分页扫描和业务过滤，让核心服务只负责流程编排。
 * Encapsulates historical retrieval, paginated scanning, and business filtering so the core service
 * can remain focused on orchestration.
 */
@Singleton
class HistoryFlightProvider(
    private val historyFlightRepository: HistoryFlightRepository,
    private val meterRegistry: MeterRegistry,
    private val config: EsttCalculationConfig,
) {
    private val historyPaginationScanner =
        HistoryPaginationScanner(historyFlightRepository, config, ::isEligibleHistoryFlight)

    companion object {
        private val log = LoggerFactory.getLogger(HistoryFlightProvider::class.java)
    }

    /**
     * 按航季计划读取并过滤历史样本。
     * Loads and filters historical samples for the provided seasonal schedule.
     *
     * SQL 的 `BETWEEN` 两端包含；这里把目标日期前一天作为上界，避免目标航班参与自身估算。
     * SQL `BETWEEN` is inclusive; this uses the day before the target date as the upper bound so the
     * target flight never appears in its own sample.
     */
    fun getHistoryFlights(seasonalFlight: SeasonalFlight, flightDate: LocalDate): List<HistoricalFlight> {
        val window = historyWindow(seasonalFlight, flightDate)
        log.debug(
            "Querying historical flights for {} from {} to {} (target {} excluded)",
            seasonalFlight.flightNumber,
            window.startDate,
            window.endDate,
            flightDate,
        )
        val historyFlights = historyFlightRepository.getArrivalFlight(
            seasonalFlight.flightNumber,
            window.startDate,
            window.endDate,
            config.maxHistoryRows,
        )
        val filtered = historyFlights.filter { isEligibleHistoryFlight(seasonalFlight, it) }
        log.debug(
            "Retrieved {} historical flights, filtered to {} valid flights",
            historyFlights.size,
            filtered.size,
        )
        return filtered
    }

    /**
     * 分块扫描原始历史记录，先过滤再分页；`offset` 和 `limit` 作用于过滤后的样本序列。
     * Scans raw history in chunks, filters first, then applies `offset` and `limit` to the filtered sequence.
     *
     * `hasMore` 同时反映已观察到的额外过滤样本和扫描上限截断，属于接口可见语义。
     * `hasMore` reflects both observed extra filtered samples and scan-limit truncation; it is API-visible behavior.
     */
    fun getPaginatedHistory(seasonalFlight: SeasonalFlight, flightDate: LocalDate, offset: Int, limit: Int): PaginatedHistoryResponse {
        val window = historyWindow(seasonalFlight, flightDate)
        val scanResult = historyPaginationScanner.scan(
            seasonalFlight = seasonalFlight,
            startDate = window.startDate,
            endDate = window.endDate,
            offset = offset,
            limit = limit,
        )
        val response = PaginatedHistoryResponse(
            items = scanResult.items,
            totalFiltered = scanResult.totalFiltered,
            offset = offset,
            limit = limit,
            hasMore = scanResult.hasMore,
        )
        recordPaginatedHistoryMetrics(response, offset, limit)
        return response
    }

    private fun recordPaginatedHistoryMetrics(response: PaginatedHistoryResponse, offset: Int, limit: Int) {
        val hasMoreTag = response.hasMore.toString()
        val cappedTag = (response.totalFiltered >= config.maxHistoryRows).toString()

        meterRegistry.counter(
            "estt.history.pagination.calls",
            "hasMore",
            hasMoreTag,
            "capped",
            cappedTag,
        ).increment()

        meterRegistry.summary(
            "estt.history.pagination.items",
            "hasMore",
            hasMoreTag,
        ).record(response.items.size.toDouble())

        meterRegistry.summary(
            "estt.history.pagination.filtered",
            "capped",
            cappedTag,
        ).record(response.totalFiltered.toDouble())

        meterRegistry.summary("estt.history.pagination.limit").record(limit.toDouble())
        meterRegistry.summary("estt.history.pagination.offset").record(offset.toDouble())
    }

    private data class HistoryWindow(val startDate: LocalDate, val endDate: LocalDate)

    private fun historyWindow(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryWindow = HistoryWindow(
        startDate = calculateHistoryStartDate(seasonalFlight.seasonStart),
        endDate = flightDate.minusDays(1),
    )

    private fun calculateHistoryStartDate(seasonStart: LocalDate): LocalDate = seasonStart.minusDays(config.historyStartOffsetDays)

    /**
     * 历史样本的业务准入规则，确保样本与航季计划一致。
     * Business gatekeeper ensuring historical samples align with the seasonal schedule.
     *
     * 飞行时长容差使用开区间，等于 [EsttCalculationConfig.maxFlyingTimeDeviation] 时会被剔除；
     * 航季飞行时长未配置时跳过该项过滤。
     * Flying-time tolerance is exclusive at [EsttCalculationConfig.maxFlyingTimeDeviation]; when seasonal
     * flying time is not configured, this tolerance check is skipped.
     */
    private fun isEligibleHistoryFlight(seasonalFlight: SeasonalFlight, historyFlight: HistoricalFlight): Boolean {
        val operationDay = historyFlight.flightDate.dayOfWeek.value
        val actualFlyTime = calculateDurationMinutes(historyFlight.previousDepartureTime, historyFlight.actualTime)

        val operationDayMatches = OperationDays.matches(seasonalFlight.operationDays, operationDay)
        val flightTimeOrderCorrect = historyFlight.previousDepartureTime < historyFlight.actualTime
        val scheduledDateMatches = historyFlight.scheduledTime.toLocalDate() == historyFlight.flightDate
        val withinFlyingTimeTolerance = isFlyingTimeWithinSeasonalTolerance(actualFlyTime, seasonalFlight.flyingTime)

        val result = operationDayMatches && flightTimeOrderCorrect && scheduledDateMatches && withinFlyingTimeTolerance
        if (!result && log.isDebugEnabled) {
            log.debug(
                "Excluded history flight on {}: dayMatch={}, timeOrder={}, dateMatch={}, withinTolerance={}",
                historyFlight.flightDate,
                operationDayMatches,
                flightTimeOrderCorrect,
                scheduledDateMatches,
                withinFlyingTimeTolerance,
            )
        }
        return result
    }

    private fun isFlyingTimeWithinSeasonalTolerance(actualFlyTime: Long, seasonalFlyTime: Long?): Boolean =
        seasonalFlyTime?.let { abs(actualFlyTime - it) < config.maxFlyingTimeDeviation } ?: true

    private fun calculateDurationMinutes(startTime: LocalDateTime, endTime: LocalDateTime): Long =
        Duration.between(startTime, endTime).toMinutes()
}
