package com.gzzn.airport.service.history

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import java.time.LocalDate

/**
 * 按原始数据分块扫描并过滤历史样本，供分页历史接口复用。
 * Scans and filters historical samples in raw-data chunks for reuse by the paginated history path.
 */
internal class HistoryPaginationScanner(
    private val historyFlightRepository: HistoryFlightRepository,
    private val config: EsttCalculationConfig,
    private val isEligibleHistoryFlight: (SeasonalFlight, HistoricalFlight) -> Boolean,
) {
    data class Result(val items: List<HistoricalFlight>, val totalFiltered: Int, val hasMore: Boolean)

    fun scan(seasonalFlight: SeasonalFlight, startDate: LocalDate, endDate: LocalDate, offset: Int, limit: Int): Result {
        val items = mutableListOf<HistoricalFlight>()
        var totalFiltered = 0
        var observedMore = false
        var rawOffset = 0
        var truncatedByScanLimit = false
        val chunkSize = maxOf(limit, 100)

        while (rawOffset < config.maxHistoryRows) {
            val fetchSize = minOf(chunkSize, config.maxHistoryRows - rawOffset)
            val batch = historyFlightRepository.getArrivalFlightPage(
                seasonalFlight.flightNumber,
                startDate,
                endDate,
                rawOffset,
                fetchSize,
            )
            if (batch.isEmpty()) {
                break
            }

            rawOffset += batch.size

            val filteredBatch = batch.filter { isEligibleHistoryFlight(seasonalFlight, it) }
            for (flight in filteredBatch) {
                val currentIndex = totalFiltered
                totalFiltered++

                if (currentIndex < offset) {
                    continue
                }

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

            if (batch.size < fetchSize) {
                break
            }
        }

        val reportedTotal = minOf(totalFiltered, config.maxHistoryRows)
        val hasMore = observedMore || reportedTotal > offset + items.size || truncatedByScanLimit
        return Result(
            items = items,
            totalFiltered = reportedTotal,
            hasMore = hasMore,
        )
    }
}
