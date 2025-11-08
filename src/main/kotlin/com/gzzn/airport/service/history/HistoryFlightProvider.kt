package com.gzzn.airport.service.history

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
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
 * Encapsulates historical flight retrieval and filtering so the core service can remain focused on orchestration.
 *
 * Responsibilities:
 * - Build deterministic repository queries using the configured history window.
 * - Apply business validation (operation day, delay bounds, chronological order) consistently across callers.
 * - Provide both all-in-memory and paginated variants while recording Micrometer metrics for observability.
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
     * Load and filter historical flights for the provided seasonal schedule.
     *
     * Steps:
     * 1. Derive the query window by subtracting `historyStartOffsetDays` from the seasonal start date.
     * 2. Fetch up to `maxHistoryRows` rows from Oracle using the repository.
     * 3. Filter each record with [isHistoryFlight] to ensure business constraints are honoured.
     *
     * @param seasonalFlight seasonal definition that supplies flight number, operation days, and expected flying time.
     * @param flightDate target date (upper bound) for the query window.
     * @return filtered list ordered by most recent flight first.
     */
    fun getHistoryFlights(seasonalFlight: SeasonalFlight, flightDate: LocalDate): List<HistoricalFlight> {
        val seasonStart = calculateHistoryStartDate(seasonalFlight.seasonStart)
        log.debug(
            "Querying historical flights for {} from {} to {}",
            seasonalFlight.flightNumber,
            seasonStart,
            flightDate,
        )
        val historyFlights = historyFlightRepository.getArrivalFlight(
            seasonalFlight.flightNumber,
            seasonStart,
            flightDate,
            config.maxHistoryRows,
        )
        val filtered = historyFlights.filter { isHistoryFlight(seasonalFlight, it) }
        log.debug(
            "Retrieved {} historical flights, filtered to {} valid flights",
            historyFlights.size,
            filtered.size,
        )
        return filtered
    }

    /**
     * Fetch paginated historical flights that match the criteria defined by the seasonal flight.
     *
     * Implementation details:
     * - Pages through the repository using `OFFSET/FETCH` until either the requested window has been assembled
     *   or `maxHistoryRows` has been scanned.
     * - Applies the same filtering logic used by [getHistoryFlights] to every batch.
     * - Records Micrometer counters/summaries to expose usage characteristics.
     *
     * @param seasonalFlight seasonal schedule context.
     * @param flightDate date upper bound used when reading repository pages.
     * @param offset number of filtered results to skip.
     * @param limit maximum number of filtered results to return.
     * @return [PaginatedHistoryResponse] containing filtered results and metadata required by the API.
     */
    fun getPaginatedHistory(seasonalFlight: SeasonalFlight, flightDate: LocalDate, offset: Int, limit: Int): PaginatedHistoryResponse {
        val seasonStart = calculateHistoryStartDate(seasonalFlight.seasonStart)
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
                seasonStart,
                flightDate,
                rawOffset,
                fetchSize,
            )
            if (batch.isEmpty()) {
                break
            }

            rawOffset += batch.size

            val filteredBatch = batch.filter { isHistoryFlight(seasonalFlight, it) }
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

            if (rawOffset >= config.maxHistoryRows && batch.size == fetchSize &&
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
        val response = PaginatedHistoryResponse(
            items = items,
            totalFiltered = reportedTotal,
            offset = offset,
            limit = limit,
            hasMore = hasMore,
        )
        recordPaginatedHistoryMetrics(response, offset, limit)
        return response
    }

    /**
     * Record Micrometer metrics for the given paginated response and request parameters.
     */
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

        meterRegistry.summary(
            "estt.history.pagination.limit",
        ).record(limit.toDouble())

        meterRegistry.summary(
            "estt.history.pagination.offset",
        ).record(offset.toDouble())
    }

    private fun calculateHistoryStartDate(seasonStart: LocalDate): LocalDate {
        return seasonStart.minusDays(config.historyStartOffsetDays)
    }

    /**
     * Business gatekeeper ensuring that history aligns with the seasonal schedule.
     */
    private fun isHistoryFlight(seasonalFlight: SeasonalFlight, historyFlight: HistoricalFlight): Boolean {
        val operationDay = historyFlight.flightDate.dayOfWeek.value
        val actualFlyTime = calculateDurationMinutes(historyFlight.previousDepartureTime, historyFlight.actualTime)

        val operationDayMatches = isOperationDayMatch(seasonalFlight.operationDays, operationDay)
        val flightTimeOrderCorrect = historyFlight.previousDepartureTime < historyFlight.actualTime
        val scheduledDateMatches = historyFlight.scheduledTime.toLocalDate() == historyFlight.flightDate
        val withinMaxDelay = abs(actualFlyTime - seasonalFlight.flyingTime) < config.maxHistoryDelay

        val result = operationDayMatches && flightTimeOrderCorrect && scheduledDateMatches && withinMaxDelay
        if (!result && log.isDebugEnabled) {
            log.debug(
                "Excluded history flight on {}: dayMatch={}, timeOrder={}, dateMatch={}, withinDelay={}",
                historyFlight.flightDate,
                operationDayMatches,
                flightTimeOrderCorrect,
                scheduledDateMatches,
                withinMaxDelay,
            )
        }
        return result
    }

    /**
     * Check if the seasonal operation days string contains the supplied day.
     */
    private fun isOperationDayMatch(operationDays: String, dayOfWeek: Int): Boolean {
        return operationDays.any { it.toString().toIntOrNull() == dayOfWeek }
    }

    /**
     * Utility to compute flying time from departure to arrival.
     */
    private fun calculateDurationMinutes(startTime: LocalDateTime, endTime: LocalDateTime): Long {
        return Duration.between(startTime, endTime).toMinutes()
    }
}
