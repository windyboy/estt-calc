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
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs

/**
 * 航季内历史到港记录的查找与筛选。
 * Looks up and filters historical arrival records within the current season window.
 *
 * 业务规则见 docs/algorithm.md §5–§7。
 * 筛选分两层（主计算路径）：
 * - **阶段 B**（[isComparableHistoryFlight]）：基本有效复查 + 与目标可比（同运营日、飞行时长容差）
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

    /**
     * 主估算路径：由近及远扫描历史，经阶段 B + C 筛选后返回合格样本。
     * Calculation path: scan history nearest-first, apply stage B then C, return qualified samples.
     */
    fun getHistoryFlights(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryFlightScan {
        val window = historyWindow(seasonalFlight, flightDate)
        log.debug(
            "Querying historical flights for {} from {} to {} (target {} excluded)",
            seasonalFlight.flightNumber,
            window.startDate,
            window.endDate,
            flightDate,
        )
        // 目标日为航季首日时窗口为空，直接跳过查询。Empty window on season opening day — skip DB lookup.
        if (window.isEmpty) {
            val empty = HistoryFlightScan(emptyList(), 0, 0)
            recordCalculationScanMetrics(empty)
            return empty
        }
        val scan = scanCalculationHistory(seasonalFlight, flightDate, window)
        log.debug(
            "Retrieved {} raw rows, {} stage-B eligible, {} stage-C qualified",
            scan.rawRows,
            scan.stageBRows,
            scan.qualifiedRows,
        )
        recordCalculationScanMetrics(scan)
        return scan
    }

    /**
     * 运维/调试历史查询接口：一次有界读取，仅阶段 B（基本有效 + 可比），不含到港时刻可信筛选。
     * Debug history API: bounded read, stage B only — no schedule-deviation filter.
     */
    fun getHistory(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryResponse {
        val window = historyWindow(seasonalFlight, flightDate)
        if (window.isEmpty) {
            return HistoryResponse(emptyList(), 0, 0, false).also(::recordHistoryMetrics)
        }

        val rawFlights = getBoundedRawHistory(seasonalFlight.flightNumber, window)
        val items = rawFlights.filter { isComparableHistoryFlight(seasonalFlight, flightDate, it) }
        val response = HistoryResponse(
            items = items,
            totalFiltered = items.size,
            rawScanned = rawFlights.size,
            capped = rawFlights.size >= config.maxHistoryRows,
        )
        recordHistoryMetrics(response)
        return response
    }

    /**
     * 主计算扫描：一次读取当前航季窗口内最多 [EsttCalculationConfig.maxHistoryRows] 条原始记录。
     * Calculation scan: bounded read of at most [EsttCalculationConfig.maxHistoryRows] raw rows in the current season window.
     */
    private fun scanCalculationHistory(
        seasonalFlight: SeasonalFlight,
        targetFlightDate: LocalDate,
        window: HistoryWindow,
    ): HistoryFlightScan {
        val rawFlights = getBoundedRawHistory(seasonalFlight.flightNumber, window)
        val stageBEligible = rawFlights.filter { isComparableHistoryFlight(seasonalFlight, targetFlightDate, it) }
        val qualified = stageBEligible.filter { passesScheduleDeviation(it, config.maxScheduleDeviation) }

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

        meterRegistry.counter(
            "estt.history.calls",
            "capped",
            cappedTag,
        ).increment()

        meterRegistry.summary("estt.history.items", "capped", cappedTag)
            .record(response.items.size.toDouble())
        meterRegistry.summary("estt.history.filtered", "capped", cappedTag)
            .record(response.totalFiltered.toDouble())
        meterRegistry.summary("estt.history.raw_rows", "capped", cappedTag)
            .record(response.rawScanned.toDouble())
    }

    private data class HistoryWindow(val startDate: LocalDate, val endDate: LocalDate) {
        val isEmpty: Boolean get() = startDate > endDate
    }

    /** 历史窗口：[seasonStart, flightDate - 1]，不含目标执行日。Window: season start through day before target. */
    private fun historyWindow(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryWindow =
        HistoryWindow(seasonalFlight.seasonStart, flightDate.minusDays(1))

    /**
     * 阶段 B：基本有效复查 + 与目标可比（algorithm.md §7.1–§7.2）。
     * Stage B: basic validity re-check + comparability with the target flight.
     *
     * 到港、时刻齐全与窗口已在 SQL 预筛；此处复查计划日、时间顺序，并校验同运营日及飞行时长容差。
     * Scheduled-date match is checked here (not in SQL) to avoid database date-truncation differences.
     */
    private fun isComparableHistoryFlight(
        seasonalFlight: SeasonalFlight,
        targetFlightDate: LocalDate,
        historyFlight: HistoricalFlight,
    ): Boolean {
        fun exclude(reason: String): Boolean {
            log.debug("Excluded history flight on {}: {}", historyFlight.flightDate, reason)
            return false
        }

        val historyWeekday = historyFlight.flightDate.dayOfWeek.value
        val targetWeekday = targetFlightDate.dayOfWeek.value

        // 只保留与目标同星期几的历史（同运营日）。Keep only history on the same weekday as the target.
        if (historyWeekday != targetWeekday) return exclude("different weekday")

        // 历史星期几须在季节班期内；目标日已匹配计划时通常与上式等价。Weekday must appear in seasonal operation days.
        if (!OperationDays.matches(seasonalFlight.operationDays, historyWeekday)) return exclude("weekday not in operation days")

        if (historyFlight.scheduledTime.toLocalDate() != historyFlight.flightDate) return exclude("scheduled date mismatch")
        if (historyFlight.previousDepartureTime >= historyFlight.actualTime) return exclude("invalid time order")

        val actualFlyTime = Duration.between(historyFlight.previousDepartureTime, historyFlight.actualTime).toMinutes()
        // 有计划时长：偏差严格小于阈值（不含等于）；无计划：闭区间弱约束 [min, max]。
        val withinFlyingTimeTolerance = seasonalFlight.flyingTime?.let { seasonalTime ->
            abs(actualFlyTime - seasonalTime) < config.maxFlyingTimeDeviation
        } ?: (actualFlyTime in config.minFlyingTime..config.maxFlyingTime)
        return withinFlyingTimeTolerance || exclude("flying time outside tolerance")
    }
}
