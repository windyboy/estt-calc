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

@Singleton
class HistoryFlightProvider(
    private val historyFlightRepository: HistoryFlightRepository,
    private val meterRegistry: MeterRegistry,
    private val config: EsttCalculationConfig,
) {
    companion object {
        private val log = LoggerFactory.getLogger(HistoryFlightProvider::class.java)
        private const val CALC_SCAN_CHUNK_SIZE = 100
    }

    fun getHistoryFlights(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryFlightScan {
        val window = historyWindow(seasonalFlight, flightDate)
        log.debug(
            "Querying historical flights for {} from {} to {} (target {} excluded)",
            seasonalFlight.flightNumber,
            window.startDate,
            window.endDate,
            flightDate,
        )
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

    private fun scanCalculationHistory(
        seasonalFlight: SeasonalFlight,
        targetFlightDate: LocalDate,
        startDate: LocalDate,
        endDate: LocalDate,
    ): HistoryFlightScan {
        val qualified = mutableListOf<HistoricalFlight>()
        var rawScanned = 0
        var rawOffset = 0
        var stageBRows = 0
        val rawCap = config.maxRawScanRows

        fun appendBatch(batch: List<HistoricalFlight>) {
            for (flight in batch) {
                if (!isEligibleHistoryFlight(seasonalFlight, targetFlightDate, flight)) {
                    continue
                }
                stageBRows++
                if (passesScheduleDeviation(flight, config.maxScheduleDeviation)) {
                    qualified += flight
                }
            }
        }

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
            appendBatch(batch)
            return batch.size >= fetchSize
        }

        while (rawScanned < config.maxHistoryRows && qualified.size < config.minHistoryFlight) {
            val fetchSize = minOf(CALC_SCAN_CHUNK_SIZE, config.maxHistoryRows - rawScanned)
            if (!fetchAndAppend(fetchSize)) {
                break
            }
        }

        val budgetExhausted = rawScanned >= config.maxHistoryRows
        var extendedScanUsed = false

        if (budgetExhausted && qualified.size < config.minHistoryFlight) {
            extendedScanUsed = true
            while (qualified.size < config.minHistoryFlight && rawScanned < rawCap) {
                val fetchSize = minOf(CALC_SCAN_CHUNK_SIZE, rawCap - rawScanned)
                if (!fetchAndAppend(fetchSize)) {
                    break
                }
            }
        }

        val insufficientAfterBudget = budgetExhausted && qualified.size < config.minHistoryFlight

        return HistoryFlightScan(
            qualifiedFlights = qualified.toList(),
            rawRows = rawScanned,
            stageBRows = stageBRows,
            qualifiedRows = qualified.size,
            extendedScanUsed = extendedScanUsed,
            insufficientAfterBudget = insufficientAfterBudget,
        )
    }

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
            val filteredBatch = batch.filter { isEligibleHistoryFlight(seasonalFlight, targetFlightDate, it) }
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

    private fun emptyHistoryFlightScan() = HistoryFlightScan(
        qualifiedFlights = emptyList(),
        rawRows = 0,
        stageBRows = 0,
        qualifiedRows = 0,
        extendedScanUsed = false,
        insufficientAfterBudget = false,
    )

    private data class PaginatedScanResult(val items: List<HistoricalFlight>, val totalFiltered: Int, val hasMore: Boolean)

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

    private fun historyWindow(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryWindow = HistoryWindow(
        startDate = seasonalFlight.seasonStart,
        endDate = flightDate.minusDays(1),
    )

    private fun isEligibleHistoryFlight(
        seasonalFlight: SeasonalFlight,
        targetFlightDate: LocalDate,
        historyFlight: HistoricalFlight,
    ): Boolean {
        val historyWeekday = historyFlight.flightDate.dayOfWeek.value
        val targetWeekday = targetFlightDate.dayOfWeek.value
        val actualFlyTime = Duration.between(historyFlight.previousDepartureTime, historyFlight.actualTime).toMinutes()

        val sameWeekdayAsTarget = historyWeekday == targetWeekday
        val operationDayMatches = OperationDays.matches(seasonalFlight.operationDays, historyWeekday)
        val flightTimeOrderCorrect = historyFlight.previousDepartureTime < historyFlight.actualTime
        val scheduledDateMatches = historyFlight.scheduledTime.toLocalDate() == historyFlight.flightDate
        val withinFlyingTimeTolerance = seasonalFlight.flyingTime?.let { seasonalTime ->
            abs(actualFlyTime - seasonalTime) < config.maxFlyingTimeDeviation
        } ?: (actualFlyTime in config.minFlyingTime..config.maxFlyingTime)

        val result = sameWeekdayAsTarget &&
            operationDayMatches &&
            flightTimeOrderCorrect &&
            scheduledDateMatches &&
            withinFlyingTimeTolerance
        if (!result && log.isDebugEnabled) {
            log.debug(
                "Excluded history flight on {}: sameWeekday={}, dayMatch={}, timeOrder={}, dateMatch={}, withinTolerance={}",
                historyFlight.flightDate,
                sameWeekdayAsTarget,
                operationDayMatches,
                flightTimeOrderCorrect,
                scheduledDateMatches,
                withinFlyingTimeTolerance,
            )
        }
        return result
    }
}
