package com.gzzn.airport.exception

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Produces
import io.micronaut.http.server.exceptions.ExceptionHandler
import jakarta.inject.Singleton

@Produces
@Singleton
@Requires(classes = [InvalidFlightDateException::class, ExceptionHandler::class])
class InvalidFlightDateExceptionHandler : ExceptionHandler<InvalidFlightDateException, HttpResponse<Map<String, String>>> {

    override fun handle(request: HttpRequest<*>, exception: InvalidFlightDateException): HttpResponse<Map<String, String>> =
        HttpResponse.status<Map<String, String>>(HttpStatus.BAD_REQUEST)
            .body(mapOf("message" to (exception.message ?: "Invalid flight date")))
}
