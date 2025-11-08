package com.gzzn.airport.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalDateTime

class ModelTest : DescribeSpec({

    describe("FlightSeason") {
        it("should create with all properties") {
            val season = FlightSeason(
                1L,
                "2021-Summer",
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 10, 30),
            )

            season.seasonId shouldBe 1L
            season.seasonName shouldBe "2021-Summer"
            season.seasonStart shouldBe LocalDate.of(2021, 3, 28)
            season.seasonEnd shouldBe LocalDate.of(2021, 10, 30)
        }
    }

    describe("SeasonalFlight") {
        it("should create with all properties") {
            val flight = SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 10, 30),
            )

            flight.flightNumber shouldBe "MU9941"
            flight.operationDays shouldBe "1234567"
            flight.flyingTime shouldBe 90L
            flight.seasonStart shouldBe LocalDate.of(2021, 3, 28)
            flight.seasonEnd shouldBe LocalDate.of(2021, 10, 30)
        }

        it("should throw exception for invalid operationDays") {
            shouldThrow<IllegalArgumentException> {
                SeasonalFlight("MU9941", "89", 90L, LocalDate.of(2021, 3, 28), LocalDate.of(2021, 10, 30))
            }
        }

        it("should throw exception for operationDays with non-digit") {
            shouldThrow<IllegalArgumentException> {
                SeasonalFlight("MU9941", "12a", 90L, LocalDate.of(2021, 3, 28), LocalDate.of(2021, 10, 30))
            }
        }

        it("should throw exception when season end precedes start") {
            shouldThrow<IllegalArgumentException> {
                SeasonalFlight(
                    "MU9941",
                    "1234567",
                    90L,
                    LocalDate.of(2021, 10, 30),
                    LocalDate.of(2021, 3, 28),
                )
            }
        }
    }

    describe("HistoricalFlight") {
        it("should create with all properties") {
            val flight = HistoricalFlight(
                LocalDate.of(2021, 12, 31),
                LocalDateTime.of(2021, 12, 31, 10, 0),
                LocalDateTime.of(2021, 12, 31, 11, 30),
                LocalDateTime.of(2021, 12, 31, 11, 0),
            )

            flight.flightDate shouldBe LocalDate.of(2021, 12, 31)
            flight.previousDepartureTime shouldBe LocalDateTime.of(2021, 12, 31, 10, 0)
            flight.actualTime shouldBe LocalDateTime.of(2021, 12, 31, 11, 30)
            flight.scheduledTime shouldBe LocalDateTime.of(2021, 12, 31, 11, 0)
        }
    }

    describe("FlyingTimeResponse") {
        it("should create with all properties") {
            val response = FlyingTimeResponse(
                "MU9941",
                LocalDate.of(2021, 12, 31),
                90L,
                true,
                true,
                "Test message",
            )

            response.flightNumber shouldBe "MU9941"
            response.flightDate shouldBe LocalDate.of(2021, 12, 31)
            response.flyingTime shouldBe 90L
            response.history.shouldBeTrue()
            response.seasonal.shouldBeTrue()
            response.message shouldBe "Test message"
        }
    }
})
