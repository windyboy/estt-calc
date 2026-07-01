package com.gzzn.airport.service

import com.gzzn.airport.config.EsttCalculationConfig
import com.gzzn.airport.model.HistoricalFlight
import com.gzzn.airport.repository.HistoryFlightRepository
import com.gzzn.airport.repository.SeasonRepository
import com.gzzn.airport.service.calculator.FlyingTimeCalculator
import com.gzzn.airport.service.history.HistoryFlightProvider
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime

fun HistoryFlightRepository.mockArrivalFlightPages(vararg pages: List<HistoricalFlight>) {
    val allRows = pages.flatMap { it }
    every { getArrivalFlightPage(any(), any(), any(), any(), any(), any()) } answers {
        val offset = invocation.args[3] as Int
        val limit = invocation.args[4] as Int
        allRows.drop(offset).take(limit)
    }
}

data class EsttServiceTestContext(
    val esttService: EsttService,
    val seasonRepository: SeasonRepository,
    val historyFlightRepository: HistoryFlightRepository,
    val meterRegistry: MeterRegistry,
    val config: EsttCalculationConfig,
)

fun defaultEsttConfig(): EsttCalculationConfig = EsttCalculationConfig(
    maxScheduleDeviation = 120,
    maxFlyingTimeDeviation = 120,
    minHistoryFlight = 20,
    dateFormat = "yyMMdd",
    historyStartOffsetDays = 60L,
    maxHistoryRows = 300,
)

fun createEsttServiceWithConfig(
    seasonRepository: SeasonRepository,
    historyFlightRepository: HistoryFlightRepository,
    meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    config: EsttCalculationConfig,
): EsttService {
    val flyingTimeCalculator = FlyingTimeCalculator(meterRegistry, config)
    val historyFlightProvider = HistoryFlightProvider(historyFlightRepository, flyingTimeCalculator, meterRegistry, config)
    return EsttService(seasonRepository, historyFlightProvider, flyingTimeCalculator, meterRegistry, config)
}

fun createEsttServiceTestContext(config: EsttCalculationConfig = defaultEsttConfig()): EsttServiceTestContext {
    val seasonRepository = mockk<SeasonRepository>()
    val historyFlightRepository = mockk<HistoryFlightRepository>()
    val meterRegistry = SimpleMeterRegistry()
    return EsttServiceTestContext(
        createEsttServiceWithConfig(seasonRepository, historyFlightRepository, meterRegistry, config),
        seasonRepository,
        historyFlightRepository,
        meterRegistry,
        config,
    )
}

fun createHistoryFlights(count: Int, baseDate: LocalDate): List<HistoricalFlight> = (0 until count).map { i ->
    val date = baseDate.minusDays((i * 7).toLong())
    HistoricalFlight(
        date,
        LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 10, 0),
        LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 11, 30),
        LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 11, 0),
    )
}

fun createHistoryFlightsWithDelay(count: Int, baseDate: LocalDate, delayMinutes: Long): List<HistoricalFlight> = (0 until count).map { i ->
    val date = baseDate.minusDays((i * 7).toLong())
    val scheduled = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, 11, 0)
    HistoricalFlight(
        date,
        scheduled.minusHours(1),
        scheduled.plusMinutes(delayMinutes),
        scheduled,
    )
}
