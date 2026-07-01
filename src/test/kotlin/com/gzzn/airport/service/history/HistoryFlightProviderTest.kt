package com.gzzn.airport.service.history

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.PaginatedHistoryResponse
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.service.calculator.FlyingTimeCalculator
import com.gzzn.airport.service.mockArrivalFlightPages
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
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime

class HistoryFlightProviderTest :
    DescribeSpec({

        lateinit var repository: HistoryFlightRepository
        lateinit var meterRegistry: MeterRegistry
        lateinit var config: EsttCalculationConfig
        lateinit var calculator: FlyingTimeCalculator
        lateinit var provider: HistoryFlightProvider
        val seasonalFlight = SeasonalFlight(
            flightNumber = "MU2001",
            operationDays = "135",
            flyingTime = 100,
            seasonStart = LocalDate.of(2024, 3, 31),
            seasonEnd = LocalDate.of(2024, 10, 26),
        )

        beforeEach {
            repository = mockk()
            meterRegistry = SimpleMeterRegistry()
            config = EsttCalculationConfig(
                maxScheduleDeviation = 120,
                maxFlyingTimeDeviation = 120,
                minHistoryFlight = 20,
                dateFormat = "yyMMdd",
                historyStartOffsetDays = 60,
                maxHistoryRows = 50,
            )
            calculator = FlyingTimeCalculator(meterRegistry, config)
            provider = HistoryFlightProvider(repository, calculator, meterRegistry, config)
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
                    date = baseDate.plusDays(1),
                    scheduledOffsetMinutes = 0,
                    actualDurationMinutes = 100,
                )
                val excessiveDelay = historyFlight(
                    date = baseDate.minusDays(2),
                    scheduledOffsetMinutes = 0,
                    actualDurationMinutes = 250,
                )
                val reversedTime = validFlight.copy(
                    previousDepartureTime = validFlight.actualTime.plusMinutes(5),
                )

                repository.mockArrivalFlightPages(listOf(validFlight, mismatchedDay, excessiveDelay, reversedTime))

                val result = provider.getHistoryFlights(seasonalFlight, baseDate)

                result.flights shouldHaveSize 1
                result.flights.first() shouldBe validFlight
            }

            it("excludes the target operation date from the repository query window") {
                val targetDate = LocalDate.of(2024, 6, 5)
                repository.mockArrivalFlightPages(emptyList())

                provider.getHistoryFlights(seasonalFlight, targetDate)

                verify {
                    repository.getArrivalFlightPage(
                        seasonalFlight.flightNumber,
                        any(),
                        targetDate.minusDays(1),
                        any(),
                        any(),
                        any(),
                    )
                }
            }

            it("rejects flying time deviation exactly at configured threshold") {
                val baseDate = LocalDate.of(2024, 6, 5)
                val seasonalFlyingTime = seasonalFlight.flyingTime ?: error("test seasonal flight should have flying time")
                val insideExclusiveThreshold = historyFlight(
                    date = baseDate,
                    scheduledOffsetMinutes = 0,
                    actualDurationMinutes = seasonalFlyingTime + config.maxFlyingTimeDeviation - 1,
                )
                val exactThreshold = historyFlight(
                    date = baseDate.minusDays(2),
                    scheduledOffsetMinutes = 0,
                    actualDurationMinutes = seasonalFlyingTime + config.maxFlyingTimeDeviation,
                )

                repository.mockArrivalFlightPages(listOf(insideExclusiveThreshold, exactThreshold))

                val result = provider.getHistoryFlights(seasonalFlight, baseDate)

                result.flights shouldHaveSize 1
                result.flights.first() shouldBe insideExclusiveThreshold
            }

            it("skips flying time tolerance filtering when seasonal flying time is not configured") {
                val baseDate = LocalDate.of(2024, 6, 5)
                val seasonalWithoutFlyingTime = seasonalFlight.copy(flyingTime = null)
                val longDurationFlight = historyFlight(
                    date = baseDate,
                    scheduledOffsetMinutes = 0,
                    actualDurationMinutes = 500,
                )

                repository.mockArrivalFlightPages(listOf(longDurationFlight))

                val result = provider.getHistoryFlights(seasonalWithoutFlyingTime, baseDate)

                result.flights shouldHaveSize 1
                result.flights.first() shouldBe longDurationFlight
            }

            it("records calculation scan metrics") {
                val baseDate = LocalDate.of(2024, 6, 5)
                val allDaysSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val flights = List(config.maxHistoryRows) { index ->
                    historyFlight(
                        date = baseDate.minusDays(index.toLong()),
                        scheduledOffsetMinutes = 0,
                        actualDurationMinutes = 100,
                    )
                }

                repository.mockArrivalFlightPages(flights)

                val scan = provider.getHistoryFlights(allDaysSeasonal, baseDate)

                scan.rawRows shouldBe config.maxHistoryRows
                scan.hitScanLimit.shouldBeFalse()
                meterRegistry.summary("estt.history.calc.scan.raw_rows").count() shouldBe 1
                meterRegistry.summary("estt.history.calc.scan.filtered_rows").count() shouldBe 1
                meterRegistry.counter(
                    "estt.history.calc.scan.calls",
                    "hit_scan_limit",
                    "false",
                    "extended_beyond_budget",
                    "false",
                ).count() shouldBe 1.0
            }

            it("extends scan beyond raw budget when qualified samples appear after the cap") {
                val baseDate = LocalDate.of(2024, 6, 5)
                val allDaysSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val rejectedInBudget = List(config.maxHistoryRows) { index ->
                    historyFlight(
                        date = baseDate.minusDays(index.toLong() + 1),
                        scheduledOffsetMinutes = 0,
                        actualDurationMinutes = 250,
                    )
                }
                val qualifiedAfterBudget = List(config.minHistoryFlight) { index ->
                    historyFlight(
                        date = baseDate.minusDays(index.toLong() + 100),
                        scheduledOffsetMinutes = 0,
                        actualDurationMinutes = 100,
                    )
                }

                repository.mockArrivalFlightPages(rejectedInBudget, qualifiedAfterBudget)

                val scan = provider.getHistoryFlights(allDaysSeasonal, baseDate)

                scan.extendedBeyondBudget.shouldBeTrue()
                scan.flights shouldHaveSize config.minHistoryFlight
                scan.hitScanLimit.shouldBeFalse()
                calculator.filterByScheduleDeviation(scan.flights) shouldHaveSize config.minHistoryFlight
            }

            it("stops extending when scan budget is exhausted and window has no more rows") {
                val baseDate = LocalDate.of(2024, 6, 5)
                val allDaysSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val rejectedOnly = List(config.maxHistoryRows) { index ->
                    historyFlight(
                        date = baseDate.minusDays(index.toLong()),
                        scheduledOffsetMinutes = 0,
                        actualDurationMinutes = 250,
                    )
                }

                repository.mockArrivalFlightPages(rejectedOnly)

                val scan = provider.getHistoryFlights(allDaysSeasonal, baseDate)

                scan.extendedBeyondBudget.shouldBeTrue()
                scan.flights.shouldBeEmpty()
                scan.hitScanLimit.shouldBeTrue()
            }
        }

        describe("getPaginatedHistory") {
            it("returns filtered items and records pagination metrics") {
                val baseDate = LocalDate.of(2024, 6, 5)
                val paginatedSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val combinedBatch = List(10) { index ->
                    historyFlight(
                        date = baseDate.minusDays(index.toLong()),
                        scheduledOffsetMinutes = 0,
                        actualDurationMinutes = 100,
                    )
                }

                every {
                    repository.getArrivalFlightPage(paginatedSeasonal.flightNumber, any(), any(), any(), any(), any())
                } returns combinedBatch

                val response: PaginatedHistoryResponse =
                    provider.getPaginatedHistory(paginatedSeasonal, baseDate, offset = 3, limit = 4)

                response.items shouldHaveSize 4
                response.totalFiltered shouldBe 10
                response.hasMore.shouldBeTrue()

                meterRegistry.counter("estt.history.pagination.calls", "hasMore", "true", "capped", "false").count() shouldBe 1.0
                meterRegistry.summary("estt.history.pagination.items", "hasMore", "true").count() shouldBe 1
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

private fun historyFlight(date: LocalDate, scheduledOffsetMinutes: Long, actualDurationMinutes: Long): HistoricalFlight {
    val scheduledTime = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0)
    val previousDeparture = scheduledTime.minusMinutes(actualDurationMinutes + scheduledOffsetMinutes)
    return HistoricalFlight(
        flightDate = date,
        previousDepartureTime = previousDeparture,
        actualTime = previousDeparture.plusMinutes(actualDurationMinutes),
        scheduledTime = scheduledTime,
    )
}
