package com.gzzn.airport.service.calculator

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDate
import java.time.LocalDateTime

class FlyingTimeCalculatorTest : DescribeSpec({

    lateinit var meterRegistry: MeterRegistry
    lateinit var config: EsttCalculationConfig
    lateinit var calculator: FlyingTimeCalculator

    beforeEach {
        meterRegistry = SimpleMeterRegistry()
        config = EsttCalculationConfig(
            maxHistoryDelay = 120,
            minHistoryFlight = 20,
            dateFormat = "yyMMdd",
            historyStartOffsetDays = 60L,
            maxHistoryRows = 300,
        )
        calculator = FlyingTimeCalculator(meterRegistry, config)
    }

    describe("calculate") {
        it("uses historical average when enough qualified flights exist") {
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
                durationGenerator = { 95L + (it % 3) }, // 95, 96, 97 repeating
            )

            val result = calculator.calculate(seasonalFlight, seasonalFlight.flightNumber, historyFlights)

            result.historyUsed.shouldBeTrue()
            result.message shouldBe "Calculated from ${config.minHistoryFlight} historical flights (${historyFlights.size} available)"
            result.flyingTime.toDouble().shouldBeBetween(95.0, 97.0, 0.5)

            meterRegistry.counter("estt.calculation.source", "source", "history").count() shouldBe 1.0
            val deviation = Math.abs(result.flyingTime - seasonalFlight.flyingTime).toString()
            meterRegistry.counter("estt.calculation.accuracy", "accuracy", deviation).count() shouldBe 1.0
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
            result.flyingTime shouldBe seasonalFlight.flyingTime
            result.message shouldBe "Using seasonal flight flying time due to insufficient historical data"

            meterRegistry.counter("estt.calculation.source", "source", "schedule").count() shouldBe 1.0
        }

        it("rejects seasonal flights with non-positive flying time") {
            val invalidSeasonal = SeasonalFlight(
                flightNumber = "MU0000",
                operationDays = "1234567",
                flyingTime = 0,
                seasonStart = LocalDate.of(2024, 1, 1),
                seasonEnd = LocalDate.of(2024, 10, 26),
            )

            val exception = shouldThrow<IllegalArgumentException> {
                calculator.calculate(invalidSeasonal, invalidSeasonal.flightNumber, emptyList())
            }

            exception.message shouldNotBe null
        }
    }
})

private fun buildHistoryFlights(
    baseDate: LocalDate,
    count: Int,
    durationGenerator: (index: Int) -> Long,
): List<HistoricalFlight> {
    return (0 until count).map { index ->
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
}

