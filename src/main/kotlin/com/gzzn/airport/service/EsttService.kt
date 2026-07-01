package com.gzzn.airport.service

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.exception.InvalidFlightDateException
import com.gzzn.airport.model.Confidence
import com.gzzn.airport.model.EstimateSource
import com.gzzn.airport.model.FlightSeason
import com.gzzn.airport.model.FlyingTimeResponse
import com.gzzn.airport.model.HistoricalFlight
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

/**
 * ESTT 计算的顶层编排服务，负责串联航季计划、历史样本、计算器、缓存、日志和指标。
 * Top-level orchestration service for ESTT calculations, coordinating seasonal schedules,
 * historical samples, the calculator, caching, logging, and metrics.
 *
 * 具体业务筛选和中位数/回退决策委托给协作者，避免控制器直接了解计算细节。
 * Business filtering and median/fallback decisions are delegated to collaborators so controllers
 * do not need to know calculation internals.
 */
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
    }

    init {
        log.info("✅ EsttService initialized successfully")
        log.info("   - maxScheduleDeviation: ${config.maxScheduleDeviation} minutes")
        log.info("   - maxFlyingTimeDeviation: ${config.maxFlyingTimeDeviation} minutes")
        log.info("   - minHistoryFlight: ${config.minHistoryFlight} flights")
        log.info("   - historyStartOffsetDays: ${config.historyStartOffsetDays} days")
        log.info("   - maxHistoryRows: ${config.maxHistoryRows} rows")
        log.info("   - dateFormat: ${config.dateFormat}")
    }

    /**
     * Get the active flight season (cached for 1 hour).
     * Cache provides sufficient protection; no circuit breaker needed.
     * @return the current flight season wrapped in a Result.
     */
    @Cacheable("active-season")
    open fun cachedActiveSeason(): FlightSeason? {
        val activeFlightSeason = seasonRepository.getFlightSeason(true)
        log.info("Active flight season: $activeFlightSeason")
        return activeFlightSeason
    }

    open fun getActiveSeason(): Result<FlightSeason?> = runCatching { cachedActiveSeason() }
        .onFailure { e ->
            log.error("Error getting active flight season", e)
        }

    /**
     * 按配置解析航班日期；当前仅支持严格的 `yyMMdd`，输入必须正好是六位数字。
     * Parses a flight date using the configured format; currently only strict `yyMMdd` is supported
     * and input must be exactly six digits.
     *
     * 年份 `00..99` 映射为 `2000..2099`；错误格式和非法日期都保留现有异常消息。
     * Years `00..99` map to `2000..2099`; invalid formats and impossible dates preserve the
     * existing exception messages.
     */
    open fun parseFlightDate(dateString: String): LocalDate {
        if (config.dateFormat != "yyMMdd") {
            throw InvalidFlightDateException("Unsupported date format: ${config.dateFormat}")
        }
        if (!YYMMDD_PATTERN.matches(dateString)) {
            throw InvalidFlightDateException("Invalid flight date: $dateString")
        }
        val formatter = DateTimeFormatter.ofPattern("uuMMdd")
            .withResolverStyle(ResolverStyle.STRICT)
        return try {
            LocalDate.parse(dateString, formatter)
        } catch (e: DateTimeParseException) {
            throw InvalidFlightDateException("Invalid flight date: $dateString", e)
        }
    }

    /**
     * Get the day of the week for a given flight date.
     * @param flightDate the flight date.
     * @return the day of the week as an integer.
     */
    private fun getOperationDay(flightDate: LocalDate): Int = flightDate.dayOfWeek.value

    /**
     * 逐位匹配航季计划的运营日编码，避免用字符串包含关系导致 `"1"` 误匹配 `"12"`。
     * Matches seasonal operation-day digits individually to avoid substring false positives such as
     * `"1"` matching `"12"`.
     */
    internal fun isOperationDayMatch(operationDays: String, dayOfWeek: Int): Boolean = OperationDays.matches(operationDays, dayOfWeek)

    /**
     * 查询并校验指定航班在目标日期适用的航季计划；数据库仅做预筛选，服务层再次校验运营日和航季边界。
     * Finds and validates the seasonal schedule for the target date; the database only prefilters,
     * while the service rechecks operation-day and season-window boundaries.
     *
     * 航季计划变化较少，因此结果缓存 4 小时；熔断器保持快速失败行为。
     * Seasonal schedules change rarely, so results are cached for four hours; the circuit breaker
     * preserves fast-fail behavior.
     */
    @Cacheable("seasonal-flight")
    @CircuitBreaker(attempts = "10", delay = "500ms", reset = "60s")
    open fun cachedSeasonalFlight(flightNumber: String, flightDate: LocalDate): SeasonalFlight? {
        val operationDay = getOperationDay(flightDate)
        log.debug("Finding seasonal flight for flight number $flightNumber, operation day $operationDay")

        // 直接传入运营日数字；SQL 中的 INSTR 只作为预筛选，不是最终业务判断。
        // Pass the operation-day digit directly; SQL INSTR is only a prefilter, not the final rule.
        val seasonalFlight = seasonRepository.getSeasonalArrivalFlight(flightNumber, operationDay.toString())

        // 再次逐位校验运营日和航季日期范围，避免数据库预筛选产生误匹配。
        // Revalidate operation-day digits and season dates to guard against prefilter false matches.
        val validatedFlight = seasonalFlight?.let {
            val operationDayMatches = isOperationDayMatch(it.operationDays, operationDay)
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

    open fun getSeasonalFlight(flightNumber: String, flightDate: LocalDate): Result<SeasonalFlight?> = runCatching {
        cachedSeasonalFlight(flightNumber, flightDate)
    }
        .onFailure { e ->
            log.error("Error finding seasonal flight for $flightNumber on $flightDate", e)
        }

    /**
     * 根据已校验的航季计划获取历史样本；没有航季计划时返回空样本并缓存该结果。
     * Loads history through the validated seasonal schedule; when no seasonal schedule exists,
     * returns and caches an empty sample list.
     */
    @Cacheable("history-flights")
    open fun cachedHistoryFlights(flightNumber: String, flightDate: LocalDate): List<HistoricalFlight> {
        val seasonalFlight = cachedSeasonalFlight(flightNumber, flightDate)
        if (seasonalFlight == null) {
            // 无航季计划时不能构造可信历史样本，保持空列表的既有行为。
            // Without a seasonal schedule there is no trusted history sample, so keep the existing empty-list behavior.
            return emptyList()
        }
        return historyFlightProvider.getHistoryFlights(seasonalFlight, flightDate)
    }

    open fun getHistoryFlights(flightNumber: String, flightDate: LocalDate): Result<List<HistoricalFlight>> = runCatching {
        cachedHistoryFlights(flightNumber, flightDate)
    }
        .onFailure { e ->
            log.error("Error getting history flights for $flightNumber on $flightDate", e)
        }

    /**
     * 分页查询历史样本时先按原始数据分块扫描，再做业务过滤，最后对过滤后的结果应用 offset/limit。
     * For paginated history, scans raw data in chunks, applies business filters, then applies
     * offset/limit to the filtered results.
     *
     * 扫描受 `maxHistoryRows` 约束；`hasMore` 和 `totalFiltered` 语义是外部可见行为，不能随意调整。
     * Scanning is bounded by `maxHistoryRows`; `hasMore` and `totalFiltered` semantics are externally
     * visible and must not change casually.
     */
    open fun getPaginatedHistoryFlights(
        flightNumber: String,
        flightDate: LocalDate,
        offset: Int,
        limit: Int,
    ): Result<PaginatedHistoryResponse> {
        return runCatching {
            val seasonalFlight = cachedSeasonalFlight(flightNumber, flightDate)
            if (seasonalFlight == null) {
                return@runCatching PaginatedHistoryResponse(
                    items = emptyList(),
                    totalFiltered = 0,
                    offset = offset,
                    limit = limit,
                    hasMore = false,
                )
            }

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
        }.onFailure { e ->
            log.error("Error getting paginated history flights for $flightNumber on $flightDate", e)
        }
    }

    /**
     * 在计算主流程中加载已匹配航季计划的历史样本，并复用 `Result` 错误传播和日志记录。
     * Loads history for the matched seasonal schedule inside the calculation pipeline while reusing
     * `Result` failure propagation and logging.
     */
    private fun getHistoryFlightsWithSeasonFlight(seasonalFlight: SeasonalFlight?, flightDate: LocalDate): Result<List<HistoricalFlight>> {
        return runCatching {
            if (seasonalFlight == null) {
                log.warn("seasonal flight is null, no history flight")
                return@runCatching emptyList()
            }
            historyFlightProvider.getHistoryFlights(seasonalFlight, flightDate)
        }.onFailure { e ->
            log.error("Error getting history flights with seasonal flight for $flightDate", e)
        }
    }

    /**
     * 执行完整飞行时长估算流程，并围绕该流程设置 MDC 和耗时/结果指标。
     * Runs the full flying-time estimation pipeline and wraps it with MDC context plus timing/result metrics.
     */
    open fun calculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse> {
        validateInputs(flightNumber, flightDate)
        return withCalculationTelemetry(flightNumber, flightDate) {
            log.info("Starting flying time calculation")
            fetchAndCalculate(flightNumber, flightDate)
        }
    }

    /**
     * Validate input parameters for the calculation.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     */
    private fun validateInputs(flightNumber: String, flightDate: LocalDate) =
        EsttInputValidator.validateCalculationInputs(flightNumber, flightDate)

    /**
     * Fetch data and perform the calculation.
     * @param flightNumber the flight number.
     * @param flightDate the flight date.
     * @return a FlyingTimeResponse wrapped in a Result.
     */
    private fun fetchAndCalculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse> =
        getSeasonalFlight(flightNumber, flightDate)
            .flatMap { seasonalFlight ->
                if (seasonalFlight == null) {
                    log.warn(NoEstimateReason.NO_SEASONAL_FLIGHT.message)
                    Result.success(
                        buildNoEstimateResponse(flightNumber, flightDate, NoEstimateReason.NO_SEASONAL_FLIGHT),
                    )
                } else {
                    calculateWithSeasonalFlight(seasonalFlight, flightNumber, flightDate)
                }
            }

    /**
     * 记录成功指标；历史、航季回退、无估算分别映射到既有的 source 标签值。
     * Records success metrics; history, seasonal fallback, and no-estimate outcomes map to the
     * existing source tag values.
     */
    private fun recordSuccessMetrics(timer: Timer.Sample, response: FlyingTimeResponse) {
        val sourceTag = when (response.source) {
            EstimateSource.HISTORY -> "history"
            EstimateSource.SEASONAL -> "schedule"
            EstimateSource.NONE -> "none"
        }
        timer.stop(
            meterRegistry.timer(
                "estt.calculation.time",
                "source",
                sourceTag,
                "result",
                "success",
            ),
        )
        meterRegistry.counter(
            "estt.calculation.success",
            "source",
            sourceTag,
        ).increment()
    }

    private fun withCalculationTelemetry(
        flightNumber: String,
        flightDate: LocalDate,
        block: () -> Result<FlyingTimeResponse>,
    ): Result<FlyingTimeResponse> {
        // 仅添加本次计算需要的 MDC 键，finally 中会逐个移除。
        // Add only the MDC keys needed for this calculation; finally removes them individually.
        MDC.put("flightNumber", flightNumber)
        MDC.put("flightDate", flightDate.toString())

        // 计时器覆盖完整计算流程，成功和失败都会停止并记录。
        // The timer covers the full calculation pipeline and is stopped for both success and failure.
        val timer = Timer.start(meterRegistry)

        try {
            return block()
                .onSuccess { response -> recordSuccessMetrics(timer, response) }
                .onFailure { e -> recordFailureMetrics(timer, e) }
        } finally {
            // 只移除本方法写入的键，避免清空调用链中已有的 MDC 上下文。
            // Remove only keys written here so upstream MDC context is not cleared.
            MDC.remove("flightNumber")
            MDC.remove("flightDate")
        }
    }

    /**
     * Record metrics for failed calculation.
     * @param timer the timer to stop.
     * @param exception the exception.
     */
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
        meterRegistry.counter(
            "estt.calculation.failure",
            "error",
            exception.javaClass.simpleName,
        ).increment()
        log.error("Flying time calculation failed", exception)
    }

    /**
     * 在存在航季计划时组合历史样本和计算器结果；无估算分支必须返回 `flyingTime = null`。
     * Combines historical samples with the calculator result when a seasonal schedule exists; the
     * no-estimate branch must return `flyingTime = null`.
     */
    private fun calculateWithSeasonalFlight(
        seasonalFlight: SeasonalFlight,
        flightNumber: String,
        flightDate: LocalDate,
    ): Result<FlyingTimeResponse> = getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
        .map { historyFlights ->
            val result = flyingTimeCalculator.calculate(seasonalFlight, flightNumber, historyFlights)
            buildCalculatedResponse(flightNumber, flightDate, result)
        }

    private fun buildCalculatedResponse(
        flightNumber: String,
        flightDate: LocalDate,
        result: FlyingTimeCalculator.Result,
    ): FlyingTimeResponse = if (result.source == EstimateSource.NONE) {
        buildNoEstimateResponse(
            flightNumber,
            flightDate,
            NoEstimateReason.INSUFFICIENT_HISTORY_NO_SEASONAL_TIME,
        )
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
        FlyingTimeResponse(
            flightNumber,
            flightDate,
            null,
            false,
            false,
            reason.message,
            EstimateSource.NONE,
            0,
            Confidence.NONE,
        )
}
