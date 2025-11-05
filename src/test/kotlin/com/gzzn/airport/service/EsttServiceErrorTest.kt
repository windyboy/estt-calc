package com.gzzn.airport.service

import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.repository.SeasonRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
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
    lateinit var meterRegistry: MeterRegistry

    beforeEach {
        seasonRepository = mockk()
        historyFlightRepository = mockk()
        meterRegistry = SimpleMeterRegistry()
        
        esttService = EsttService(
            seasonRepository,
            historyFlightRepository,
            meterRegistry,
            120,
            20,
            "yyMMdd",
            60L,
            300
        )
        esttService.init()
    }

    describe("database failure scenarios") {
        it("should handle connection timeout in getActiveSeason") {
            every { seasonRepository.getFlightSeasonByTag(true) } throws 
                SQLException("Connection timeout")
            
            val result = esttService.getActiveSeason()
            
            result.isFailure.shouldBeTrue()
            result.exceptionOrNull().shouldBeInstanceOf<SQLException>()
        }
        
        it("should handle null pointer in repository call") {
            every { seasonRepository.getFlightSeasonByTag(true) } throws 
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
        it("should handle null flyingTime in seasonal flight") {
            val flight = SeasonalFlight(
                "MU9941",
                "1234567",
                null,  // null flying time
                LocalDate.of(2021, 3, 28)
            )
            
            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") } returns flight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } returns emptyList()
            
            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            
            result.isSuccess.shouldBeTrue()
            // Should use 0 when flyingTime is null and no history
            result.getOrNull()?.flyingTime shouldBe 0
        }
        
        it("should handle empty operation days") {
            val flight = SeasonalFlight(
                "MU9941",
                "",  // empty operation days
                90L,
                LocalDate.of(2021, 3, 28)
            )
            
            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") } returns flight
            
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
        
        it("should handle future date query") {
            val futureDate = LocalDate.of(2030, 12, 31)
            
            every { seasonRepository.getSeasonalArrivalFlight(any(), any()) } returns null
            
            val result = esttService.calculate("MU9941", futureDate)
            
            result.isSuccess.shouldBeTrue()
            result.getOrNull()?.seasonal shouldBe false
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
            val flight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
            
            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") } returns flight
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

