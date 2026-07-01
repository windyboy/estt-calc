package com.gzzn.airport.service

import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.repository.SeasonRepository
import com.gzzn.airport.service.mockArrivalFlightPages
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import java.time.LocalDate
import java.time.LocalDateTime

/** Verifies EsttService helpers for seasonal matching, input validation, and history filtering. */
class EsttServiceValidationAndMatchingTest :
    DescribeSpec({

        lateinit var esttService: EsttService
        lateinit var seasonRepository: SeasonRepository
        lateinit var historyFlightRepository: HistoryFlightRepository

        beforeEach {
            val ctx = createEsttServiceTestContext()
            esttService = ctx.esttService
            seasonRepository = ctx.seasonRepository
            historyFlightRepository = ctx.historyFlightRepository
        }

        describe("Operation Day Matching") {
            it("should correctly match single operation day") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "1",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "1") } returns seasonalFlight
                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "2") } returns seasonalFlight

                val resultMonday = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 29))
                resultMonday.isSuccess.shouldBeTrue()
                resultMonday.getOrNull() shouldBe seasonalFlight

                val resultTuesday = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 30))
                resultTuesday.isSuccess.shouldBeTrue()
                resultTuesday.getOrNull().shouldBeNull()
            }

            it("should NOT match '1' when operation days is '12'") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "12",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "1") } returns seasonalFlight

                val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 29))
                result.isSuccess.shouldBeTrue()
                result.getOrNull() shouldBe seasonalFlight
            }

            it("should NOT match '7' when operation days is '17'") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "17",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "2") } returns seasonalFlight

                val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 30))
                result.isSuccess.shouldBeTrue()
                result.getOrNull().shouldBeNull()
            }

            it("should match weekday pattern correctly") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "12345",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", any()) } returns seasonalFlight

                val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 3, 31))
                result.isSuccess.shouldBeTrue()
                result.getOrNull() shouldBe seasonalFlight
            }

            it("should ignore seasonal flights outside the season window") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "1234567",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 9, 30),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", any()) } returns seasonalFlight

                val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))
                result.isSuccess.shouldBeTrue()
                result.getOrNull().shouldBeNull()
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
                    LocalDate.of(2021, 12, 31),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", any()) } returns seasonalFlight
                historyFlightRepository.mockArrivalFlightPages(emptyList())

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
                    LocalDate.of(2021, 12, 31),
                )

                val invalidFlight = HistoricalFlight(
                    flightDate = LocalDate.of(2021, 12, 31),
                    previousDepartureTime = LocalDateTime.of(2021, 12, 31, 10, 0),
                    actualTime = LocalDateTime.of(2021, 12, 31, 11, 30),
                    scheduledTime = LocalDateTime.of(2021, 12, 30, 11, 0),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
                historyFlightRepository.mockArrivalFlightPages(listOf(invalidFlight))

                val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))
                result.isSuccess.shouldBeTrue()
                result.getOrNull().shouldNotBeNull().isEmpty().shouldBeTrue()
            }

            it("should filter flights with excessive flying time deviation") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "5",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )

                val validFlight = HistoricalFlight(
                    flightDate = LocalDate.of(2021, 12, 31),
                    previousDepartureTime = LocalDateTime.of(2021, 12, 31, 9, 0),
                    actualTime = LocalDateTime.of(2021, 12, 31, 10, 30),
                    scheduledTime = LocalDateTime.of(2021, 12, 31, 10, 0),
                )
                val deviatedFlight = validFlight.copy(
                    previousDepartureTime = validFlight.previousDepartureTime.minusHours(3),
                    actualTime = validFlight.actualTime.plusHours(3),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5") } returns seasonalFlight
                historyFlightRepository.mockArrivalFlightPages(listOf(validFlight, deviatedFlight))

                val result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))
                result.isSuccess.shouldBeTrue()
                result.getOrNull().shouldNotBeNull().size shouldBe 1
            }
        }
    })
