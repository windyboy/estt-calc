package com.gzzn.airport.service.history

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.HistoryResponse
import com.gzzn.airport.model.OperationDays
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs

/**
 * 航季内历史到港记录的查找与筛选。
 * Looks up and filters historical arrival records within the current season window.
 *
 * 业务规则见 docs/algorithm.md §5–§7。
 * 筛选分两层（主计算路径）：
 * - **阶段 B**（[passesStageB]）：基本有效复查 + 与目标可比（同运营日、飞行时长容差）
 * - **阶段 C**（[passesScheduleDeviation]）：到港时刻可信（早到保留，晚到不超过阈值）
 *
 * SQL 预筛（到港、时刻齐全、窗口）见 [HistoryFlightRepository]；计划日一致性在 Kotlin 层过滤以兼容多数据库。
 * 历史查询接口仅做阶段 B，不做到港时刻筛选（algorithm.md 附录）。
 */
@Singleton
class HistoryFlightProvider(
    private val historyFlightRepository: HistoryFlightRepository,
    private val meterRegistry: MeterRegistry,
    private val config: EsttCalculationConfig,
) {
    companion object {
        private val log = LoggerFactory.getLogger(HistoryFlightProvider::class.java)
    }

    fun getHistoryFlights(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryFlightScan {
        requireTargetInSeasonalOperationDays(seasonalFlight, flightDate)
        val window = historyWindow(seasonalFlight, flightDate)
        log.debug(
            "Querying historical flights for {} from {} to {} (target {} excluded)",
            seasonalFlight.flightNumber,
            window.startDate,
            window.endDate,
            flightDate,
        )
        if (window.isEmpty) {
            val empty = HistoryFlightScan(emptyList(), 0, 0)
            recordCalculationScanMetrics(empty)
            return empty
        }
        val scan = scanCalculationHistory(seasonalFlight, flightDate.dayOfWeek, window)
        log.debug(
            "Retrieved {} raw rows, {} stage-B eligible, {} stage-C qualified",
            scan.rawRows,
            scan.stageBRows,
            scan.qualifiedRows,
        )
        recordCalculationScanMetrics(scan)
        return scan
    }

    fun getHistory(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryResponse {
        requireTargetInSeasonalOperationDays(seasonalFlight, flightDate)
        val window = historyWindow(seasonalFlight, flightDate)
        if (window.isEmpty) {
            return HistoryResponse(emptyList(), 0, 0, false).also(::recordHistoryMetrics)
        }

        val rawFlights = getBoundedRawHistory(seasonalFlight.flightNumber, window)
        val items = rawFlights.filter { passesStageB(seasonalFlight, flightDate.dayOfWeek, it) }
        return HistoryResponse(
            items = items,
            totalFiltered = items.size,
            rawScanned = rawFlights.size,
            capped = rawFlights.size >= config.maxHistoryRows,
        ).also(::recordHistoryMetrics)
    }

    private fun scanCalculationHistory(
        seasonalFlight: SeasonalFlight,
        targetDayOfWeek: DayOfWeek,
        window: HistoryWindow,
    ): HistoryFlightScan {
        val rawFlights = getBoundedRawHistory(seasonalFlight.flightNumber, window)
        val stageBEligible = mutableListOf<HistoricalFlight>()
        val qualified = mutableListOf<HistoricalFlight>()
        for (flight in rawFlights) {
            if (!passesStageB(seasonalFlight, targetDayOfWeek, flight)) continue
            stageBEligible += flight
            if (passesScheduleDeviation(flight, config.maxScheduleDeviation)) {
                qualified += flight
            }
        }
        return HistoryFlightScan(
            qualifiedFlights = qualified,
            rawRows = rawFlights.size,
            stageBRows = stageBEligible.size,
            insufficientAfterCap = rawFlights.size >= config.maxHistoryRows && qualified.size < config.minHistoryFlight,
        )
    }

    private fun getBoundedRawHistory(flightNumber: String, window: HistoryWindow): List<HistoricalFlight> =
        historyFlightRepository.getArrivalFlights(
            flightNumber,
            window.startDate,
            window.endDate,
            config.maxHistoryRows,
        )

    private fun recordCalculationScanMetrics(scan: HistoryFlightScan) {
        meterRegistry.summary("estt.history.scan.raw_rows").record(scan.rawRows.toDouble())
        meterRegistry.summary("estt.history.scan.stage_b_rows").record(scan.stageBRows.toDouble())
        meterRegistry.summary("estt.history.scan.qualified_rows").record(scan.qualifiedRows.toDouble())
        meterRegistry.counter(
            "estt.history.scan.calls",
            "insufficient_after_cap",
            scan.insufficientAfterCap.toString(),
        ).increment()
    }

    private fun recordHistoryMetrics(response: HistoryResponse) {
        val cappedTag = response.capped.toString()
        meterRegistry.counter("estt.history.calls", "capped", cappedTag).increment()
        meterRegistry.summary("estt.history.items", "capped", cappedTag).record(response.items.size.toDouble())
        meterRegistry.summary("estt.history.filtered", "capped", cappedTag).record(response.totalFiltered.toDouble())
        meterRegistry.summary("estt.history.raw_rows", "capped", cappedTag).record(response.rawScanned.toDouble())
    }

    private data class HistoryWindow(val startDate: LocalDate, val endDate: LocalDate) {
        val isEmpty: Boolean get() = startDate > endDate
    }

    private fun historyWindow(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryWindow =
        HistoryWindow(seasonalFlight.seasonStart, flightDate.minusDays(1))

    private fun requireTargetInSeasonalOperationDays(seasonalFlight: SeasonalFlight, targetFlightDate: LocalDate) {
        val weekday = targetFlightDate.dayOfWeek.value
        check(OperationDays.matches(seasonalFlight.operationDays, weekday)) {
            "Target date $targetFlightDate (weekday $weekday) is not in seasonal operation days ${seasonalFlight.operationDays}"
        }
    }

    /** 阶段 B（algorithm.md §7.1–§7.2）：基本有效复查 + 与目标同运营日 + 飞行时长容差。 */
    private fun passesStageB(seasonalFlight: SeasonalFlight, targetDayOfWeek: DayOfWeek, historyFlight: HistoricalFlight): Boolean {
        fun exclude(reason: String): Boolean {
            log.debug("Excluded history flight on {}: {}", historyFlight.flightDate, reason)
            return false
        }

        if (historyFlight.flightDate.dayOfWeek != targetDayOfWeek) return exclude("different weekday")
        if (historyFlight.scheduledTime.toLocalDate() != historyFlight.flightDate) return exclude("scheduled date mismatch")
        if (historyFlight.previousDepartureTime >= historyFlight.actualTime) return exclude("invalid time order")

        val flyMinutes = Duration.between(historyFlight.previousDepartureTime, historyFlight.actualTime).toMinutes()
        val withinTolerance = seasonalFlight.flyingTime?.let { planned ->
            abs(flyMinutes - planned) < config.maxFlyingTimeDeviation
        } ?: (flyMinutes in config.minFlyingTime..config.maxFlyingTime)
        if (!withinTolerance) return exclude("flying time outside tolerance")

        return true
    }
}
