package com.gzzn.airport.service.history

import com.gzzn.airport.model.HistoricalFlight
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleDeviationTest :
    DescribeSpec({

        val maxScheduleDeviation = 120

        describe("passesScheduleDeviation") {
            it("accepts early arrivals") {
                val date = LocalDate.of(2024, 6, 1)
                val scheduled = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0)
                val earlyArrival = HistoricalFlight(
                    flightDate = date,
                    previousDepartureTime = scheduled.minusMinutes(100),
                    actualTime = scheduled.minusMinutes(30),
                    scheduledTime = scheduled,
                )

                passesScheduleDeviation(earlyArrival, maxScheduleDeviation).shouldBeTrue()
            }

            it("accepts flights exactly at max late schedule deviation") {
                val date = LocalDate.of(2024, 6, 1)
                val scheduled = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0)
                val thresholdLateArrival = HistoricalFlight(
                    flightDate = date,
                    previousDepartureTime = scheduled.plusMinutes(maxScheduleDeviation.toLong()).minusMinutes(100),
                    actualTime = scheduled.plusMinutes(maxScheduleDeviation.toLong()),
                    scheduledTime = scheduled,
                )

                passesScheduleDeviation(thresholdLateArrival, maxScheduleDeviation).shouldBeTrue()
            }

            it("rejects flights one minute beyond max late schedule deviation") {
                val date = LocalDate.of(2024, 6, 1)
                val scheduled = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 12, 0)
                val tooLateFlight = HistoricalFlight(
                    flightDate = date,
                    previousDepartureTime = scheduled.plusMinutes(maxScheduleDeviation.toLong() + 1).minusMinutes(100),
                    actualTime = scheduled.plusMinutes(maxScheduleDeviation.toLong() + 1),
                    scheduledTime = scheduled,
                )

                passesScheduleDeviation(tooLateFlight, maxScheduleDeviation).shouldBeFalse()
            }
        }
    })
