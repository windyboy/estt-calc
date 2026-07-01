# ESTT Kotlin Second Refactoring Pass Plan

## Scope

Second-pass plan for deeper internal Kotlin refactoring and bilingual source documentation.

Phases 0–4 are complete. Phases 5–6 were partially delivered and incorrectly marked complete; Phase 7 closed prematurely. Phases 8–11 cover the remaining refactor work, uncommitted change reconciliation, and a final verification pass.

Planning files are updated to reflect actual delivery status before resuming implementation.

## Non-negotiable constraints

- No public API changes.
- No behavior changes.
- Preserve external behavior exactly.
- Do not change HTTP routes.
- Do not change controller method signatures.
- Do not change service public method signatures.
- Do not change DTO fields, serialized names, request/response models, or validation semantics.
- Do not change configuration keys or default values.
- Do not change database schema, SQL semantics, repository behavior, or transaction boundaries.
- Do not change error messages, exception types, HTTP status codes, CLI output, or exit codes.
- Do not upgrade dependencies.
- Do not introduce new frameworks.
- Do not reformat unrelated files.
- Do not perform opportunistic bug fixes.
- Do not remove Chinese or English comments unless they are clearly wrong, obsolete, or redundant.

## Bilingual documentation rules for later implementation

- Major business logic must have both Chinese and English explanation.
- Use KDoc for classes, public-facing internal services, important private helpers, and non-obvious algorithms.
- Use inline comments only for non-obvious branches, edge cases, domain rules, data mapping assumptions, and integration constraints.
- Do not comment obvious syntax.
- Do not add noisy comments that merely restate code.
- Keep comments concise.
- Put Chinese first, then English, for business/domain explanations.
- Keep technical identifiers, class names, method names, and config keys in English.
- If code is moved, move the relevant bilingual comments with it.
- If a comment is outdated, update it instead of deleting it.

Preferred style:

```kotlin
/**
 * 说明这段主要业务逻辑的目的、规则或边界条件。
 * Explain the purpose, rule, or edge case behind this major business logic.
 */
```

```kotlin
// 这里说明非显而易见的业务规则或边界条件。
// Explain the non-obvious business rule or edge case here.
```

## Repository baseline observed on 2026-06-29 (plan correction)

- Source root: `src/main/kotlin`.
- Test root: `src/test/kotlin` (Kotest + MockK; legacy `plans/` Groovy/Spock paths are obsolete).
- Documentation: `README.md`, `docs/development.md`.
- Build files: Groovy DSL (`build.gradle`, `settings.gradle`).
- Current HEAD: `135a15c Record final verification`.
- Second-pass commits already landed:
  - `d44a956 Plan second refactoring pass`
  - `1b592f4 Add bilingual source comments`
  - `4592a0d Add characterization tests`
  - `5861a00 Refactor ESTT orchestration` — partial Phase 5 (`withCalculationTelemetry`, `buildCalculatedResponse`)
  - `03c2bb5 Refactor history pagination` — partial Phase 6 (`HistoryPaginationScanner`)
  - `135a15c Record final verification` — Phase 7 verification only; remaining refactors not done
- Uncommitted source/test changes (Phase 10 scope):
  - `.gitignore`
  - `InvalidFlightDateException.kt`
  - `InvalidFlightDateExceptionHandler.kt`
  - `FlyingTimeCalculator.kt` (removes `ensureValidSeasonalFlight`)
  - `GlobalExceptionHandlerTest.kt`

## Summary findings

- Phases 0–4 delivered as planned: audit, bilingual comments, characterization tests.
- Phases 5–6 were only partially delivered:
  - Done: `EsttService` telemetry/response helpers; `HistoryPaginationScanner` extraction.
  - Not done: `EsttController` request-flow/error helpers; `FlyingTimeCalculator` branch/metrics extraction; further `EsttService` cache/metrics helpers.
- Phase 7 verification passed on the partial delivery but should be rerun after Phases 8–10.
- Current production file sizes:
  - `EsttService.kt`: 436 lines (partially refactored).
  - `EsttController.kt`: 239 lines (unchanged — Phase 8 target).
  - `HistoryFlightProvider.kt`: 168 lines (refactored).
  - `FlyingTimeCalculator.kt`: ~142 lines after uncommitted edit (Phase 9 target).
- Bilingual source comments are in place for major business logic (Phase 3 complete).
- Uncommitted exception-handler changes alter HTTP 400 response shape (`ErrorResponse`); reconcile in Phase 10 before final verification.

## Meaningful internal Kotlin refactoring opportunities

Highest-priority production files:

- `src/main/kotlin/com/gzzn/airport/service/EsttService.kt` — **partial (Phase 5)**
  - Done: `withCalculationTelemetry`, `buildCalculatedResponse`.
  - Remaining (optional, lower priority): metric source-tag mapper, seasonal-flight validation helper, no-estimate response helper, non-null history retrieval helper.
- `src/main/kotlin/com/gzzn/airport/resource/EsttController.kt` — **pending (Phase 8)**
  - Repeats normalize/validate/parse/log/fold/error-response patterns across endpoints.
  - Candidate private helpers: flight request normalization, common database-error response builder, common calculation-error response builder, pagination validation helper.
- `src/main/kotlin/com/gzzn/airport/service/history/HistoryFlightProvider.kt` — **done (Phase 6)**
  - Pagination scan extracted to `HistoryPaginationScanner`.
- `src/main/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculator.kt` — **pending (Phase 9)**
  - `calculate` still combines qualification, median decision, seasonal fallback, no-estimate result construction, and metrics.
  - Candidate private helpers: `historyResult`, `seasonalFallbackResult`, `noEstimateResult`, `recordHistoryMetrics`, `recordSeasonalFallbackMetrics`, median duration extraction.
- `src/main/kotlin/com/gzzn/airport/model/OperationDays.kt` — **done (Phase 3)**
- `src/main/kotlin/com/gzzn/airport/config/EsttCalculationConfig.kt` — **done (Phase 3)**
- `src/main/kotlin/com/gzzn/airport/repository/HistoryFlightRepository.kt` and `SeasonRepository.kt` — **done (Phase 3)**

Test files likely involved for characterization before risky refactors:

- `src/test/kotlin/com/gzzn/airport/resource/EsttControllerTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/EsttServiceTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/EsttServiceValidationAndMatchingTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/EsttServiceErrorTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/history/HistoryFlightProviderTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculatorTest.kt`
- `src/test/kotlin/com/gzzn/airport/model/OperationDaysTest.kt`

## Major business logic needing bilingual source documentation

Add or improve bilingual KDoc/inline comments in a later implementation phase around:

- ESTT orchestration: active season lookup, seasonal flight lookup, history lookup, calculate pipeline, no-estimate mapping.
- Date parsing: `yyMMdd` only, strict six digits, years `00..99` map to `2000..2099`, exact invalid-date exception behavior.
- Operation-day matching: encoded digits `1..7`, digit-wise matching to avoid substring false positives, invalid characters currently ignored.
- Seasonal-flight validation: DB query uses `INSTR` as a prefilter; service revalidates operation day and season date window.
- History window: start derived from season start minus `historyStartOffsetDays`; upper bound is target flight date minus one day to avoid self-sampling.
- History filters: operation day match, previous departure before actual arrival, scheduled date equals flight date, and flying-time tolerance.
- Flying-time tolerance: strict `< maxFlyingTimeDeviation`; equality is rejected; skipped when seasonal flying time is null.
- Schedule deviation: early arrivals are always accepted; late arrivals are accepted through the inclusive threshold `<= maxScheduleDeviation`.
- Median calculation: all qualified samples are sorted; even counts use integer average of middle two values; empty list returns `0` as private defensive behavior.
- Seasonal fallback/no-estimate: positive seasonal `flyingTime` falls back to source `SEASONAL`; otherwise source `NONE` and `flyingTime = null`.
- Pagination: repository scans raw rows in chunks, filters in memory, reports filtered totals capped by `maxHistoryRows`, and preserves item ordering.
- Metrics: source tag `EstimateSource.SEASONAL` maps to metric tag `schedule`; this observable tag must stay unchanged.
- Repository SQL assumptions: Oracle date truncation, arrival-only `ARRI_OR_DEPT`, inclusive `BETWEEN`, and explicit order by most recent flight date.

## Baseline verification commands for later phases

Run before implementation starts, escalating only if Gradle needs access outside the sandbox, for example `~/.gradle` wrapper/cache locks:

```bash
./gradlew test
./gradlew check
git diff --check
```

Focused commands for planned refactor zones:

```bash
./gradlew test --tests com.gzzn.airport.resource.EsttControllerTest
./gradlew test --tests com.gzzn.airport.service.EsttServiceTest --tests com.gzzn.airport.service.EsttServiceValidationAndMatchingTest --tests com.gzzn.airport.service.EsttServiceErrorTest
./gradlew test --tests com.gzzn.airport.service.history.HistoryFlightProviderTest
./gradlew test --tests com.gzzn.airport.service.calculator.FlyingTimeCalculatorTest
./gradlew test --tests com.gzzn.airport.model.OperationDaysTest
```

## Phase overview

| Phase | Status | Risk | Main outcome |
|---|---|---|---|
| Phase 0: Second-pass baseline and previous diff review | complete | Low | Established first-pass shallowness, current HEAD, and pre-existing working-tree changes. |
| Phase 1: Bilingual inline documentation audit | complete | Low | Found English-only source comments; identified domain logic needing bilingual comments. |
| Phase 2: Complexity and duplication audit | complete | Low | Identified concrete private/internal refactor opportunities. |
| Phase 3: Restore/improve bilingual KDoc and inline docs | complete | Low | Added concise Chinese-first, English-second comments for major business logic. |
| Phase 4: Characterization tests for risky behavior | complete | Medium | Added characterization tests and verified behavior before deeper refactors. |
| Phase 5: Deeper internal Kotlin refactor | complete | Medium | `EsttService` telemetry/response helpers; Phase 8–9 completed remaining controller/calculator refactors. |
| Phase 6: Split oversized private/internal components | complete | Medium | `HistoryPaginationScanner` extracted. |
| Phase 7: Final behavior verification and documentation sync | superseded | Low-Medium | Premature closure; superseded by Phase 11. |
| Phase 8: EsttController internal refactor | complete | Medium | Extracted request parsing, pagination validation, and error-response helpers. |
| Phase 9: FlyingTimeCalculator internal refactor | complete | Medium | Extracted history/seasonal/no-estimate branch builders and accuracy metrics helper. |
| Phase 10: Reconcile uncommitted exception/calculator work | complete | Medium | Standardized invalid-date handler to `ErrorResponse`; removed unused `ensureValidSeasonalFlight`. |
| Phase 11: Final verification and documentation sync (redo) | complete | Low-Medium | `./gradlew check` passed; no README/docs sync required. |

### Phase 0: Second-pass baseline and previous diff review

**Status:** complete

**Goal**

- Review current working tree, previous first-pass commits, current diff, and baseline project files before any code/doc edits.
- Identify why the previous refactor was too shallow.
- Separate existing user/unrelated changes from future second-pass changes.

**Files likely involved**

- Read-only inspection:
  - `src/main/kotlin`
  - `src/test/kotlin`
  - `README.md`
  - `docs/development.md`
  - `build.gradle`
  - `settings.gradle`
  - recent `git log`, `git diff`, and `git show` output
- Planning updates only:
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

**Allowed changes**

- Planning-file updates only.
- No source, test, README, docs, or Gradle changes.

**Forbidden changes**

- No Kotlin source edits.
- No test edits.
- No README/docs edits.
- No Gradle edits.
- No commit/reset/rebase/checkout that could overwrite user work.

**Risk level**

- Low. Inspection only; main risk is misattributing pre-existing uncommitted changes.

**Verification commands**

```bash
git status --short
git log --oneline -10
git diff --stat
git diff -- src/main/kotlin src/test/kotlin README.md docs/development.md build.gradle settings.gradle
```

**Rollback notes**

- Revert only planning-file changes if this plan is discarded.
- Do not revert current uncommitted source/test changes unless user explicitly asks.

### Phase 1: Bilingual inline documentation audit

**Status:** complete

**Goal**

- Identify major business logic that lacks clear bilingual documentation.
- Identify useful English-only comments that need Chinese explanation.
- Identify useful Chinese-only comments that need English explanation.
- Preserve any useful existing Chinese or English comments and plan to update outdated comments instead of deleting them.

**Files likely involved**

- Read-only inspection:
  - `src/main/kotlin/**/*.kt`
  - `src/test/kotlin/**/*.kt`
  - `README.md`
  - `docs/development.md`
  - relevant git history/diffs
- Candidate future source-documentation files:
  - `EsttService.kt`
  - `EsttController.kt`
  - `HistoryFlightProvider.kt`
  - `FlyingTimeCalculator.kt`
  - `OperationDays.kt`
  - `EsttCalculationConfig.kt`
  - `HistoryFlightRepository.kt`
  - `SeasonRepository.kt`
  - model files for externally serialized response semantics where currently English-only comments describe business meaning

**Allowed changes**

- Planning-file notes only in this phase.

**Forbidden changes**

- Do not add, remove, or rewrite source comments yet.
- Do not touch README/docs yet.
- Do not alter source behavior.

**Risk level**

- Low during audit. Later comment changes are runtime-safe but carry maintainability risk if comments become noisy or inaccurate.

**Verification commands**

```bash
rg -n "[一-龥]" src/main/kotlin src/test/kotlin README.md docs/development.md build.gradle settings.gradle || true
rg -n "^\s*/\*\*|^\s*//" src/main/kotlin src/test/kotlin || true
git diff 7fa7ddc..0094d4d -- src/main/kotlin src/test/kotlin
```

**Rollback notes**

- Revert planning notes if the audit is inaccurate.
- During later implementation, keep comment-only hunks separately reviewable and reversible.

### Phase 2: Complexity and duplication audit

**Status:** complete

**Goal**

- Find Kotlin files with real internal complexity, duplication, oversized functions/classes, unclear naming, or unclear boundaries.
- Prioritize meaningful private/internal refactors that preserve public API and behavior.

**Files likely involved**

- `src/main/kotlin/com/gzzn/airport/service/EsttService.kt`
- `src/main/kotlin/com/gzzn/airport/resource/EsttController.kt`
- `src/main/kotlin/com/gzzn/airport/service/history/HistoryFlightProvider.kt`
- `src/main/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculator.kt`
- `src/main/kotlin/com/gzzn/airport/model/OperationDays.kt`
- `src/main/kotlin/com/gzzn/airport/config/EsttCalculationConfig.kt`
- `src/main/kotlin/com/gzzn/airport/repository/HistoryFlightRepository.kt`
- `src/main/kotlin/com/gzzn/airport/repository/SeasonRepository.kt`
- Related focused tests under `src/test/kotlin`.

**Allowed changes**

- Planning-file notes only in this phase.

**Forbidden changes**

- No source refactor edits yet.
- No test edits yet.
- No documentation edits yet.

**Risk level**

- Low during audit. The riskiest future areas are pagination semantics, controller errors, metric tags, result mapping, and exception wrapping.

**Verification commands**

```bash
find src/main/kotlin src/test/kotlin -name '*.kt' -print0 | xargs -0 wc -l | sort -nr | head -45
rg -n "fold\(|runCatching|return@runCatching|MDC|meterRegistry|Duration\.between|OperationDays\.matches|getArrivalFlightPage|HttpResponse\.serverError|HttpResponse\.badRequest" src/main/kotlin src/test/kotlin
```

**Rollback notes**

- Revert planning notes if priorities change.

### Phase 3: Restore/improve bilingual KDoc and inline docs

**Status:** complete

**Goal**

- Restore or improve bilingual source documentation where major business logic is currently English-only or undocumented.
- Add Chinese explanation where English-only comments describe important business rules.
- Add English explanation if any Chinese-only comments are introduced or later found.
- Keep comments concise, Chinese first then English for business/domain explanations.

**Files likely involved**

- `src/main/kotlin/com/gzzn/airport/model/OperationDays.kt`
- `src/main/kotlin/com/gzzn/airport/service/history/HistoryFlightProvider.kt`
- `src/main/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculator.kt`
- `src/main/kotlin/com/gzzn/airport/service/EsttService.kt`
- `src/main/kotlin/com/gzzn/airport/resource/EsttController.kt`
- `src/main/kotlin/com/gzzn/airport/config/EsttCalculationConfig.kt`
- `src/main/kotlin/com/gzzn/airport/repository/HistoryFlightRepository.kt`
- `src/main/kotlin/com/gzzn/airport/repository/SeasonRepository.kt`
- Selected model files only where comments explain externally visible business semantics.

**Allowed changes**

- Add or improve KDoc/inline comments.
- Move comments together with the code they explain.
- Update outdated comments instead of deleting them.
- Preserve existing useful English comments by making them bilingual where needed.

**Forbidden changes**

- No executable logic changes in this phase.
- No changes to tests, README, `docs/development.md`, Gradle files, SQL, annotations, routes, signatures, DTOs, config keys/defaults, validation semantics, messages, logs, or metrics.
- No noisy comments that merely restate syntax.

**Risk level**

- Low for runtime behavior; medium for maintainability if comments drift from code.

**Verification commands**

```bash
git diff --check
./gradlew spotlessCheck
```

**Rollback notes**

- Revert comment-only hunks if comments are inaccurate, noisy, or fail formatting.

### Phase 4: Characterization tests for risky behavior

**Status:** complete

**Goal**

- Add focused characterization tests before refactoring risky behavior areas.
- Lock current behavior for controller errors, pagination metadata, calculator fallback/metrics, operation-day parsing, no-estimate response mapping, and date parsing edge cases.

**Files likely involved**

- `src/test/kotlin/com/gzzn/airport/resource/EsttControllerTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/EsttServiceTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/EsttServiceValidationAndMatchingTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/EsttServiceErrorTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/history/HistoryFlightProviderTest.kt`
- `src/test/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculatorTest.kt`
- `src/test/kotlin/com/gzzn/airport/model/OperationDaysTest.kt`

**Allowed changes**

- Add tests that assert current behavior only.
- Add private test helper functions/fixtures when they reduce duplication.

**Forbidden changes**

- No production code changes in this phase.
- No changing existing expectations to redefine behavior.
- Do not enable currently disabled integration/repository tests unless explicitly requested.
- Do not fix behavior opportunistically if characterization reveals a bug.

**Risk level**

- Medium. New tests can expose pre-existing behavior or conflict with uncommitted user changes.

**Verification commands**

```bash
./gradlew test --tests com.gzzn.airport.resource.EsttControllerTest
./gradlew test --tests com.gzzn.airport.service.EsttServiceTest --tests com.gzzn.airport.service.EsttServiceValidationAndMatchingTest --tests com.gzzn.airport.service.EsttServiceErrorTest
./gradlew test --tests com.gzzn.airport.service.history.HistoryFlightProviderTest
./gradlew test --tests com.gzzn.airport.service.calculator.FlyingTimeCalculatorTest
./gradlew test --tests com.gzzn.airport.model.OperationDaysTest
./gradlew test
```

**Rollback notes**

- Revert only newly added/changed tests if they incorrectly characterize behavior.
- Record unexpected behavior in `findings.md` before deciding next steps.

### Phase 5: Deeper internal Kotlin refactor

**Status:** complete

**Delivered**

- `EsttService.withCalculationTelemetry` — MDC/timer lifecycle wrapper.
- `EsttService.buildCalculatedResponse` — calculator-result-to-response mapping.
- Remaining controller/calculator refactors completed in Phases 8–9.

**Original goal**

- Perform meaningful internal simplification without public API or behavior changes.
- Reduce private duplication, oversized private methods, and nested branching in the main hotspots.

**Files likely involved**

- `src/main/kotlin/com/gzzn/airport/service/EsttService.kt`
- `src/main/kotlin/com/gzzn/airport/resource/EsttController.kt`
- `src/main/kotlin/com/gzzn/airport/service/history/HistoryFlightProvider.kt`
- `src/main/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculator.kt`
- Existing focused tests from Phase 4.

**Allowed changes**

- Extract private functions.
- Extract private/internal helper classes.
- Rename private/local variables or private functions when safe.
- Reduce duplicated internal logic.
- Simplify nested conditionals without changing branch behavior.
- Move private implementation details when public API remains unchanged.
- Split oversized private/internal functions.
- Move bilingual comments together with the code they explain.

**Forbidden changes**

- No public class/function/property signature changes.
- No controller method signature changes.
- No service public method signature changes.
- No route/annotation behavior changes.
- No DTO/model shape changes.
- No nullable/default-value behavior changes.
- No collection ordering or lazy/eager evaluation changes.
- No coroutine/blocking execution changes.
- No exception wrapping changes.
- No log/error text changes if tests or users may rely on it.
- No metric name/tag, SQL, transaction, dependency, or Gradle behavior changes.

**Risk level**

- Medium. Internal refactors touch behavior-sensitive orchestration, pagination, metrics, and controller response mapping.

**Verification commands**

```bash
./gradlew test --tests com.gzzn.airport.resource.EsttControllerTest
./gradlew test --tests com.gzzn.airport.service.EsttServiceTest --tests com.gzzn.airport.service.EsttServiceValidationAndMatchingTest --tests com.gzzn.airport.service.EsttServiceErrorTest
./gradlew test --tests com.gzzn.airport.service.history.HistoryFlightProviderTest
./gradlew test --tests com.gzzn.airport.service.calculator.FlyingTimeCalculatorTest
./gradlew test
```

**Rollback notes**

- Prefer small cohesive hunks per hotspot.
- If a focused test fails, revert the smallest refactor hunk first rather than changing expected behavior.

### Phase 6: Split oversized private/internal components

**Status:** complete

**Delivered**

- `HistoryPaginationScanner` extracted from `HistoryFlightProvider`.

**Original goal**

- Split oversized private/internal components only where Phase 5 leaves clearly separable responsibilities.
- Improve boundaries without changing public imports, bean behavior, or external API.

**Files likely involved**

- Potential new internal/private implementation files under existing packages:
  - `src/main/kotlin/com/gzzn/airport/service/...`
  - `src/main/kotlin/com/gzzn/airport/service/history/...`
  - `src/main/kotlin/com/gzzn/airport/service/calculator/...`
  - `src/main/kotlin/com/gzzn/airport/resource/...`
- Existing tests for affected packages.

**Allowed changes**

- Extract internal helper classes/data classes in the same package.
- Extract private file-level helpers where safe.
- Keep helper names implementation-focused and non-public where possible.
- Move bilingual comments with extracted logic.

**Forbidden changes**

- No package moves that affect public imports.
- No new framework abstractions.
- No changed injection graph unless behavior and bean selection remain identical.
- No public API, DTO, route, SQL, config, message, metric, or log-text changes.

**Risk level**

- Medium. New files/classes can accidentally expand API surface or alter Micronaut bean discovery.

**Verification commands**

```bash
./gradlew test
./gradlew check
git diff --check
```

Focused reruns based on touched files:

```bash
./gradlew test --tests com.gzzn.airport.service.history.HistoryFlightProviderTest
./gradlew test --tests com.gzzn.airport.service.calculator.FlyingTimeCalculatorTest
./gradlew test --tests com.gzzn.airport.resource.EsttControllerTest
```

**Rollback notes**

- Revert extracted helper file and corresponding call-site hunk together.
- If bean discovery or tests change unexpectedly, inline helper back into original file.

### Phase 7: Final behavior verification and documentation sync

**Status:** superseded

**Note**

- `./gradlew check` passed at commit `135a15c`, but Phases 5–6 were incomplete.
- Do not treat Phase 7 as the final gate; rerun verification in Phase 11 after Phases 8–10.

**Original goal**

- Verify external behavior is unchanged after all approved implementation phases.
- Sync README/developer documentation only if internal structure documented there changed.

**Files likely involved**

- Verification only:
  - all source/test files touched in Phases 3-6
- Documentation sync, if necessary:
  - `README.md`
  - `docs/development.md`
- Planning updates:
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

**Allowed changes**

- Run full verification.
- Update README/docs only to keep implementation-structure descriptions accurate after the refactor.
- Record final verification and residual known risks.

**Forbidden changes**

- No late behavior changes.
- No dependency upgrades.
- No opportunistic bug fixes.
- No broad reformatting.
- No documentation claims not verified against the final tree.

**Risk level**

- Low-Medium. Verification is low-risk; documentation sync can accidentally imply unsupported behavior if not checked.

**Verification commands**

```bash
git diff --check
./gradlew test
./gradlew check
git status --short
git diff --stat
```

Optional if packaging/runtime behavior must be checked:

```bash
./gradlew assembleDist
./gradlew shadowJar
```

**Rollback notes**

- Revert documentation-sync hunks independently if inaccurate.
- For source refactors, revert the smallest cohesive commit/hunk that caused verification failure.
- Preserve pre-existing user changes unless explicitly instructed otherwise.

### Phase 8: EsttController internal refactor

**Status:** complete

**Goal**

- Extract private request-flow and error-response helpers from `EsttController.kt` without changing routes, method signatures, HTTP statuses, or messages.

**Files likely involved**

- `src/main/kotlin/com/gzzn/airport/resource/EsttController.kt`
- `src/test/kotlin/com/gzzn/airport/resource/EsttControllerTest.kt`

**Candidate extractions**

- Flight-number normalization and validation flow.
- Pagination parameter validation.
- Shared `DATABASE_ERROR` and `CALCULATION_ERROR` response builders.
- Common parse-date / fold-Result endpoint pattern.

**Verification commands**

```bash
./gradlew test --tests com.gzzn.airport.resource.EsttControllerTest
./gradlew check
```

### Phase 9: FlyingTimeCalculator internal refactor

**Status:** complete

**Goal**

- Split `FlyingTimeCalculator.calculate` into private branch builders and metric helpers without changing decision logic, metric tags, or log messages.

**Files likely involved**

- `src/main/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculator.kt`
- `src/test/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculatorTest.kt`

**Candidate extractions**

- `historyResult`, `seasonalFallbackResult`, `noEstimateResult`.
- `recordHistoryMetrics`, `recordSeasonalFallbackMetrics`.
- Median duration extraction helper.

**Verification commands**

```bash
./gradlew test --tests com.gzzn.airport.service.calculator.FlyingTimeCalculatorTest
./gradlew check
```

### Phase 10: Reconcile uncommitted exception/calculator work

**Status:** complete

**Goal**

- Resolve uncommitted working-tree changes that were outside the original phase plan but affect observable behavior.

**Files involved**

- `InvalidFlightDateException.kt`
- `InvalidFlightDateExceptionHandler.kt` — response shape changed from `Map` to `ErrorResponse`
- `FlyingTimeCalculator.kt` — `ensureValidSeasonalFlight` removed
- `GlobalExceptionHandlerTest.kt`
- `.gitignore`

**Allowed changes**

- Commit intentional changes with tests that lock the chosen contract.
- Revert changes that violate the no-behavior-change constraint.
- Update README/OpenAPI only if the committed response contract changes.

**Verification commands**

```bash
./gradlew test --tests com.gzzn.airport.exception.GlobalExceptionHandlerTest
./gradlew test --tests com.gzzn.airport.resource.EsttControllerTest
./gradlew check
```

### Phase 11: Final verification and documentation sync (redo)

**Status:** complete

**Goal**

- Run full verification after Phases 8–10 complete.
- Sync `README.md` and `docs/development.md` if internal structure or error-response contract changed.

**Verification commands**

```bash
git diff --check
./gradlew test
./gradlew check
git status --short
```

## Errors encountered during planning

| Error | Attempt | Resolution |
|---|---|---|
| `~/.codex/skills/planning-with-files/scripts/session-catchup.py` not found | Earlier planning attempt used the home-path command from the skill docs | Used the repository-local skill script `.codex/skills/planning-with-files/scripts/session-catchup.py`, which completed successfully. |

## Current stop point

Phases 0–11 complete. Second refactoring pass finished 2026-06-29.
