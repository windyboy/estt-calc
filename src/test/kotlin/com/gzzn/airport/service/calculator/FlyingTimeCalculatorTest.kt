package com.gzzn.airport.service.calculator

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.Confidence
import com.gzzn.airport.model.EstimateSource
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDate
import java.time.LocalDateTime

class FlyingTimeCalculatorTest :
    DescribeSpec({

        lateinit var meterRegistry: MeterRegistry
        lateinit var config: EsttCalculationConfig
        lateinit var calculator: FlyingTimeCalculator

        beforeEach {
            meterRegistry = SimpleMeterRegistry()
            config = EsttCalculationConfig(
                maxScheduleDeviation = 120,
                maxFlyingTimeDeviation = 120,
                minHistoryFlight = 20,
                dateFormat = "yyMMdd",
                historyStartOffsetDays = 60L,
                maxHistoryRows = 300,
            )
            calculator = FlyingTimeCalculator(meterRegistry, config)
        }

        describe("calculate") {
            it("uses historical median when enough qualified flights exist") {
                val seasonalFlight = SeasonalFlight(
                    flightNumber = "MU1001",
                    operationDays = "1234567",
                    flyingTime = 100,
                    seasonStart = LocalDate.of(2024, 3, 31),
                    seasonEnd = LocalDate.of(2024, 10, 26),
                )
                val historyFlights = buildHistoryFlights(
                    baseDate = LocalDate.of(2024, 6, 1),
                    count = config.minHistoryFlight,
                    durationGenerator = { index -> 95L + (index % 3) },
                )

                val result = calculator.calculate(seasonalFlight, seasonalFlight.flightNumber, historyFlights)

                result.historyUsed.shouldBeTrue()
                result.source shouldBe EstimateSource.HISTORY
                result.confidence shouldBe Confidence.HIGH
                result.sampleSize shouldBe config.minHistoryFlight
                result.flyingTime shouldBe 96L

                meterRegistry.counter("estt.calculation.source", "source", "history").count() shouldBe 1.0
            }

            it("falls back to seasonal flying time when history is insufficient") {
                val seasonalFlight = SeasonalFlight(
                    flightNumber = "MU1002",
                    operationDays = "1234567",
                    flyingTime = 105,
                    seasonStart = LocalDate.of(2024, 3, 31),
                    seasonEnd = LocalDate.of(2024, 10, 26),
                )
                val historyFlights = buildHistoryFlights(
                    baseDate = LocalDate.of(2024, 6, 1),
                    count = config.minHistoryFlight / 2,
                    durationGenerator = { 110L },
                )

                val result = calculator.calculate(seasonalFlight, seasonalFlight.flightNumber, historyFlights)

                result.historyUsed.shouldBeFalse()
                result.source shouldBe EstimateSource.SEASONAL
                result.flyingTime shouldBe seasonalFlight.flyingTime
                result.message shouldBe "Using seasonal flight flying time due to insufficient historical data"

                meterRegistry.counter("estt.calculation.source", "source", "schedule").count() shouldBe 1.0
            }

            it("returns no estimate when history is insufficient and seasonal flying time is null") {
                val seasonalFlight = SeasonalFlight(
                    flightNumber = "MU1003",
                    operationDays = "1234567",
                    flyingTime = null,
                    seasonStart = LocalDate.of(2024, 3, 31),
                    seasonEnd = LocalDate.of(2024, 10, 26),
                )
                val historyFlights = buildHistoryFlights(
                    baseDate = LocalDate.of(2024, 6, 1),
                    count = config.minHistoryFlight / 2,
                    durationGenerator = { 100L },
                )

                val result = calculator.calculate(seasonalFlight, seasonalFlight.flightNumber, historyFlights)

                result.source shouldBe EstimateSource.NONE
                result.flyingTime shouldBe null
                result.confidence shouldBe Confidence.NONE
            }

            it("rejects seasonal flights with non-positive flying time when used as fallback") {
                val invalidSeasonal = SeasonalFlight(
                    flightNumber = "MU0000",
                    operationDays = "1234567",
                    flyingTime = 0,
                    seasonStart = LocalDate.of(2024, 1, 1),
                    seasonEnd = LocalDate.of(2024, 10, 26),
                )

                val result = calculator.calculate(invalidSeasonal, invalidSeasonal.flightNumber, emptyList())

                result.source shouldBe EstimateSource.NONE
                result.flyingTime shouldBe null
            }

            it("accepts early arrivals within schedule deviation filter") {
                val seasonalFlight = SeasonalFlight(
                    flightNumber = "MU1004",
                    operationDays = "1234567",
                    flyingTime = 100,
                    seasonStart = LocalDate.of(2024, 3, 31),
                    seasonEnd = LocalDate.of(2024, 10, 26),
                )
                val date = LocalDate.of(2024, 6, 1)
                val scheduled = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0)
                val earlyArrival = HistoricalFlight(
                    flightDate = date,
                    previousDepartureTime = scheduled.minusMinutes(100),
                    actualTime = scheduled.minusMinutes(30),
                    scheduledTime = scheduled,
                )
                val historyFlights = List(config.minHistoryFlight) { earlyArrival }

                val result = calculator.calculate(seasonalFlight, seasonalFlight.flightNumber, historyFlights)

                result.source shouldBe EstimateSource.HISTORY
            }

            it("keeps flights exactly at max late schedule deviation") {
                val seasonalFlight = SeasonalFlight(
                    flightNumber = "MU1005",
                    operationDays = "1234567",
                    flyingTime = 100,
                    seasonStart = LocalDate.of(2024, 3, 31),
                    seasonEnd = LocalDate.of(2024, 10, 26),
                )
                val date = LocalDate.of(2024, 6, 1)
                val scheduled = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0)
                val thresholdLateArrival = HistoricalFlight(
                    flightDate = date,
                    previousDepartureTime = scheduled.plusMinutes(config.maxScheduleDeviation.toLong()).minusMinutes(100),
                    actualTime = scheduled.plusMinutes(config.maxScheduleDeviation.toLong()),
                    scheduledTime = scheduled,
                )
                val historyFlights = List(config.minHistoryFlight) { thresholdLateArrival }

                val result = calculator.calculate(seasonalFlight, seasonalFlight.flightNumber, historyFlights)

                result.source shouldBe EstimateSource.HISTORY
                result.sampleSize shouldBe config.minHistoryFlight
                result.flyingTime shouldBe 100L
            }

            it("rejects flights one minute beyond max late schedule deviation before counting history") {
                val seasonalFlight = SeasonalFlight(
                    flightNumber = "MU1006",
                    operationDays = "1234567",
                    flyingTime = 100,
                    seasonStart = LocalDate.of(2024, 3, 31),
                    seasonEnd = LocalDate.of(2024, 10, 26),
                )
                val qualifiedFlights = buildHistoryFlights(
                    baseDate = LocalDate.of(2024, 6, 1),
                    count = config.minHistoryFlight - 1,
                    durationGenerator = { 100L },
                )
                val date = LocalDate.of(2024, 5, 1)
                val scheduled = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0)
                val tooLateFlight = HistoricalFlight(
                    flightDate = date,
                    previousDepartureTime = scheduled.plusMinutes(config.maxScheduleDeviation.toLong() + 1).minusMinutes(100),
                    actualTime = scheduled.plusMinutes(config.maxScheduleDeviation.toLong() + 1),
                    scheduledTime = scheduled,
                )

                val result = calculator.calculate(seasonalFlight, seasonalFlight.flightNumber, qualifiedFlights + tooLateFlight)

                result.source shouldBe EstimateSource.SEASONAL
                result.sampleSize shouldBe 0
                result.flyingTime shouldBe seasonalFlight.flyingTime
            }

            it("uses truncated integer average for even-sized median") {
                val seasonalFlight = SeasonalFlight(
                    flightNumber = "MU1007",
                    operationDays = "1234567",
                    flyingTime = 100,
                    seasonStart = LocalDate.of(2024, 3, 31),
                    seasonEnd = LocalDate.of(2024, 10, 26),
                )
                val historyFlights = buildHistoryFlights(
                    baseDate = LocalDate.of(2024, 6, 1),
                    count = config.minHistoryFlight,
                    durationGenerator = { index -> if (index < config.minHistoryFlight / 2) 95L else 96L },
                )

                val result = calculator.calculate(seasonalFlight, seasonalFlight.flightNumber, historyFlights)

                result.source shouldBe EstimateSource.HISTORY
                result.flyingTime shouldBe 95L
            }
        }
    })

private fun buildHistoryFlights(baseDate: LocalDate, count: Int, durationGenerator: (index: Int) -> Long): List<HistoricalFlight> =
    (0 until count).map { index ->
        val date = baseDate.minusDays(index.toLong())
        val start = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 10, 0)
        val duration = durationGenerator(index)
        HistoricalFlight(
            flightDate = date,
            previousDepartureTime = start.minusHours(1),
            actualTime = start.minusHours(1).plusMinutes(duration),
            scheduledTime = start,
        )
    }
