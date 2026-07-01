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
 * Encapsulates historical flight retrieval and filtering so the core service can remain focused on orchestration.
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
     * The repository query uses an inclusive `BETWEEN` on `flight_date`; the caller passes
     * the day before the target operation date so the flight being estimated never appears
     * in its own sample.
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

    fun getPaginatedHistory(seasonalFlight: SeasonalFlight, flightDate: LocalDate, offset: Int, limit: Int): PaginatedHistoryResponse {
        val window = historyWindow(seasonalFlight, flightDate)
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
                window.startDate,
                window.endDate,
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

    private fun historyWindow(seasonalFlight: SeasonalFlight, flightDate: LocalDate): HistoryWindow =
        HistoryWindow(
            startDate = calculateHistoryStartDate(seasonalFlight.seasonStart),
            endDate = flightDate.minusDays(1),
        )

    private fun calculateHistoryStartDate(seasonStart: LocalDate): LocalDate = seasonStart.minusDays(config.historyStartOffsetDays)

    /**
     * Business gatekeeper ensuring history aligns with the seasonal schedule.
     *
     * Flying-time tolerance is exclusive at [EsttCalculationConfig.maxFlyingTimeDeviation].
     * When seasonal flying time is not configured, the tolerance check is skipped.
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
