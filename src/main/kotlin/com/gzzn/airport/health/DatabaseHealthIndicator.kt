package com.gzzn.airport.health

import com.gzzn.airport.repository.SeasonRepository
import io.micronaut.context.annotation.Requires
import io.micronaut.health.HealthStatus
import io.micronaut.management.health.indicator.HealthIndicator
import io.micronaut.management.health.indicator.HealthResult
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * Custom health indicator that validates database connectivity.
 * Queries the active season as a health check probe.
 */
@Singleton
@Requires(beans = [SeasonRepository::class])
class DatabaseHealthIndicator(
    private val seasonRepository: SeasonRepository
) : HealthIndicator {
    
    companion object {
        private val log = LoggerFactory.getLogger(DatabaseHealthIndicator::class.java)
    }
    
    override fun getResult(): Publisher<HealthResult> {
        return Mono.fromCallable {
            try {
                // Try to query the database
                seasonRepository.getFlightSeason(true)
                
                HealthResult.builder("database", HealthStatus.UP)
                    .details(mapOf(
                        "type" to "Oracle",
                        "check" to "season_query"
                    ))
                    .build()
            } catch (e: Exception) {
                log.error("Database health check failed", e)
                HealthResult.builder("database", HealthStatus.DOWN)
                    .details(mapOf(
                        "type" to "Oracle",
                        "check" to "season_query",
                        "error" to (e.message ?: "Unknown error")
                    ))
                    .build()
            }
        }
    }
}

