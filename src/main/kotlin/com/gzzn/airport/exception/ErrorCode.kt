package com.gzzn.airport.exception

import java.time.Instant

/**
 * Standardized error codes for the ESTT calculation service.
 * Each error code has an associated HTTP status and can generate ErrorResponse objects.
 */
enum class ErrorCode(val code: String, val httpStatus: Int) {
    // Client errors (4xx)
    BAD_REQUEST("BAD_REQUEST", 400),
    VALIDATION_ERROR("VALIDATION_ERROR", 400),
    NOT_FOUND("NOT_FOUND", 404),
    
    // Server errors (5xx)
    SYSTEM_ERROR("SYSTEM_ERROR", 500),
    DATABASE_ERROR("DATABASE_ERROR", 500),
    CALCULATION_ERROR("CALCULATION_ERROR", 500),
    INTERNAL_ERROR("INTERNAL_ERROR", 500);
    
    /**
     * Create an ErrorResponse with this error code.
     * @param message the error message
     * @return an ErrorResponse object
     */
    fun toErrorResponse(message: String): ErrorResponse {
        return ErrorResponse(
            status = httpStatus,
            error = code,
            message = message,
            timestamp = Instant.now()
        )
    }
}

