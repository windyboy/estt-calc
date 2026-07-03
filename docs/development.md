# Development Guide

This guide summarizes the local Micronaut/Kotlin workflow for this service.

## Prerequisites

- JDK 25. The Gradle build configures Java and Kotlin toolchains for version 25.
- Gradle wrapper from this repository (`./gradlew`).
- Oracle connection settings for running against a real database. Unit tests use the H2 test configuration in `src/test/resources/application-test.yml`.

## Documentation

| 文档 / Document | 内容 / Contents |
|-----------------|-----------------|
| [algorithm.md](algorithm.md) | 飞行时长估算算法 |
| [api.md](api.md) | REST 端点与响应契约 |
| [code-map.md](code-map.md) | 源码布局与领域注释位置 |
| This guide | 构建、测试、排障 |

## Common commands

Run commands from the repository root.

```bash
# Clean and run the test suite
./gradlew clean test

# Run the full build, including checks wired into the Gradle build
./gradlew build

# Run formatting checks only
./gradlew spotlessCheck

# Generate coverage HTML
./gradlew koverHtmlReport

# Start the Micronaut application locally
./gradlew run
```

Focused test runs are useful before broader verification:

```bash
./gradlew test --tests com.gzzn.airport.service.EsttServiceTest
./gradlew test --tests com.gzzn.airport.service.history.HistoryFlightProviderTest
./gradlew test --tests com.gzzn.airport.service.calculator.FlyingTimeCalculatorTest
./gradlew test --tests com.gzzn.airport.resource.EsttControllerTest
```

## Project structure

```text
src/main/kotlin/com/gzzn/airport/
├── config/              # ESTT calculation configuration binding
├── exception/           # Error codes and Micronaut exception handlers
├── health/              # Database health indicator
├── model/               # API/domain DTOs and enums
├── repository/          # Micronaut Data JDBC repositories and SQL queries
├── resource/            # REST controller endpoints
├── service/             # Application orchestration service
│   ├── calculator/      # Flying-time median/fallback decision rules
│   └── history/         # History retrieval, pagination scan, and filtering
└── util/                # Kotlin Result helper extensions

src/test/kotlin/com/gzzn/airport/
├── exception/           # Exception handler tests
├── model/               # DTO/model tests
├── repository/          # Repository integration tests, currently disabled
├── resource/            # Controller tests and disabled HTTP integration tests
├── service/             # Service, calculator, and history provider tests
└── util/                # Result helper tests
```

Build configuration currently uses Groovy Gradle files: `build.gradle` and `settings.gradle`. There are no `build.gradle.kts` or `settings.gradle.kts` files.

## Configuration workflow

Runtime configuration is defined in `src/main/resources/application.yml` and can be overridden with environment variables. Important variables include:

- `ORACLE_HOST`, `ORACLE_PORT`, `ORACLE_SID`, `ORACLE_USER`, `ORACLE_PASS`
- `MAX_SCHEDULE_DEVIATION`
- `MAX_FLYING_TIME_DEVIATION`
- `MIN_HISTORY`
- `MIN_FLYING_TIME`
- `MAX_FLYING_TIME`
- `MAX_HISTORY_ROWS`
- `FLIGHT_NUMBER_PATTERN`

Tests use `src/test/resources/application-test.yml`, which configures an in-memory H2 database and loads `src/test/resources/schema.sql`.

## Development workflow

1. Start from the clean baseline: `./gradlew clean test` and `./gradlew build` should pass.
2. For documentation-only edits, verify links and command snippets manually; run `./gradlew test` if the change also documents test behavior.
3. Before Kotlin refactoring, add or update characterization tests that lock current behavior.
4. Run focused tests for touched areas first, then run `./gradlew check` before committing.
5. Avoid changing public API signatures, endpoint paths, response shapes, error messages, exit codes, dependency versions, or Gradle configuration unless explicitly approved.

## Troubleshooting

### JDK/toolchain mismatch

The project targets JDK 25. If Gradle cannot find a matching toolchain, install JDK 25 or configure Gradle toolchain discovery for your environment.

### Native access warnings

Gradle may print warnings about restricted native access from `net.rubygrapefruit.platform.internal.NativeLibraryLoader`. The current baseline still succeeds with these warnings.

### Shadow JAR duplicate entries

`./gradlew build` may report duplicate metadata entries while building the shadow JAR. The current baseline build succeeds despite these warnings.

### Oracle connection failures

Check `ORACLE_HOST`, `ORACLE_PORT`, `ORACLE_SID`, `ORACLE_USER`, and `ORACLE_PASS`. The application has no default for `ORACLE_PASS`.

### Test report locations

- Test report: `build/reports/tests/test/index.html`
- Test result XML: `build/test-results/test/`
- Kover HTML report: `build/reports/kover/html/index.html`

### Disabled tests

Some integration-style tests are currently disabled with Kotest `xdescribe`, including repository and HTTP integration coverage. Do not enable them as part of low-risk documentation or refactoring work unless explicitly approved.

## Known limitations

- The service computes estimates on demand; it does not pre-compute estimates in the background.
- Calculation history scan uses `MAX_HISTORY_ROWS` as the phase-one raw budget and `MAX_HISTORY_ROWS × 3` as the hard cap.
- The `/estt/history` response scans at most `MAX_HISTORY_ROWS` raw rows and reports stage-A/B filtered records observed within that bounded scan window.
- `message` fields are human-readable operational details; clients should rely on stable fields such as `source`, `flyingTime`, `sampleSize`, and `confidence`.
- Real database behavior depends on Oracle schema/data outside this repository; unit tests use H2 test data.
