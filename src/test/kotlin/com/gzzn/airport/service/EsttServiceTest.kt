package com.gzzn.airport.service

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.exception.InvalidFlightDateException
import com.gzzn.airport.model.EstimateSource
import com.gzzn.airport.model.FlightSeason
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
import io.micrometer.core.instrument.MeterRegistry
import io.mockk.every
import io.mockk.verify
import java.time.LocalDate

class EsttServiceTest :
    DescribeSpec({

        lateinit var ctx: EsttServiceTestContext
        lateinit var esttService: EsttService
        lateinit var seasonRepository: SeasonRepository
        lateinit var historyFlightRepository: HistoryFlightRepository
        lateinit var meterRegistry: MeterRegistry
        lateinit var config: EsttCalculationConfig

        beforeEach {
            ctx = createEsttServiceTestContext()
            esttService = ctx.esttService
            seasonRepository = ctx.seasonRepository
            historyFlightRepository = ctx.historyFlightRepository
            meterRegistry = ctx.meterRegistry
            config = ctx.config
        }

        describe("parseFlightDate") {
            it("should parse valid date") {
                val date = esttService.parseFlightDate("211231")
                date shouldBe LocalDate.of(2021, 12, 31)
            }

            it("should throw exception for invalid format") {
                shouldThrow<InvalidFlightDateException> {
                    esttService.parseFlightDate("invalid")
                }
            }

            it("should throw exception for wrong length") {
                shouldThrow<InvalidFlightDateException> {
                    esttService.parseFlightDate("2112")
                }
            }

            it("should parse century boundary dates") {
                esttService.parseFlightDate("000101") shouldBe LocalDate.of(2000, 1, 1)
                esttService.parseFlightDate("991231") shouldBe LocalDate.of(2099, 12, 31)
                esttService.parseFlightDate("240229") shouldBe LocalDate.of(2024, 2, 29)
            }

            it("should reject invalid leap day") {
                shouldThrow<InvalidFlightDateException> {
                    esttService.parseFlightDate("230229")
                }
            }
        }

        describe("getActiveSeason") {
            it("should return season successfully") {
                val season = FlightSeason(
                    1L,
                    "2021-Summer",
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 10, 30),
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
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5", any()) } returns seasonalFlight

                val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))

                result.isSuccess.shouldBeTrue()
                result.getOrNull() shouldBe seasonalFlight
            }

            it("should return null when operation day doesn't match") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "246",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5", any()) } returns seasonalFlight

                val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))

                result.isSuccess.shouldBeTrue()
                result.getOrNull().shouldBeNull()
            }

            it("should return null when not found") {
                every { seasonRepository.getSeasonalArrivalFlight("XX9999", "5", any()) } returns null

                val result = esttService.getSeasonalFlight("XX9999", LocalDate.of(2021, 12, 31))

                result.isSuccess.shouldBeTrue()
                result.getOrNull().shouldBeNull()
            }

            it("should return failure when repository throws exception") {
                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5", any()) } throws
                    RuntimeException("Database connection failed")

                val result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))

                result.isFailure.shouldBeTrue()
            }
        }

        describe("calculate") {
            it("should calculate with sufficient history") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "1234567",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )
                val historyFlights = createHistoryFlights(25, LocalDate.of(2021, 12, 31))

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5", any()) } returns seasonalFlight
                historyFlightRepository.mockArrivalFlightPages(historyFlights)

                val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

                result.isSuccess.shouldBeTrue()
                val response = result.getOrNull()
                response.shouldNotBeNull()
                response.flightNumber shouldBe "MU9941"
                (response.source == EstimateSource.HISTORY).shouldBeTrue()
                (response.source == EstimateSource.SEASONAL).shouldBeFalse()
                response.source shouldBe EstimateSource.HISTORY
                response.flyingTime!! shouldBeGreaterThan 0

                meterRegistry.counter("estt.calculation.source", "source", "history").count() shouldBe 1.0
                meterRegistry.counter("estt.calculation.success", "source", "history").count() shouldBe 1.0
                meterRegistry.timer(
                    "estt.calculation.time",
                    "source",
                    "history",
                    "result",
                    "success",
                ).count() shouldBe 1
            }

            it("should use seasonal time with insufficient history") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "1234567",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )
                val historyFlights = createHistoryFlights(5, LocalDate.of(2021, 12, 31))

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5", any()) } returns seasonalFlight
                historyFlightRepository.mockArrivalFlightPages(historyFlights)

                val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

                result.isSuccess.shouldBeTrue()
                val response = result.getOrNull()
                response.shouldNotBeNull()
                response.flightNumber shouldBe "MU9941"
                response.flyingTime shouldBe 90L
                (response.source == EstimateSource.SEASONAL).shouldBeTrue()

                meterRegistry.counter("estt.calculation.source", "source", "schedule").count() shouldBe 1.0
                meterRegistry.counter("estt.calculation.success", "source", "schedule").count() shouldBe 1.0
            }

            it("should handle no seasonal flight") {
                every { seasonRepository.getSeasonalArrivalFlight("XX9999", "5", any()) } returns null

                val result = esttService.calculate("XX9999", LocalDate.of(2021, 12, 31))

                result.isSuccess.shouldBeTrue()
                val response = result.getOrNull()
                response.shouldNotBeNull()
                response.flightNumber shouldBe "XX9999"
                response.flyingTime shouldBe null
                response.source shouldBe EstimateSource.NONE
                (response.source == EstimateSource.SEASONAL).shouldBeFalse()
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
                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5", any()) } throws
                    RuntimeException("Database connection failed")

                val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

                result.isFailure.shouldBeTrue()
                meterRegistry.counter("estt.calculation.failure", "error", "RuntimeException").count() shouldBe 1.0
            }
        }

        describe("getPaginatedHistoryFlights") {
            it("should return paginated list successfully") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "1234567",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )
                val historyFlights = createHistoryFlights(10, LocalDate.of(2021, 12, 31))

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5", any()) } returns seasonalFlight
                every {
                    historyFlightRepository.getArrivalFlightPage(
                        "MU9941",
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns historyFlights

                val result = esttService.getPaginatedHistoryFlights("MU9941", LocalDate.of(2021, 12, 31), 2, 3)

                result.isSuccess.shouldBeTrue()
                val paginated = result.getOrNull()
                paginated.shouldNotBeNull()
                paginated.items.size shouldBe 3 // offset 2, limit 3
                paginated.totalFiltered shouldBe 10 // scans entire window to provide accurate totals
                paginated.hasMore shouldBe true // offset 2 + limit 3 = 5, which is < total
            }

            it("should stop fetching once hasMore is detected") {
                val seasonalFlight = SeasonalFlight(
                    "MU0001",
                    "1234567",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )
                val flightDate = LocalDate.of(2021, 12, 31)
                val historyFlights = createHistoryFlights(6, flightDate)

                every { seasonRepository.getSeasonalArrivalFlight("MU0001", "5", any()) } returns seasonalFlight
                every {
                    historyFlightRepository.getArrivalFlightPage("MU0001", any(), any(), any(), any())
                } returns historyFlights

                val result = esttService.getPaginatedHistoryFlights("MU0001", flightDate, 0, 5)

                result.isSuccess.shouldBeTrue()
                val paginated = result.getOrNull()
                paginated.shouldNotBeNull()
                paginated.items.size shouldBe 5
                paginated.totalFiltered shouldBe 6
                paginated.hasMore.shouldBeTrue()

                verify(exactly = 1) {
                    historyFlightRepository.getArrivalFlightPage("MU0001", any(), any(), any(), any())
                }
            }

            it("should respect maxHistoryRows even when most records are filtered out") {
                val seasonalFlight = SeasonalFlight(
                    "MU0002",
                    "1234567",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )
                val flightDate = LocalDate.of(2021, 12, 31)
                val unqualifiedFlights = createHistoryFlightsWithDelay(10, flightDate, delayMinutes = 400)

                every { seasonRepository.getSeasonalArrivalFlight("MU0002", "5", any()) } returns seasonalFlight
                every {
                    historyFlightRepository.getArrivalFlightPage("MU0002", any(), any(), any(), any())
                } returns unqualifiedFlights

                val constrainedConfig = EsttCalculationConfig(
                    maxScheduleDeviation = 120,
                    maxFlyingTimeDeviation = 120,
                    minHistoryFlight = 20,
                    dateFormat = "yyMMdd",
                    maxHistoryRows = 10,
                    minFlyingTime = 30,
                    maxFlyingTime = 600,
                )
                val constrainedService = createEsttServiceWithConfig(
                    seasonRepository,
                    historyFlightRepository,
                    meterRegistry,
                    constrainedConfig,
                )

                val result = constrainedService.getPaginatedHistoryFlights("MU0002", flightDate, 0, 5)

                result.isSuccess.shouldBeTrue()
                val paginated = result.getOrNull()
                paginated.shouldNotBeNull()
                paginated.items.isEmpty().shouldBeTrue()
                paginated.totalFiltered shouldBe 0
                paginated.hasMore.shouldBeFalse()

                verify(exactly = 1) {
                    historyFlightRepository.getArrivalFlightPage("MU0002", any(), any(), any(), any())
                }
            }

            it("should return empty list when seasonal flight not found") {
                every { seasonRepository.getSeasonalArrivalFlight("XX9999", "5", any()) } returns null

                val result = esttService.getPaginatedHistoryFlights("XX9999", LocalDate.of(2021, 12, 31), 0, 10)

                result.isSuccess.shouldBeTrue()
                val response = result.getOrNull()!!
                response.items.isEmpty().shouldBeTrue()
                response.totalFiltered shouldBe 0
            }

            it("should return failure when repository throws exception") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "1234567",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )

                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5", any()) } returns seasonalFlight
                every {
                    historyFlightRepository.getArrivalFlightPage(any(), any(), any(), any(), any())
                } throws RuntimeException("Database error")

                val result = esttService.getPaginatedHistoryFlights("MU9941", LocalDate.of(2021, 12, 31), 0, 10)

                result.isFailure.shouldBeTrue()
            }
        }

        describe("caching behavior") {
            it("should not cache failures for seasonal flight lookup") {
                val seasonalFlight = SeasonalFlight(
                    "MU9941",
                    "1234567",
                    90L,
                    LocalDate.of(2021, 3, 28),
                    LocalDate.of(2021, 12, 31),
                )
                var invocation = 0
                every { seasonRepository.getSeasonalArrivalFlight("MU9941", "5", any()) } answers {
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
        }

        describe("configuration validation") {
            it("should reject invalid max schedule deviation") {
                shouldThrow<IllegalArgumentException> {
                    EsttCalculationConfig(
                        maxScheduleDeviation = 0,
                        maxFlyingTimeDeviation = 120,
                        minHistoryFlight = 20,
                        dateFormat = "yyMMdd",
                        maxHistoryRows = 300,
                        minFlyingTime = 30,
                        maxFlyingTime = 600,
                    )
                }
            }

            it("should reject max history rows that overflow extended scan multiplier") {
                shouldThrow<IllegalArgumentException> {
                    EsttCalculationConfig(
                        maxScheduleDeviation = 120,
                        maxFlyingTimeDeviation = 120,
                        minHistoryFlight = 20,
                        dateFormat = "yyMMdd",
                        maxHistoryRows = Int.MAX_VALUE,
                        minFlyingTime = 30,
                        maxFlyingTime = 600,
                    )
                }
            }
        }
    })
