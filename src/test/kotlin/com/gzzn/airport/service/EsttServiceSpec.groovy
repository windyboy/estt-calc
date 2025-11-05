package com.gzzn.airport.service

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.repository.SeasonRepository
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Specification

import java.time.LocalDate
import java.time.LocalDateTime

@MicronautTest
class EsttServiceSpec extends Specification {

    EsttService esttService
    SeasonRepository seasonRepository = Mock()
    HistoryFlightRepository historyFlightRepository = Mock()

    void setup() {
        esttService = new EsttService(
                seasonRepository,
                historyFlightRepository,
                120,  // maxHistoryDelay
                20,   // minHistoryFlight
                'yyMMdd',  // dateFormat
                60L,  // historyStartOffsetDays
                300   // maxHistoryRows
        )
        esttService.init()
    }

    void "test parseFlightDate with valid date"() {
        when:
        def date = esttService.parseFlightDate("211231")

        then:
        date == LocalDate.of(2021, 12, 31)
    }

    void "test parseFlightDate with invalid format throws exception"() {
        when:
        esttService.parseFlightDate("invalid")

        then:
        thrown(IllegalArgumentException)
    }

    void "test parseFlightDate with wrong length throws exception"() {
        when:
        esttService.parseFlightDate("2112")

        then:
        thrown(IllegalArgumentException)
    }

    void "test getActiveSeason returns season"() {
        given:
        def season = new FlightSeason(
                1L,
                "2021-Summer",
                LocalDate.of(2021, 3, 28),
                LocalDate.of(2021, 10, 30)
        )

        when:
        def result = esttService.getActiveSeason()

        then:
        1 * seasonRepository.getFlightSeasonByTag(true) >> season
        result.isSuccess()
        result.get() == season
    }

    void "test getActiveSeason handles exception gracefully"() {
        when:
        def result = esttService.getActiveSeason()

        then:
        1 * seasonRepository.getFlightSeasonByTag(true) >> { throw new RuntimeException("DB error") }
        result.isFailure()
    }

    void "test getSeasonalFlight returns flight when found"() {
        given:
        def seasonalFlight = new SeasonalFlight(
                "MU9941",
                "1234567",  // operates all days
                90L,
                LocalDate.of(2021, 3, 28)
        )

        when:
        def result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") >> seasonalFlight
        result.isSuccess()
        result.get() == seasonalFlight
    }

    void "test getSeasonalFlight returns null when operation day doesn't match"() {
        given:
        def seasonalFlight = new SeasonalFlight(
                "MU9941",
                "246",  // only operates on Tue, Thu, Sat
                90L,
                LocalDate.of(2021, 3, 28)
        )

        when:
        // 2021-12-31 is Friday (day 5)
        def result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") >> seasonalFlight
        result.isSuccess()
        result.get() == null  // Filtered out because operation days doesn't contain "5"
    }

    void "test getSeasonalFlight returns null when not found"() {
        when:
        def result = esttService.getSeasonalFlight("XX9999", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("XX9999", "%5%") >> null
        result.isSuccess()
        result.get() == null
    }

    void "test calculate with sufficient history"() {
        given:
        def seasonalFlight = new SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28)
        )
        def historyFlights = createHistoryFlights(25, LocalDate.of(2021, 12, 31))

        when:
        def result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") >> seasonalFlight
        1 * historyFlightRepository.getArrivalFlight(
                "MU9941",
                _ as LocalDate,
                LocalDate.of(2021, 12, 31),
                300
        ) >> historyFlights

        result.isSuccess()
        def response = result.get()
        response.flightNumber == "MU9941"
        response.history == true
        response.seasonal == true
        response.flyingTime > 0
    }

    void "test calculate with insufficient history uses seasonal time"() {
        given:
        def seasonalFlight = new SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28)
        )
        def historyFlights = createHistoryFlights(5, LocalDate.of(2021, 12, 31))

        when:
        def result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") >> seasonalFlight
        1 * historyFlightRepository.getArrivalFlight(
                "MU9941",
                _ as LocalDate,
                LocalDate.of(2021, 12, 31),
                300
        ) >> historyFlights

        result.isSuccess()
        def response = result.get()
        response.flightNumber == "MU9941"
        response.flyingTime == 90L
        response.seasonal == true
    }

    void "test calculate with no seasonal flight"() {
        when:
        def result = esttService.calculate("XX9999", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("XX9999", "%5%") >> null
        0 * historyFlightRepository._

        result.isSuccess()
        def response = result.get()
        response.flightNumber == "XX9999"
        response.flyingTime == 0
        response.seasonal == false
    }

    void "test calculate with empty flight number throws exception"() {
        when:
        esttService.calculate("", LocalDate.now())

        then:
        thrown(IllegalArgumentException)
    }

    void "test calculate with blank flight number throws exception"() {
        when:
        esttService.calculate("   ", LocalDate.now())

        then:
        thrown(IllegalArgumentException)
    }

    void "test getHistoryFlights returns empty list when seasonal flight not found"() {
        when:
        def result = esttService.getHistoryFlights("XX9999", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("XX9999", "%5%") >> null
        result.isSuccess()
        result.get() == []
    }

    void "test getSeasonalFlight returns failure when repository throws exception"() {
        when:
        def result = esttService.getSeasonalFlight("MU9941", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") >> { throw new RuntimeException("Database connection failed") }
        result.isFailure()
    }

    void "test getHistoryFlights returns failure when repository throws exception"() {
        given:
        def seasonalFlight = new SeasonalFlight(
                "MU9941",
                "1234567",
                90L,
                LocalDate.of(2021, 3, 28)
        )

        when:
        def result = esttService.getHistoryFlights("MU9941", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") >> seasonalFlight
        1 * historyFlightRepository.getArrivalFlight(_, _, _, _) >> { throw new RuntimeException("Database error") }
        result.isFailure()
    }

    void "test calculate returns failure when repository throws exception"() {
        when:
        def result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))

        then:
        1 * seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") >> { throw new RuntimeException("Database connection failed") }
        result.isFailure()
    }

    private List<HistoricalFlight> createHistoryFlights(int count, LocalDate baseDate) {
        def flights = []
        for (int i = 0; i < count; i++) {
            def date = baseDate.minusDays(i * 7)  // Weekly flights
            flights.add(new HistoricalFlight(
                    date,
                    LocalDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), 10, 0),
                    LocalDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), 11, 30),
                    LocalDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), 11, 0)
            ))
        }
        return flights
    }
}

