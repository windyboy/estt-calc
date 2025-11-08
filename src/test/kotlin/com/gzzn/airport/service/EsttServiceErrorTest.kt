package com.gzzn.airport.service

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.repository.SeasonRepository
import com.gzzn.airport.service.calculator.FlyingTimeCalculator
import com.gzzn.airport.service.history.HistoryFlightProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.sql.SQLException
import java.time.LocalDate

class EsttServiceErrorTest : DescribeSpec({

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

    describe("database failure scenarios") {
        it("should handle connection timeout in getActiveSeason") {
            every { seasonRepository.getFlightSeason(true) } throws
                SQLException("Connection timeout")

            val result = esttService.getActiveSeason()

            result.isFailure.shouldBeTrue()
            result.exceptionOrNull().shouldBeInstanceOf<SQLException>()
        }

        it("should handle null pointer in repository call") {
            every { seasonRepository.getFlightSeason(true) } throws
                NullPointerException("Unexpected null")

            val result = esttService.getActiveSeason()

            result.isFailure.shouldBeTrue()
            result.exceptionOrNull().shouldBeInstanceOf<NullPointerException>()
        }

        it("should handle database error in getSeasonalFlight") {
            every { seasonRepository.getSeasonalArrivalFlight(any(), any()) } throws
                SQLException("Database unavailable")

            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))

            result.isFailure.shouldBeTrue()
            result.exceptionOrNull().shouldBeInstanceOf<SQLException>()
        }
    }

    describe("data inconsistency scenarios") {
        it("should reject zero flyingTime in seasonal flight") {
            // zero flying time should be treated as invalid configuration.
            val flight = SeasonalFlight(
                "MU9941",
                "1234567",
                0L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns flight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns emptyList()

            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            }
            exception.message shouldBe "Invalid seasonal flight time: 0 for flight MU9941"
        }

        it("should handle empty operation days") {
            // empty operation days should fail validation.
            val flight = SeasonalFlight(
                "MU9941",
                "",
                90L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns flight

            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))

            // Flight with Friday operation day should not match empty operation days
            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe null
        }
    }

    describe("edge case scenarios") {
        it("should handle very old historical data query") {
            val veryOldDate = LocalDate.of(2000, 1, 1)

            every { seasonRepository.getSeasonalArrivalFlight(any(), any()) } returns null

            val result = esttService.getHistoryFlights("MU9941", veryOldDate)

            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe emptyList()
        }

        it("should reject far future date query") {
            val futureDate = LocalDate.of(2030, 12, 31)

            val exception = shouldThrow<IllegalArgumentException> {
                esttService.calculate("MU9941", futureDate)
            }
            exception.message shouldBe "Flight date cannot be more than 1 year in the future, got: $futureDate"
        }

        it("should handle invalid flight number in calculate") {
            val exception = kotlin.runCatching {
                esttService.calculate("", LocalDate.now())
            }.exceptionOrNull()

            exception.shouldBeInstanceOf<IllegalArgumentException>()
        }

        it("should record failure metrics when calculation fails") {
            every { seasonRepository.getSeasonalArrivalFlight(any(), any()) } throws
                SQLException("DB Error")

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

            result.isFailure.shouldBeTrue()

            // Verify metrics were recorded
            val failureCounter = meterRegistry.counter("estt.calculation.failure", "error", "SQLException")
            failureCounter.count() shouldBe 1.0
        }

        it("should record success metrics when calculation succeeds") {
            val flight = SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 12, 31),
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns flight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns emptyList()

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

            result.isSuccess.shouldBeTrue()

            // Verify metrics were recorded
            val successCounter = meterRegistry.counter("estt.calculation.success", "source", "schedule")
            successCounter.count() shouldBe 1.0
        }
    }

    describe("flatMap error propagation") {
        it("should propagate seasonal flight lookup failure to history flights") {
            every { seasonRepository.getSeasonalArrivalFlight(any(), any()) } throws
                SQLException("DB Error")

            val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))

            result.isFailure.shouldBeTrue()
            result.exceptionOrNull().shouldBeInstanceOf<SQLException>()
        }

        it("should propagate seasonal flight lookup failure to calculate") {
            every { seasonRepository.getSeasonalArrivalFlight(any(), any()) } throws
                RuntimeException("Unexpected error")

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

            result.isFailure.shouldBeTrue()
            result.exceptionOrNull().shouldBeInstanceOf<RuntimeException>()
        }
    }
})
