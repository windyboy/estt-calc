package com.gzzn.airport.service

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.repository.SeasonRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime

class EsttServiceTest : DescribeSpec({

    lateinit var esttService: EsttService
    lateinit var seasonRepository: SeasonRepository
    lateinit var historyFlightRepository: HistoryFlightRepository
    lateinit var meterRegistry: io.micrometer.core.instrument.MeterRegistry

    beforeEach {
        seasonRepository = mockk()
        historyFlightRepository = mockk()
        meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        
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

    describe("parseFlightDate") {
        it("should parse valid date") {
            val date = esttService.parseFlightDate("211231")
            date shouldBe LocalDate.of(2021, 12, 31)
        }

        it("should throw exception for invalid format") {
            shouldThrow<IllegalArgumentException> {
                esttService.parseFlightDate("invalid")
            }
        }

        it("should throw exception for wrong length") {
            shouldThrow<IllegalArgumentException> {
                esttService.parseFlightDate("2112")
            }
        }
    }

    describe("getActiveSeason") {
        it("should return season successfully") {
            val season = FlightSeason(
                1L,
                "2021-Summer",
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 10, 30)
            )

            every { seasonRepository.getFlightSeason(true) } returns season

            val result = esttService.getActiveSeason()

            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe season
            verify(exactly = 1) { seasonRepository.getFlightSeason(true) }
        }

        it("should handle exception gracefully") {
            every { seasonRepository.getFlightSeason(true) } throws RuntimeException("DB error")

            val result = esttService.getActiveSeason()
            
            result.isFailure.shouldBeTrue()
        }
    }

    describe("getSeasonalFlight") {
        it("should return flight when found") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28)
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight

            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))
            
            result.isSuccess.shouldBeTrue()
            result.getOrNull() shouldBe seasonalFlight
        }

        it("should return null when operation day doesn't match") {
            val seasonalFlight = SeasonalFlight(
                "MU9941",
                "246",
                90L,
                LocalDate.of(2021, 3, 28)
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight

            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))
            
            result.isSuccess.shouldBeTrue()
            result.getOrNull().shouldBeNull()
        }

        it("should return null when not found") {
            every { seasonRepository.getSeasonalArrivalFlight("XX9999", "5") } returns null

            val result = esttService.getSeasonalFlight("XX9999", LocalDate.of(2021, 12, 31))
            
            result.isSuccess.shouldBeTrue()
            result.getOrNull().shouldBeNull()
        }

        it("should return failure when repository throws exception") {
            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } throws 
                RuntimeException("Database connection failed")

            val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))
            
            result.isFailure.shouldBeTrue()
        }
    }

    describe("calculate") {
        it("should calculate with sufficient history") {
            val seasonalFlight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
            val historyFlights = createHistoryFlights(25, LocalDate.of(2021, 12, 31))

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { 
                historyFlightRepository.getArrivalFlight(
                    "MU9941",
                    any(),
                    LocalDate.of(2021, 12, 31),
                    300
                ) 
            } returns historyFlights

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            
            result.isSuccess.shouldBeTrue()
            val response = result.getOrNull()
            response.shouldNotBeNull()
            response.flightNumber shouldBe "MU9941"
            response.history.shouldBeTrue()
            response.seasonal.shouldBeTrue()
            response.flyingTime shouldBeGreaterThan 0
        }

        it("should use seasonal time with insufficient history") {
            val seasonalFlight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
            val historyFlights = createHistoryFlights(5, LocalDate.of(2021, 12, 31))

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { 
                historyFlightRepository.getArrivalFlight(
                    "MU9941",
                    any(),
                    LocalDate.of(2021, 12, 31),
                    300
                ) 
            } returns historyFlights

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            
            result.isSuccess.shouldBeTrue()
            val response = result.getOrNull()
            response.shouldNotBeNull()
            response.flightNumber shouldBe "MU9941"
            response.flyingTime shouldBe 90L
            response.seasonal.shouldBeTrue()
        }

        it("should handle no seasonal flight") {
            every { seasonRepository.getSeasonalArrivalFlight("XX9999", "5") } returns null

            val result = esttService.calculate("XX9999", LocalDate.of(2021, 12, 31))
            
            result.isSuccess.shouldBeTrue()
            val response = result.getOrNull()
            response.shouldNotBeNull()
            response.flightNumber shouldBe "XX9999"
            response.flyingTime shouldBe 0
            response.seasonal.shouldBeFalse()
        }

        it("should throw exception for empty flight number") {
            shouldThrow<IllegalArgumentException> {
                esttService.calculate("", LocalDate.now())
            }
        }

        it("should throw exception for blank flight number") {
            shouldThrow<IllegalArgumentException> {
                esttService.calculate("   ", LocalDate.now())
            }
        }

        it("should return failure when repository throws exception") {
            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } throws 
                RuntimeException("Database connection failed")

            val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
            
            result.isFailure.shouldBeTrue()
        }
    }

    describe("getHistoryFlights") {
        it("should return empty list when seasonal flight not found") {
            every { seasonRepository.getSeasonalArrivalFlight("XX9999", "5") } returns null

            val result = esttService.getHistoryFlights("XX9999", LocalDate.of(2021, 12, 31))

            result.isSuccess.shouldBeTrue()
            result.getOrNull()!!.isEmpty().shouldBeTrue()
        }

        it("should return failure when repository throws exception") {
            val seasonalFlight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every { historyFlightRepository.getArrivalFlight(any(), any(), any(), any()) } throws
                RuntimeException("Database error")

            val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))

            result.isFailure.shouldBeTrue()
        }
    }

    describe("isOperationDayMatch") {
        it("should return true when day is in operation days") {
            esttService.isOperationDayMatch("1234567", 1).shouldBeTrue()
            esttService.isOperationDayMatch("1234567", 5).shouldBeTrue()
            esttService.isOperationDayMatch("246", 2).shouldBeTrue()
        }

        it("should return false when day is not in operation days") {
            esttService.isOperationDayMatch("246", 1).shouldBeFalse()
            esttService.isOperationDayMatch("246", 5).shouldBeFalse()
            esttService.isOperationDayMatch("1", 2).shouldBeFalse()
        }

        it("should return false for empty operation days") {
            esttService.isOperationDayMatch("", 1).shouldBeFalse()
        }

        it("should return false for invalid characters") {
            esttService.isOperationDayMatch("abc", 1).shouldBeFalse()
            esttService.isOperationDayMatch("1a2", 1).shouldBeTrue()
            esttService.isOperationDayMatch("1a2", 2).shouldBeTrue()
        }

        it("should handle duplicate days") {
            esttService.isOperationDayMatch("112233", 1).shouldBeTrue()
            esttService.isOperationDayMatch("112233", 4).shouldBeFalse()
        }
    }

    describe("getPaginatedHistoryFlights") {
        it("should return paginated list successfully") {
            val seasonalFlight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
            val historyFlights = createHistoryFlights(10, LocalDate.of(2021, 12, 31))

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every {
                historyFlightRepository.getArrivalFlightPage(
                    "MU9941",
                    any(),
                    any(),
                    0,
                    any()
                )
            } returns historyFlights

            val result = esttService.getPaginatedHistoryFlights("MU9941", LocalDate.of(2021, 12, 31), 2, 3)

            result.isSuccess.shouldBeTrue()
            val paginated = result.getOrNull()
            paginated.shouldNotBeNull()
            paginated.items.size shouldBe 3  // offset 2, limit 3
            paginated.totalFiltered shouldBe 10  // All 10 history flights should pass filter
            paginated.hasMore shouldBe true  // offset 2 + limit 3 = 5, which is < 10
        }

        it("should return empty list when seasonal flight not found") {
            every { seasonRepository.getSeasonalArrivalFlight("XX9999", "5") } returns null

            val result = esttService.getPaginatedHistoryFlights("XX9999", LocalDate.of(2021, 12, 31), 0, 10)

            result.isSuccess.shouldBeTrue()
            val response = result.getOrNull()!!
            response.items.isEmpty().shouldBeTrue()
            response.totalFiltered shouldBe 0
        }

        it("should return failure when repository throws exception") {
            val seasonalFlight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))

            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
            every {
                historyFlightRepository.getArrivalFlightPage(any(), any(), any(), any(), any())
            } throws
                RuntimeException("Database error")

            val result = esttService.getPaginatedHistoryFlights("MU9941", LocalDate.of(2021, 12, 31), 0, 10)

            result.isFailure.shouldBeTrue()
        }
    }

    describe("caching and filtering edge cases") {
        it("should not cache failures for seasonal flight lookup") {
            val seasonalFlight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
            var invocation = 0
            every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } answers {
                if (invocation++ == 0) {
                    throw RuntimeException("DB error")
                } else {
                    seasonalFlight
                }
            }

            val first = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))
            first.isFailure.shouldBeTrue()

            val second = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))
            second.isSuccess.shouldBeTrue()
            second.getOrNull() shouldBe seasonalFlight
        }

        it("should respect pagination limits and not exceed requested size") {
            val seasonalFlight = SeasonalFlight("MU1234", "1234567", 80L, LocalDate.of(2021, 3, 28))
            val historyFlights = createHistoryFlights(15, LocalDate.of(2021, 12, 31))

            every { seasonRepository.getSeasonalArrivalFlight("MU1234", "5") } returns seasonalFlight
            every {
                historyFlightRepository.getArrivalFlightPage(
                    "MU1234",
                    any(),
                    any(),
                    any(),
                    any()
                )
            } returns historyFlights

            val result = esttService.getPaginatedHistoryFlights("MU1234", LocalDate.of(2021, 12, 31), 0, 5)
            result.isSuccess.shouldBeTrue()
            val response = result.getOrNull()
            response.shouldNotBeNull()
            response.items.size shouldBe 5
            response.totalFiltered shouldBe 15
            response.hasMore.shouldBeTrue()
        }

        it("should filter out extreme early arrivals") {
            val seasonalFlight = SeasonalFlight("MU5678", "1234567", 90L, LocalDate.of(2021, 3, 28))
            val baseDate = LocalDate.of(2021, 12, 31)
            val extremeEarly = HistoricalFlight(
                baseDate,
                LocalDateTime.of(baseDate.year, baseDate.monthValue, baseDate.dayOfMonth, 12, 0),
                LocalDateTime.of(baseDate.year, baseDate.monthValue, baseDate.dayOfMonth, 10, 0),
                LocalDateTime.of(baseDate.year, baseDate.monthValue, baseDate.dayOfMonth, 11, 0)
            )

            every { seasonRepository.getSeasonalArrivalFlight("MU5678", "5") } returns seasonalFlight
            every {
                historyFlightRepository.getArrivalFlight(
                    "MU5678",
                    any(),
                    baseDate,
                    any()
                )
            } returns listOf(extremeEarly)

            val result = esttService.getHistoryFlights("MU5678", baseDate)
            result.isSuccess.shouldBeTrue()
            result.getOrNull().shouldNotBeNull().isEmpty().shouldBeTrue()
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
            LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 11, 0)
        )
    }
}
