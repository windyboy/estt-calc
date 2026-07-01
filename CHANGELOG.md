# Changelog

## 2026-07-01 — Documentation and safe refactor pass

### Documentation

- Added a Quick Start section to README with baseline verification and local run commands.
- Added `docs/development.md` for developer workflow, project structure, configuration notes, troubleshooting, and known limitations.

### Tests

- Added characterization tests for schedule-deviation boundaries, integer median behavior, and flying-time tolerance filtering.

### Code quality

- Extracted calculation input validation from `EsttService` into internal `EsttInputValidator` while preserving public API and validation messages.
- Simplified private history-window and eligibility-filtering helpers in `HistoryFlightProvider` without changing repository SQL, metrics, cache names, API output, or behavior.

### Verification

- Verified with focused tests, full `./gradlew test`, and `./gradlew check`.

---

## 2026-06-29 — Post-review improvements (v2 plan)

### API v2

- `FlyingTimeResponse.flyingTime` is now nullable; no-estimate responses use `null` instead of `0`
- Deprecated legacy `history` / `seasonal` booleans; integrations should use `source`
- OpenAPI documented via Micronaut/Swagger annotations on `EsttController`; served at `/swagger-ui`

### Logic and API

- Distinct no-estimate `message` strings per failure reason (ops/logging only; not a stable contract)
- Strict `yyMMdd` date parsing; invalid dates return HTTP 400 via `InvalidFlightDateException`
- Changed history window to exclude the target operation date, preventing target-flight actuals from leaking into samples

### Tests

- Kotlin/Kotest coverage for date parsing, history filtering, median calculation, and `OperationDays`
- `EsttControllerIntegrationTest` provides HTTP-level cases (currently disabled with `xdescribe`)

### Documentation

- README synced with v2 algorithm, config keys, API response shape, boundary rules, and toolchain
- Documented migration from deprecated `max-history-delay` / `MAX_DELAY` to `max-schedule-deviation` + `max-flying-time-deviation`
- Documented timezone expectation for `yyMMdd` parsing (JVM default zone, strict `uuMMdd`)
- KDoc on service helpers, models, and controller

### Code quality

- Removed dead sort in qualified-history path (median no longer depends on order)
- Fixed `EsttController` SLF4J log placeholder
- Simplified `maxScheduleDeviation` property injection (fixes Micronaut nested placeholder parsing)

### Toolchain

- Micronaut Platform 5.0.2 / Gradle Micronaut plugin 5.0.0 / JDK 25
- Drone CI: `gradle:9.5-jdk25`, `./gradlew --no-daemon clean test`, `build`, and `check`
- Docker: `eclipse-temurin:25-jre-jammy` base image

---

## 2026-06-15 — Logic improvement (v1 plan)

### Algorithm

- Median over all qualified historical flights (replaces mean of top 20)
- Asymmetric schedule delay filter (reject late beyond tolerance; allow early)
- Split config: `maxScheduleDeviation` + `maxFlyingTimeDeviation`
- Exact operation-day digit matching (`OperationDays`)

### API (additive v1)

- Added `source`, `sampleSize`, `confidence` to `FlyingTimeResponse`
- No estimate: `flyingTime: 0` with `source: NONE` (v1 behavior; superseded by nullable `flyingTime` in 2026-06-29 API v2)
- Nullable seasonal `flyingTime` without misleading default

### Tests

- Service-layer tests with mocked repositories (Kotest; formerly Spock/Groovy)

### Toolchain

- Upgraded to Micronaut 5 and JDK 25
