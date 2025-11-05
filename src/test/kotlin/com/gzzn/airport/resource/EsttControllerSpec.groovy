package com.gzzn.airport.resource

import com.gzzn.airport.exception.ErrorResponse
import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.model.SeasonalFlight
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

import java.time.LocalDate

@MicronautTest
class EsttControllerSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void "test GET /estt/season returns season"() {
        when:
        def response = client.toBlocking().exchange(
                HttpRequest.GET("/estt/season"),
                FlightSeason
        )

        then:
        response.status == HttpStatus.OK
        response.body() != null || response.status == HttpStatus.NOT_FOUND
    }

    void "test GET /estt/flyTime with valid flight number"() {
        when:
        def response = client.toBlocking().exchange(
                HttpRequest.GET("/estt/flyTime/MU9941/211231"),
                FlyingTimeResponse
        )

        then:
        response.status == HttpStatus.OK
        response.body() != null
        response.body().flightNumber == "MU9941"
    }

    void "test GET /estt/flyTime with invalid date format returns 400"() {
        when:
        client.toBlocking().exchange(
                HttpRequest.GET("/estt/flyTime/MU9941/invalid"),
                FlyingTimeResponse
        )

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    void "test GET /estt/flyTime with invalid flight number format returns 400"() {
        when:
        client.toBlocking().exchange(
                HttpRequest.GET("/estt/flyTime/INVALID123456/211231"),
                FlyingTimeResponse
        )

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    void "test GET /estt/flyTime with single letter flight number returns 400"() {
        when:
        client.toBlocking().exchange(
                HttpRequest.GET("/estt/flyTime/A123/211231"),
                FlyingTimeResponse
        )

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    void "test GET /estt/flyTime with lowercase flight number is accepted"() {
        when:
        def response = client.toBlocking().exchange(
                HttpRequest.GET("/estt/flyTime/mu9941/211231"),
                FlyingTimeResponse
        )

        then:
        response.status == HttpStatus.OK
        response.body().flightNumber == "MU9941"
    }

    void "test GET /estt/seasonal with valid parameters"() {
        when:
        def response = client.toBlocking().exchange(
                HttpRequest.GET("/estt/seasonal/MU9941/211231"),
                SeasonalFlight
        )

        then:
        response.status == HttpStatus.OK || response.status == HttpStatus.NOT_FOUND
    }

    void "test GET /estt/seasonal with invalid flight number returns 400"() {
        when:
        client.toBlocking().exchange(
                HttpRequest.GET("/estt/seasonal/12345/211231"),
                SeasonalFlight
        )

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    void "test GET /estt/history returns list"() {
        when:
        def response = client.toBlocking().exchange(
                HttpRequest.GET("/estt/history/MU9941/211231"),
                List
        )

        then:
        response.status == HttpStatus.OK
        response.body() != null
        response.body() instanceof List
    }

    void "test GET /estt/history with invalid date returns 400"() {
        when:
        client.toBlocking().exchange(
                HttpRequest.GET("/estt/history/MU9941/99999999"),
                List
        )

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.BAD_REQUEST
    }

    void "test health endpoint is accessible"() {
        when:
        def response = client.toBlocking().exchange(
                HttpRequest.GET("/health"),
                Map
        )

        then:
        response.status == HttpStatus.OK
        response.body() != null
    }
}

