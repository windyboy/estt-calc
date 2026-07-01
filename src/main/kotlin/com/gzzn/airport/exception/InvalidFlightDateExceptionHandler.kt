package com.gzzn.airport.exception

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Produces
import io.micronaut.http.server.exceptions.ExceptionHandler
import jakarta.inject.Singleton

@Produces
@Singleton
@Requires(classes = [InvalidFlightDateException::class, ExceptionHandler::class])
class InvalidFlightDateExceptionHandler : ExceptionHandler<InvalidFlightDateException, HttpResponse<ErrorResponse>> {

    override fun handle(request: HttpRequest<*>, exception: InvalidFlightDateException): HttpResponse<ErrorResponse> =
        HttpResponse.badRequest(
            ErrorCode.VALIDATION_ERROR.toErrorResponse(
                exception.message ?: "Invalid flight date",
            ),
        )
}
