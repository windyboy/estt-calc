# 到港航班预计时间计算 (Estimated Arrival Time Calculator)

[![Build Status](http://d2.int.it2000.com.cn/api/badges/thyia/estt-calc-kotlin/status.svg)](http://d2.int.it2000.com.cn/thyia/estt-calc-kotlin)

A Micronaut-based Kotlin microservice that calculates estimated flying times for arriving flights based on seasonal flight schedules and historical flight data.

## 问题 (Problem)

旧的电报系统，查询航班历史的时候使用单一航班号为限制条件，因为同一个航班号的航班不同运营日执行的是不同飞行计划，导致航班历史查询不准确，影响预计时间的计算。

The legacy telegram system uses only flight numbers as query criteria when retrieving flight history. Since the same flight number may operate different flight plans on different operational days, this leads to inaccurate historical queries and affects arrival time estimation.

## 解决方案 (Solution)

由于原有电报系统开发时间较早，难以在原有系统上增加复杂的方法，于是开发一个单独的服务程序，执行单一的飞行时长计算功能。

This standalone microservice implements flight duration calculation logic that considers operational days and historical patterns, providing more accurate arrival time estimates.

## 算法 (Algorithm)

1. 查询当前激活的航季计划 (`EsttService.cachedActiveSeason`)。
2. 按航班日期推导运营日，过滤出当天有效的季节航班 (`cachedSeasonalFlight`)。
3. 从季节开始日期往前回溯 `historyStartOffsetDays`（默认 60 天），查询历史航班，最多 `maxHistoryRows`（默认 300 条）。
4. 逐条应用业务过滤：
   - 运营日必须与季节计划匹配（逐位比较，防止 “1” 匹配 “12”）。
   - `previousDepartureTime < actualTime`，避免脏数据。
   - `scheduledTime` 的日期必须等于 `flightDate`。
   - 实际飞行时长与季节飞行时长的差值需小于 `maxHistoryDelay`（默认 120 分钟）。
   - 额外检查：计划 vs 实际落地时间差值也须在 `maxHistoryDelay` 内，排除异常提前/延误。
5. 将合格样本按 `scheduledTime` 逆序排列，若数量 ≥ `minHistoryFlight`（默认 20），取最新的 `minHistoryFlight` 条计算平均飞行时长（双精度求平均后四舍五入）。
6. 当历史样本不足时，直接使用季节航班的飞行时长作为预计值。
7. 如果季节航班不存在，则返回飞行时长为 0，`history=false`、`seasonal=false`。

### English Summary
1. Load the active seasonal schedule and pick the flight matching the operation day.
2. Query historical arrivals between `seasonStart - historyStartOffsetDays` and the target date, capped by `maxHistoryRows`.
3. Filter history by:
   - matching operation day,
   - chronological timestamps (`previousDepartureTime < actualTime`),
   - scheduled date equals `flightDate`,
   - actual flying time within `maxHistoryDelay` minutes of the seasonal value,
   - arrival delay within `maxHistoryDelay`.
4. Sort valid samples by scheduled time descending. If at least `minHistoryFlight` remain, average the latest `minHistoryFlight`; otherwise fall back to the seasonal flying time.
5. When no seasonal schedule exists, return zero minutes with both `history` and `seasonal` set to `false`.

### 实现结构 (Implementation Structure)
- `EsttService`：对外的服务入口，负责输入校验、缓存、日志/指标采集以及结果封装。
- `HistoryFlightProvider`：封装历史数据的查询与业务过滤逻辑，确保分页与批量模式一致。
- `FlyingTimeCalculator`：执行平均/兜底决策并记录 Micrometer 指标（数据来源、误差、准确度）。

## 技术栈 (Technology Stack)

- **Language**: Kotlin 1.9.25
- **Framework**: Micronaut 4.6.1
- **Java**: 21 (LTS)
- **Database**: Oracle 19c
- **Build Tool**: Gradle
- **Testing**: Kotest 5.8.0

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

- Java 21 or higher
- Oracle Database 19c or compatible version
- 2GB+ RAM (recommended)
- Network access to Oracle database

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

# Run quality checks (tests + detekt + spotless)
./gradlew check
```

### Build Artifacts

The build produces several distribution formats:

- `build/libs/estt-calc-0.1.2.jar` - Standard JAR
- `build/libs/estt-calc-0.1.2-all.jar` - Fat JAR (shadowJar)
- `build/distributions/estt-calc-0.1.2.tar` - Distribution archive
- `build/distributions/estt-calc-0.1.2.zip` - Distribution archive

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
java -jar build/libs/estt-calc-0.1.2-all.jar
```

#### Using Distribution Package

```bash
# Extract the distribution
unzip build/distributions/estt-calc-0.1.2.zip

# Run the startup script
cd estt-calc-0.1.2
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
| `MAX_DELAY` | 最大延误时间（分钟）(Max delay in minutes) | Integer | `120` |
| `MIN_HISTORY` | 最少历史航班数 (Min historical flights) | Integer | `20` |
| `START_MINUS` | 历史查询起始偏移天数 (History start offset days) | Integer | `60` |
| `MAX_HISTORY_ROWS` | 历史航班查询最大数量 (Max history rows to fetch) | Integer | `300` |
| `FLIGHT_NUMBER_PATTERN` | 航班号验证正则 (Flight number validation regex) | Regex | `^[A-Z]{2}[0-9]{3,4}$` |

> Notes:
> - The paginated history endpoint now pages directly at the repository layer and will never scan more than `MAX_HISTORY_ROWS` records for a request.
> - `totalFiltered` reflects the number of filtered records scanned so far (bounded by `MAX_HISTORY_ROWS`). When `hasMore=true`, use `offset + limit` to request the next window.
> - Delay filtering uses the absolute difference between scheduled and actual times to discard extreme early/late arrivals from calculations.

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
    max-history-delay: 120
    min-history-flight: 20
    date-format: 'yyMMdd'
    history-start-offset-days: 60
    max-history-rows: 300
  validation:
    flight-number-pattern: '^[A-Z]{2}[0-9]{3,4}$'
```

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

**Response**:
```json
{
  "flightNumber": "MU9941",
  "flightDate": "2021-12-31",
  "flyingTime": 95,
  "history": true,
  "seasonal": true,
  "message": "Calculated from 20 historical flights"
}
```

### 验证规则 (Validation Rules)

- Flight numbers must match format: `[A-Z]{2}[0-9]{3,4}` (e.g., MU9941, CA1234)
- Flight numbers are case-insensitive (automatically converted to uppercase)
- Date format must be `yyMMdd` (e.g., 211231 for Dec 31, 2021)

### 错误响应 (Error Responses)

- **400 Bad Request**: Invalid flight number or date format
- **404 Not Found**: Seasonal flight or season not found (business case - no data exists)
- **500 Internal Server Error**: System error (database failure, network issues, etc.)

**Error Response Format**:
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
- **EsttService**: Core business logic and calculation algorithm
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
    return getSeasonalFlight(flightNumber, flightDate)
        .flatMap { seasonalFlight ->
            if (seasonalFlight == null) {
                Result.success(emptyList())  // Business case: no data
            } else {
                getHistoryFlightsWithSeasonFlight(seasonalFlight, flightDate)
            }
        }
        .onFailure { e ->
            log.error("Error getting history", e)
        }
}
```

#### Error Classification

**Business Cases** (Success with empty/null data):
- No seasonal flight found → Returns `Result.success(null)` or empty response
- Insufficient historical data → Uses seasonal schedule time
- No historical flights → Returns empty list

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
- **Testing**: 96.4% test coverage with 112 tests using Kotest framework
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
        val seasonalFlight = SeasonalFlight("MU9941", "1234567", 90L, LocalDate.of(2021, 3, 28))
        every { seasonRepository.getSeasonalArrivalFlight("MU9941", "%5%") } returns seasonalFlight
        
        val result = esttService.calculate("MU9941", LocalDate.of(2021, 12, 31))
        
        result.isSuccess.shouldBeTrue()
        result.getOrNull()!!.flyingTime shouldBeGreaterThan 0
    }
}
```

### Test Coverage

**Current Coverage:** 96.4% line coverage, 86.5% branch coverage (112 tests)

| Package | Classes | Methods | Lines | Branches | Instructions |
|---------|---------|---------|-------|----------|--------------|
| **Service** | 75.0% | 94.7% | **95.8%** | **87.5%** | 96.7% |
| **Controller** | 100% | 100% | **100%** | **83.3%** | 97.1% |
| **Model** | 100% | 100% | **100%** | N/A | 100% |
| **Exception** | 100% | 100% | **100%** | **100%** | 100% |
| **Overall** | 80.0% | 92.1% | **96.4%** | **86.5%** | 95.2% |

### Test Structure

```
src/test/kotlin/com/gzzn/airport/
├── service/
│   ├── EsttServiceTest.kt              # 20 core business logic tests (including pagination)
│   └── EsttServiceEdgeCaseTest.kt      # 12 edge case & boundary tests
├── resource/
│   ├── EsttControllerTest.kt           # 17 controller unit tests
│   └── EsttControllerIntegrationTest.kt # Integration tests (optional)
├── exception/
│   └── GlobalExceptionHandlerTest.kt   # 6 error handling tests
├── model/
│   └── ModelTest.kt                    # 5 data class tests (including operationDays validation)
├── repository/
│   └── RepositoryTest.kt               # Repository injection tests
├── ApplicationTest.kt                  # Application startup tests
└── EsttCalcTest.kt                     # Integration tests
```

**Test Categories:**
- ✅ **Unit Tests**: Service logic, controller logic, exception handling (60 tests)
- ✅ **Edge Cases**: Boundary conditions, null handling, error scenarios (12 tests)
- ✅ **Integration Tests**: Full stack testing with H2 database (optional, 6 tests)
- ✅ **Model Tests**: Data class validation (5 tests, including new validation)

## 开发 (Development)

### Prerequisites

- JDK 21
- Oracle Database (or H2 for testing)
- IDE with Kotlin support (IntelliJ IDEA recommended)

### Running in Development Mode

```bash
./gradlew run
```

### Hot Reload

Micronaut supports automatic restart on file changes in development mode.

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

1. **Database Query Limits**: Maximum 100 rows fetched per history query
2. **Caching**: Active season cached for 1 hour
3. **Connection Pooling**: HikariCP for efficient database connections
4. **JVM Tuning**: Container-aware heap management

## 日志 (Logging)

Logs are written to:
- Console (stdout)
- File: `logs/estt.log`
- Rolling policy: Daily rotation, 50MB max per file, 180 days retention

Configure log levels in `logback.xml` or via environment variables.

## 版本历史 (Version History)

- **v0.1.2** (Current - 2025-11-06)
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
  - 🚀 Upgraded to Java 21 and Micronaut 4.6.1
  - 🔧 Upgraded to Kotlin 1.9.25
  - 🔒 Enhanced security: removed default passwords, updated Docker to Eclipse Temurin 21
  - ✅ **Migrated to Kotest testing framework**
    - Replaced Spock/Groovy tests with Kotest (pure Kotlin)
    - 72 comprehensive tests covering all layers
    - **96.4% line coverage, 86.5% branch coverage**
    - BDD-style testing with describe/it blocks
    - Modern matchers and assertions
    - Better IDE support and faster execution
  - 📊 **Comprehensive test coverage**
    - Service: 95.8% line coverage, 87.5% branch coverage
    - Controller: 100% line coverage, 83.3% branch coverage
    - Exception handlers: 100% coverage
    - Models: 100% coverage
  - 🔧 Added Kover for Kotlin-native code coverage reporting
  - ⚡ Performance optimizations: caching, query limits (max-history-rows: 300)
  - 📚 Added OpenAPI/Swagger documentation
  - 🛡️ **Improved error handling with Result type pattern**
    - Service methods return `Result<T>` for clear error classification
    - Distinguishes business cases (404) from system errors (500)
    - Enhanced exception propagation through call chain
    - Comprehensive error logging for monitoring and debugging
    - Updated test suite to verify Result behavior
  - 🎯 Added input validation and flight number regex configuration
  - 📊 Added health checks and Prometheus metrics
  - 🔧 Configurable parameters: max-history-rows, flight-number-pattern
  - 🐛 Fixed operation day matching bug
  - 📝 Comprehensive README update with testing guide

- **v0.0.21** (Legacy)
  - Initial stable release
  - Basic flight time calculation functionality
  - Spock-based testing framework

## 维护者 (Maintainers)

- **Contact**: fengzhq@it2000.com.cn
- **Organization**: Airport Systems

## 许可证 (License)

Proprietary - Internal use only
