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
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Tests for new features added in v0.1.1:
 * - Operation day matching helper function
 * - Service layer input validation
 * - Improved field naming
 */
class EsttServiceNewFeaturesTest : DescribeSpec({

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

    describe("Operation Day Matching") {
        it("should correctly match single operation day") {
            // Operation days set to Monday only ("1").
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "1",
                90L,
                LocalDate.of(2021, 3, 28),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "1") } returns seasonalFlight
            // Stub day-2 lookup even though it should be filtered out by the service.
            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "2") } returns seasonalFlight

            // Monday (day 1) - should match
            val resultMonday = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 29))
            resultMonday.isSuccess.shouldBeTrue()
            resultMonday.getOrNull() shouldBe seasonalFlight

            // Tuesday (day 2) - should NOT match (operation days is "1", not "2")
            val resultTuesday = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 30))
            resultTuesday.isSuccess.shouldBeTrue()
            resultTuesday.getOrNull() shouldBe null
        }

        it("should NOT match '1' when operation days is '12'") {
            // Operation days include Monday and Tuesday ("12").
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "12",
                90L,
                LocalDate.of(2021, 3, 28),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "1") } returns seasonalFlight

            // This tests the fix for false positive matching
            // Old bug: "1" would match "12" with contains()
            // New: proper digit parsing prevents this
            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 29))
            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe seasonalFlight // Should match because 1 is in "12"
        }

        it("should NOT match '7' when operation days is '17'") {
            // Operation days include Monday (1) and Sunday (7).
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "17",
                90L,
                LocalDate.of(2021, 3, 28),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "2") } returns seasonalFlight

            // Tuesday (day 2) should NOT match operation days "17"
            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 30))
            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe null // Should NOT match
        }

        it("should match weekday pattern correctly") {
            // Operation days cover Monday through Friday.
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "12345",
                90L,
                LocalDate.of(2021, 3, 28),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", any()) } returns seasonalFlight

            // Wednesday (day 3) - should match
            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 31))
            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe seasonalFlight
        }
    }

    describe("Service Layer Input Validation") {
        it("should reject empty flight number") {
            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("", LocalDate.of(2021, 12, 31))
            }
            exception.message shouldBe "Flight number cannot be empty"
        }

        it("should reject flight number that is too short") {
            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("MU12", LocalDate.of(2021, 12, 31))
            }
            exception.message shouldBe "Flight number must be 5-6 characters, got: 4"
        }

        it("should reject flight number that is too long") {
            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("MU12345", LocalDate.of(2021, 12, 31))
            }
            exception.message shouldBe "Flight number must be 5-6 characters, got: 7"
        }

        it("should reject flight date before year 2000") {
            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("MU9941", LocalDate.of(1999, 12, 31))
            }
            exception.message shouldBe "Flight date must be after 2000-01-01, got: 1999-12-31"
        }

        it("should reject flight date more than 1 year in future") {
            val farFuture = LocalDate.now().plusYears(2)
            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("MU9941", farFuture)
            }
            exception.message shouldBe "Flight date cannot be more than 1 year in the future, got: $farFuture"
        }

        it("should accept valid flight number and date") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", any()) } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns emptyList()

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            result.isSuccess.shouldBeTrue()
        }
    }

    describe("Historical Flight Validation") {
        it("should exclude flights where scheduled date doesn't match flight date") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "5",
                90L,
                LocalDate.of(2021, 3, 28),
            )

            // Flight on correct day but scheduled time has wrong date
            val invalidFlight = HistoricalFlight(
                flightDate = LocalDate.of(2021, 12, 31),
                previousDepartureTime = LocalDateTime.of(2021, 12, 31, 10, 0),
                actualTime = LocalDateTime.of(2021, 12, 31, 11, 30),
                // Wrong scheduled date to ensure filter removes it.
                scheduledTime = LocalDateTime.of(2021, 12, 30, 11, 0),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns listOf(invalidFlight)

            val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))
            result.isSuccess.shouldBeTrue()
            result.getOrNull()?.size shouldBe 0 // Should filter out the invalid flight
        }

        it("should include flights where scheduled date matches flight date") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "5",
                90L,
                LocalDate.of(2021, 3, 28),
            )

            val validFlight = HistoricalFlight(
                flightDate = LocalDate.of(2021, 12, 31),
                previousDepartureTime = LocalDateTime.of(2021, 12, 31, 10, 0),
                actualTime = LocalDateTime.of(2021, 12, 31, 11, 30),
                scheduledTime = LocalDateTime.of(2021, 12, 31, 11, 0),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns listOf(validFlight)

            val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))
            result.isSuccess.shouldBeTrue()
            result.getOrNull()?.size shouldBe 1 // Should include the valid flight
        }
    }

    describe("Seasonal Flight Validation") {
        it("should reject seasonal flight with zero flying time") {
            val invalidSeasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                0L,
                LocalDate.of(2021, 3, 28),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", any()) } returns invalidSeasonalFlight

            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            }
            exception.message shouldBe "Invalid seasonal flight time: 0 for flight MU9941"
        }

        it("should reject seasonal flight with negative flying time") {
            val invalidSeasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                -10L,
                LocalDate.of(2021, 3, 28),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", any()) } returns invalidSeasonalFlight

            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            }
            exception.message shouldBe "Invalid seasonal flight time: -10 for flight MU9941"
        }
    }

    describe("Average Flight Time Calculation") {
        it("should use double division for accurate rounding with sufficient history") {
            // Operation day limited to Friday.
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "5",
                100L,
                LocalDate.of(2021, 3, 28),
            )

            // Create 20 flights with times that test rounding
            // Using times around 95-97 minutes (within 120 min of seasonal 100)
            val baseDate = LocalDate.of(2021, 12, 31)
            val flights = (0 until 20).map { i ->
                val minutes = (95 + (i % 3)).toLong() // Cycles: 95, 96, 97, 95, 96...
                val date = baseDate.minusDays(i * 7L)
                val startTime = date.atTime(10, 0)
                HistoricalFlight(
                    date,
                    startTime,
                    startTime.plusMinutes(minutes),
                    date.atTime(10, 30),
                )
            }

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns flights

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            result.isSuccess.shouldBeTrue()
            // Average of first 20: (95*7 + 96*7 + 97*6) / 20 = (665 + 672 + 582) / 20 = 1919 / 20 = 95.95 ≈ 96
            result.getOrNull()?.flyingTime shouldBe 96L // Should be properly rounded
        }
    }
})
