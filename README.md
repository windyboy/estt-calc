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

1. 首先查询已经激活的季度计划
2. 根据季度计划，航班的飞行日期，查询到符合运营日的季度计划航班
3. 从季度计划开始的日期，到目前航班日期查询航班历史，历史航班必须符合运营日条件，同时不能延误太长时间
4. 如果符合条件的历史航班数超过20（可配置），则计算平均飞行时长
5. 如果历史航班不足，则使用季度计划飞行时长
6. 如果无法找到季度计划，则不计算飞行时长

**English Summary:**
1. Query active seasonal schedule
2. Find seasonal flights matching the operation day
3. Query historical flights within date range and operation day constraints
4. If sufficient qualified history (default: 20+ flights), calculate average duration
5. Otherwise, use scheduled seasonal flight time
6. Return zero if no seasonal schedule exists

## 技术栈 (Technology Stack)

- **Language**: Kotlin 1.9.23
- **Framework**: Micronaut 4.3.7
- **Java**: 21 (LTS)
- **Database**: Oracle 19c
- **Build Tool**: Gradle
- **Testing**: Spock 2

### Key Dependencies

- Micronaut Data JDBC for database access
- Micronaut Cache (Caffeine) for performance optimization
- Micronaut Micrometer for metrics and monitoring
- OpenAPI/Swagger for API documentation
- Logback for logging

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
```

### Build Artifacts

The build produces several distribution formats:

- `build/libs/estt-calc-0.0.21.jar` - Standard JAR
- `build/libs/estt-calc-0.0.21-all.jar` - Fat JAR (shadowJar)
- `build/distributions/estt-calc-0.0.21.tar` - Distribution archive
- `build/distributions/estt-calc-0.0.21.zip` - Distribution archive

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
java -jar build/libs/estt-calc-0.0.21-all.jar
```

#### Using Distribution Package

```bash
# Extract the distribution
unzip build/distributions/estt-calc-0.0.21.zip

# Run the startup script
cd estt-calc-0.0.21
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
| `MAX_HISTORY_ROWS` | 历史航班查询最大数量 (Max history rows to fetch) | Integer | `100` |
| `FLIGHT_NUMBER_PATTERN` | 航班号验证正则 (Flight number validation regex) | Regex | `^[A-Z]{2}[0-9]{3,4}$` |

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
    max-history-rows: 100
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
- **404 Not Found**: Seasonal flight or season not found
- **500 Internal Server Error**: Unexpected server error

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

### Features

- **Caching**: Active season is cached for 1 hour
- **Performance**: Query limits prevent excessive data retrieval
- **Validation**: Comprehensive input validation
- **Monitoring**: Health checks and Prometheus metrics
- **Documentation**: OpenAPI/Swagger integration
- **Security**: Non-root Docker user, no hardcoded credentials

## 测试 (Testing)

```bash
# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests EsttServiceSpec

# Generate test report
./gradlew test jacocoTestReport
```

### Test Coverage

- Service layer: Comprehensive unit tests
- Controller layer: HTTP integration tests
- Repository layer: Database access tests
- End-to-end: Full workflow integration tests

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

- **v0.0.21** (Current)
  - Upgraded to Java 21 and Micronaut 4.3.7
  - Added comprehensive testing
  - Implemented caching and performance optimizations
  - Added OpenAPI documentation
  - Enhanced security and error handling

## 维护者 (Maintainers)

- **Contact**: fengzhq@it2000.com.cn
- **Organization**: Airport Systems

## 许可证 (License)

Proprietary - Internal use only
