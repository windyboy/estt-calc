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
 * 分页查询接口仅做阶段 B，不做到港时刻筛选，不启用扩展扫描（algorithm.md 附录）。
 */
@Singleton
class HistoryFlightProvider(
    private val historyFlightRepository: HistoryFlightRepository,
    private val meterRegistry: MeterRegistry,
    private val config: EsttCalculationConfig,
) {
    companion object {
        private val log = LoggerFactory.getLogger(HistoryFlightProvider::class.java)

        /** 每次向数据库分页拉取的原始行数。Raw rows fetched per repository page. */
        private const val CALC_SCAN_CHUNK_SIZE = 100
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
        if (window.startDate > window.endDate) {
            val empty = emptyHistoryFlightScan()
            recordCalculationScanMetrics(empty)
            return empty
        }
        val scan = scanCalculationHistory(seasonalFlight, flightDate, window.startDate, window.endDate)
        log.debug(
            "Retrieved {} raw rows, {} stage-B eligible, {} stage-C qualified (extendedScanUsed={})",
            scan.rawRows,
            scan.stageBRows,
            scan.qualifiedRows,
            scan.extendedScanUsed,
        )
        recordCalculationScanMetrics(scan)
        return scan
    }

    /**
     * 运维/调试分页接口：仅阶段 B（基本有效 + 可比），不含到港时刻可信筛选与扩展扫描。
     * Paginated debug API: stage B only — no schedule-deviation filter, no extended scan.
     */
    fun getPaginatedHistory(seasonalFlight: SeasonalFlight, flightDate: LocalDate, offset: Int, limit: Int): PaginatedHistoryResponse {
        val window = historyWindow(seasonalFlight, flightDate)
        if (window.startDate > window.endDate) {
            return PaginatedHistoryResponse(emptyList(), 0, offset, limit, false).also {
                recordPaginatedHistoryMetrics(it, offset, limit)
            }
        }
        val scanResult = scanPaginatedHistory(seasonalFlight, flightDate, window.startDate, window.endDate, offset, limit)
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

    /**
     * 主计算扫描：先常规预算（[EsttCalculationConfig.maxHistoryRows]），
     * 合格样本仍不足时扩展到 [EsttCalculationConfig.maxRawScanRows]（常规 × 3）。
     * Calculation scan: regular raw-row budget first, then extended cap if still below minimum.
     */
    private fun scanCalculationHistory(
        seasonalFlight: SeasonalFlight,
        targetFlightDate: LocalDate,
        startDate: LocalDate,
        endDate: LocalDate,
    ): HistoryFlightScan {
        val qualified = mutableListOf<HistoricalFlight>()
        val pager = RawHistoryPager(seasonalFlight.flightNumber, startDate, endDate)
        var stageBRows = 0

        fun appendBatch(batch: List<HistoricalFlight>) {
            for (flight in batch) {
                if (!hasMatchingScheduledDate(flight)) continue
                if (!isComparableHistoryFlight(seasonalFlight, targetFlightDate, flight)) continue

                stageBRows++
                // 阶段 C：到港时刻可信。Stage C: trustworthy arrival time.
                if (passesScheduleDeviation(flight, config.maxScheduleDeviation)) {
                    qualified += flight
                }
            }
        }

        fun scanUntil(rawCap: Int): Boolean {
            while (pager.rawScanned < rawCap && qualified.size < config.minHistoryFlight) {
                val batch = pager.nextBatch(rawCap) ?: return false
                appendBatch(batch.flights)
                if (!batch.hasMoreInWindow) return false
            }
            return pager.rawScanned >= rawCap
        }

        // 阶段 1：由近及远，合格够门槛或触及常规上限即停。Phase 1: stop when enough qualified or regular cap hit.
        val regularBudgetExhausted = scanUntil(config.maxHistoryRows)
        val extendedScanUsed = regularBudgetExhausted && qualified.size < config.minHistoryFlight

        // 阶段 2：常规范围内样本不足时向前加深，总量不超过扩展上限。Phase 2: extend scan up to 3× regular cap.
        if (extendedScanUsed) {
            scanUntil(config.maxRawScanRows)
        }

        val insufficientAfterBudget = regularBudgetExhausted && qualified.size < config.minHistoryFlight
        // insufficientAfterBudget：常规+扩展扫描后仍不足 minHistoryFlight，供指标与上层决策参考。

        return HistoryFlightScan(
            qualifiedFlights = qualified.toList(),
            rawRows = pager.rawScanned,
            stageBRows = stageBRows,
            extendedScanUsed = extendedScanUsed,
            insufficientAfterBudget = insufficientAfterBudget,
        )
    }

    /** 分页扫描：仅阶段 B，原始行上限为 [EsttCalculationConfig.maxHistoryRows]，无扩展。 */
    private fun scanPaginatedHistory(
        seasonalFlight: SeasonalFlight,
        targetFlightDate: LocalDate,
        startDate: LocalDate,
        endDate: LocalDate,
        offset: Int,
        limit: Int,
    ): PaginatedScanResult {
        val items = mutableListOf<HistoricalFlight>()
        var totalFiltered = 0
        var observedMore = false
        var rawOffset = 0
        var truncatedByScanLimit = false
        val chunkSize = maxOf(limit, CALC_SCAN_CHUNK_SIZE)

        // 不启用扩展扫描；触及 maxHistoryRows 后 hasMore 可能为 true 但不再继续查。
        while (rawOffset < config.maxHistoryRows) {
            val fetchSize = minOf(chunkSize, config.maxHistoryRows - rawOffset)
            val batch = historyFlightRepository.getArrivalFlightPage(
                seasonalFlight.flightNumber,
                startDate,
                endDate,
                rawOffset,
                fetchSize,
            )
            if (batch.isEmpty()) break

            rawOffset += batch.size
            // 仅阶段 B，不调用 passesScheduleDeviation。Stage B only — no schedule-deviation filter.
            val filteredBatch = batch.filter {
                hasMatchingScheduledDate(it) &&
                    isComparableHistoryFlight(seasonalFlight, targetFlightDate, it)
            }
            for (flight in filteredBatch) {
                val currentIndex = totalFiltered++
                if (currentIndex < offset) continue
                if (items.size < limit) {
                    items += flight
                } else {
                    observedMore = true
                }
            }

            // 原始行已达上限但筛选后仍有未返回记录 → 标记截断。Raw cap hit with more filtered rows beyond this page.
            if (rawOffset >= config.maxHistoryRows &&
                batch.size == fetchSize &&
                totalFiltered > offset + items.size
            ) {
                truncatedByScanLimit = true
            }
            if (batch.size < fetchSize) break
        }

        val reportedTotal = minOf(totalFiltered, config.maxHistoryRows)
        val hasMore = observedMore || reportedTotal > offset + items.size || truncatedByScanLimit
        return PaginatedScanResult(items, reportedTotal, hasMore)
    }

    private fun emptyHistoryFlightScan() = HistoryFlightScan(
        qualifiedFlights = emptyList(),
        rawRows = 0,
        stageBRows = 0,
        extendedScanUsed = false,
        insufficientAfterBudget = false,
    )

    private data class PaginatedScanResult(val items: List<HistoricalFlight>, val totalFiltered: Int, val hasMore: Boolean)

    private data class RawHistoryBatch(val flights: List<HistoricalFlight>, val hasMoreInWindow: Boolean)

    private inner class RawHistoryPager(
        private val flightNumber: String,
        private val startDate: LocalDate,
        private val endDate: LocalDate,
    ) {
        var rawScanned: Int = 0
            private set

        private var rawOffset: Int = 0

        fun nextBatch(rawCap: Int): RawHistoryBatch? {
            val fetchSize = minOf(CALC_SCAN_CHUNK_SIZE, rawCap - rawScanned)
            if (fetchSize <= 0) return null

            val batch = historyFlightRepository.getArrivalFlightPage(
                flightNumber,
                startDate,
                endDate,
                rawOffset,
                fetchSize,
            )
            if (batch.isEmpty()) return null

            rawOffset += batch.size
            rawScanned += batch.size
            return RawHistoryBatch(batch, hasMoreInWindow = batch.size >= fetchSize)
        }
    }

    private fun recordCalculationScanMetrics(scan: HistoryFlightScan) {
        meterRegistry.summary("estt.history.scan.raw_rows").record(scan.rawRows.toDouble())
        meterRegistry.summary("estt.history.scan.stage_b_rows").record(scan.stageBRows.toDouble())
        meterRegistry.summary("estt.history.scan.qualified_rows").record(scan.qualifiedRows.toDouble())
        meterRegistry.counter("estt.history.scan.extended_used", "used", scan.extendedScanUsed.toString()).increment()
        meterRegistry.counter(
            "estt.history.scan.calls",
            "insufficient_after_budget",
            scan.insufficientAfterBudget.toString(),
        ).increment()
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

        meterRegistry.summary("estt.history.pagination.items", "hasMore", hasMoreTag)
            .record(response.items.size.toDouble())
        meterRegistry.summary("estt.history.pagination.filtered", "capped", cappedTag)
            .record(response.totalFiltered.toDouble())
        meterRegistry.summary("estt.history.pagination.offset").record(offset.toDouble())
        meterRegistry.summary("estt.history.pagination.limit").record(limit.toDouble())
    }

    private data class HistoryWindow(val startDate: LocalDate, val endDate: LocalDate)

    /** 历史窗口：[seasonStart, flightDate - 1]，不含目标执行日。Window: season start through day before target. */
    private fun historyWindow(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryWindow = HistoryWindow(
        startDate = seasonalFlight.seasonStart,
        endDate = flightDate.minusDays(1),
    )

    /**
     * 阶段 B：基本有效复查 + 与目标可比（algorithm.md §7.1–§7.2）。
     * Stage B: basic validity re-check + comparability with the target flight.
     *
     * 到港、时刻齐全与窗口已在 SQL 预筛；此处复查时间顺序，并校验同运营日及飞行时长容差。
     * 计划日一致性由 [hasMatchingScheduledDate] 过滤，以避免数据库日期截断函数差异。
     * Arrival/time/window checks are in SQL; this re-validates order and applies weekday + fly-time rules.
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

        if (historyFlight.previousDepartureTime >= historyFlight.actualTime) return exclude("invalid time order")

        val actualFlyTime = Duration.between(historyFlight.previousDepartureTime, historyFlight.actualTime).toMinutes()
        // 有计划时长：偏差严格小于阈值（不含等于）；无计划：闭区间弱约束 [min, max]。
        // With seasonal time: strict < deviation; without: inclusive [minFlyingTime, maxFlyingTime] weak bounds.
        val withinFlyingTimeTolerance = seasonalFlight.flyingTime?.let { seasonalTime ->
            abs(actualFlyTime - seasonalTime) < config.maxFlyingTimeDeviation
        } ?: (actualFlyTime in config.minFlyingTime..config.maxFlyingTime)

        if (!withinFlyingTimeTolerance) return exclude("flying time outside tolerance")
        return true
    }

    private fun hasMatchingScheduledDate(historyFlight: HistoricalFlight): Boolean {
        val matches = historyFlight.scheduledTime.toLocalDate() == historyFlight.flightDate
        if (!matches) {
            log.debug("Excluded history flight on {}: scheduled date mismatch", historyFlight.flightDate)
        }
        return matches
    }
}
