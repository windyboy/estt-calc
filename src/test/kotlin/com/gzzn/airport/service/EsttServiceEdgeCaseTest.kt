package com.gzzn.airport.service

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.repository.SeasonRepository
import com.gzzn.airport.service.calculator.FlyingTimeCalculator
import com.gzzn.airport.service.history.HistoryFlightProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime

class EsttServiceEdgeCaseTest : DescribeSpec({

    lateinit var esttService: EsttService
    lateinit var seasonRepository: SeasonRepository
    lateinit var historyFlightRepository: HistoryFlightRepository
    lateinit var historyFlightProvider: HistoryFlightProvider
    lateinit var flyingTimeCalculator: FlyingTimeCalculator
    lateinit var meterRegistry: MeterRegistry
    lateinit var config: EsttCalculationConfig

    beforeEach {
        seasonRepository = mockk()
        historyFlightRepository = mockk()
        meterRegistry = SimpleMeterRegistry()
        config = EsttCalculationConfig(
            maxHistoryDelay = 120,
            minHistoryFlight = 20,
            dateFormat = "yyMMdd",
            historyStartOffsetDays = 60L,
            maxHistoryRows = 300,
        )
        historyFlightProvider = HistoryFlightProvider(historyFlightRepository, meterRegistry, config)
        flyingTimeCalculator = FlyingTimeCalculator(meterRegistry, config)
        esttService = EsttService(
            seasonRepository,
            historyFlightProvider,
            flyingTimeCalculator,
            meterRegistry,
            config,
        )
    }

    describe("calculate with edge cases") {
        it("should handle exactly minimum history flights") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )
            val historyFlights = createHistoryFlights(20, LocalDate.of(2021, 12, 31))

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns historyFlights

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

            result.isSuccess.shouldBeTrue()
            val response = result.getOrNull()!!
            response.history.shouldBeTrue()
            response.flyingTime shouldBeGreaterThan 0
        }

        it("should use seasonal time with one less than minimum history") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )
            val historyFlights = createHistoryFlights(19, LocalDate.of(2021, 12, 31))

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns historyFlights

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

            result.isSuccess.shouldBeTrue()
            result.getOrNull()!!.flyingTime shouldBe 90L
        }

        it("should reject zero seasonal flying time") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                0L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns emptyList()

            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            }
            exception.message shouldBe "Invalid seasonal flight time: 0 for flight MU9941"
        }
    }

    describe("getSeasonalFlight with operation day edge cases") {
        it("should match operation day at boundary") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "17",
                90L,
                LocalDate.of(2020, 10, 25),
                LocalDate.of(2021, 12, 31),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "1") } returns seasonalFlight

            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 1, 4)) // Monday

            result.isSuccess.shouldBeTrue()
            result.getOrNull().shouldNotBeNull()
        }

        it("should filter out partial day matches") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "27",
                90L,
                LocalDate.of(2020, 10, 25),
                LocalDate.of(2021, 12, 31),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "1") } returns seasonalFlight

            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 1, 4)) // Monday (day 1)

            result.isSuccess.shouldBeTrue()
            result.getOrNull().shouldBeNull()
        }
    }

    describe("history flight filtering") {
        it("should filter by operation day mismatch") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "246",
                90L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )

            val historyFlights = listOf(
                HistoricalFlight(
                    LocalDate.of(2021, 12, 27),
                    LocalDateTime.of(2021, 12, 27, 10, 0),
                    LocalDateTime.of(2021, 12, 27, 11, 30),
                    LocalDateTime.of(2021, 12, 27, 11, 0),
                ),
                HistoricalFlight(
                    LocalDate.of(2021, 12, 28),
                    LocalDateTime.of(2021, 12, 28, 10, 0),
                    LocalDateTime.of(2021, 12, 28, 11, 30),
                    LocalDateTime.of(2021, 12, 28, 11, 0),
                ),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "2") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns historyFlights

            val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 28))

            result.isSuccess.shouldBeTrue()
            result.getOrNull()!!.size shouldBe 1
        }

        it("should filter by excessive delay") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )

            val historyFlights = listOf(
                HistoricalFlight(
                    LocalDate.of(2021, 12, 31),
                    LocalDateTime.of(2021, 12, 31, 10, 0),
                    LocalDateTime.of(2021, 12, 31, 11, 30),
                    LocalDateTime.of(2021, 12, 31, 11, 0),
                ),
                HistoricalFlight(
                    LocalDate.of(2021, 12, 24),
                    LocalDateTime.of(2021, 12, 24, 10, 0),
                    LocalDateTime.of(2021, 12, 24, 14, 0),
                    LocalDateTime.of(2021, 12, 24, 11, 0),
                ),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns historyFlights

            val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))

            result.isSuccess.shouldBeTrue()
            result.getOrNull()!!.size shouldBe 1
        }

        it("should filter by flying time deviation") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )

            val historyFlights = listOf(
                HistoricalFlight(
                    LocalDate.of(2021, 12, 31),
                    LocalDateTime.of(2021, 12, 31, 10, 0),
                    LocalDateTime.of(2021, 12, 31, 11, 30),
                    LocalDateTime.of(2021, 12, 31, 11, 30),
                ),
                HistoricalFlight(
                    LocalDate.of(2021, 12, 24),
                    LocalDateTime.of(2021, 12, 24, 10, 0),
                    LocalDateTime.of(2021, 12, 24, 14, 10),
                    LocalDateTime.of(2021, 12, 24, 14, 10),
                ),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns historyFlights

            val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))

            result.isSuccess.shouldBeTrue()
            result.getOrNull()!!.size shouldBe 1
        }

        it("should filter by invalid time order") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )

            val historyFlights = listOf(
                HistoricalFlight(
                    LocalDate.of(2021, 12, 31),
                    LocalDateTime.of(2021, 12, 31, 10, 0),
                    LocalDateTime.of(2021, 12, 31, 11, 30),
                    LocalDateTime.of(2021, 12, 31, 11, 0),
                ),
                HistoricalFlight(
                    LocalDate.of(2021, 12, 24),
                    LocalDateTime.of(2021, 12, 24, 11, 30),
                    LocalDateTime.of(2021, 12, 24, 10, 0),
                    LocalDateTime.of(2021, 12, 24, 11, 0),
                ),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns historyFlights

            val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))

            result.isSuccess.shouldBeTrue()
            result.getOrNull()!!.size shouldBe 1
        }
    }

    describe("parseFlightDate variations") {
        it("should parse various valid date formats") {
            val testCases = mapOf(
                "211231" to LocalDate.of(2021, 12, 31),
                "220101" to LocalDate.of(2022, 1, 1),
                "991231" to LocalDate.of(2099, 12, 31),
            )

            testCases.forEach { (input, expected) ->
                esttService.parseFlightDate(input) shouldBe expected
            }
        }
    }
})

private fun createHistoryFlights(count: Int, baseDate: LocalDate): List<HistoricalFlight> {
    return (0 until count).map { i ->
        val date = baseDate.minusDays((i * 7).toLong())
        HistoricalFlight(
            date,
            LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 10, 0),
            LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 11, 30),
            LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 11, 0),
        )
    }
}
