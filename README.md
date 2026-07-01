# 到港航班预计时间计算 (Estimated Arrival Time Calculator)

[![Build Status](http://d2.int.it2000.com.cn/api/badges/thyia/estt-calc-kotlin/status.svg)](http://d2.int.it2000.com.cn/thyia/estt-calc-kotlin)

Micronaut/Kotlin microservice that estimates arriving-flight duration from seasonal schedules and filtered historical data.

## Quick Start

```bash
./gradlew clean test
./gradlew build
./gradlew run
```

Local endpoints: `GET /estt/season`, `GET /estt/flyTime/{flightNumber}/{flightDate}`, `GET /health`, `GET /prometheus`, Swagger UI at `http://localhost:8080/swagger-ui`.

## Documentation

| 文档 / Document | 内容 / Contents |
|-----------------|-----------------|
| [docs/algorithm.md](docs/algorithm.md) | 计算步骤、过滤、边界、运营日编码（**中英双语**） |
| [docs/api.md](docs/api.md) | REST 端点、响应字段、校验、错误（**中英双语**） |
| [docs/code-map.md](docs/code-map.md) | 源码布局、关键文件、注释约定（**中英双语**） |
| [docs/development.md](docs/development.md) | 构建/测试流程、排障、已知限制 |
| [CHANGELOG.md](CHANGELOG.md) | 版本变更记录 |

## Problem & solution

旧电报系统仅用航班号查历史，忽略不同运营日对应不同飞行计划，导致预计时间偏差。本服务独立实现飞行时长计算，按运营日与历史样本规则给出更准确的到港预估。

The legacy system queries history by flight number only; this service matches operational days and applies business filters before median/seasonal estimation.

## Implementation (high level)

- `EsttService` — validation, cache, orchestration, metrics, responses
- `HistoryFlightProvider` — history window, filtering, paginated scan
- `FlyingTimeCalculator` — median, seasonal fallback, no-estimate branch

Details: [docs/algorithm.md](docs/algorithm.md) and [docs/code-map.md](docs/code-map.md).

## Technology

Kotlin 2.3.21 · Micronaut 5.0.2 · JDK 25 · Oracle 19c · Gradle (Groovy DSL: `build.gradle`, `settings.gradle`) · Kotest + MockK

## Requirements

JDK 25, Oracle 19c (tests use H2), 2GB+ RAM. CI/Docker: Gradle `jdk25`, runtime Temurin 25 JRE.

## Build & verify

```bash
./gradlew build          # full build
./gradlew test           # unit tests
./gradlew check          # tests + spotless + kover
./gradlew spotlessCheck  # formatting only
./gradlew koverHtmlReport
```

Artifacts: `build/libs/estt-calc-<version>-all.jar`, distribution zips under `build/distributions/`.

## Run

**Docker** (`reg.int.it2000.com.cn/changde/estt-calc`):

```bash
docker run -d -p 8080:8080 \
  -e ORACLE_HOST=... -e ORACLE_PORT=1521 -e ORACLE_SID=orcl \
  -e ORACLE_USER=... -e ORACLE_PASS=... \
  reg.int.it2000.com.cn/changde/estt-calc:latest
```

**JAR:** `java -jar build/libs/estt-calc-*-all.jar`

## Configuration

Environment variables (defaults in `application.yml`):

| Variable | Meaning | Default |
|----------|---------|---------|
| `ORACLE_HOST` / `PORT` / `SID` / `USER` / `PASS` | Database | `ORACLE_PASS` required |
| `MAX_SCHEDULE_DEVIATION` | Late schedule tolerance (minutes) | 120 |
| `MAX_FLYING_TIME_DEVIATION` | Flying-time tolerance (strict `<`) | 120 |
| `MIN_HISTORY` | Min qualified samples for median | 20 |
| `START_MINUS` | History start offset (days) | 60 |
| `MAX_HISTORY_ROWS` | Max rows scanned per history request | 300 |
| `FLIGHT_NUMBER_PATTERN` | Flight number regex | `^[A-Z]{2}[0-9]{3,4}$` |

`yyMMdd` dates: strict six digits, years 00–99 → 2000–2099, JVM default timezone.

## Architecture

```text
EsttController → EsttService → HistoryFlightProvider / FlyingTimeCalculator
                              → SeasonRepository / HistoryFlightRepository → Oracle
```

Service layer uses Kotlin `Result<T>` for system errors vs business empty/null cases. See [docs/code-map.md](docs/code-map.md).

## Testing

```bash
./gradlew test
./gradlew test --tests com.gzzn.airport.service.EsttServiceTest
open build/reports/kover/html/index.html
```

Shared service test fixtures: `EsttServiceTestSupport.kt`. Some integration tests are disabled (`xdescribe`).

## Version notes

- **2026-07-01 (v4)** — Merged `HistoryPaginationScanner` into provider; slimmed `EsttService`; deduplicated tests; docs split into `docs/`. `./gradlew check` green.
- **2026-07-01 (v3)** — ~18% main-source reduction; merged `EsttInputValidator`; trimmed verbose KDoc while keeping domain comments.
- **v0.1.1** — API v2 (`source`, nullable `flyingTime`), median over qualified history, Micronaut 5 / JDK 25.

Full history: [CHANGELOG.md](CHANGELOG.md).

## Maintainers

fengzhq@it2000.com.cn · Airport Systems · Proprietary / internal use
