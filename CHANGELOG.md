# Changelog

## 2026-07-03 — HistoryFlightProvider 阶段 B 整理 / Stage-B filter cleanup

### 算法 / Algorithm

- 阶段 B 筛选函数重命名为 `passesStageB`（原 `isComparableHistoryFlight`）。
- 目标执行日须在季节班期内：由 Provider 入口 `requireTargetInSeasonalOperationDays` 一次性校验（`EsttService` 已校验；直接调用 Provider 时 fail-fast）。
- 逐条阶段 B 仅保留：同星期几、计划日与执行日一致、起飞早于到港、飞行时长容差；班期不再在循环内重复解析。

### 验证 / Verification

- `./gradlew check` 通过 / passed.

---

## 2026-07-03 — 实现简化与指标整理 / Implementation simplification

### 算法 / Algorithm

- 历史窗口固定 `startDate = seasonStart`；移除无效配置 `START_MINUS`。
- `HistoryFlightProvider` 负责主计算阶段 B/C 筛选与有界历史查询（历史查询接口仅阶段 B）；`FlyingTimeCalculator` 仅处理已合格样本。
- 主计算与历史查询均改为**单次有界 SQL 读取**（`MAX_HISTORY_ROWS`）；移除分页翻扫、达标提前停与扩展扫描（原 `×3` 硬顶）。
- 历史查询 API 由 `PaginatedHistoryResponse`（`offset`/`limit`/`hasMore`）改为 `HistoryResponse`（`items`/`totalFiltered`/`rawScanned`/`capped`）。
- `HistoryFlightScan` 精简为 `qualifiedFlights` 与扫描元数据：`stageBRows`、`qualifiedRows`、`insufficientAfterCap`（替代 `extendedScanUsed` / `insufficientAfterBudget`）。

### 可观测性 / Observability

- 历史扫描指标统一为 `estt.history.scan.*`（`raw_rows`、`stage_b_rows`、`qualified_rows`、`calls` 含 `insufficient_after_cap` 标签）。
- 历史查询指标统一为 `estt.history.*`（`calls`、`items`、`filtered`、`raw_rows`，含 `capped` 标签）；移除 `estt.history.pagination.*` 与 `estt.history.scan.extended_used`。
- 移除未实现的 `estt.rate-limiting` 配置项。

### 文档 / Documentation

- 算法修订历史移至本 CHANGELOG；`docs/algorithm.md` 仅保留现行规则与实现附录。

### 验证 / Verification

- `./gradlew check` 通过 / passed.

---

## 2026-07-03 — 算法规则修订 / Algorithm rule corrections

| # | 问题 | 现行规则 |
|---|------|----------|
| 1 | 历史混入其它运营日 | 历史星期须等于目标 `flightDate` 星期 |
| 2 | 窗口早于航季 | `startDate = seasonStart` |
| 3 | 扫描无界 / 不提前停 | ~~阶段一达标即停；阶段二硬顶 `×3`~~ → **2026-07-03 简化**：单次有界读取 `MAX_HISTORY_ROWS`，无提前停与扩展扫描 |
| 4 | `flyingTime` 为 null 无约束 | 弱约束 `[MIN_FLYING_TIME, MAX_FLYING_TIME]` |
| 5 | 空窗口 | `startDate > endDate` 时跳过历史 |
| 6 | 季节多段误选 | `START_DATE ≤ flightDate`；取最新已生效段 |

---

## 2026-07-01 — 算法修正（扫描 + 季节查询）/ Algorithm correction (scan + seasonal lookup)

### 算法 / Algorithm

- 计算路径历史加载改为**分页扫描**；当时刻偏差过滤后合格样本仍 `< minHistoryFlight` 时，**超出 `maxHistoryRows` 继续扫描窗口**，减少误判 `SEASONAL`/`NONE`。
- Calculation history load **pages** raw rows and **extends beyond `maxHistoryRows`** when schedule-qualified samples are still below `minHistoryFlight`.
- 季节航班 SQL **确定性**选行：`ORDER BY START_DATE DESC, FLIGHT_NUMBER FETCH FIRST 1 ROW ONLY`。
- Seasonal flight SQL picks a **deterministic** row with the same `ORDER BY`.

### 可观测性 / Observability

- 新指标 / New metrics: `estt.history.calc.scan.*`（`raw_rows`、`filtered_rows`、`qualified_rows`、`hit_scan_limit`、`extended_beyond_budget`）。
- 触达原始行预算且合格样本不足时记录 INFO 日志。
- INFO log when raw cap is hit with insufficient qualified samples.

### 文档 / Documentation

- 更新 `docs/algorithm.md`、`docs/api.md`、`docs/code-map.md` 算法与 API 说明。

### 验证 / Verification

- `./gradlew check` 通过 / passed.

---

## 2026-07-01 — Simplification pass (v3)

### Code quality

- Reduced main Kotlin source volume (~1,800 → ~1,477 lines; 25 → 24 production files) without changing public API, HTTP routes, response fields, error messages, metrics tags, SQL, or cache names.
- Merged `EsttInputValidator` back into `EsttService` as a private `validateInputs` method (same validation messages and date bounds).
- Removed redundant `getHistoryFlightsWithSeasonFlight`; calculation path calls `HistoryFlightProvider` directly when seasonal schedule is already known.
- Consolidated verbose startup logging in `EsttService` to a single structured info line.
- Trimmed verbose KDoc across service, models, and repositories; kept short bilingual comments on domain-critical rules only (date parsing, operation-day matching, history window, tolerance thresholds, SQL prefilter semantics).

### Verification

- `./gradlew check` passed.

---

## 2026-07-01 — Documentation and safe refactor pass

### Documentation

- Added a Quick Start section to README with baseline verification and local run commands.
- Added `docs/development.md` for developer workflow, project structure, configuration notes, troubleshooting, and known limitations.

### Tests

- Added characterization tests for schedule-deviation boundaries, integer median behavior, and flying-time tolerance filtering.

### Code quality

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
