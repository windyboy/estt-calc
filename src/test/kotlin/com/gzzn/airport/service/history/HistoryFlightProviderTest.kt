package com.gzzn.airport.service.history

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.PaginatedHistoryResponse
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
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
                maxHistoryRows = 50,
                minFlyingTime = 30,
                maxFlyingTime = 600,
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

                result.stageBRows shouldBe 1
                result.qualifiedFlights shouldHaveSize 1
                result.qualifiedFlights.first() shouldBe validFlight
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

                result.stageBRows shouldBe 1
                result.qualifiedFlights shouldHaveSize 1
                result.qualifiedFlights.first() shouldBe insideExclusiveThreshold
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

                result.stageBRows shouldBe 1
                result.qualifiedFlights shouldHaveSize 1
                result.qualifiedFlights.first() shouldBe longDurationFlight
            }

            it("records calculation scan metrics") {
                val baseDate = LocalDate.of(2024, 6, 5)
                val allDaysSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val flights = List(config.maxHistoryRows) { index ->
                    historyFlight(
                        date = baseDate.minusWeeks(index.toLong()),
                        scheduledOffsetMinutes = 0,
                        actualDurationMinutes = 100,
                    )
                }

                repository.mockArrivalFlightPages(flights)

                val scan = provider.getHistoryFlights(allDaysSeasonal, baseDate)

                scan.rawRows shouldBe config.maxHistoryRows
                scan.insufficientAfterBudget.shouldBeFalse()
                meterRegistry.summary("estt.history.scan.raw_rows").count() shouldBe 1
                meterRegistry.summary("estt.history.scan.stage_b_rows").count() shouldBe 1
                meterRegistry.summary("estt.history.scan.qualified_rows").count() shouldBe 1
                meterRegistry.counter("estt.history.scan.extended_used", "used", "false").count() shouldBe 1.0
                meterRegistry.counter(
                    "estt.history.scan.calls",
                    "insufficient_after_budget",
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
                        date = baseDate.minusWeeks(index.toLong() + 10),
                        scheduledOffsetMinutes = 0,
                        actualDurationMinutes = 100,
                    )
                }

                repository.mockArrivalFlightPages(rejectedInBudget, qualifiedAfterBudget)

                val scan = provider.getHistoryFlights(allDaysSeasonal, baseDate)

                scan.extendedScanUsed.shouldBeTrue()
                scan.qualifiedFlights shouldHaveSize config.minHistoryFlight
                scan.insufficientAfterBudget.shouldBeFalse()
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

                scan.extendedScanUsed.shouldBeTrue()
                scan.stageBRows shouldBe 0
                scan.qualifiedFlights.shouldBeEmpty()
                scan.insufficientAfterBudget.shouldBeTrue()
            }
        }

        describe("getPaginatedHistory") {
            it("returns filtered items and records pagination metrics") {
                val baseDate = LocalDate.of(2024, 6, 5)
                val paginatedSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val combinedBatch = List(10) { index ->
                    historyFlight(
                        date = baseDate.minusWeeks(index.toLong()),
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

        describe("algorithm corrections") {
            it("keeps only history on the same weekday as the target flight") {
                val targetDate = LocalDate.of(2024, 6, 5) // Wednesday
                val sameWeekday = historyFlight(targetDate.minusWeeks(1), 0, 100)
                val otherWeekday = historyFlight(targetDate.minusDays(1), 0, 100) // Tuesday

                repository.mockArrivalFlightPages(listOf(sameWeekday, otherWeekday))

                val scan = provider.getHistoryFlights(seasonalFlight, targetDate)

                scan.stageBRows shouldBe 1
                scan.qualifiedFlights shouldHaveSize 1
                scan.qualifiedFlights.first().flightDate shouldBe sameWeekday.flightDate
            }

            it("filters history whose scheduled date differs from flight date") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val matchingDate = historyFlight(targetDate.minusWeeks(1), 0, 100)
                val scheduledDateMismatch = historyFlight(targetDate.minusWeeks(2), 0, 100).let {
                    it.copy(scheduledTime = it.scheduledTime.plusDays(1))
                }

                repository.mockArrivalFlightPages(listOf(matchingDate, scheduledDateMismatch))

                val scan = provider.getHistoryFlights(seasonalFlight, targetDate)

                scan.stageBRows shouldBe 1
                scan.qualifiedFlights shouldHaveSize 1
                scan.qualifiedFlights.single() shouldBe matchingDate
            }

            it("filters scheduled-date mismatches from paginated history") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val allDaysSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val matchingDate = historyFlight(targetDate.minusWeeks(1), 0, 100)
                val scheduledDateMismatch = historyFlight(targetDate.minusWeeks(2), 0, 100).let {
                    it.copy(scheduledTime = it.scheduledTime.plusDays(1))
                }

                repository.mockArrivalFlightPages(listOf(matchingDate, scheduledDateMismatch))

                val paginated = provider.getPaginatedHistory(allDaysSeasonal, targetDate, offset = 0, limit = 10)

                paginated.items shouldHaveSize 1
                paginated.items.single() shouldBe matchingDate
                paginated.totalFiltered shouldBe 1
                paginated.hasMore.shouldBeFalse()
            }

            it("does not let history window start before seasonStart") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val earlySeason = seasonalFlight.copy(seasonStart = LocalDate.of(2024, 6, 1))
                repository.mockArrivalFlightPages(emptyList())

                provider.getHistoryFlights(earlySeason, targetDate)

                verify {
                    repository.getArrivalFlightPage(
                        earlySeason.flightNumber,
                        LocalDate.of(2024, 6, 1),
                        targetDate.minusDays(1),
                        any(),
                        any(),
                        any(),
                    )
                }
            }

            it("stops phase 1 once enough schedule-qualified samples are found") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val allDaysSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val qualified = List(config.minHistoryFlight) { index ->
                    historyFlight(targetDate.minusWeeks(index.toLong() + 1), 0, 100)
                }
                val padding = List(200) { index ->
                    historyFlight(targetDate.minusDays(index.toLong() + 30), 0, 250)
                }
                repository.mockArrivalFlightPages(qualified + padding)

                val scan = provider.getHistoryFlights(allDaysSeasonal, targetDate)

                scan.rawRows shouldBe config.maxHistoryRows
                scan.qualifiedRows shouldBe config.minHistoryFlight
            }

            it("caps extended scan at maxHistoryRows times three") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val allDaysSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val rejectedOnly = List(config.maxHistoryRows * EsttCalculationConfig.EXTENDED_SCAN_MULTIPLIER) { index ->
                    historyFlight(targetDate.minusDays(index.toLong() + 1), 0, 250)
                }
                repository.mockArrivalFlightPages(rejectedOnly)

                val scan = provider.getHistoryFlights(allDaysSeasonal, targetDate)

                scan.rawRows shouldBe config.maxHistoryRows * EsttCalculationConfig.EXTENDED_SCAN_MULTIPLIER
                scan.insufficientAfterBudget.shouldBeTrue()
                scan.stageBRows shouldBe 0
                scan.qualifiedFlights.shouldBeEmpty()
            }

            it("rejects out-of-range duration when seasonal flyingTime is null") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val seasonalWithoutFlyingTime = seasonalFlight.copy(flyingTime = null)
                val tooShort = historyFlight(targetDate.minusWeeks(1), 0, 10)
                val inRange = historyFlight(targetDate.minusWeeks(2), 0, 100)

                repository.mockArrivalFlightPages(listOf(tooShort, inRange))

                val scan = provider.getHistoryFlights(seasonalWithoutFlyingTime, targetDate)

                scan.stageBRows shouldBe 1
                scan.qualifiedFlights shouldHaveSize 1
                scan.qualifiedFlights.first() shouldBe inRange
            }

            it("accepts duration exactly at minFlyingTime when seasonal flyingTime is null") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val seasonalWithoutFlyingTime = seasonalFlight.copy(flyingTime = null)
                val atMin = historyFlight(targetDate.minusWeeks(1), 0, config.minFlyingTime)

                repository.mockArrivalFlightPages(listOf(atMin))

                val scan = provider.getHistoryFlights(seasonalWithoutFlyingTime, targetDate)

                scan.stageBRows shouldBe 1
                scan.qualifiedFlights.single() shouldBe atMin
            }

            it("accepts duration exactly at maxFlyingTime when seasonal flyingTime is null") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val seasonalWithoutFlyingTime = seasonalFlight.copy(flyingTime = null)
                val atMax = historyFlight(targetDate.minusWeeks(1), 0, config.maxFlyingTime)

                repository.mockArrivalFlightPages(listOf(atMax))

                val scan = provider.getHistoryFlights(seasonalWithoutFlyingTime, targetDate)

                scan.stageBRows shouldBe 1
                scan.qualifiedFlights.single() shouldBe atMax
            }

            it("rejects duration one minute above maxFlyingTime when seasonal flyingTime is null") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val seasonalWithoutFlyingTime = seasonalFlight.copy(flyingTime = null)
                val aboveMax = historyFlight(targetDate.minusWeeks(1), 0, config.maxFlyingTime + 1)

                repository.mockArrivalFlightPages(listOf(aboveMax))

                val scan = provider.getHistoryFlights(seasonalWithoutFlyingTime, targetDate)

                scan.stageBRows shouldBe 0
                scan.qualifiedFlights.shouldBeEmpty()
            }

            it("applies stage B only for paginated history") {
                val targetDate = LocalDate.of(2024, 6, 5)
                val allDaysSeasonal = seasonalFlight.copy(operationDays = "1234567")
                val onTime = historyFlight(targetDate.minusWeeks(1), 0, 100)
                val lateBeyondSchedule = onTime.copy(
                    actualTime = onTime.scheduledTime.plusMinutes(config.maxScheduleDeviation.toLong() + 1),
                    previousDepartureTime = onTime.scheduledTime
                        .plusMinutes(config.maxScheduleDeviation.toLong() + 1)
                        .minusMinutes(100),
                )

                repository.mockArrivalFlightPages(listOf(onTime, lateBeyondSchedule))

                val paginated = provider.getPaginatedHistory(allDaysSeasonal, targetDate, offset = 0, limit = 10)
                val calcScan = provider.getHistoryFlights(allDaysSeasonal, targetDate)

                paginated.items shouldHaveSize 2
                paginated.totalFiltered shouldBe 2
                calcScan.stageBRows shouldBe 2
                calcScan.qualifiedFlights shouldHaveSize 1
                calcScan.qualifiedFlights.single() shouldBe onTime
            }

            it("returns empty scan when history window is empty") {
                val seasonStart = LocalDate.of(2024, 6, 1)
                val targetDate = seasonStart

                val scan = provider.getHistoryFlights(
                    seasonalFlight.copy(seasonStart = seasonStart),
                    targetDate,
                )

                scan.stageBRows shouldBe 0
                scan.qualifiedFlights.shouldBeEmpty()
                scan.rawRows shouldBe 0
                verify(exactly = 0) {
                    repository.getArrivalFlightPage(any(), any(), any(), any(), any(), any())
                }
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
