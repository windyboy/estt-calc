package com.gzzn.airport.exception

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus

class GlobalExceptionHandlerTest : DescribeSpec({

    describe("IllegalArgumentExceptionHandler") {
        val handler = IllegalArgumentExceptionHandler()

        it("should return 400 with error message") {
            val request = HttpRequest.GET<Any>("/test")
            val exception = IllegalArgumentException("Test error message")

            val response = handler.handle(request, exception)

            response.status shouldBe HttpStatus.BAD_REQUEST
            response.body().status shouldBe 400
            response.body().error shouldBe "VALIDATION_ERROR"
            response.body().message shouldBe "Test error message"
        }

        it("should handle null message") {
            val request = HttpRequest.GET<Any>("/test")
            val exception = IllegalArgumentException()

            val response = handler.handle(request, exception)

            response.status shouldBe HttpStatus.BAD_REQUEST
            response.body().status shouldBe 400
            response.body().error shouldBe "VALIDATION_ERROR"
            response.body().message shouldBe "Invalid request parameters"
        }
    }

    describe("GenericExceptionHandler") {
        val handler = GenericExceptionHandler()

        it("should return 500 for runtime exception") {
            val request = HttpRequest.GET<Any>("/test")
            val exception = RuntimeException("Unexpected error")

            val response = handler.handle(request, exception)

            response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            response.body().status shouldBe 500
            response.body().error shouldBe "INTERNAL_ERROR"
            response.body().message shouldBe "An unexpected error occurred"
        }

        it("should handle different exception types") {
            val request = HttpRequest.GET<Any>("/test")
            val exception = NullPointerException("NPE occurred")

            val response = handler.handle(request, exception)

            response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            response.body().status shouldBe 500
            response.body().error shouldBe "INTERNAL_ERROR"
            response.body().message shouldBe "An unexpected error occurred"
        }
    }

    describe("ErrorResponse") {
        it("should create error response with all fields") {
            val timestamp = java.time.Instant.now()
            val errorResponse = ErrorResponse(404, "NOT_FOUND", "Resource not found", timestamp)

            errorResponse.status shouldBe 404
            errorResponse.error shouldBe "NOT_FOUND"
            errorResponse.message shouldBe "Resource not found"
            errorResponse.timestamp shouldBe timestamp
        }

        it("should use ErrorCode helper") {
            val errorResponse = ErrorCode.NOT_FOUND.toErrorResponse("Resource not found")

            errorResponse.status shouldBe 404
            errorResponse.error shouldBe "NOT_FOUND"
            errorResponse.message shouldBe "Resource not found"
            errorResponse.timestamp shouldNotBe null
        }
    }
})
