package com.gzzn.airport.repository

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.time.LocalDate

@MicronautTest
class RepositorySpec extends Specification {

    @Inject
    SeasonRepository seasonRepository

    @Inject
    HistoryFlightRepository historyFlightRepository

    void "test SeasonRepository is injected"() {
        expect:
        seasonRepository != null
    }

    void "test HistoryFlightRepository is injected"() {
        expect:
        historyFlightRepository != null
    }

    void "test getFlightSeasonByTag with active season"() {
        when:
        def season = seasonRepository.getFlightSeasonByTag(true)

        then:
        // May or may not have data, but should not throw exception
        notThrown(Exception)
    }

    void "test getSeasonalArrivalFlight with valid parameters"() {
        when:
        def flight = seasonRepository.getSeasonalArrivalFlight("MU9941", "%1%")

        then:
        notThrown(Exception)
    }

    void "test getArrivalFlight with date range"() {
        given:
        def startDate = LocalDate.now().minusMonths(3)
        def endDate = LocalDate.now()

        when:
        def flights = historyFlightRepository.getArrivalFlight("MU9941", startDate, endDate, 100)

        then:
        notThrown(Exception)
        flights != null
    }

    void "test getArrivalFlight respects max rows limit"() {
        given:
        def startDate = LocalDate.now().minusYears(5)
        def endDate = LocalDate.now()

        when:
        def flights = historyFlightRepository.getArrivalFlight("MU9941", startDate, endDate, 10)

        then:
        notThrown(Exception)
        flights != null
        flights.size() <= 10
    }
}

