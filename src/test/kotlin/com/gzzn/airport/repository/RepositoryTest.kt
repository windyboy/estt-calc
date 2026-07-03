package com.gzzn.airport.repository

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.sql.Date
import java.sql.DriverManager
import java.time.LocalDate

class RepositoryTest :
    DescribeSpec({
        describe("HistoryFlightRepository H2 bounded SQL") {
            it("executes row-number bounded query and returns rows in stable descending order") {
                queryArrivalFlightDates(maxRows = 2) shouldBe listOf(
                    LocalDate.of(2021, 12, 24),
                    LocalDate.of(2021, 12, 17),
                )
            }

            it("respects maxRows") {
                queryArrivalFlightDates(maxRows = 1) shouldBe listOf(LocalDate.of(2021, 12, 24))
            }
        }
    })

private fun queryArrivalFlightDates(maxRows: Int): List<LocalDate> {
    DriverManager.getConnection(H2_URL, "sa", "").use { connection ->
        connection.prepareStatement(toJdbcSql(HistoryFlightRepository.ARRIVAL_FLIGHTS_SQL)).use { statement ->
            val params = jdbcParameterNames(HistoryFlightRepository.ARRIVAL_FLIGHTS_SQL)
            params.forEachIndexed { index, name ->
                val value = when (name) {
                    "arriOrDept" -> HistoryFlightRepository.ARRI_OR_DEPT_ARRIVAL.toString()
                    "flightNumber" -> "MU9941"
                    "startDate" -> Date.valueOf(LocalDate.of(2021, 12, 1))
                    "endDate" -> Date.valueOf(LocalDate.of(2021, 12, 31))
                    "maxRows" -> maxRows
                    else -> error("Unexpected SQL parameter: $name")
                }
                statement.setObject(index + 1, value)
            }

            statement.executeQuery().use { rs ->
                val dates = mutableListOf<LocalDate>()
                while (rs.next()) {
                    dates += rs.getDate("flight_date").toLocalDate()
                }
                return dates
            }
        }
    }
}

private fun toJdbcSql(sql: String): String = NAMED_PARAMETER.replace(sql, "?")

private fun jdbcParameterNames(sql: String): List<String> = NAMED_PARAMETER.findAll(sql).map { it.groupValues[1] }.toList()

private val NAMED_PARAMETER = Regex(":([A-Za-z][A-Za-z0-9_]*)")

private const val H2_URL = "jdbc:h2:mem:repository-test;" +
    "DB_CLOSE_DELAY=-1;" +
    "DB_CLOSE_ON_EXIT=FALSE;" +
    "MODE=Oracle;" +
    "INIT=RUNSCRIPT FROM 'classpath:schema.sql'"
