# Findings: ESTT Kotlin Simplification and Documentation

## Repository layout

- Project root: `/Users/windy/Projects/airport/mini/estt-calc-kotlin`.
- Kotlin main source: `src/main/kotlin/com/gzzn/airport`.
- Kotlin tests: `src/test/kotlin/com/gzzn/airport`.
- Resources: `src/main/resources/application.yml`, `src/main/resources/logback.xml`, `src/test/resources/application-test.yml`, `src/test/resources/schema.sql`.
- README exists and is substantial.
- `docs/` directory is absent.
- Existing prior plans are under `plans/`:
  - `plans/2026-06-29-estt-logic-improvement-task-list-v2.md`
  - `plans/2026-06-29-estt-test-improvement-task-list-v1.md`

## Build and configuration

- The project currently uses Groovy Gradle files:
  - `build.gradle`
  - `settings.gradle`
- Requested files `build.gradle.kts` and `settings.gradle.kts` are not present.
- `settings.gradle` sets `rootProject.name="estt-calc"`.
- `gradle.properties` includes:
  - `oracleVersion=19.7.0.0`
  - `appVersion=0.1.1`
  - `micronautVersion=5.0.2`
  - `kotlinVersion=2.3.21`
  - Gradle configuration cache, parallel execution, and build cache enabled.
- `build.gradle` applies Kotlin JVM/allopen, KSP, Groovy, Shadow, Micronaut application/AOT, Kover, and Spotless.
- Java/Kotlin toolchain target is 25.
- `check` depends on `spotlessCheck` and `koverHtmlReport`.
- Tests use Kotest 5 runtime with MockK.
- `tasks.withType(Test).configureEach { failOnNoDiscoveredTests = false }` is set.

## Existing baseline reports

- Existing test XML reports under `build/test-results/test` show:
  - 15 test suites.
  - 135 tests total.
  - 9 skipped.
  - 0 failures.
  - 0 errors.
  - Timestamps around `2026-07-01T02:07:50Z`.
- Disabled/skipped test areas found via `xdescribe`:
  - `EsttCalcTest` application running test.
  - `RepositoryTest` repository injection and integration tests.
  - `EsttControllerIntegrationTest` Micronaut HTTP integration tests.
- During this planning-only pass, no Gradle test/build command was run; existing reports were inspected only.

## Source-size hotspots

Largest Kotlin files by line count:

- `src/main/kotlin/com/gzzn/airport/service/EsttService.kt`: 434 lines.
- `src/main/kotlin/com/gzzn/airport/resource/EsttController.kt`: 239 lines.
- `src/main/kotlin/com/gzzn/airport/service/history/HistoryFlightProvider.kt`: 197 lines.
- `src/main/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculator.kt`: 154 lines.

Largest test files:

- `src/test/kotlin/com/gzzn/airport/service/EsttServiceTest.kt`: 634 lines.
- `src/test/kotlin/com/gzzn/airport/service/EsttServiceEdgeCaseTest.kt`: 313 lines.
- `src/test/kotlin/com/gzzn/airport/service/EsttServiceValidationAndMatchingTest.kt`: 252 lines.
- `src/test/kotlin/com/gzzn/airport/resource/EsttControllerTest.kt`: 247 lines.

## Current package boundaries

- `config`: calculation configuration.
- `exception`: error codes and exception handlers.
- `health`: database health indicator.
- `model`: response/domain DTOs and enums.
- `repository`: Micronaut Data JDBC repositories and SQL queries.
- `resource`: REST controller.
- `service`: orchestration service.
- `service.calculator`: flying-time decision calculation.
- `service.history`: history retrieval/pagination/filtering.
- `util`: Kotlin `Result` helpers.

## Simplification opportunities to investigate later

Do not implement until characterization tests and explicit approval to proceed beyond planning.

- `EsttService` mixes orchestration, cache-wrapped repository access, input validation, MDC, metrics recording, and response construction.
- `EsttController` repeats endpoint flow: normalize flight number, validate, parse date, log, fold `Result` into HTTP response.
- Duration calculation appears in both `HistoryFlightProvider` and `FlyingTimeCalculator`.
- Filtering is split between `HistoryFlightProvider` and `FlyingTimeCalculator`; boundaries are documented but should be protected with tests before any changes.
- `OperationDays.matches` parses the string on every call; optimization could change behavior around invalid characters and should not be done opportunistically.
- `getHistoryFlightsWithSeasonFlight` accepts nullable `SeasonalFlight` even though the main calculation path passes non-null after branching; simplification may be possible privately.
- `recordSuccessMetrics` maps `EstimateSource.SEASONAL` to metric tag `schedule`; this is observable and must be preserved.
- `FlyingTimeCalculator.Result` name shadows Kotlin `Result` conceptually; renaming would be public within package/API if referenced by tests and should be handled cautiously.

## Documentation inventory findings

- README already includes extensive bilingual product overview, algorithm, API endpoints, environment variables, build/run/test commands, architecture, testing, logging, and version history.
- README describes `docs/`-like content inline; there is no separate developer documentation directory.
- Potential README/documentation issues to address later:
  - It should explicitly state this repo uses `build.gradle`/`settings.gradle` Groovy DSL, not `.kts`, unless migration is separately approved.
  - Build/test/run instructions exist but could be reorganized into a concise developer workflow section.
  - Troubleshooting could be expanded for JDK 25 mismatch, `ORACLE_PASS` missing, Oracle connectivity, Gradle cache/configuration-cache problems, test report locations, and disabled integration tests.
  - Project structure could mention the current `service.calculator` and `service.history` boundaries and generated reports.
  - README version history appears inconsistent: two `v0.1.1` entries and a `v0.1.2` dated `2025-11-06`, while the current date is 2026-07-01 and `appVersion=0.1.1`; treat carefully and do not alter without approval.
  - README includes API contract notes saying `message` is not stable; however user constraints for this task say not to change error messages or output format. Future docs should reflect the task constraint for refactoring work.

## Test coverage and characterization notes

- Existing tests cover date parsing, operation-day matching, calculation fallback behavior, pagination, model validation, controller result mapping, exception handlers, and Result helpers.
- Integration/repository tests are currently disabled with `xdescribe`; do not enable as part of low-risk refactoring unless explicitly approved.
- Characterization tests should prioritize externally observable behavior:
  - HTTP status and response body shape from controller methods.
  - Exact error messages for validation failures.
  - `FlyingTimeResponse` fields for history/seasonal/none sources.
  - Boundary behavior for schedule deviation inclusive threshold and flying-time deviation exclusive threshold.
  - History window excludes target operation date.
  - Pagination `hasMore`, `totalFiltered`, `offset`, and `limit` semantics.
  - Metric names/tags where current tests already assert them or behavior is operationally important.

## Git working tree observation

Before creating planning files, `git status --short` showed existing modified/untracked files not created by this planning pass:

- Modified: `.gitignore`
- Modified: `src/main/kotlin/com/gzzn/airport/exception/InvalidFlightDateException.kt`
- Modified: `src/main/kotlin/com/gzzn/airport/exception/InvalidFlightDateExceptionHandler.kt`
- Modified: `src/main/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculator.kt`
- Modified: `src/test/kotlin/com/gzzn/airport/exception/GlobalExceptionHandlerTest.kt`
- Untracked: `.codex/`

These should be preserved and not overwritten without user approval.
