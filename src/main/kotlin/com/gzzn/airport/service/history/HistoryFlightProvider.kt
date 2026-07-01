package com.gzzn.airport.service.history

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.OperationDays
import com.gzzn.airport.model.PaginatedHistoryResponse
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.service.calculator.FlyingTimeCalculator
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs

@Singleton
class HistoryFlightProvider(
    private val historyFlightRepository: HistoryFlightRepository,
    private val flyingTimeCalculator: FlyingTimeCalculator,
    private val meterRegistry: MeterRegistry,
    private val config: EsttCalculationConfig,
) {
    companion object {
        private val log = LoggerFactory.getLogger(HistoryFlightProvider::class.java)
        private const val CALC_SCAN_CHUNK_SIZE = 100
    }

    // 计算路径分页扫描；预算内样本不足时继续扫完整窗口。
    // Calculation path pages raw rows; extends beyond budget when qualified samples are still insufficient.
    fun getHistoryFlights(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryFlightScan {
        val window = historyWindow(seasonalFlight, flightDate)
        log.debug(
            "Querying historical flights for {} from {} to {} (target {} excluded)",
            seasonalFlight.flightNumber,
            window.startDate,
            window.endDate,
            flightDate,
        )
        val scan = scanCalculationHistory(seasonalFlight, window.startDate, window.endDate)
        log.debug(
            "Retrieved {} raw rows, filtered to {} eligible flights (extendedBeyondBudget={})",
            scan.rawRows,
            scan.filteredRows,
            scan.extendedBeyondBudget,
        )
        recordCalculationScanMetrics(scan)
        return scan
    }

    fun getPaginatedHistory(seasonalFlight: SeasonalFlight, flightDate: LocalDate, offset: Int, limit: Int): PaginatedHistoryResponse {
        val window = historyWindow(seasonalFlight, flightDate)
        val scanResult = scanPaginatedHistory(seasonalFlight, window.startDate, window.endDate, offset, limit)
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

    private fun scanCalculationHistory(seasonalFlight: SeasonalFlight, startDate: LocalDate, endDate: LocalDate): HistoryFlightScan {
        val eligible = mutableListOf<HistoricalFlight>()
        var rawScanned = 0
        var rawOffset = 0

        fun qualifiedCount(): Int = flyingTimeCalculator.filterByScheduleDeviation(eligible).size

        fun fetchAndAppend(fetchSize: Int): Boolean {
            val batch = historyFlightRepository.getArrivalFlightPage(
                seasonalFlight.flightNumber,
                startDate,
                endDate,
                rawOffset,
                fetchSize,
            )
            if (batch.isEmpty()) {
                return false
            }
            rawOffset += batch.size
            rawScanned += batch.size
            eligible += batch.filter { isEligibleHistoryFlight(seasonalFlight, it) }
            return batch.size >= fetchSize
        }

        while (rawScanned < config.maxHistoryRows) {
            val fetchSize = minOf(CALC_SCAN_CHUNK_SIZE, config.maxHistoryRows - rawScanned)
            if (!fetchAndAppend(fetchSize)) {
                break
            }
        }

        val budgetExhausted = rawScanned >= config.maxHistoryRows
        var extendedBeyondBudget = false

        if (budgetExhausted && qualifiedCount() < config.minHistoryFlight) {
            extendedBeyondBudget = true
            while (qualifiedCount() < config.minHistoryFlight) {
                if (!fetchAndAppend(CALC_SCAN_CHUNK_SIZE)) {
                    break
                }
            }
        }

        val hitScanLimit = budgetExhausted && qualifiedCount() < config.minHistoryFlight

        return HistoryFlightScan(
            flights = eligible.toList(),
            rawRows = rawScanned,
            filteredRows = eligible.size,
            hitScanLimit = hitScanLimit,
            extendedBeyondBudget = extendedBeyondBudget,
        )
    }

    // 按原始数据分块扫描并过滤，供分页历史接口使用。
    // Scans raw DB pages, filters in memory, then applies offset/limit on filtered rows.
    private fun scanPaginatedHistory(
        seasonalFlight: SeasonalFlight,
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
            val filteredBatch = batch.filter { isEligibleHistoryFlight(seasonalFlight, it) }
            for (flight in filteredBatch) {
                val currentIndex = totalFiltered++
                if (currentIndex < offset) continue
                if (items.size < limit) {
                    items += flight
                } else {
                    observedMore = true
                }
            }

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

    private data class PaginatedScanResult(val items: List<HistoricalFlight>, val totalFiltered: Int, val hasMore: Boolean)

    private fun recordCalculationScanMetrics(scan: HistoryFlightScan) {
        meterRegistry.summary("estt.history.calc.scan.raw_rows").record(scan.rawRows.toDouble())
        meterRegistry.summary("estt.history.calc.scan.filtered_rows").record(scan.filteredRows.toDouble())
        meterRegistry.counter(
            "estt.history.calc.scan.calls",
            "hit_scan_limit",
            scan.hitScanLimit.toString(),
            "extended_beyond_budget",
            scan.extendedBeyondBudget.toString(),
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
        meterRegistry.summary("estt.history.pagination.limit").record(limit.toDouble())
        meterRegistry.summary("estt.history.pagination.offset").record(offset.toDouble())
    }

    private data class HistoryWindow(val startDate: LocalDate, val endDate: LocalDate)

    private fun historyWindow(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryWindow = HistoryWindow(
        startDate = seasonalFlight.seasonStart.minusDays(config.historyStartOffsetDays),
        endDate = flightDate.minusDays(1),
    )

    // 飞行时长容差为开区间（< maxFlyingTimeDeviation）；航季时长未配置时跳过。
    // Flying-time tolerance is strict (< maxFlyingTimeDeviation); skip when seasonal time is null.
    private fun isEligibleHistoryFlight(seasonalFlight: SeasonalFlight, historyFlight: HistoricalFlight): Boolean {
        val operationDay = historyFlight.flightDate.dayOfWeek.value
        val actualFlyTime = Duration.between(historyFlight.previousDepartureTime, historyFlight.actualTime).toMinutes()

        val operationDayMatches = OperationDays.matches(seasonalFlight.operationDays, operationDay)
        val flightTimeOrderCorrect = historyFlight.previousDepartureTime < historyFlight.actualTime
        val scheduledDateMatches = historyFlight.scheduledTime.toLocalDate() == historyFlight.flightDate
        val withinFlyingTimeTolerance = seasonalFlight.flyingTime?.let { abs(actualFlyTime - it) < config.maxFlyingTimeDeviation } ?: true

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
}
