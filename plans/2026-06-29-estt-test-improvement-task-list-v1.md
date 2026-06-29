# ESTT Test Improvement Task List v1

**Date:** 2026-06-29  
**Project:** `estt-calc-kotlin`  
**Purpose:** Turn the unit-test review gaps into an actionable checklist.  
**Status:** Complete
**Source:** `plans/2026-06-29-estt-logic-code-review-v1.md` §6.2 / §6.3 and follow-up test coverage review

---

## 1. Goal

Improve confidence beyond the current strong core service coverage by adding targeted tests for date edge cases, HTTP success/no-estimate contracts, and configuration migration behavior.

Current baseline:

- Core `EsttService` algorithm coverage is strong.
- `OperationDaysSpec` already covers empty string, invalid digits (`"89"`), duplicates (`"111"`), and invalid chars.
- HTTP coverage exists for invalid dates and `/flyTime` happy path.
- Remaining gaps are mostly date edge cases, endpoint contracts, and deployment/config migration clarity.

---

## 2. P0 — Add Before Merge / Release

### Task 1. Date parsing edge cases

Add tests to `src/test/groovy/com/gzzn/airport/EsttServiceSpec.groovy`.

- [x] `000101` parses to `DateTime.parse("2000-01-01T00:00:00").toDate()`
- [x] `991231` parses to `DateTime.parse("2099-12-31T00:00:00").toDate()`
- [x] `240229` parses to `DateTime.parse("2024-02-29T00:00:00").toDate()`
- [x] `230229` is rejected as an invalid leap day
- [x] Unsupported configured `dateFormat` throws `InvalidFlightDateException` by constructing `new EsttService(..., "MMddyy", ...)`

**Acceptance criteria:**

- Tests assert the exact parsed `Date` for valid cases using the JVM/system-default zone consistently with production.
- Tests prove the documented `yyMMdd` two-digit-year rule: `00–99 -> 2000–2099`.
- Invalid date failures use `InvalidFlightDateException`.

---

### Task 2. HTTP no-estimate response shape

Add tests to `src/test/groovy/com/gzzn/airport/EsttControllerSpec.groovy`. Use a minimal fixture: empty seasonal/history tables are enough; do not reuse the happy-path fixture.

- [x] `/estt/flyTime/{flightNumber}/{date}` with no matching seasonal flight returns HTTP 200
- [x] Response has `source == "NONE"`
- [x] Response has `flyingTime == null`
- [x] Response has `history == false`, `seasonal == false`
- [x] Response has `confidence == "NONE"`

**Acceptance criteria:**

- Confirms the API v2 no-estimate contract at HTTP level.
- Do not assert exact `message` text except as an optional ops-wording smoke check.

---

### Task 3. HTTP success coverage for `/seasonal` and `/history`

Extend `src/test/groovy/com/gzzn/airport/EsttControllerSpec.groovy` using the existing H2 fixture setup.

- [x] `/estt/seasonal/{flightNumber}/{date}` returns a matching seasonal row
- [x] Seasonal response normalizes lowercase flight number input to uppercase lookup
- [x] `/estt/seasonal/{flightNumber}/{date}` with no matching operation day pins current nullable behavior: HTTP 200 with null/empty body, not 404
- [x] `/estt/history/{flightNumber}/{date}` returns filtered history rows
- [x] `/estt/history` target-date exclusion fixture inserts one row on the target date and one row before it; assert only the prior row appears in the HTTP response

**Acceptance criteria:**

- Covers HTTP wiring for all date-taking endpoints, not only `/flyTime`.
- Confirms target-date exclusion is visible through the HTTP history endpoint.

---

## 3. P1 — Compatibility / Maintainability

### Task 4. Deprecated config migration behavior

Decide and test the intended behavior for deployments that still set only `default.maxHistoryDelay`.

Decision: fallback is intentionally removed. Do **not** restore the nested `@Value` alias unless a new product/deployment decision explicitly requires backward compatibility.

- [x] Add a Micronaut/config test showing `default.maxScheduleDeviation` is the active key
- [x] Add a test or doc assertion that `default.maxHistoryDelay` alone is not relied on
- [x] Ensure related plans/review docs consistently say deprecated-key fallback is removed and custom YAML must migrate to `default.maxScheduleDeviation`

**Acceptance criteria:**

- The repo has executable coverage or explicit docs for the deprecated-key migration.
- No ambiguity remains for custom deployment YAMLs.

---

### Task 5. Parameterize schedule-deviation boundary tests

Refactor existing schedule-deviation tests in `EsttServiceSpec.groovy` with Spock `@Unroll` where useful.

Existing tests already cover 30 minutes accepted, 31 minutes rejected, and early arrival accepted. The net-new boundary case is 29 minutes accepted.

- [x] Refactor schedule deviation checks into `@Unroll` with columns `lateMinutes | accepted`
- [x] Include `29 | true` as the missing net-new case
- [x] Preserve `30 | true` and `31 | false` coverage
- [x] Keep a separate early-arrival case showing negative deviation is accepted

**Acceptance criteria:**

- Boundary semantics are obvious from a compact table.
- Existing behavior remains unchanged.

---

### Task 6. HTTP 400 response body contract

Strengthen invalid-date HTTP tests in `EsttControllerSpec.groovy`.

- [x] Assert response body contains a `message` key
- [x] Assert body message is non-empty
- [x] Do not require exact wording unless API consumers depend on it

**Acceptance criteria:**

- Confirms error body shape without over-coupling to text.

---

## 4. P2 — Optional Integration Quality

### Task 7. Improve H2 history fixture realism if practical

Current `/flyTime` HTTP happy path uses date-spanning rows to work around H2/Micronaut mapping behavior. Keep it if necessary, but investigate whether H2 schema or column mapping can preserve time-of-day more realistically.

- [x] Try H2 column types / aliases that preserve timestamp time-of-day in Micronaut Data mapping
- [ ] If feasible, change fixture to realistic same-day flight timestamps (not feasible — H2 strips time-of-day)
- [x] If not feasible, add a short comment explaining why 1440-minute rows are used in the HTTP smoke test

**Acceptance criteria:**

- Either the HTTP fixture becomes realistic, or the artificial fixture is clearly documented.

---

## 5. Definition of Done

- [x] `./gradlew test` passes
- [x] `./gradlew check` passes
- [x] New tests are grouped by behavior with clear names
- [x] No test asserts `message` as a stable consumer contract
- [x] HTTP tests cover success and invalid-date paths for all date-taking endpoints
- [x] Date parsing edge cases document the `yyMMdd` contract as executable tests

---

## 6. References

- `src/test/groovy/com/gzzn/airport/EsttServiceSpec.groovy`
- `src/test/groovy/com/gzzn/airport/EsttControllerSpec.groovy`
- `src/test/groovy/com/gzzn/airport/EsttConfigSpec.groovy`
- `src/test/groovy/com/gzzn/airport/EsttLegacyConfigSpec.groovy`
- `src/test/groovy/com/gzzn/airport/OperationDaysSpec.groovy`
- `src/main/kotlin/com/gzzn/airport/service/EsttService.kt`
- `src/main/kotlin/com/gzzn/airport/exception/InvalidFlightDateExceptionHandler.kt`
- `plans/2026-06-29-estt-logic-code-review-v1.md` §6.2 / §6.3
- `plans/2026-06-29-estt-logic-improvement-task-list-v2.md`
