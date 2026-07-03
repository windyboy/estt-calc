package com.gzzn.airport.service

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.exception.InvalidFlightDateException
import com.gzzn.airport.model.Confidence
import com.gzzn.airport.model.EstimateSource
import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.NoEstimateReason
import com.gzzn.airport.model.OperationDays
import com.gzzn.airport.model.PaginatedHistoryResponse
import com.gzzn.airport.model.SeasonalFlight
import com.gzzn.airport.repository.SeasonRepository
import com.gzzn.airport.service.calculator.FlyingTimeCalculator
import com.gzzn.airport.service.history.HistoryFlightProvider
import com.gzzn.airport.util.flatMap
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micronaut.cache.annotation.Cacheable
import io.micronaut.retry.annotation.CircuitBreaker
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

@Singleton
open class EsttService(
    private val seasonRepository: SeasonRepository,
    private val historyFlightProvider: HistoryFlightProvider,
    private val flyingTimeCalculator: FlyingTimeCalculator,
    private val meterRegistry: MeterRegistry,
    private val config: EsttCalculationConfig,
) {
    companion object {
        private val log = LoggerFactory.getLogger(EsttService::class.java)
        private val YYMMDD_PATTERN = Regex("\\d{6}")

        private fun <T> catching(block: () -> T, message: String): Result<T> = runCatching(block).onFailure { e -> log.error(message, e) }
    }

    init {
        log.info(
            "EsttService initialized: maxScheduleDeviation={} maxFlyingTimeDeviation={} minHistoryFlight={} maxHistoryRows={} maxRawScanRows={} minFlyingTime={} maxFlyingTime={} dateFormat={}",
            config.maxScheduleDeviation,
            config.maxFlyingTimeDeviation,
            config.minHistoryFlight,
            config.maxHistoryRows,
            config.maxRawScanRows,
            config.minFlyingTime,
            config.maxFlyingTime,
            config.dateFormat,
        )
    }

    @Cacheable("active-season")
    open fun cachedActiveSeason(): FlightSeason? {
        val activeFlightSeason = seasonRepository.getFlightSeason(true)
        log.info("Active flight season: $activeFlightSeason")
        return activeFlightSeason
    }

    open fun getActiveSeason(): Result<FlightSeason?> = catching({ cachedActiveSeason() }, "Error getting active flight season")

    // 严格 yyMMdd：正好六位数字，年份 00..99 映射为 2000..2099。
    // Strict yyMMdd: exactly six digits; years 00..99 map to 2000..2099.
    open fun parseFlightDate(dateString: String): LocalDate {
        if (config.dateFormat != "yyMMdd") {
            throw InvalidFlightDateException("Unsupported date format: ${config.dateFormat}")
        }
        if (!YYMMDD_PATTERN.matches(dateString)) {
            throw InvalidFlightDateException("Invalid flight date: $dateString")
        }
        val formatter = DateTimeFormatter.ofPattern("uuMMdd").withResolverStyle(ResolverStyle.STRICT)
        return try {
            LocalDate.parse(dateString, formatter)
        } catch (e: DateTimeParseException) {
            throw InvalidFlightDateException("Invalid flight date: $dateString", e)
        }
    }

    @Cacheable("seasonal-flight")
    @CircuitBreaker(attempts = "10", delay = "500ms", reset = "60s")
    open fun cachedSeasonalFlight(flightNumber: String, flightDate: LocalDate): SeasonalFlight? {
        val operationDay = flightDate.dayOfWeek.value
        log.debug("Finding seasonal flight for flight number $flightNumber, operation day $operationDay")

        // INSTR 仅为数据库预筛选；服务层再次校验运营日和航季边界。
        // INSTR is a DB prefilter only; service revalidates operation day and season bounds.
        val seasonalFlight = seasonRepository.getSeasonalArrivalFlight(flightNumber, operationDay.toString(), flightDate)

        val validatedFlight = seasonalFlight?.let {
            val operationDayMatches = OperationDays.matches(it.operationDays, operationDay)
            val withinSeasonBounds = !flightDate.isBefore(it.seasonStart) && !flightDate.isAfter(it.seasonEnd)

            if (!operationDayMatches) {
                log.warn(
                    "Seasonal flight found but operation day validation failed: flight={}, day={}, operationDays={}",
                    flightNumber,
                    operationDay,
                    it.operationDays,
                )
            }
            if (!withinSeasonBounds) {
                log.warn(
                    "Seasonal flight found but date {} is outside season window: start={}, end={}",
                    flightDate,
                    it.seasonStart,
                    it.seasonEnd,
                )
            }
            if (operationDayMatches && withinSeasonBounds) it else null
        }

        log.debug("Validated seasonal flight: {}", validatedFlight)
        return validatedFlight
    }

    open fun getSeasonalFlight(flightNumber: String, flightDate: LocalDate): Result<SeasonalFlight?> =
        catching({ cachedSeasonalFlight(flightNumber, flightDate) }, "Error finding seasonal flight for $flightNumber on $flightDate")

    open fun getPaginatedHistoryFlights(
        flightNumber: String,
        flightDate: LocalDate,
        offset: Int,
        limit: Int,
    ): Result<PaginatedHistoryResponse> = catching({
        val seasonalFlight = cachedSeasonalFlight(flightNumber, flightDate)
            ?: return@catching PaginatedHistoryResponse(emptyList(), 0, offset, limit, false)

        val paginated = historyFlightProvider.getPaginatedHistory(seasonalFlight, flightDate, offset, limit)
        log.debug(
            "Paginated history for {}: returned={}, totalFiltered={}, hasMore={}, offset={}, limit={}, capped={}",
            seasonalFlight.flightNumber,
            paginated.items.size,
            paginated.totalFiltered,
            paginated.hasMore,
            offset,
            limit,
            paginated.totalFiltered >= config.maxHistoryRows,
        )
        if (paginated.hasMore || paginated.totalFiltered >= config.maxHistoryRows) {
            log.info(
                "History pagination truncated for {}: hasMore={}, filtered={}, offset={}, limit={}, maxRows={}",
                seasonalFlight.flightNumber,
                paginated.hasMore,
                paginated.totalFiltered,
                offset,
                limit,
                config.maxHistoryRows,
            )
        }
        paginated
    }, "Error getting paginated history flights for $flightNumber on $flightDate")

    open fun calculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse> {
        validateInputs(flightNumber, flightDate)
        MDC.put("flightNumber", flightNumber)
        MDC.put("flightDate", flightDate.toString())
        val timer = Timer.start(meterRegistry)
        return try {
            log.info("Starting flying time calculation")
            fetchAndCalculate(flightNumber, flightDate)
                .onSuccess { recordSuccessMetrics(timer, it) }
                .onFailure { recordFailureMetrics(timer, it) }
        } finally {
            MDC.remove("flightNumber")
            MDC.remove("flightDate")
        }
    }

    private fun validateInputs(flightNumber: String, flightDate: LocalDate) {
        require(flightNumber.isNotBlank()) { "Flight number cannot be empty" }
        require(flightNumber.length in 5..6) { "Flight number must be 5-6 characters, got: ${flightNumber.length}" }
        require(flightDate.isAfter(LocalDate.of(2000, 1, 1))) {
            "Flight date must be after 2000-01-01, got: $flightDate"
        }
        require(flightDate.isBefore(LocalDate.now().plusYears(1))) {
            "Flight date cannot be more than 1 year in the future, got: $flightDate"
        }
    }

    private fun fetchAndCalculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse> =
        getSeasonalFlight(flightNumber, flightDate).flatMap { seasonalFlight ->
            if (seasonalFlight == null) {
                log.warn(NoEstimateReason.NO_SEASONAL_FLIGHT.message)
                Result.success(buildNoEstimateResponse(flightNumber, flightDate, NoEstimateReason.NO_SEASONAL_FLIGHT))
            } else {
                calculateWithSeasonalFlight(seasonalFlight, flightNumber, flightDate)
            }
        }

    // EstimateSource.SEASONAL 映射到指标标签 "schedule"。
    // EstimateSource.SEASONAL maps to metric tag "schedule".
    private fun recordSuccessMetrics(timer: Timer.Sample, response: FlyingTimeResponse) {
        val sourceTag = when (response.source) {
            EstimateSource.HISTORY -> "history"
            EstimateSource.SEASONAL -> "schedule"
            EstimateSource.NONE -> "none"
        }
        timer.stop(meterRegistry.timer("estt.calculation.time", "source", sourceTag, "result", "success"))
        meterRegistry.counter("estt.calculation.success", "source", sourceTag).increment()
    }

    private fun recordFailureMetrics(timer: Timer.Sample, exception: Throwable) {
        timer.stop(
            meterRegistry.timer(
                "estt.calculation.time",
                "result",
                "failure",
                "error",
                exception.javaClass.simpleName,
            ),
        )
        meterRegistry.counter("estt.calculation.failure", "error", exception.javaClass.simpleName).increment()
        log.error("Flying time calculation failed", exception)
    }

    private fun calculateWithSeasonalFlight(
        seasonalFlight: SeasonalFlight,
        flightNumber: String,
        flightDate: LocalDate,
    ): Result<FlyingTimeResponse> = catching({
        historyFlightProvider.getHistoryFlights(seasonalFlight, flightDate)
    }, "Error getting history flights with seasonal flight for $flightDate").map { scan ->
        val result = flyingTimeCalculator.calculate(seasonalFlight, flightNumber, scan.qualifiedFlights)
        if (scan.insufficientAfterBudget &&
            result.qualifiedCount < config.minHistoryFlight &&
            result.source != EstimateSource.HISTORY
        ) {
            log.info(
                "History scan ended without enough qualified samples: rawCap={}, raw={}, filtered={}, qualified={}",
                config.maxRawScanRows,
                scan.rawRows,
                scan.stageBRows,
                result.qualifiedCount,
            )
        }
        buildCalculatedResponse(flightNumber, flightDate, result)
    }

    private fun buildCalculatedResponse(
        flightNumber: String,
        flightDate: LocalDate,
        result: FlyingTimeCalculator.Result,
    ): FlyingTimeResponse = if (result.source == EstimateSource.NONE) {
        buildNoEstimateResponse(flightNumber, flightDate, NoEstimateReason.INSUFFICIENT_HISTORY_NO_SEASONAL_TIME)
    } else {
        FlyingTimeResponse(
            flightNumber,
            flightDate,
            result.flyingTime,
            result.historyUsed,
            result.source == EstimateSource.SEASONAL,
            result.message,
            result.source,
            result.sampleSize,
            result.confidence,
        )
    }

    private fun buildNoEstimateResponse(flightNumber: String, flightDate: LocalDate, reason: NoEstimateReason): FlyingTimeResponse =
        FlyingTimeResponse(flightNumber, flightDate, null, false, false, reason.message, EstimateSource.NONE, 0, Confidence.NONE)
}
