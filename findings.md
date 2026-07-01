# Findings: ESTT Kotlin Simplification and Documentation

## Repository layout

- Project root: `/Users/windy/Projects/airport/mini/estt-calc-kotlin`.
- Kotlin main source: `src/main/kotlin/com/gzzn/airport`.
- Kotlin tests: `src/test/kotlin/com/gzzn/airport`.
- Resources: `src/main/resources/application.yml`, `src/main/resources/logback.xml`, `src/test/resources/application-test.yml`, `src/test/resources/schema.sql`.
- README exists and is substantial.
- `docs/development.md` exists (developer workflow guide created in first refactor pass).
- Existing prior plans are under `plans/` (historical; test paths reference obsolete Groovy/Spock layout):
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

Characterization tests from Phases 3–4 are in place. Refactor status as of plan correction (2026-06-29):

| Area | Status | Next phase |
|------|--------|------------|
| `EsttService` telemetry/response helpers | done | optional further extractions |
| `HistoryPaginationScanner` | done | — |
| `EsttController` request-flow/error helpers | pending | Phase 8 |
| `FlyingTimeCalculator` branch/metrics helpers | pending | Phase 9 |
| Uncommitted exception-handler changes | pending | Phase 10 |

Remaining notes:

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
- `docs/development.md` supplements README with prerequisites, build/test commands, project structure, configuration workflow, troubleshooting, and known limitations.
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

# Findings: ESTT Second Refactoring Pass Planning

## Previous diff and baseline review

- Current HEAD is `0094d4d Document refactor pass`.
- The first-pass refactor was too shallow because production changes were limited mainly to:
  - `HistoryFlightProvider`: history-window extraction and minor predicate/tolerance cleanup.
  - `EsttService`: extraction of input validation into `EsttInputValidator` while retaining most orchestration complexity.
- Most first-pass work was documentation, characterization tests, planning/progress updates, and final verification.
- Current uncommitted source/test changes predate this second-pass planning session and must be preserved:
  - `.gitignore`
  - `InvalidFlightDateException.kt`
  - `InvalidFlightDateExceptionHandler.kt`
  - `FlyingTimeCalculator.kt`
  - `GlobalExceptionHandlerTest.kt`
  - `.codex/`
- The uncommitted `FlyingTimeCalculator.kt` diff removes `ensureValidSeasonalFlight(...)` and its KDoc. Treat as existing user work; do not overwrite or assume it is part of the second pass.

## Chinese inline documentation audit

- Current `src/main/kotlin` and `src/test/kotlin` contain no Chinese comments/KDoc.
- Accessible recent git revisions checked (`HEAD`, `8d5696d`, `7fa7ddc`) also did not show Chinese text in `src/**/*.kt` using `git grep -P "[\\x{4e00}-\\x{9fff}]"`.
- README contains detailed Chinese business rules that should be reflected in source comments where they clarify non-obvious behavior:
  - Operation days are digit-encoded `1..7`; matching must be digit-wise, not substring-unsafe.
  - History query excludes the target operation date by using the previous day as the upper bound.
  - Historical sample filters include operation day, previous departure before actual arrival, scheduled date equals flight date, flying-time tolerance, and schedule-deviation tolerance.
  - Flying-time tolerance is strict (`< maxFlyingTimeDeviation`); equality is rejected.
  - Schedule late deviation is inclusive at the threshold (`<= maxScheduleDeviation`); early arrivals are always accepted.
  - Median is integer median; even sample count uses integer average of the two middle values.
  - Seasonal fallback requires a positive configured `flyingTime`; otherwise source is `NONE` with null flying time.

## Complexity and duplication audit

- Largest production files:
  - `EsttService.kt`: 426 lines.
  - `EsttController.kt`: 239 lines.
  - `HistoryFlightProvider.kt`: 198 lines.
  - `FlyingTimeCalculator.kt`: 154 lines.
- Highest-value internal refactor candidates:
  - `EsttService`: separate MDC/timer lifecycle, metric source tag mapping, calculator-result response mapping, and seasonal validation helpers while preserving public service methods.
  - `EsttController`: extract private request-flow/error helpers carefully; routes, annotations, method signatures, response statuses, and messages must stay unchanged.
  - `HistoryFlightProvider`: split mutable pagination scan state from filtering and metrics; preserve order, capped total, offset/limit, and `hasMore` semantics.
  - `FlyingTimeCalculator`: split history path, seasonal fallback path, no-estimate path, and metric recording from `calculate`.
  - `OperationDays`, repositories, and config: restore Chinese comments for domain/SQL/config semantics without logic changes.

## Documentation files inspected

- `README.md` now includes implementation structure and detailed Chinese/English algorithm notes.
- `docs/development.md` exists from the prior pass and documents developer workflow.
- Documentation should not be changed during the first planning-only phase. Future documentation sync should be limited to reflecting actual structure if internal helper files/classes are added.

# Findings: ESTT Second Refactoring Pass Bilingual Planning Update

## Bilingual documentation audit update

- Current source and tests contain no Chinese text. README contains the bilingual domain description; source comments are currently English-only where present.
- Because this project should keep bilingual documentation for major business logic, later implementation should convert important English-only KDoc/inline comments to Chinese-first, English-second comments rather than deleting them.
- No Chinese-only source comments were found, so there are currently no Chinese-only comments needing English additions.
- Important existing English comments should be preserved and expanded where they explain domain behavior. Examples include date parsing, operation-day matching, seasonal-flight validation, target-day exclusion, history filtering, schedule-deviation acceptance, median calculation, no-estimate fallback, pagination metrics, and repository SQL assumptions.

## Business logic lacking clear bilingual source documentation

- `EsttService.parseFlightDate`: strict `yyMMdd`, exact six digits, `2000..2099` mapping, and invalid-date exception behavior.
- `EsttService.cachedSeasonalFlight`: DB `INSTR` prefilter plus in-service operation-day/date-window revalidation.
- `EsttService.cachedHistoryFlights` / `getHistoryFlightsWithSeasonFlight`: no seasonal flight means no history sample and empty list behavior.
- `EsttService.calculate`: MDC lifecycle and timer metrics around calculation pipeline.
- `EsttService.recordSuccessMetrics`: public metric tag maps `EstimateSource.SEASONAL` to `schedule`.
- `OperationDays`: digit encoding `1..7`, ignored invalid characters, deduplication, and avoidance of substring false positives.
- `HistoryFlightProvider`: history window excludes target date, repository scan limit, in-memory filtering, strict flying-time tolerance, and paginated scan semantics.
- `FlyingTimeCalculator`: schedule deviation rule, median rule, history-vs-seasonal-vs-none decision, and metrics.
- `HistoryFlightRepository` / `SeasonRepository`: Oracle SQL assumptions and why service-layer filtering/validation remains necessary.

## Complexity and duplication update

- Meaningful second-pass refactor should target private/internal structure rather than public API:
  - Extract controller request normalization/error helpers without touching routes/signatures/messages.
  - Extract `EsttService` calculation context/metric mapping/response mapping helpers.
  - Extract `HistoryFlightProvider` pagination scan state or private scanner helper.
  - Extract `FlyingTimeCalculator` decision branches and metric recording helpers.
- Phase 4 characterization tests should precede refactors in these areas because tests/users may rely on exact messages, statuses, response shape, metric tags, pagination semantics, and exception wrapping.

## Phase 0 execution findings on second-pass start

- Phase 0 was restarted from the current working tree at user request.
- Current HEAD remains `0094d4d Document refactor pass`.
- Recent history confirms the previous pass was shallow at production-code level:
  - `4675ffb Simplify history flight filtering` touched only `HistoryFlightProvider.kt` plus planning/progress.
  - `d6a84fa Extract ESTT input validation` added `EsttInputValidator.kt` and reduced `EsttService.validateInputs` to a delegating private function.
  - The rest of first-pass work was mainly docs, tests, and verification records.
- Current scoped source/test diff still contains pre-existing user work, not second-pass refactor work:
  - `InvalidFlightDateException.kt`: constructor message changed to nullable/default null.
  - `InvalidFlightDateExceptionHandler.kt`: response type changed from `Map<String, String>` to `ErrorResponse` with `VALIDATION_ERROR`.
  - `FlyingTimeCalculator.kt`: `ensureValidSeasonalFlight(...)` and its KDoc removed.
  - `GlobalExceptionHandlerTest.kt`: tests added for `InvalidFlightDateExceptionHandler` standard validation response and null default message.
- `README.md`, `docs/development.md`, `build.gradle`, and `settings.gradle` have no current uncommitted diff in the scoped review.
- First-pass source diff (`7fa7ddc..0094d4d`) confirms no deeper structural split happened beyond validation extraction and small history filtering cleanup.


## Phase 1 execution findings: bilingual inline documentation audit

- Phase 1 was executed after the user requested the next phase from Phase 0.
- Current `src/main/kotlin` and `src/test/kotlin` still contain no Chinese text; all source-level business comments/KDoc are English-only or absent.
- README contains bilingual business/domain descriptions, especially algorithm steps and implementation structure, but those rules are not mirrored bilingually in source KDoc/inline comments.
- Existing English comments that should be preserved and expanded bilingually include:
  - `EsttService.parseFlightDate`: strict `yyMMdd`, exact six digits, and `2000..2099` mapping.
  - `EsttService.cachedSeasonalFlight`: `INSTR` DB prefilter and service-side revalidation for operation day and season bounds.
  - `EsttService.cachedHistoryFlights`: no seasonal flight produces an empty cached history list.
  - `EsttService.calculate`: MDC keys and timer lifecycle; removing only keys added by this method.
  - `HistoryFlightProvider.getHistoryFlights`: inclusive SQL `BETWEEN` with end date set to target date minus one day to avoid self-sampling.
  - `HistoryFlightProvider.isEligibleHistoryFlight`: operation-day alignment, time-order gate, scheduled-date match, strict flying-time tolerance, null seasonal time skip.
  - `FlyingTimeCalculator.calculate`: history threshold, median decision, seasonal fallback, no-estimate branch, and Micrometer source counters.
  - `FlyingTimeCalculator.getQualifiedHistoryFlights`: early arrivals accepted and late arrivals accepted through the inclusive configured threshold.
  - `FlyingTimeCalculator.medianFlyingTime`: integer median and integer average for even sample counts.
  - `OperationDays`: digit encoding `1..7`, ignored invalid characters, duplicate deduplication, and avoiding substring false positives.
  - Repository KDoc: Oracle SQL assumptions, arrival-only filter, inclusive date bounds, and paginated scans.
- Comments that are likely too obvious and should not receive bilingual expansion unless they remain near important extracted logic:
  - `// Validate pagination parameters` in `EsttController`.
  - Generic KDoc such as "Get the day of the week" unless retained as part of a larger domain explanation.
- Phase 1 did not modify source, tests, README, docs, Gradle files, SQL, configuration, dependencies, routes, DTOs, or behavior.


## Phase 2 execution findings: complexity and duplication audit

- Phase 2 was executed after Phase 1. No source or documentation files were modified.
- Largest production complexity hotspots remain:
  - `EsttService.kt` (426 lines): mixes repository cache wrappers, seasonal validation, history lookup, calculation pipeline, MDC lifecycle, metrics, and response construction.
  - `EsttController.kt` (239 lines): repeats normalize/validate/parse/log/fold/error-response flow across seasonal, history, and calculate endpoints.
  - `HistoryFlightProvider.kt` (198 lines): `getPaginatedHistory` combines raw-page scanning, mutable pagination state, filtering, total capping, `hasMore` derivation, and metrics.
  - `FlyingTimeCalculator.kt` (154 lines): `calculate` combines sample qualification, history median branch, seasonal fallback branch, no-estimate branch, logging, and metric counters.
- Meaningful internal refactor opportunities, preserving public API/behavior:
  - `EsttService`: extract private helpers for MDC/timer calculation context, metric source-tag mapping, no-estimate response mapping, calculator-result-to-response mapping, and seasonal-flight validation logging.
  - `EsttController`: extract private helpers for flight-number normalization/validation flow, pagination validation, and repeated `DATABASE_ERROR`/`CALCULATION_ERROR` response construction, while preserving route annotations, method signatures, HTTP statuses, and exact messages.
  - `HistoryFlightProvider`: extract a private pagination scan accumulator/state object or private scanner helper. Preserve raw fetch ordering, chunk size `maxOf(limit, 100)`, `rawOffset` behavior, `reportedTotal = minOf(totalFiltered, maxHistoryRows)`, `hasMore`, and all metric names/tags.
  - `FlyingTimeCalculator`: extract private branch builders and metrics helpers: history result, history accuracy metrics, seasonal fallback result, no-estimate result, schedule deviation predicate, and duration-to-median helper. Preserve messages and metric tags exactly (`source=schedule` for seasonal fallback).
  - `OperationDays`: small but domain-sensitive; avoid behavior changes. Potential documentation-only improvement is safer than implementation refactor unless tests are expanded first.
- Test complexity observations:
  - `EsttServiceTest.kt` is the largest test file (634 lines) and includes pagination, metrics, and helper construction. Test refactor is optional and should not precede behavior characterization unless it improves clarity without changing assertions.
  - Existing focused tests cover many risky behaviors, but Phase 4 should add/confirm characterization before changing pagination scan state, controller error mapping, and metric tag logic.
- Risk hotspots requiring exact preservation in later phases:
  - Controller error messages/statuses and exception propagation.
  - `InvalidFlightDateException` handling, especially because current uncommitted changes alter its response shape.
  - Pagination `hasMore`, `totalFiltered`, offset/limit, chunking, and cap semantics.
  - Metric names/tags and source mapping (`EstimateSource.SEASONAL` -> `schedule`).
  - Date parsing and operation-day matching semantics.
  - History filter ordering only if logs/tests depend on diagnostics; boolean outcome must remain identical.


## Phase 3 execution findings: bilingual KDoc and inline docs

- Phase 3 added or improved bilingual source comments only; no executable logic was intentionally changed.
- Per user correction, comments now use Chinese-first and English-second content without literal labels such as `中文：` or `English:`. The plan's preferred style was updated accordingly.
- Bilingual comments were added/improved in:
  - `EsttService.kt`: orchestration role, strict date parsing, operation-day matching, seasonal prefilter/revalidation, no-seasonal empty history behavior, paginated history semantics, calculation MDC/timer lifecycle, success metric source tags, and no-estimate response mapping.
  - `HistoryFlightProvider.kt`: provider responsibility, target-date exclusion, filtered pagination semantics, `hasMore` visibility, business sample gates, and strict flying-time tolerance.
  - `FlyingTimeCalculator.kt`: calculator responsibility, internal result role, history/seasonal/none decision rules, schedule deviation rule, and integer median rule.
  - `OperationDays.kt`: digit-wise operation-day encoding and invalid/duplicate character behavior.
  - `EsttCalculationConfig.kt`: centralized fail-fast configuration purpose.
  - `HistoryFlightRepository.kt` and `SeasonRepository.kt`: SQL/data-source assumptions and service-layer validation responsibilities.
  - `FlyingTimeResponse.kt` and `PaginatedHistoryResponse.kt`: externally visible response semantics.
- Pre-existing uncommitted changes in `InvalidFlightDateException.kt`, `InvalidFlightDateExceptionHandler.kt`, `FlyingTimeCalculator.kt` removal of `ensureValidSeasonalFlight`, and `GlobalExceptionHandlerTest.kt` remain mixed in the working tree relative to HEAD. Phase 3 did not intentionally alter their executable behavior.
- Verification:
  - `git diff --check` passed.
  - Initial non-escalated `./gradlew spotlessCheck` failed due sandbox denial on `~/.gradle` wrapper lock.
  - Escalated `./gradlew spotlessCheck` passed.


## Phase 4 execution findings: characterization tests

- Added characterization coverage for operation-day parsing semantics:
  - `OperationDays.parse("1a227")` returns `setOf(1, 2, 7)`, preserving invalid-character ignore and duplicate-deduplication behavior.
  - Multi-digit day values are not treated as valid weekdays: `OperationDays.matches("12", 12)` is false.
  - `0` remains invalid even when present next to a valid digit.
- Added characterization coverage for calculator accuracy metrics:
  - History median `111` against seasonal flying time `100` records `estt.calculation.accuracy` with tag `accuracy=11`.
  - The `estt.calculation.high_accuracy` counter remains `0.0` when the absolute difference is greater than 10.
- Verification:
  - Initial non-escalated focused Gradle run failed due sandbox denial on the Gradle wrapper lock under `~/.gradle`.
  - Escalated focused test run passed for `OperationDaysTest` and `FlyingTimeCalculatorTest`.
  - Escalated full `./gradlew test` passed.
- These tests lock behavior needed before refactoring `OperationDays` and `FlyingTimeCalculator` internals.


## Phase 5 execution findings: deeper internal Kotlin refactor

- `EsttService.calculate` now delegates MDC and timing concerns to `withCalculationTelemetry`, reducing nested orchestration while preserving the same logging, success/failure metrics, and MDC cleanup.
- `EsttService.calculateWithSeasonalFlight` now delegates response mapping to `buildCalculatedResponse`, keeping the no-estimate branch and legacy `FlyingTimeResponse` mapping unchanged.
- These changes were verified with focused service/controller/history tests and the full `./gradlew check` suite.
- Spotless required a formatting pass on the refactored files; `./gradlew spotlessApply` was run, then `./gradlew check` passed.

## Phase 6 execution findings: split oversized private/internal components

- Extracted the paginated-history scan loop from `HistoryFlightProvider` into a dedicated internal helper class `HistoryPaginationScanner`.
- `HistoryFlightProvider` now focuses on window derivation, business filtering, and metric emission, while the scanner owns chunking, offset/limit application, `totalFiltered`, and `hasMore` derivation.
- Scanner behavior remains unchanged: raw fetches are chunked with `maxOf(limit, 100)`, filtered results are paged after filtering, and `hasMore` still reflects observed extra filtered rows or scan-limit truncation.
- Verification:
  - Focused tests for `HistoryFlightProviderTest`, `EsttServiceTest`, `EsttServiceValidationAndMatchingTest`, and `EsttServiceErrorTest` passed.
  - Full `./gradlew check` passed after applying Spotless formatting to the new scanner file.
- Pre-existing working-tree changes remain untouched: `InvalidFlightDateException.kt`, `InvalidFlightDateExceptionHandler.kt`, `FlyingTimeCalculator.kt`, `GlobalExceptionHandlerTest.kt`, `.gitignore`, `.codex/`, and `.cursor/`.


## Phase 7 execution findings: final verification and documentation sync

- Final verification passed after the Phase 5/6 refactors:
  - `git diff --check` passed.
  - `./gradlew check` passed.
- No README or `docs/development.md` update was needed because the internal helper split did not change documented external structure or behavior.
- The working tree still contains pre-existing user changes unrelated to the phase7 implementation: `InvalidFlightDateException.kt`, `InvalidFlightDateExceptionHandler.kt`, `FlyingTimeCalculator.kt`, `GlobalExceptionHandlerTest.kt`, `.gitignore`, `.codex/`, and `.cursor/`.

# Findings: Plan correction (2026-06-29)

## Issues corrected in planning files

- `task_plan.md` incorrectly marked Phases 5–7 complete despite partial delivery.
- Stale "plan-only / do not proceed" stop point contradicted landed commits.
- `findings.md` incorrectly stated `docs/` was absent.
- `plans/` task lists reference obsolete `src/test/groovy/` Spock paths; tests now live under `src/test/kotlin/`.

## Corrected delivery status

| Phase | Correct status | Notes |
|-------|----------------|-------|
| 0–4 | complete | Audit, bilingual docs, characterization tests |
| 5 | partial | `EsttService` helpers only |
| 6 | partial | `HistoryPaginationScanner` only |
| 7 | superseded | Verification passed but premature |
| 8 | complete | `EsttController` refactor |
| 9 | complete | `FlyingTimeCalculator` refactor |
| 10 | complete | Exception handler + calculator cleanup committed |
| 11 | complete | Final `./gradlew check` passed |

## Current production hotspots (post-correction)

- `EsttService.kt`: 436 lines — partially refactored.
- `EsttController.kt`: 239 lines — unchanged; Phase 8 target.
- `HistoryFlightProvider.kt`: 168 lines — refactored.
- `FlyingTimeCalculator.kt`: ~142 lines — Phase 9 target.
- Uncommitted `InvalidFlightDateExceptionHandler` changes HTTP 400 body from `Map` to `ErrorResponse`; reconcile in Phase 10 before treating verification as final.

# Findings: Simplicity review (2026-06-29)

## Conclusion

The refactor passes improved correctness and test coverage, but **did not reduce total code size**. Core service layer grew from ~825 to ~1115 lines across main files. `EsttService` (~436 lines) remains the primary complexity hotspot.

## Keep (worth the indirection)

- `EsttInputValidator` — validation out of orchestrator
- `HistoryPaginationScanner` — pagination scan separated from business filtering
- `EsttController.parseFlightRequest` — DRY across three date-taking endpoints

## Rolled back or avoid re-adding

- One-line error-response wrappers (`databaseErrorResponse`, `calculationErrorResponse`)
- Calculator branch helper methods that only moved code without deleting duplication
- Further private helper extraction unless a whole class/file can be removed

## Comment policy going forward

- Keep bilingual notes only on non-obvious domain rules (operation-day matching, history window, deviation thresholds, median rule)
- Do not expand comments that restate what the code already says

## No further action unless goal changes

Stop new planning phases. Future work should **delete or merge**, not split.

# Findings: Simplification plan v3 (2026-06-29)

## Why a new plan

The refactor pass added structure without reducing size. v3 optimizes for **measurable deletion**, not extraction.

## Baseline (HEAD `4bc60cb`)

| Item | Value |
|------|-------|
| `EsttService.kt` | 436 lines |
| `EsttController.kt` | 243 lines (mostly OpenAPI; poor ROI) |
| `FlyingTimeCalculator.kt` | 128 lines (stable after rollback) |
| `HistoryFlightProvider` + scanner | 168 + 79 lines (keep) |
| `EsttInputValidator.kt` | 21 lines (merge candidate) |
| Production `.kt` files | 25 |

## Highest-yield simplification targets

1. **Comment trim in `EsttService`** — bilingual KDoc adds ~80+ lines with little runtime value
2. **Remove `getHistoryFlightsWithSeasonFlight`** — duplicate of provider call when seasonal is already known
3. **Merge `EsttInputValidator`** — one call site, one file too many
4. **Init/log ceremony in `EsttService`** — verbose startup logs

## Explicitly not recommended

- Re-splitting `FlyingTimeCalculator` or `EsttController`
- Inlining `HistoryPaginationScanner` back into provider (would grow one file, not shrink total)
- New helper classes or packages
- Test file merges in v3 (high risk, low line savings)

## Success criteria for v3

- `EsttService.kt` ≤ 340 lines
- Total main source −10% or more
- File count ≤ 24
- `./gradlew check` green

# Findings: Simplification plan v3 execution (2026-07-01)

## Before / after

| Metric | Baseline | After | Δ |
|--------|----------|-------|---|
| `EsttService.kt` | 436 | 342 | −94 |
| Main `src/main/kotlin` total | ~1,800 | 1,477 | −323 (~18%) |
| Production `.kt` files | 25 | 24 | −1 |

## Changes delivered

- **Phase 1:** Trimmed verbose KDoc; kept short bilingual comments on domain rules per user compromise (option 2). Model field essays (`FlyingTimeResponse`, `PaginatedHistoryResponse`) not restored.
- **Phase 2:** Removed `getHistoryFlightsWithSeasonFlight`; `calculateWithSeasonalFlight` calls `historyFlightProvider.getHistoryFlights` directly with same error log message.
- **Phase 3:** Consolidated 7-line `init` into one structured log; inlined `getOperationDay`.
- **Phase 4:** Deleted `EsttInputValidator.kt`; inlined 4 `require` checks into `EsttService.validateInputs`.
- **Phase 5:** `git diff --check` and `./gradlew check` passed.

## Comment policy applied

- Chinese-first, English-second, no `中文：` / `English:` labels.
- Domain rules only: `yyMMdd`, operation-day digits, history window, flying-time `<` tolerance, INSTR/BETWEEN SQL semantics, SEASONAL→`schedule` metric tag.
- `FlyingTimeCalculator.kt` bilingual comments left unchanged.

## Verification

```bash
git diff --check          # pass
./gradlew spotlessCheck   # pass
./gradlew check           # pass (2026-07-01)
```
