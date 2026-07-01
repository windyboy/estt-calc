# ESTT Kotlin Simplification and Documentation Plan

## Scope

First-phase planning artifact for simplifying Kotlin code and improving documentation in this Micronaut/Kotlin project.

## Non-negotiable constraints

- Preserve existing behavior.
- Do not change public APIs unless explicitly approved.
- Do not change CLI arguments or output format.
- Do not change error messages or exit codes.
- Do not upgrade dependencies.
- Do not introduce new frameworks.
- Do not perform opportunistic bug fixes.
- Do not reformat unrelated files.

## Repository snapshot

- Build files present: `settings.gradle`, `build.gradle`, `gradle.properties`.
- Requested Kotlin DSL files `settings.gradle.kts` and `build.gradle.kts` are not present; this project currently uses Groovy Gradle files.
- Main source root: `src/main/kotlin/com/gzzn/airport`.
- Test source root: `src/test/kotlin/com/gzzn/airport`.
- README present: `README.md`.
- `docs/` directory not present.
- Existing `plans/` directory contains prior planning notes; this plan is the active root-level planning-with-files plan.

## Baseline verification commands identified

Use wrapper commands unless explicitly investigating installed Gradle behavior:

```bash
./gradlew test
./gradlew check
./gradlew spotlessCheck
./gradlew koverHtmlReport
./gradlew run
./gradlew assembleDist
./gradlew shadowJar
```

Useful focused commands:

```bash
./gradlew test --tests com.gzzn.airport.service.EsttServiceTest
./gradlew test --tests com.gzzn.airport.service.history.HistoryFlightProviderTest
./gradlew test --tests com.gzzn.airport.service.calculator.FlyingTimeCalculatorTest
./gradlew test --tests com.gzzn.airport.resource.EsttControllerTest
```

## Current phase status

| Phase | Status | Notes |
|---|---|---|
| Phase 0: Baseline verification | complete | Clean baseline completed successfully with `./gradlew clean test` and `./gradlew build`. |
| Phase 1: Documentation inventory | complete | Documentation inventory completed: README inspected, original `docs/` absence recorded, and documentation gaps captured in `findings.md`. |
| Phase 2: Low-risk documentation improvements | complete | README quick start added and `docs/development.md` created with workflow, structure, configuration, troubleshooting, and limitations. |
| Phase 3: Characterization tests | complete | Added characterization tests for schedule-deviation boundaries, integer median behavior, and flying-time tolerance filtering. |
| Phase 4: Low-risk Kotlin simplification | complete | Simplified private history-window/filtering helpers in `HistoryFlightProvider` while preserving behavior. |
| Phase 5: Split oversized classes/functions | complete | Extracted calculation input validation from `EsttService` into an internal helper while preserving public API and behavior. |
| Phase 6: Final verification | complete | Final `./gradlew check`, `git diff --check`, and status review completed successfully; remaining changes are pre-existing/unrelated. |

### Phase 0: Baseline verification

**Status:** complete

Baseline commands completed successfully on 2026-07-01:

```bash
./gradlew clean test
./gradlew build
```

Result: both commands exited with code 0. This is the clean baseline before documentation or Kotlin simplification changes.

1. **Goal**
   - Establish a clean, reproducible baseline before any source or documentation edits.
   - Confirm build/test commands and record failures, skips, warnings, and generated reports.

2. **Files likely involved**
   - Read-only: `settings.gradle`, `build.gradle`, `gradle.properties`, `README.md`.
   - Generated outputs only: `build/reports/**`, `build/test-results/**`, `.gradle/**`.
   - Planning updates: `progress.md`, `findings.md`, `task_plan.md`.

3. **Allowed changes**
   - Planning-file updates only.
   - Build/test generated artifacts if commands are run.

4. **Forbidden changes**
   - No source code edits.
   - No README/docs edits.
   - No dependency upgrades.
   - No Gradle configuration changes.

5. **Risk level**
   - Low. Test/build commands may update generated files but should not alter source behavior.

6. **Verification commands**
   ```bash
   ./gradlew test
   ./gradlew check
   ./gradlew spotlessCheck
   ./gradlew koverHtmlReport
   ```

7. **Rollback notes**
   - Remove generated build artifacts if needed with an approved cleanup command.
   - Revert only planning-file edits if the plan needs to be discarded.

### Phase 1: Documentation inventory

**Status:** complete

Documentation inventory completed on 2026-07-01. Findings were recorded in `findings.md`, including:

- README already contained extensive user/API/build/test information.
- `docs/` was absent at inventory time.
- Developer workflow, troubleshooting, project structure, and known limitations needed clearer standalone documentation.
- Version-history inconsistencies should be treated carefully and not changed without approval.

No source code, test code, README, docs, or Gradle files were modified during the inventory itself.

1. **Goal**
   - Identify missing, stale, redundant, or unclear developer and user documentation.
   - Compare README claims against Gradle configuration, source packages, resources, tests, and existing build reports.

2. **Files likely involved**
   - Read-only: `README.md`, `CHANGELOG.md`, `Dockerfile`, `.drone.yml`, `micronaut-cli.yml`, `qodana.yaml`, `src/main/resources/**`, `src/test/resources/**`, `plans/**`.
   - `docs/` if created later; currently absent.
   - Planning updates only in this phase.

3. **Allowed changes**
   - Inventory notes in `findings.md` and status notes in `progress.md`.

4. **Forbidden changes**
   - No README/docs edits yet.
   - No code edits.
   - No dependency or build changes.

5. **Risk level**
   - Low.

6. **Verification commands**
   ```bash
   find . -maxdepth 3 -type f | sort
   find src/main/kotlin src/test/kotlin -name '*.kt' -print0 | xargs -0 wc -l | sort -nr
   grep -R "xdescribe\|TODO\|FIXME" -n README.md src/main/kotlin src/test/kotlin
   ```

7. **Rollback notes**
   - Revert planning files if inventory notes are incorrect.

### Phase 2: Low-risk documentation improvements

**Status:** complete

Completed documentation-only updates on 2026-07-01:

- Added a README Quick Start section with baseline verification and local run commands.
- Added README links to developer workflow documentation.
- Created `docs/development.md` covering common commands, project structure, configuration workflow, Micronaut/Kotlin development workflow, troubleshooting, and known limitations.

No source code, test code, or Gradle files were modified for this phase.

1. **Goal**
   - Improve README and/or add developer docs without touching production code.
   - Document usage examples, build/test/run workflows, project structure, troubleshooting, and known test skips.

2. **Files likely involved**
   - `README.md`.
   - Potential new `docs/` files, for example `docs/development.md`, `docs/testing.md`, or `docs/troubleshooting.md` if approved.
   - Possibly `CHANGELOG.md` only if explicitly requested; otherwise avoid.

3. **Allowed changes**
   - Documentation-only edits.
   - Clarify existing Gradle files are Groovy DSL (`build.gradle`, `settings.gradle`) unless the project is later migrated by explicit approval.
   - Add exact wrapper commands and environment-variable explanations based on current config.
   - Add troubleshooting notes for JDK 25, Oracle env vars, generated reports, disabled integration tests, and local logs.

4. **Forbidden changes**
   - No production or test source edits.
   - No dependency upgrades.
   - No behavior, API, CLI, output-format, error-message, or exit-code changes.
   - No generated doc refresh that requires code-generation changes unless explicitly approved.

5. **Risk level**
   - Low, but stale documentation can mislead users; verify every command/path against repository state.

6. **Verification commands**
   ```bash
   ./gradlew test
   ./gradlew spotlessCheck
   ```
   Optional, if only markdown changed and project lacks markdown linting: inspect rendered markdown manually.

7. **Rollback notes**
   - Revert README/docs changes only.
   - Do not revert generated files unless they were produced by verification and are unwanted.

### Phase 3: Characterization tests

**Status:** complete

Completed on 2026-07-01. Added characterization tests covering:

- Schedule-deviation boundary: exactly `maxScheduleDeviation` late remains eligible.
- Schedule-deviation boundary: one minute beyond `maxScheduleDeviation` is rejected before history count.
- Even-sized history median uses truncated integer average.
- Flying-time deviation tolerance is exclusive at `maxFlyingTimeDeviation`.
- Flying-time tolerance filtering is skipped when seasonal flying time is not configured.

Verification completed successfully with focused tests and the full test suite.

1. **Goal**
   - Lock down existing behavior before refactoring, especially API payloads, error behavior, date parsing, filtering boundaries, pagination, and metrics side effects where practical.

2. **Files likely involved**
   - `src/test/kotlin/com/gzzn/airport/service/EsttServiceTest.kt`.
   - `src/test/kotlin/com/gzzn/airport/service/EsttServiceEdgeCaseTest.kt`.
   - `src/test/kotlin/com/gzzn/airport/service/EsttServiceValidationAndMatchingTest.kt`.
   - `src/test/kotlin/com/gzzn/airport/service/history/HistoryFlightProviderTest.kt`.
   - `src/test/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculatorTest.kt`.
   - `src/test/kotlin/com/gzzn/airport/resource/EsttControllerTest.kt`.

3. **Allowed changes**
   - Add or improve tests that describe current behavior.
   - Prefer small, focused tests around private-refactor targets via public/internal behavior.
   - Use existing Kotest/MockK patterns only.

4. **Forbidden changes**
   - No production source changes.
   - No test changes that assert desired-but-not-current behavior.
   - No new testing frameworks or dependency upgrades.
   - Do not enable currently disabled integration tests unless explicitly approved and baseline dependencies are available.

5. **Risk level**
   - Medium-low. Tests can accidentally encode misunderstood behavior; compare against current implementation and reports.

6. **Verification commands**
   ```bash
   ./gradlew test
   ./gradlew test --tests com.gzzn.airport.service.EsttServiceTest
   ./gradlew test --tests com.gzzn.airport.service.history.HistoryFlightProviderTest
   ./gradlew test --tests com.gzzn.airport.service.calculator.FlyingTimeCalculatorTest
   ```

7. **Rollback notes**
   - Revert only added/modified tests from this phase.
   - Keep planning notes about uncovered behavior even if test approach is rolled back.

### Phase 4: Low-risk Kotlin simplification

**Status:** complete

Completed on 2026-07-01. Low-risk private simplification only:

- Extracted a private `HistoryWindow` helper in `HistoryFlightProvider` to remove duplicated start/end window setup.
- Renamed the private history predicate from `isHistoryFlight` to `isEligibleHistoryFlight` for clearer intent.
- Simplified nullable seasonal flying-time tolerance logic without changing threshold behavior.

Verification completed successfully with focused history-provider tests and the full test suite.

1. **Goal**
   - Simplify implementation while preserving behavior and public API.
   - Reduce duplicated logic, extract private helpers, simplify branches, and improve private names where safe.

2. **Files likely involved**
   - `src/main/kotlin/com/gzzn/airport/service/EsttService.kt`.
   - `src/main/kotlin/com/gzzn/airport/service/history/HistoryFlightProvider.kt`.
   - `src/main/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculator.kt`.
   - `src/main/kotlin/com/gzzn/airport/resource/EsttController.kt`.
   - Potentially `src/main/kotlin/com/gzzn/airport/util/ResultExtensions.kt` and model helpers.

3. **Allowed changes**
   - Extract private helper functions/classes where behavior is clearly preserved.
   - Consolidate duplicated date-duration calculation if no public API changes are required.
   - Improve private/local variable names.
   - Simplify branch structure without changing messages, status codes, response fields, metrics names/tags, cache names, or log semantics materially.

4. **Forbidden changes**
   - No public API signature changes.
   - No endpoint path, parameter, output, error-message, or status-code changes.
   - No repository SQL semantic changes.
   - No dependency upgrades or framework additions.
   - No opportunistic bug fixes.
   - No broad reformatting.

5. **Risk level**
   - Medium. Main risk is subtle behavior changes in filtering boundaries, fallback decisions, error propagation, caching, or metrics.

6. **Verification commands**
   ```bash
   ./gradlew test
   ./gradlew check
   ./gradlew spotlessCheck
   ```
   Run focused tests around touched code first, then full suite.

7. **Rollback notes**
   - Revert only the smallest refactor commit/patch if a behavior mismatch appears.
   - Keep characterization tests when they correctly describe existing behavior.

### Phase 5: Split oversized classes/functions

**Status:** complete

Completed on 2026-07-01 with a minimal cohesive split:

- Added internal `EsttInputValidator` to hold calculation input validation rules previously embedded in `EsttService`.
- Updated private `EsttService.validateInputs(...)` to delegate to the new helper.
- Preserved existing validation messages and public service/controller APIs.

Verification completed successfully with focused service tests, full test suite, and `./gradlew check`.

1. **Goal**
   - Split cohesive logic from oversized classes/functions into smaller files/classes while preserving public API and behavior.

2. **Files likely involved**
   - `src/main/kotlin/com/gzzn/airport/service/EsttService.kt` (434 lines; orchestration, validation, caching, metrics, response building).
   - `src/main/kotlin/com/gzzn/airport/resource/EsttController.kt` (239 lines; endpoint handling, validation, OpenAPI annotations).
   - `src/main/kotlin/com/gzzn/airport/service/history/HistoryFlightProvider.kt` (197 lines; retrieval, pagination scan, filtering, metrics).
   - `src/main/kotlin/com/gzzn/airport/service/calculator/FlyingTimeCalculator.kt` (154 lines; qualification, median, fallback, metrics).
   - New internal/private collaborators only if justified and covered by tests.

3. **Allowed changes**
   - Introduce package-private/internal collaborators that preserve existing externally visible classes and methods.
   - Move cohesive private logic, such as validation or metrics recording, behind injected or private helpers only if behavior is locked by tests.
   - Keep source packages consistent with current domain boundaries unless an explicit package-boundary plan is approved.

4. **Forbidden changes**
   - No breaking public API changes.
   - No endpoint/controller contract changes.
   - No database query behavior changes.
   - No changes to cache names, metric names/tags, messages, or status codes unless explicitly approved.
   - No new frameworks.

5. **Risk level**
   - Medium-high. Splits can affect Micronaut injection/proxying, cache annotations, circuit breaker behavior, test mocks, and visibility.

6. **Verification commands**
   ```bash
   ./gradlew test
   ./gradlew check
   ./gradlew koverHtmlReport
   ```

7. **Rollback notes**
   - Prefer one cohesive split per commit/patch so it can be reverted independently.
   - If Micronaut injection/proxy behavior changes, revert the split and consider a private-helper-only approach.

### Phase 6: Final verification

**Status:** complete

Completed on 2026-07-01. Final verification results:

- Created Phase 5 commit `d6a84fa Extract ESTT input validation`.
- `git diff --check` passed.
- `./gradlew check` passed.
- `git status --short` reviewed; remaining uncommitted files are pre-existing/unrelated to the completed phases in this plan.

No additional behavior changes were made during final verification.

1. **Goal**
   - Prove the completed documentation/test/refactor work preserved behavior and meets constraints.
   - Update `progress.md` with final command results and remaining risks.

2. **Files likely involved**
   - Planning files.
   - Any files intentionally modified in prior approved phases.
   - Generated reports under `build/reports/**`.

3. **Allowed changes**
   - Final planning updates.
   - Documentation corrections if verification finds docs-only mistakes.
   - Test-only corrections if characterization tests were inaccurate, with care.

4. **Forbidden changes**
   - No new refactors during final verification.
   - No opportunistic bug fixes.
   - No unrelated formatting.

5. **Risk level**
   - Low-medium depending on prior phase scope.

6. **Verification commands**
   ```bash
   ./gradlew clean test check
   ./gradlew koverHtmlReport
   git diff --check
   git status --short
   ```
   Note: `clean` removes generated build outputs; request confirmation if cleanup would be disruptive.

7. **Rollback notes**
   - Use `git diff` to identify exact scope.
   - Revert by phase if needed, starting with the most recent phase.
   - Preserve planning files unless the user asks to discard them.

## Errors encountered during planning setup

| Error | Attempt | Resolution |
|---|---|---|
| Skill catchup script path from generic instructions was missing at `~/.codex/skills/planning-with-files/scripts/session-catchup.py`. | Ran the generic command from the skill instructions. | Re-ran the catchup script from the project-local skill path `.codex/skills/planning-with-files/scripts/session-catchup.py`; it exited successfully with no output. |
