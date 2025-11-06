package com.gzzn.airport.repository

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.micronaut.test.extensions.kotest5.annotation.MicronautTest
import jakarta.inject.Inject
import java.time.LocalDate

@MicronautTest(environments = ["test"])
class RepositoryTest : DescribeSpec() {

    @Inject
    lateinit var seasonRepository: SeasonRepository

    @Inject
    lateinit var historyFlightRepository: HistoryFlightRepository

    init {

    xdescribe("Repository Injection - disabled") {
        it("should inject SeasonRepository") {
            seasonRepository.shouldNotBeNull()
        }

        it("should inject HistoryFlightRepository") {
            historyFlightRepository.shouldNotBeNull()
        }
    }

    xdescribe("SeasonRepository Integration") {
        it("should get flight season by tag") {
            shouldNotThrowAny {
                seasonRepository.getFlightSeason(true)
            }
        }

        it("should get seasonal arrival flight") {
            shouldNotThrowAny {
                seasonRepository.getSeasonalArrivalFlight("MU9941", "1")
            }
        }
    }

    xdescribe("HistoryFlightRepository Integration") {
        it("should get arrival flights with date range") {
            val startDate = LocalDate.now().minusMonths(3)
            val endDate = LocalDate.now()

            shouldNotThrowAny {
                val flights = historyFlightRepository.getArrivalFlight("MU9941", startDate, endDate, 100)
                flights.shouldNotBeNull()
            }
        }

        it("should respect max rows limit") {
            val startDate = LocalDate.now().minusYears(5)
            val endDate = LocalDate.now()

            shouldNotThrowAny {
                val flights = historyFlightRepository.getArrivalFlight("MU9941", startDate, endDate, 10)
                flights.shouldNotBeNull()
                flights.size shouldBeLessThanOrEqualTo 10
            }
        }
    }
    }
}

