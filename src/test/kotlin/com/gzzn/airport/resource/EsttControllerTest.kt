package com.gzzn.airport.resource

import com.gzzn.airport.exception.ErrorResponse
import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.service.EsttService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micronaut.http.HttpStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate

class EsttControllerTest : DescribeSpec({

    lateinit var controller: EsttController
    lateinit var esttService: EsttService

    beforeEach {
        esttService = mockk()
        controller = EsttController(esttService, "^[A-Z]{2}[0-9]{3,4}$")
    }

    describe("getFlightSeason") {
        it("should return season successfully") {
            val season = FlightSeason(1L, "2021-Summer", LocalDate.of(2021, 3, 28), LocalDate.of(2021, 10, 30))
            every { esttService.getActiveSeason() } returns Result.success(season)

            val response = controller.getFlightSeason()

            response.status shouldBe HttpStatus.OK
            response.body() shouldBe season
        }

        it("should return not found when no season") {
            every { esttService.getActiveSeason() } returns Result.success(null)

            val response = controller.getFlightSeason()

            response.status shouldBe HttpStatus.NOT_FOUND
        }

        it("should return server error on exception") {
            every { esttService.getActiveSeason() } returns Result.failure(RuntimeException("DB error"))

            val response = controller.getFlightSeason()

            response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            val error = response.body() as ErrorResponse
            error.status shouldBe 500
            error.error shouldBe "DATABASE_ERROR"
        }
    }

    describe("getSeasonalFlight") {
        it("should return flight with valid parameters") {
            val flight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)
            every { esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31)) } returns Result.success(flight)

            val response = controller.getSeasonalFlight("MU9941", "211231")

            response.status shouldBe HttpStatus.OK
            response.body() shouldBe flight
        }

        it("should normalize flight number to uppercase") {
            val flight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)
            every { esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31)) } returns Result.success(flight)

            val response = controller.getSeasonalFlight("mu9941", "211231")

            response.status shouldBe HttpStatus.OK
            verify { esttService.getSeasonalFlight("MU9941", any()) }
        }

        it("should return not found when no flight") {
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)
            every { esttService.getSeasonalFlight("XX9999", LocalDate.of(2021, 12, 31)) } returns Result.success(null)

            val response = controller.getSeasonalFlight("XX9999", "211231")

            response.status shouldBe HttpStatus.NOT_FOUND
        }

        it("should return server error on exception") {
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)
            every { esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31)) } returns 
                Result.failure(RuntimeException("DB error"))

            val response = controller.getSeasonalFlight("MU9941", "211231")

            response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            val error = response.body() as ErrorResponse
            error.status shouldBe 500
        }
    }

    describe("getHistoryFlights") {
        it("should return list successfully") {
            val seasonalFlight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
            every { esttService.parseFlightDate(any()) } returns LocalDate.of(2021, 12, 31)
            every { esttService.getSeasonalFlight(any(), any()) } returns Result.success(seasonalFlight)
            every { esttService.getPaginatedHistoryFlights(any(), any(), any(), any()) } returns Result.success(emptyList())

            val response = controller.getHistoryFlights("MU9941", "211231", 100, 0)

            response.status shouldBe HttpStatus.OK
            response.body() shouldBe emptyList<Any>()
        }

        it("should return server error on exception") {
            val seasonalFlight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
            every { esttService.parseFlightDate(any()) } returns LocalDate.of(2021, 12, 31)
            every { esttService.getSeasonalFlight(any(), any()) } returns Result.success(seasonalFlight)
            every { esttService.getPaginatedHistoryFlights(any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("DB error"))

            val response = controller.getHistoryFlights("MU9941", "211231", 100, 0)

            response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
        }
    }

    describe("calculate") {
        it("should return response successfully") {
            val flyingTimeResponse = FlyingTimeResponse("MU9941", LocalDate.of(2021, 12, 31), 90L, true, true, "Success")
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)
            every { esttService.calculate("MU9941", LocalDate.of(2021, 12, 31)) } returns Result.success(flyingTimeResponse)

            val response = controller.calculate("MU9941", "211231")

            response.status shouldBe HttpStatus.OK
            response.body() shouldBe flyingTimeResponse
        }

        it("should return server error on exception") {
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)
            every { esttService.calculate("MU9941", LocalDate.of(2021, 12, 31)) } returns 
                Result.failure(RuntimeException("Calculation error"))

            val response = controller.calculate("MU9941", "211231")

            response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            val error = response.body() as ErrorResponse
            error.status shouldBe 500
            error.error shouldBe "CALCULATION_ERROR"
        }
    }

    describe("flight number validation") {
        it("should throw exception for empty flight number") {
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)

            shouldThrow<IllegalArgumentException> {
                controller.getSeasonalFlight("", "211231")
            }
        }

        it("should throw exception for blank flight number") {
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)

            shouldThrow<IllegalArgumentException> {
                controller.getSeasonalFlight("   ", "211231")
            }
        }

        it("should throw exception for too short format") {
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)

            shouldThrow<IllegalArgumentException> {
                controller.getSeasonalFlight("A1", "211231")
            }
        }

        it("should throw exception for wrong pattern") {
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)

            shouldThrow<IllegalArgumentException> {
                controller.getSeasonalFlight("123ABC", "211231")
            }
        }

        it("should throw exception for too many digits") {
            every { esttService.parseFlightDate("211231") } returns LocalDate.of(2021, 12, 31)

            shouldThrow<IllegalArgumentException> {
                controller.getSeasonalFlight("MU99999", "211231")
            }
        }
    }

    describe("date parsing") {
        it("should throw exception for invalid date") {
            every { esttService.parseFlightDate("invalid") } throws IllegalArgumentException("Invalid date format")

            shouldThrow<IllegalArgumentException> {
                controller.calculate("MU9941", "invalid")
            }
        }
    }
})

