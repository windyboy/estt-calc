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
            response.body().error shouldBe "Bad Request"
            response.body().message shouldBe "Test error message"
        }

        it("should handle null message") {
            val request = HttpRequest.GET<Any>("/test")
            val exception = IllegalArgumentException()

            val response = handler.handle(request, exception)

            response.status shouldBe HttpStatus.BAD_REQUEST
            response.body().status shouldBe 400
            response.body().error shouldBe "Bad Request"
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
            response.body().error shouldBe "Internal Server Error"
            response.body().message shouldBe "An unexpected error occurred"
        }

        it("should handle different exception types") {
            val request = HttpRequest.GET<Any>("/test")
            val exception = NullPointerException("NPE occurred")

            val response = handler.handle(request, exception)

            response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            response.body().status shouldBe 500
            response.body().error shouldBe "Internal Server Error"
            response.body().message shouldBe "An unexpected error occurred"
        }
    }

    describe("ErrorResponse") {
        it("should create error response with all fields") {
            val errorResponse = ErrorResponse(404, "Not Found", "Resource not found")

            errorResponse.status shouldBe 404
            errorResponse.error shouldBe "Not Found"
            errorResponse.message shouldBe "Resource not found"
        }

        it("should have proper equality") {
            val error1 = ErrorResponse(500, "Error", "Message")
            val error2 = ErrorResponse(500, "Error", "Message")
            val error3 = ErrorResponse(400, "Bad Request", "Different")

            error1 shouldBe error2
            error1 shouldNotBe error3
        }
    }
})

