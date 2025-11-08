package com.gzzn.airport.exception

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Produces
import io.micronaut.http.server.exceptions.ExceptionHandler
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Produces
@Singleton
@Requires(classes = [IllegalArgumentException::class, ExceptionHandler::class])
class IllegalArgumentExceptionHandler : ExceptionHandler<IllegalArgumentException, HttpResponse<ErrorResponse>> {
    private val log = LoggerFactory.getLogger(IllegalArgumentExceptionHandler::class.java)

    override fun handle(request: HttpRequest<*>, exception: IllegalArgumentException): HttpResponse<ErrorResponse> {
        log.warn("Bad request: ${exception.message}", exception)
        return HttpResponse.badRequest(
            ErrorCode.VALIDATION_ERROR.toErrorResponse(
                exception.message ?: "Invalid request parameters",
            ),
        )
    }
}

@Produces
@Singleton
@Requires(classes = [Exception::class, ExceptionHandler::class])
class GenericExceptionHandler : ExceptionHandler<Exception, HttpResponse<ErrorResponse>> {
    private val log = LoggerFactory.getLogger(GenericExceptionHandler::class.java)

    override fun handle(request: HttpRequest<*>, exception: Exception): HttpResponse<ErrorResponse> {
        log.error("Internal server error: ${exception.message}", exception)
        return HttpResponse.serverError(
            ErrorCode.INTERNAL_ERROR.toErrorResponse(
                "An unexpected error occurred",
            ),
        )
    }
}

@Serdeable
data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val timestamp: java.time.Instant = java.time.Instant.now(),
)
