# 到港航班预计时间计算 (Estimated Arrival Time Calculator)

[![Build Status](http://d2.int.it2000.com.cn/api/badges/thyia/estt-calc-kotlin/status.svg)](http://d2.int.it2000.com.cn/thyia/estt-calc-kotlin)

A Micronaut-based Kotlin microservice that calculates estimated flying times for arriving flights based on seasonal flight schedules and historical flight data.

## Quick Start

```bash
# Verify the baseline
./gradlew clean test
./gradlew build

# Run locally with configuration from application.yml/environment
./gradlew run
```

Useful local endpoints after startup:

- `GET /estt/season`
- `GET /estt/flyTime/{flightNumber}/{flightDate}`
- `GET /health`
- `GET /prometheus`
- Swagger UI: `http://localhost:8080/swagger-ui`

For day-to-day development workflow, project structure, troubleshooting, and known limitations, see [docs/development.md](docs/development.md).

## 问题 (Problem)

旧的电报系统，查询航班历史的时候使用单一航班号为限制条件，因为同一个航班号的航班不同运营日执行的是不同飞行计划，导致航班历史查询不准确，影响预计时间的计算。

The legacy telegram system uses only flight numbers as query criteria when retrieving flight history. Since the same flight number may operate different flight plans on different operational days, this leads to inaccurate historical queries and affects arrival time estimation.

## 解决方案 (Solution)

由于原有电报系统开发时间较早，难以在原有系统上增加复杂的方法，于是开发一个单独的服务程序，执行单一的飞行时长计算功能。

This standalone microservice implements flight duration calculation logic that considers operational days and historical patterns, providing more accurate arrival time estimates.

## 算法 (Algorithm)

1. 查询当前激活的航季计划，按航班日期推导运营日，匹配当天有效的季节航班。
2. 从航季开始日期往前回溯 `historyStartOffsetDays`（默认 60 天）查询历史航班，**上界为目标运营日的前一天**（目标日本身不参与样本），最多 `maxHistoryRows`（默认 300 条）。
3. 逐条应用业务过滤：
   - 运营日必须与季节计划匹配（逐位比较，防止 “1” 匹配 “12”；编码见下文）。
   - `previousDepartureTime < actualTime`，排除时间顺序异常的数据。
   - `scheduledTime` 的日期必须等于 `flightDate`。
   - 实际飞行时长与季节飞行时长的差值须 **严格小于** `maxFlyingTimeDeviation`（默认 120 分钟；恰好等于阈值则剔除）。季节飞行时长未配置时跳过此项。
   - 计划落地 vs 实际落地的延误：早到始终保留；晚到超过 `maxScheduleDeviation`（默认 120 分钟）则剔除（阈值处 **含等于**，即恰好晚 120 分钟仍保留）。
4. 若合格样本数 ≥ `minHistoryFlight`（默认 20），对**全部**合格样本的飞行时长取**整数中位数**（偶数个样本时取中间两数之整数平均）。
5. 样本不足时，若季节航班配置了正的 `flyingTime`，则回退到季节计划时长（`source: SEASONAL`）。
6. 无季节航班，或样本不足且季节 `flyingTime` 未配置：返回 `flyingTime: null`、`source: NONE`（不再返回 `0`）。

### English Summary

1. Load the active seasonal schedule and pick the flight matching the operation day.
2. Query historical arrivals from `seasonStart - historyStartOffsetDays` through **the day before** the target operation date (the target date is excluded), capped by `maxHistoryRows`.
3. Filter history by:
   - matching operation day (digit-wise encoding, see below),
   - chronological timestamps (`previousDepartureTime < actualTime`),
   - scheduled date equals `flightDate`,
   - actual flying time strictly within `maxFlyingTimeDeviation` of the seasonal value (exclusive at threshold; skipped when seasonal time is null),
   - schedule delay: early arrivals always kept; late arrivals beyond `maxScheduleDeviation` rejected (inclusive at threshold).
4. If at least `minHistoryFlight` qualified samples remain, return the **integer median** of all qualified durations (even count: average of the two middles, truncated to integer).
5. Otherwise fall back to seasonal `flyingTime` when configured (`source: SEASONAL`).
6. When no seasonal flight exists, or history is insufficient and seasonal time is missing, return `flyingTime: null` with `source: NONE`.

### When estimates run

Estimates are computed **on demand** when a client calls `GET /estt/flyTime/{flightNumber}/{flightDate}`. There is no background pre-computation. Seasonal and history queries used inside the calculation are cached (active season: 1 hour; seasonal flight and history lists: 4 hours).

### Operation day encoding

Seasonal `operationDays` is a string of digits **1–7** where **1 = Monday … 7 = Sunday** (ISO-8601 / `java.time.DayOfWeek`). Example: `"135"` means Monday, Wednesday, Friday. Invalid characters are ignored; duplicate digits are deduplicated.

### Boundary rules (quick reference)

| Rule | Boundary |
|------|----------|
| Schedule delay (late only) | Inclusive: `actual - scheduled ≤ maxScheduleDeviation` |
| Flying-time vs seasonal | Exclusive: `|actual duration - seasonal| < maxFlyingTimeDeviation` |
| History date window | Target operation date **excluded** (query ends at `flightDate - 1 day`) |

### API contract notes

- Use `source` (`HISTORY` / `SEASONAL` / `NONE`) to determine estimate origin. Legacy `history` and `seasonal` booleans are deprecated.
- Treat `flyingTime == null` with `source == NONE` as “no estimate” (do not rely on `flyingTime == 0`).
- The `message` field is for operators and logs only; **it is not a stable API contract** and may change without a version bump.

### 实现结构 (Implementation Structure)

- `EsttService`：编排入口，负责输入校验、缓存、日志/指标及响应封装。
- `HistoryFlightProvider`：历史数据查询与业务过滤（运营日、时间顺序、飞行时长容差等）。
- `FlyingTimeCalculator`：中位数计算、季节回退/无估计决策及 Micrometer 指标。

## 技术栈 (Technology Stack)

- **Language**: Kotlin 2.3.21
- **Framework**: Micronaut 5.0.2
- **Java**: 25
- **Database**: Oracle 19c
- **Build Tool**: Gradle 9.x (wrapper)
- **Testing**: Kotest 5.9.x

### Key Dependencies

- Micronaut Data JDBC for database access
- Micronaut Cache (Caffeine) for performance optimization
- Micronaut Micrometer for metrics and monitoring
- OpenAPI/Swagger for API documentation
- Logback for logging
- Kotest for idiomatic Kotlin testing
- MockK for mocking in tests
- Kover for code coverage reporting

## 系统要求 (Requirements)

- JDK 25 (build, CI, and Docker runtime)
- Oracle Database 19c or compatible version
- 2GB+ RAM (recommended)
- Network access to Oracle database

> **CI / Docker alignment:** Drone builds use `gradle:9.5-jdk25`; the production image is based on `eclipse-temurin:25-jre-jammy`. Confirm your deployment platform provides JDK 25 images before tagging a release.

## 构建 (Build)

### 从源代码构建 (Build from Source)

```bash
# Clone the repository
git clone <repository-url>
cd estt-calc-kotlin

# Build with Gradle
./gradlew build

# Run tests
./gradlew test

# Create distribution packages
./gradlew assembleDist

# Run quality checks (tests + spotless + coverage)
./gradlew check
```

### Build Artifacts

The build produces several distribution formats (version from `appVersion` in `gradle.properties`):

- `build/libs/estt-calc-<version>.jar` - Standard JAR
- `build/libs/estt-calc-<version>-all.jar` - Fat JAR (shadowJar)
- `build/distributions/estt-calc-<version>.tar` - Distribution archive
- `build/distributions/estt-calc-<version>.zip` - Distribution archive

## 运行 (Running the Application)

### 使用Docker (Using Docker)

镜像名称 (Image name): `reg.int.it2000.com.cn/changde/estt-calc`

```bash
docker pull reg.int.it2000.com.cn/changde/estt-calc:latest

docker run -d \
  -p 8080:8080 \
  -e ORACLE_HOST=your-db-host \
  -e ORACLE_PORT=1521 \
  -e ORACLE_SID=orcl \
  -e ORACLE_USER=username \
  -e ORACLE_PASS=password \
  reg.int.it2000.com.cn/changde/estt-calc:latest
```

### 直接运行 (Direct Execution)

#### Using Shadow JAR

```bash
# Download/build the shadow JAR
java -jar build/libs/estt-calc-*-all.jar
```

#### Using Distribution Package

```bash
# Extract the distribution
unzip build/distributions/estt-calc-*.zip

# Run the startup script
cd estt-calc-*
./bin/estt-calc
```

## 配置 (Configuration)

参数配置可以通过环境变量或`application.yml`文件设置。

Configuration can be set via environment variables or the `application.yml` file.

### 环境变量 (Environment Variables)

| 参数名称 (Parameter) | 参数含义 (Description) | 取值 (Value) | 默认值 (Default) |
|---------------------|----------------------|-------------|-----------------|
| `ORACLE_HOST` | 数据库主机名 (Database host) | IP or hostname | `bj024.int.it2000.com.cn` |
| `ORACLE_PORT` | 数据库端口 (Database port) | Port number | `1521` |
| `ORACLE_SID` | 数据库SID (Database SID) | SID name | `orcl` |
| `ORACLE_USER` | 数据库用户名 (Database username) | Username | `cdjc` |
| `ORACLE_PASS` | 数据库密码 (Database password) | Password | **Required (no default)** |
| `MAX_SCHEDULE_DEVIATION` | 计划落地延误上限（分钟）；仅拒绝晚到 (Max late schedule deviation) | Integer | `120` |
| `MAX_FLYING_TIME_DEVIATION` | 实际 vs 季节飞行时长容差（分钟）；阈值为开区间 (Flying-time tolerance) | Integer | `120` |
| `MIN_HISTORY` | 最少合格历史航班数 (Min qualified historical flights) | Integer | `20` |
| `START_MINUS` | 历史查询起始偏移天数 (History start offset days) | Integer | `60` |
| `MAX_HISTORY_ROWS` | 历史航班查询最大数量 (Max history rows to fetch) | Integer | `300` |
| `FLIGHT_NUMBER_PATTERN` | 航班号验证正则 (Flight number validation regex) | Regex | `^[A-Z]{2}[0-9]{3,4}$` |

> Notes:
> - `MAX_DELAY` / `max-history-delay` are **removed**. Use `MAX_SCHEDULE_DEVIATION` and `MAX_FLYING_TIME_DEVIATION` instead.
> - The paginated history endpoint pages at the repository layer and scans at most `MAX_HISTORY_ROWS` records per request.
> - `totalFiltered` reflects filtered records scanned so far (bounded by `MAX_HISTORY_ROWS`). When `hasMore=true`, use `offset + limit` for the next window.
> - Schedule delay filtering is **asymmetric**: early arrivals are kept; only late arrivals beyond `MAX_SCHEDULE_DEVIATION` are dropped.

### 配置文件示例 (Configuration Example)

```yaml
micronaut:
  application:
    name: estt-calc
  caches:
    active-season:
      expire-after-write: 1h

datasources:
  gzzn:
    url: "jdbc:oracle:thin:@${ORACLE_HOST}:${ORACLE_PORT}:${ORACLE_SID}"
    driverClassName: oracle.jdbc.OracleDriver
    username: ${ORACLE_USER}
    password: ${ORACLE_PASS}

estt:
  calculation:
    max-schedule-deviation: 120
    max-flying-time-deviation: 120
    min-history-flight: 20
    date-format: 'yyMMdd'
    history-start-offset-days: 60
    max-history-rows: 300
  validation:
    flight-number-pattern: '^[A-Z]{2}[0-9]{3,4}$'
```

`yyMMdd` dates are parsed in the JVM default timezone with strict validation (exactly six digits; years 00–99 → 2000–2099).

## API 接口 (API Endpoints)

### 1. 查询当前飞行季度 (Query Active Season)

```
GET /estt/season
```

**Response**: FlightSeason object or 404 if not found

### 2. 查询航班的季度计划 (Query Seasonal Flight Schedule)

```
GET /estt/seasonal/{flightNumber}/{flightDate}
```

**Parameters**:
- `flightNumber`: 航班号 (Flight number, e.g., MU9941)
- `flightDate`: 航班日期 (Flight date in format `yyMMdd`, e.g., 211231)

**Example**: `/estt/seasonal/MU9941/211231`

**Response**: SeasonalFlight object or 404 if not found

### 3. 查询航班历史 (Query Flight History)

```
GET /estt/history/{flightNumber}/{flightDate}
```

**Example**: `/estt/history/MU9941/211231`

**Response**: Array of HistoricalFlight objects

> **Pagination behavior**
> - Results are paginated at the database layer to avoid loading the entire season into memory.
> - The service scans at most `MAX_HISTORY_ROWS` records per request; use `limit/offset` to walk the history window.
> - Filtering (operation day, schedule alignment, delay threshold) is applied before pagination metadata is computed.
> - When `hasMore=true`, request the next page by increasing `offset` by the previous `limit`.

**Example**

```bash
curl "http://localhost:8080/estt/history/MU9941/211231?limit=5&offset=10"
```

```json
{
  "items": [
    // ... trimmed for brevity ...
  ],
  "totalFiltered": 16,
  "offset": 10,
  "limit": 5,
  "hasMore": true
}
```

`totalFiltered` is best-effort — it represents the number of matching flights scanned in the current request (plus one extra when `hasMore=true`).

### 4. 计算飞行时长 (Calculate Flying Time) - 核心功能

```
GET /estt/flyTime/{flightNumber}/{flightDate}
```

**Example**: `/estt/flyTime/MU9941/211231`

**Response fields** (`FlyingTimeResponse`):

| Field | Description |
|-------|-------------|
| `flyingTime` | Estimated minutes; `null` when `source` is `NONE` |
| `source` | `HISTORY`, `SEASONAL`, or `NONE` (preferred over legacy booleans) |
| `sampleSize` | Qualified historical flights used; `0` for seasonal/none |
| `confidence` | `HIGH` when `source` is `HISTORY`; otherwise `NONE` |
| `history` / `seasonal` | Deprecated; mirror `source` for backward compatibility |
| `message` | Human-readable detail; **not stable** for programmatic use |

**Examples**:

History-based estimate:

```bash
curl -s "http://localhost:8080/estt/flyTime/MU9941/211231"
```

```json
{
  "flightNumber": "MU9941",
  "flightDate": "2021-12-31",
  "flyingTime": 95,
  "history": true,
  "seasonal": false,
  "message": "Calculated from 22 historical flights",
  "source": "HISTORY",
  "sampleSize": 22,
  "confidence": "HIGH"
}
```

Seasonal fallback (insufficient history):

```json
{
  "flightNumber": "MU9941",
  "flightDate": "2021-12-31",
  "flyingTime": 90,
  "history": false,
  "seasonal": true,
  "message": "Using seasonal flight flying time due to insufficient historical data",
  "source": "SEASONAL",
  "sampleSize": 0,
  "confidence": "NONE"
}
```

No estimate (no seasonal flight or no seasonal time configured):

```json
{
  "flightNumber": "XX9999",
  "flightDate": "2021-12-31",
  "flyingTime": null,
  "history": false,
  "seasonal": false,
  "message": "no seasonal flight for operation day",
  "source": "NONE",
  "sampleSize": 0,
  "confidence": "NONE"
}
```

**Consumer migration:** replace checks for `flyingTime == 0` with `source == "NONE"` or `flyingTime == null`.

### 验证规则 (Validation Rules)

- Flight numbers must match format: `[A-Z]{2}[0-9]{3,4}` (e.g., MU9941, CA1234)
- Flight numbers are case-insensitive (automatically converted to uppercase)
- Date format must be `yyMMdd` (e.g., 211231 for Dec 31, 2021) — exactly six digits, strict parse; invalid dates return **400**
- Trailing junk (e.g. `210101x`) and invalid calendar dates (e.g. `230229`) are rejected with **400**

### 错误响应 (Error Responses)

- **400 Bad Request**: Invalid flight number or date format (`InvalidFlightDateException` for bad dates)
- **404 Not Found**: Seasonal flight or season not found (business case - no data exists)
- **500 Internal Server Error**: System error (database failure, network issues, etc.)

**Validation error (invalid date)**:

```json
{
  "message": "Invalid flight date: invalid"
}
```

**Structured error (system failures)**:
```json
{
  "status": 500,
  "error": "SYSTEM_ERROR",
  "message": "Database connection failed"
}
```

#### Error Handling Strategy

The application uses Kotlin's `Result` type to distinguish between:
- **Business Cases**: No seasonal flight found, insufficient historical data (returns 200/404 with appropriate response)
- **System Errors**: Database failures, network issues, unexpected exceptions (returns 500 with error details)

This approach ensures:
- Clear error classification for monitoring and alerting
- Proper HTTP status codes for different scenarios
- Comprehensive error logging for debugging
- Exception propagation through the service layer

## 监控和健康检查 (Monitoring & Health Checks)

### Health Endpoint

```
GET /health
```

Returns application health status

### Metrics Endpoint (Prometheus)

```
GET /prometheus
```

Returns metrics in Prometheus format for monitoring

### Pagination Metrics

- `estt.history.pagination.calls{hasMore,capped}` – invocation counter tagged with whether a response was truncated or capped.
- `estt.history.pagination.items{hasMore}` – distribution summary of returned items per page.
- `estt.history.pagination.filtered{capped}` – distribution summary of filtered records scanned per request.
- `estt.history.pagination.limit` / `estt.history.pagination.offset` – distribution summaries for requested pagination window sizes.

When logs contain `History pagination truncated`, the returned page did not include all available rows. Clients should request the next page using the reported `offset + limit`.

### API Documentation (Swagger UI)

```
http://localhost:8080/swagger-ui
```

Interactive API documentation and testing interface

## 架构 (Architecture)

```
┌─────────────────┐
│   Controller    │  - HTTP endpoints
│   (REST API)    │  - Input validation
└────────┬────────┘
         │
┌────────▼────────┐
│    Service      │  - Business logic
│   (EsttService) │  - Calculation algorithm
└────────┬────────┘
         │
┌────────▼────────┐
│   Repository    │  - Data access
│   (JDBC)        │  - Query execution
└────────┬────────┘
         │
┌────────▼────────┐
│  Oracle Database│  - Flight data storage
└─────────────────┘
```

### Key Components

- **EsttController**: REST API endpoints with validation
- **EsttService**: Orchestrates input validation, seasonal lookup, history retrieval, calculation, metrics, and responses
- **HistoryFlightProvider**: Historical flight retrieval, pagination, and eligibility filtering
- **FlyingTimeCalculator**: Median calculation, seasonal fallback, no-estimate decisions, and calculation metrics
- **SeasonRepository**: Access to seasonal flight schedules
- **HistoryFlightRepository**: Access to historical flight records
- **GlobalExceptionHandler**: Centralized error handling

### Result Type Pattern (Error Handling) 🔧

The service layer uses Kotlin's `Result<T>` type to provide robust, type-safe error handling that clearly distinguishes between business cases and system errors:

#### Why Result Type?

1. **Type-Safe Error Handling**: Compile-time guarantee of error handling
2. **Clear Error Classification**: Business cases (no data) vs System errors (database failure)
3. **Explicit Error Propagation**: Errors flow through call chain without exception swallowing
4. **Better Monitoring**: Track success/failure rates and error types

#### Result Usage Pattern

**Service Layer Methods**:
```kotlin
fun getActiveSeason(): Result<FlightSeason?>
fun getSeasonalFlight(flightNumber: String, flightDate: LocalDate): Result<SeasonalFlight?>
fun getHistoryFlights(flightNumber: String, flightDate: LocalDate): Result<List<HistoricalFlight>>
fun calculate(flightNumber: String, flightDate: LocalDate): Result<FlyingTimeResponse>
```

**Controller Layer Handling**:
```kotlin
return esttService.calculate(flightNumber, flightDate).fold(
    onSuccess = { result -> HttpResponse.ok(result) },
    onFailure = { e -> 
        log.error("Calculation failed", e)
        HttpResponse.serverError(
            ErrorCode.CALCULATION_ERROR.toErrorResponse(e.message ?: "Calculation failed")
        )
    }
)
```

#### FlatMap for Chaining Operations

The service layer uses custom `flatMap` extension to chain Result operations elegantly:

```kotlin
fun getHistoryFlights(...): Result<List<HistoricalFlight>> {
    return runCatching {
        val seasonalFlight = cachedSeasonalFlight(flightNumber, flightDate)
        if (seasonalFlight == null) {
            emptyList()  // Business case: no seasonal schedule
        } else {
            historyFlightProvider.getHistoryFlights(seasonalFlight, flightDate)
        }
    }.onFailure { e ->
        log.error("Error getting history flights", e)
    }
}
```

#### Error Classification

**Business Cases** (Success with empty/null data):
- No seasonal flight found → `source: NONE`, `flyingTime: null`
- Insufficient historical data with seasonal time configured → `source: SEASONAL`
- Insufficient historical data without seasonal time → `source: NONE`, `flyingTime: null`
- No historical flights for history endpoint → Returns empty list

**System Errors** (Failure):
- Database connection timeout → `Result.failure(SQLException)`
- Null pointer exceptions → `Result.failure(NullPointerException)`
- Data parsing errors → `Result.failure(IllegalArgumentException)`

**Benefits**:
- ✅ Clear separation between business cases and system errors
- ✅ Type-safe error handling (compiler enforces error checking)
- ✅ Explicit error propagation through call chain
- ✅ Improved monitoring and alerting capabilities
- ✅ Better debugging with comprehensive error logging
- ✅ Functional programming patterns (flatMap, map, fold)
- ✅ No silent failures or exception swallowing

### Features

- **Caching**: Active season is cached for 1 hour
- **Performance**: Query limits and optimizations prevent excessive data retrieval
- **Pagination**: Efficient history pagination with proper filtering
- **Validation**: Comprehensive input validation including operation days format
- **Error Handling**: Result type pattern for clear business/system error distinction
- **Monitoring**: Health checks and Prometheus metrics
- **Documentation**: OpenAPI/Swagger integration with comprehensive API annotations
- **Security**: Non-root Docker user, no hardcoded credentials, rate limiting ready
- **Testing**: Kotest with MockK; run `./gradlew test` and `./gradlew koverHtmlReport` for current coverage
- **Code Quality**: Kotlin-native tooling (Kotest, MockK, Kover), refactored maintainable code
- **Error Handling**: Result type pattern with flatMap for functional error propagation
- **Observability**: MDC-based structured logging, Micrometer metrics for all endpoints

## 测试 (Testing)

### Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests EsttServiceTest

# Run tests with coverage report
./gradlew test koverHtmlReport koverXmlReport

# View coverage report
open build/reports/kover/html/index.html
```

### Test Framework: Kotest

This project uses **Kotest**, a modern Kotlin-native testing framework that provides:

- **BDD-Style Testing**: Describe/It blocks for readable test structure
- **Rich Matchers**: Idiomatic Kotlin assertions (`shouldBe`, `shouldNotBeNull`, etc.)
- **MockK Integration**: Seamless mocking with Kotlin-first mocking library
- **Fast Execution**: Performance-optimized test runner
- **Great IDE Support**: First-class IntelliJ IDEA integration

**Example Test:**
```kotlin
describe("calculate") {
    it("should calculate with sufficient history") {
        val seasonalFlight = SeasonalFlight(
            "MU9941",
            "1234567",
            90L,
            LocalDate.of(2021, 3, 28),
            LocalDate.of(2021, 12, 31),
        )
        every { seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") } returns seasonalFlight
        
        val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
        
        result.isSuccess.shouldBeTrue()
        result.getOrNull()!!.flyingTime shouldBeGreaterThan 0
    }
}
```

### Test Coverage

Run `./gradlew test koverHtmlReport` and open `build/reports/kover/html/index.html` for current line/branch coverage.

### Test Structure

```
src/test/kotlin/com/gzzn/airport/
├── service/
│   ├── EsttServiceTest.kt
│   ├── EsttServiceEdgeCaseTest.kt
│   ├── EsttServiceErrorTest.kt
│   ├── EsttServiceValidationAndMatchingTest.kt
│   ├── calculator/FlyingTimeCalculatorTest.kt
│   └── history/HistoryFlightProviderTest.kt
├── resource/
│   ├── EsttControllerTest.kt
│   └── EsttControllerIntegrationTest.kt   # Micronaut HTTP tests (currently disabled via xdescribe)
├── exception/
│   └── GlobalExceptionHandlerTest.kt
├── model/
│   ├── ModelTest.kt
│   └── OperationDaysTest.kt
├── repository/
│   └── RepositoryTest.kt
├── ApplicationTest.kt
└── EsttCalcTest.kt
```

## 开发 (Development)

See [docs/development.md](docs/development.md) for the current developer workflow, project structure, configuration notes, troubleshooting, and known limitations.

### Prerequisites

- JDK 25
- Oracle Database for real runtime data; tests use H2 via `src/test/resources/application-test.yml`
- IDE with Kotlin support (IntelliJ IDEA recommended)

### Running in Development Mode

```bash
./gradlew run
```

### Common Verification Commands

```bash
./gradlew clean test
./gradlew build
./gradlew spotlessCheck
./gradlew koverHtmlReport
```

## 部署 (Deployment)

### Docker Deployment

The application is containerized and can be deployed using Docker or Kubernetes.

**Security Features**:
- Runs as non-root user
- Container-aware JVM settings
- Health check support

### Environment-Specific Configuration

Use environment variables to configure different environments (dev, test, prod) without modifying code.

## 性能优化 (Performance Optimizations)

1. **Database Query Limits**: Up to `maxHistoryRows` (default 300) per history query
2. **Caching**: Active season cached for 1 hour; seasonal flight and history lists for 4 hours
3. **Connection Pooling**: HikariCP for efficient database connections
4. **JVM Tuning**: Container-aware heap management

## 日志 (Logging)

Logs are written to:
- Console (stdout)
- File: `logs/estt.log`
- Rolling policy: Daily rotation, 50MB max per file, 180 days retention

Configure log levels in `logback.xml` or via environment variables.

## 版本历史 (Version History)

See [CHANGELOG.md](CHANGELOG.md) for detailed release notes.

- **2026-07-01** (simplification pass v3)
  - Reduced main Kotlin source by ~18% while preserving public API and behavior
  - Merged `EsttInputValidator` back into `EsttService`; removed duplicate history-fetch path
  - Trimmed verbose KDoc; kept short bilingual comments on domain-critical rules only
  - Verified with `./gradlew check`

- **2026-07-01** (documentation and safe refactor pass)
  - Added README Quick Start and `docs/development.md` developer workflow/troubleshooting guide
  - Added characterization tests for schedule-deviation boundaries, integer median behavior, and flying-time tolerance filtering
  - Simplified private history-window/filtering helpers in `HistoryFlightProvider`
  - Verified with focused tests, full `./gradlew test`, and `./gradlew check`

- **v0.1.1** (2026-06-29 — logic v2)
  - Median over all qualified history (replaces mean of latest 20)
  - Split schedule vs flying-time deviation; asymmetric late-only schedule filter
  - API v2: nullable `flyingTime`, `source` / `sampleSize` / `confidence`; deprecated `history`/`seasonal`
  - Strict `yyMMdd` parsing; invalid dates → HTTP 400
  - History window excludes target operation date
  - Micronaut 5.0.2 / JDK 25; Drone `./gradlew` pipeline; Docker Temurin 25 JRE
  - OpenAPI via Micronaut annotations and Swagger UI (`/swagger-ui`)

- **v0.1.2** (2025-11-06)
  - 🔧 **Code Quality & Performance Improvements**
    - **Pagination Optimization**: Moved history pagination from in-memory to repository level, preventing incomplete results and improving efficiency
    - **Query Optimization**: Added date consistency filter (`TRUNC(SCHEDULED_DATETIME) = flight_date`) to reduce invalid data fetching
    - **Method Refactoring**: Split `EsttService.calculate` into smaller, maintainable functions (`validateInputs`, `fetchAndCalculate`, `recordSuccessMetrics`, `recordFailureMetrics`)
    - **Input Validation Enhancement**: Added `operationDays` validation in `SeasonalFlight` model (digits 1-7 only)
    - **Code Cleanup**: Removed redundant `abs()` in duration calculations, extracted query constants to companion objects
  - 🏷️ **Improved Error Handling & Validation**
    - Enhanced `operationDays` validation with clear error messages
    - Better parameter naming (`tag` → `isActive` in repository methods)
    - Strengthened input validation for flight dates and numbers
  - 📊 **Enhanced Testing & Coverage**
    - Added comprehensive tests for `getPaginatedHistoryFlights` service method
    - Added edge case tests for `operationDays` validation in models
    - Updated existing tests for renamed methods
    - Maintained high test coverage (112 tests, 96.4% line coverage)
  - 🔒 **Security & Resilience**
    - Enabled rate limiting annotation for calculation endpoint (ready for Resilience4j configuration)
    - Improved data integrity checks in queries
  - 📝 **Documentation Updates**
    - Updated README with improvement details and test coverage
    - Enhanced code comments for better maintainability

- **v0.1.1** (2025-11-05)
  - 🔧 **Result Type Pattern Enhancement**
    - Added `flatMap` and `mapNotNull` extension functions for elegant Result chaining
    - Refactored service methods to use functional Result composition
    - Eliminated `getOrThrow()` anti-pattern for clearer error propagation
  - 🏷️ **Unified Error Code System**
    - Created `ErrorCode` enum for standardized error responses
    - Added timestamp to all error responses
    - Consistent error classification across all endpoints (DATABASE_ERROR, CALCULATION_ERROR, etc.)
  - 📊 **Enhanced Observability**
    - Added MDC (Mapped Diagnostic Context) for structured logging
    - Implemented Micrometer metrics on all API endpoints (@Timed, @Counted)
    - Service-level metrics tracking (calculation time, success/failure rates)
    - Better error tracking with error type tagging
  - 📝 **Improved Logging Strategy**
    - Optimized log levels (reduced INFO noise, enhanced DEBUG details)
    - Context-aware logging with flight number and date in MDC
    - More actionable log messages for production monitoring
  - 📚 **Comprehensive API Documentation**
    - Added OpenAPI annotations to all endpoints
    - Parameter descriptions and examples
    - Response schemas and status codes documentation
    - Enhanced Swagger UI experience
  - ✅ **Test Coverage Expansion**
    - Added Result extension function tests
    - Added error scenario tests (database failures, edge cases)
    - Verified Result behavior in all test suites
    - Maintained 96.4% code coverage
  - 🔧 **Configuration Enhancement**
    - Configurable max-history-rows (default: 300 for half-season coverage)
    - Configurable flight-number-pattern for flexible validation
    - All parameters externalized via environment variables

- **v0.1.0** (2025-11-05)
  - Initial Kotest migration; Java 21 / Micronaut 4.6 era (superseded by v0.1.1 toolchain upgrade)

- **v0.0.21** (Legacy)
  - Initial stable release
  - Basic flight time calculation functionality
  - Spock-based testing framework

## 维护者 (Maintainers)

- **Contact**: fengzhq@it2000.com.cn
- **Organization**: Airport Systems

## 许可证 (License)

Proprietary - Internal use only
