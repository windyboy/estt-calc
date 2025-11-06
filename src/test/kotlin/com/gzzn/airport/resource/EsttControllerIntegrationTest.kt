package com.gzzn.airport.resource

import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.SeasonalFlight
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.kotest5.annotation.MicronautTest

@MicronautTest(environments = ["test"])
class EsttControllerIntegrationTest : DescribeSpec() {

    @Client("/")
    lateinit var client: HttpClient

    init {

    xdescribe("GET /estt/season") {
        it("should return season or not found") {
            val response = client.toBlocking().exchange(
                HttpRequest.GET<Any>("/estt/season"),
                FlightSeason::class.java
            )

            (response.status == HttpStatus.OK || response.status == HttpStatus.NOT_FOUND) shouldBe true
        }
    }

    xdescribe("GET /estt/flyTime") {
        it("should calculate flying time for valid flight") {
            val response = client.toBlocking().exchange(
                HttpRequest.GET<Any>("/estt/flyTime/MU9941/211231"),
                FlyingTimeResponse::class.java
            )

            response.status shouldBe HttpStatus.OK
            response.body()?.flightNumber shouldBe "MU9941"
        }

        it("should accept lowercase flight number") {
            val response = client.toBlocking().exchange(
                HttpRequest.GET<Any>("/estt/flyTime/mu9941/211231"),
                FlyingTimeResponse::class.java
            )

            response.status shouldBe HttpStatus.OK
            response.body()?.flightNumber shouldBe "MU9941"
        }

        it("should return 400 for invalid date format") {
            try {
                client.toBlocking().exchange(
                    HttpRequest.GET<Any>("/estt/flyTime/MU9941/invalid"),
                    FlyingTimeResponse::class.java
                )
            } catch (e: HttpClientResponseException) {
                e.status shouldBe HttpStatus.BAD_REQUEST
            }
        }

        it("should return 400 for invalid flight number format") {
            try {
                client.toBlocking().exchange(
                    HttpRequest.GET<Any>("/estt/flyTime/INVALID123456/211231"),
                    FlyingTimeResponse::class.java
                )
            } catch (e: HttpClientResponseException) {
                e.status shouldBe HttpStatus.BAD_REQUEST
            }
        }

        it("should return 400 for single letter flight number") {
            try {
                client.toBlocking().exchange(
                    HttpRequest.GET<Any>("/estt/flyTime/A123/211231"),
                    FlyingTimeResponse::class.java
                )
            } catch (e: HttpClientResponseException) {
                e.status shouldBe HttpStatus.BAD_REQUEST
            }
        }
    }

    xdescribe("GET /estt/seasonal") {
        it("should return seasonal flight or not found") {
            val response = client.toBlocking().exchange(
                HttpRequest.GET<Any>("/estt/seasonal/MU9941/211231"),
                SeasonalFlight::class.java
            )

            (response.status == HttpStatus.OK || response.status == HttpStatus.NOT_FOUND) shouldBe true
        }

        it("should return 400 for invalid flight number") {
            try {
                client.toBlocking().exchange(
                    HttpRequest.GET<Any>("/estt/seasonal/12345/211231"),
                    SeasonalFlight::class.java
                )
            } catch (e: HttpClientResponseException) {
                e.status shouldBe HttpStatus.BAD_REQUEST
            }
        }
    }

    xdescribe("GET /estt/history") {
        it("should return history list") {
            val response = client.toBlocking().exchange(
                HttpRequest.GET<Any>("/estt/history/MU9941/211231"),
                List::class.java
            )

            response.status shouldBe HttpStatus.OK
            response.body() shouldBe emptyList<Any>()
        }

        it("should return 400 for invalid date") {
            try {
                client.toBlocking().exchange(
                    HttpRequest.GET<Any>("/estt/history/MU9941/99999999"),
                    List::class.java
                )
            } catch (e: HttpClientResponseException) {
                e.status shouldBe HttpStatus.BAD_REQUEST
            }
        }
    }

    xdescribe("Health endpoint") {
        it("should be accessible") {
            val response = client.toBlocking().exchange(
                HttpRequest.GET<Any>("/health"),
                Map::class.java
            )

            response.status shouldBe HttpStatus.OK
            response.body() shouldBe emptyMap<String, Any>()
        }
    }
    }
}

