package com.gzzn.airport.service.history

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.PaginatedHistoryResponse
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime

class HistoryFlightProviderTest : DescribeSpec({

    lateinit var repository: HistoryFlightRepository
    lateinit var meterRegistry: MeterRegistry
    lateinit var config: EsttCalculationConfig
    lateinit var provider: HistoryFlightProvider
    val seasonalFlight = SeasonalFlight(
        flightNumber = "MU2001",
        operationDays = "135",
        flyingTime = 100,
        seasonStart = LocalDate.of(2024, 3, 31),
    )

    beforeEach {
        repository = mockk()
        meterRegistry = SimpleMeterRegistry()
        config = EsttCalculationConfig(
            maxHistoryDelay = 120,
            minHistoryFlight = 20,
            dateFormat = "yyMMdd",
            historyStartOffsetDays = 60,
            maxHistoryRows = 50,
        )
        provider = HistoryFlightProvider(repository, meterRegistry, config)
    }

    describe("getHistoryFlights") {
        it("filters out flights that violate business rules") {
            val baseDate = LocalDate.of(2024, 6, 5) // Wednesday (3)
            val validFlight = historyFlight(
                date = baseDate,
                scheduledOffsetMinutes = 0,
                actualDurationMinutes = 100,
            )
            val mismatchedDay = historyFlight(
                date = baseDate.plusDays(1), // Thursday (4)
                scheduledOffsetMinutes = 0,
                actualDurationMinutes = 100,
            )
            val excessiveDelay = historyFlight(
                date = baseDate.minusDays(2),
                scheduledOffsetMinutes = 0,
                actualDurationMinutes = 250, // > max delay
            )
            val reversedTime = validFlight.copy(
                previousDepartureTime = validFlight.actualTime.plusMinutes(5),
            )

            every {
                repository.getArrivalFlight(seasonalFlight.flightNumber, any(), any(), config.maxHistoryRows, any())
            } returns listOf(validFlight, mismatchedDay, excessiveDelay, reversedTime)

            val result = provider.getHistoryFlights(seasonalFlight, baseDate)

            result shouldHaveSize 1
            result.first() shouldBe validFlight
        }
    }

    describe("getPaginatedHistory") {
        it("returns filtered items and records pagination metrics") {
            val baseDate = LocalDate.of(2024, 6, 5)
            val paginatedSeasonal = seasonalFlight.copy(operationDays = "1234567")
            val firstBatch = List(5) { index ->
                historyFlight(
                    date = baseDate.minusDays(index.toLong()),
                    scheduledOffsetMinutes = 0,
                    actualDurationMinutes = 100,
                )
            }
            val secondBatch = List(5) { index ->
                historyFlight(
                    date = baseDate.minusDays(10 + index.toLong()),
                    scheduledOffsetMinutes = 0,
                    actualDurationMinutes = 100,
                )
            }

            every {
                repository.getArrivalFlightPage(paginatedSeasonal.flightNumber, any(), any(), 0, any(), any())
            } returns firstBatch

            every {
                repository.getArrivalFlightPage(paginatedSeasonal.flightNumber, any(), any(), 5, any(), any())
            } returns secondBatch

            val response: PaginatedHistoryResponse =
                provider.getPaginatedHistory(paginatedSeasonal, baseDate, offset = 3, limit = 4)

            response.items shouldHaveSize 2
            response.totalFiltered shouldBe 5
            response.hasMore.shouldBeFalse()

            meterRegistry.counter("estt.history.pagination.calls", "hasMore", "false", "capped", "false").count() shouldBe 1.0
            meterRegistry.summary("estt.history.pagination.items", "hasMore", "false").count() shouldBe 1
        }

        it("returns empty response when repository yields no data") {
            every {
                repository.getArrivalFlightPage(seasonalFlight.flightNumber, any(), any(), any(), any(), any())
            } returns emptyList()

            val response = provider.getPaginatedHistory(seasonalFlight, LocalDate.of(2024, 6, 5), offset = 0, limit = 5)

            response.items.shouldBeEmpty()
            response.hasMore.shouldBeFalse()
            response.totalFiltered shouldBe 0
        }
    }
})

private fun historyFlight(
    date: LocalDate,
    scheduledOffsetMinutes: Long,
    actualDurationMinutes: Long,
): HistoricalFlight {
    val scheduledTime = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0)
    val previousDeparture = scheduledTime.minusMinutes(actualDurationMinutes + scheduledOffsetMinutes)
    return HistoricalFlight(
        flightDate = date,
        previousDepartureTime = previousDeparture,
        actualTime = previousDeparture.plusMinutes(actualDurationMinutes),
        scheduledTime = scheduledTime,
    )
}

