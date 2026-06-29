# ESTT Logic Improvement Task List v2

**Date:** 2026-06-29  
**Project:** `estt-calc-kotlin`  
**Derived from:**
- `plans/2026-06-29-estt-logic-code-review-v1.md`
- `plans/2026-06-29-estt-logic-improvement-plan-v2.md`  
**Status:** Local implementation complete; README/CHANGELOG synced 2026-06-29; external release verification pending

**Blocked on before release tag:** external confirmation of Q4 (JDK 25 images available in CI/Docker/runtime) and Drone green on `develop`.

---

## 1. Purpose

Checkbox backlog for post-review improvements. P0 items block the next production release; P1 is the following sprint; P2 is v2 preparation.

**Gates / assumptions (2026-06-29):**

- Q1: External timing evidence not required — implementation excludes target flight date from history
- Q3: Invalid `yyMMdd` → HTTP 400
- Guardrail: Do not combine P0 release-readiness changes with P2 response-shape changes

---

## 2. P0 — Release Blockers

### Task 1. Toolchain alignment (review P0 #7)

- [x] Update `.drone.yml` — JDK 25 build image
- [x] Replace system `gradle` with `./gradlew --no-daemon clean test`, `./gradlew --no-daemon build`, and `./gradlew --no-daemon check` (parity with current Drone build step)
- [x] Update `Dockerfile` — JDK 25 base image
- [ ] Confirm Q4 with platform before release tag — external verification required
- [x] Verify `gradlew` is executable in CI
- [ ] Verify Drone green on `develop` — external CI verification required

**Targets:** `.drone.yml`, `Dockerfile`

---

### Task 2. Distinct no-estimate messages (review P0 #1)

- [x] Add `NoEstimateReason` enum or equivalent parameter to `buildNoEstimateResponse`
- [x] `NO_SEASONAL_FLIGHT` → `"no seasonal flight for operation day"`
- [x] `INSUFFICIENT_HISTORY_NO_SEASONAL_TIME` → `"insufficient qualified history; seasonal flying time not configured"`
- [x] Branch in `calculate()` before `buildNoEstimateResponse`:
  - `seasonalFlight == null` → `NO_SEASONAL_FLIGHT`
  - `seasonalFlight != null && flyingTime == null && qualified < minHistoryFlight` → `INSUFFICIENT_HISTORY_NO_SEASONAL_TIME`
- [x] Align `log.warn` at ~line 154 with the same reason (not always `"can't find seasonal flight"`)
- [x] Do not add `message` assertions to consumer contract tests

**Targets:** `src/main/kotlin/com/gzzn/airport/service/EsttService.kt:137-156`, `EsttService.kt:220-231`

---

### Task 3. Strict date parsing + HTTP 400 (review P0 #5)

- [x] Validate input against configured `dateFormat` (`yyMMdd` in v1); document that only `yyMMdd` is supported
- [x] For `yyMMdd`: require exactly six digits (`^\d{6}$`); reject trailing junk such as `210101x`
- [x] Parse strictly (`SimpleDateFormat.isLenient = false` with full input consumption, or strict `java.time.DateTimeFormatter`)
- [x] Two-digit year rule: explicit century (e.g. 00–99 → 2000–2099) or match Joda `DateTime` behavior — do not rely on undocumented `SimpleDateFormat` pivot
- [x] Throw `InvalidFlightDateException` (or equivalent) on parse failure
- [x] Map exception to HTTP 400 in `EsttController` for `/seasonal`, `/history`, `/flyTime`
- [x] Fix log placeholder: `log.info("flightDate {}", flightDate)` at line 29
- [x] Unit test: valid and invalid date strings in `EsttServiceSpec`
- [x] Create `EsttControllerSpec.groovy` — HTTP test invalid date → 400 (reused by Task 12)

**Targets:**
- `src/main/kotlin/com/gzzn/airport/service/EsttService.kt:49-51`
- `src/main/kotlin/com/gzzn/airport/resource/EsttController.kt`
- `src/test/groovy/com/gzzn/airport/EsttServiceSpec.groovy`
- `src/test/groovy/com/gzzn/airport/EsttControllerSpec.groovy` (new)

---

### Task 4. High-priority unit tests (review P0 #2–4)

- [x] Test: `preActual >= actual` excluded from history (`isHistoryFlight`)
- [x] Test: flying-time deviation at threshold — `diff == maxFlyingTimeDeviation` rejected (exclusive)
- [x] Test: seasonal `flyingTime=null` + qualified history ≥ 20 → `source: HISTORY`

- Group with Spock comments by filter stage (`isHistoryFlight` vs `getQualifiedHistoryFlights` vs `calculate` integration). Use `nQualifiedFlights`, `setupSeasonal` helpers.
- Note: existing test `null seasonal flying time skips seasonal fallback` covers null `flyingTime` with **empty** history only — Task 4 adds the ≥20 qualified history combo.

**Targets:** `src/test/groovy/com/gzzn/airport/EsttServiceSpec.groovy`

---

### Task 5. History window documentation (review P0 #6)

- [x] Q1 evidence no longer required: target flight date is excluded from history
- [x] Exclusive upper bound implemented with a boundary regression test
- [x] KDoc on `getHistoryFlightsWithSeasonFlight` — target date excluded from history
- [x] README "When estimates run" subsection
- [x] KDoc/comment above `HistoryFlightRepository.getArrivalFlight` on inclusive repository bounds and caller-provided previous-day end date; avoid SQL comments inside the `@Query` string unless tested against Oracle/Micronaut Data

**Targets:**
- `src/main/kotlin/com/gzzn/airport/service/EsttService.kt:78-105`
- `src/main/kotlin/com/gzzn/airport/respository/HistoryFlightRepository.kt:24-25`
- `README.md`

---

## 3. P1 — Next Sprint

### Task 6. KDoc completeness (review P1 #8–9)

- [x] `EsttService.kt` — `isScheduleDeviationAcceptable`: early arrivals always accepted (D3)
- [x] `EsttService.kt` — `isFlyingTimeWithinSeasonalTolerance`: exclusive at threshold
- [x] `EsttService.kt` — `median`: even-count integer average of two middles
- [x] `EsttService.kt` — `getOperationDay`: Joda 1=Mon … 7=Sun
- [x] `FlyingTimeResponse.kt` — field semantics, nullable no-estimate `flyingTime`, boolean deprecation path
- [x] `EstimateSource.kt`, `Confidence.kt` — one-line per enum value
- [x] `OperationDays.kt` — encoding, valid digits, invalid chars ignored
- [x] `SeasonalFlight.kt`, `HistoryFlight.kt` (`HistoricalFlight`) — field definitions
- [x] `EsttController.kt` — path params, `yyMMdd`, uppercase normalization, HTTP statuses

**Targets:** `src/main/kotlin/com/gzzn/airport/`

---

### Task 7. Remove dead sort (review P1 #10)

- [x] Remove `.sortedByDescending { it.scheduledTime }` from `getQualifiedHistoryFlights`
- [x] Update KDoc — remove "newest schedule time to oldest" claim

**Targets:** `src/main/kotlin/com/gzzn/airport/service/EsttService.kt:234-246`

---

### Task 8. README, CHANGELOG, and plan sync (review P1 #12–14)

- [x] README — changelog / what's new (June 2026)
- [x] README — day-of-week encoding (1=Mon … 7=Sun)
- [x] README — boundary rules (schedule inclusive, flying-time exclusive)
- [x] README — `message` not a stable API contract
- [x] README — history window + estimate timing
- [x] README — CI/Docker JDK alignment note
- [x] README — example `curl` for HISTORY / SEASONAL / NONE responses
- [x] Create `CHANGELOG.md` — logic + Micronaut 5 / JDK 25
- [ ] Fix `plans/2026-06-15-estt-logic-improvement-task-list-v1.md` section 6 drift (file not in repo)
- [ ] Fix task list v1 line 63: "history average" → "history median" (file not in repo)

---

### Task 9. Additional tests (review P1 #13)

- [x] Create `OperationDaysSpec.groovy` — extract inline `expect:` from `EsttServiceSpec` (~line 300)
- [x] Cover: empty string, `"89"`, duplicates `"111"`, invalid chars
- [x] Use `OperationDays.INSTANCE.matches(...)` from Groovy (not bare static call)
- [x] Even-sized median test — 20 flights, `(a+b)/2` integer rounding
- [x] Optional: multiple seasonal rows, only matching operation day wins
- [x] Deprecated `maxHistoryDelay` fallback intentionally removed; README documents migration to `maxScheduleDeviation`
- [ ] Optional: `@Unroll` parameterized schedule-deviation boundaries
- [x] Optional: `FlyingTimeContext` → `data class`

**Targets:**
- `src/test/groovy/com/gzzn/airport/OperationDaysSpec.groovy` (new)
- `src/test/groovy/com/gzzn/airport/EsttServiceSpec.groovy`

---

## 4. Release-Scope Guardrail

- [x] P0/P1 release-readiness changes were implemented before P2 response-shape changes
- [x] P2 response-shape changes were applied explicitly after release-readiness work

---

## 5. P2 — v2 Preparation (Completed)

### Task 10. API v2 migration (D5, D9)

- [x] Make `flyingTime` nullable
- [x] Deprecate `history` / `seasonal` booleans
- [x] Consumer migration checklist: replace `flyingTime == 0` with `source == NONE`
- [x] Validate client compatibility before rollout

**Targets:** `FlyingTimeResponse.kt`, `EsttService.kt`

---

### Task 11. OpenAPI spec

- [x] Add OpenAPI / Swagger for `/estt/flyTime` and related endpoints
- [x] Document `FlyingTimeResponse` fields including `source`, `sampleSize`, `confidence`

**Targets:** new `openapi.yml` or Micronaut OpenAPI annotations

---

### Task 12. Micronaut HTTP smoke test

- [x] Extend `EsttControllerSpec` from Task 3 (do not create a second HTTP test class)
- [x] Happy-path `GET /estt/flyTime/{flightNumber}/{date}` on test profile (H2)

**Targets:** `src/test/groovy/com/gzzn/airport/EsttControllerSpec.groovy`

---

## 6. Execution Order

1. [ ] Task 1 — JDK alignment (repo changes done; external Q4/Drone verification pending)
2. [x] Task 2 — No-estimate messages
3. [x] Task 3 — Strict dates + HTTP 400
4. [x] Task 4 — High-priority unit tests
5. [x] Task 5 — History window docs and exclusive upper bound
6. [x] Task 6 — KDoc
7. [x] Task 7 — Remove dead sort
8. [x] Task 8 — README / CHANGELOG / plan sync
9. [x] Task 9 — Additional tests
10. [x] Tasks 10–12 — P2

---

## 7. Milestone Checklist

### Milestone A — P0 complete (release-ready)

- [x] CI and Docker on JDK 25 (`./gradlew` including `check`)
- [x] Distinct no-estimate messages and aligned warn logs
- [x] Controller log placeholder fixed
- [x] Invalid dates return HTTP 400 (`EsttControllerSpec` created)
- [x] Three boundary/filter tests added
- [x] History target-date exclusion implemented, tested, and documented

### Milestone B — P1 complete (quality pass)

- [x] KDoc parity across service, models, controller
- [x] Dead sort removed
- [x] README + CHANGELOG current
- [x] `OperationDaysSpec` + even-median test

### Milestone C — P2 (v2, future)

- [x] Nullable `flyingTime` and boolean deprecation
- [x] OpenAPI spec published
- [x] HTTP smoke test for `/estt/flyTime`

---

## 8. Test Hygiene Notes

- From Groovy, use `OperationDays.INSTANCE.matches(...)` unless `@JvmStatic` is added
- Do not assert `message` in consumer contract tests — use `source`, `confidence`, `sampleSize`
- Test path is `src/test/groovy/...` (not `src/test/kotlin/...`)

---

## 9. References

- `plans/2026-06-29-estt-logic-code-review-v1.md`
- `plans/2026-06-29-estt-logic-improvement-plan-v2.md`
- `plans/2026-06-15-estt-logic-improvement-decisions-v1.md`
