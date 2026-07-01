# ESTT 飞行时长估算算法 / ESTT Calculation Algorithm

本文描述本服务实现的飞行时长（ESTT）估算规则，与 `EsttService`、`HistoryFlightProvider`、`FlyingTimeCalculator` 及仓储层源码一致；源码在关键决策点保留**中文在前、英文在后**的双语注释。

This document describes the flying-time estimation rules implemented in this service. Source code mirrors these rules with bilingual comments (Chinese first, English second) in `EsttService`, `HistoryFlightProvider`, `FlyingTimeCalculator`, and the repository layer.

- HTTP 契约见 [api.md](api.md) · HTTP contracts: [api.md](api.md)
- 代码导航见 [code-map.md](code-map.md) · File navigation: [code-map.md](code-map.md)

## 概述 / Overview

客户端调用 `GET /estt/flyTime/{flightNumber}/{flightDate}` 时**按需**计算，无后台预计算。季节与历史查询结果有缓存（见 [缓存 / Caching](#缓存--caching)）。

Estimates are computed **on demand** on `GET /estt/flyTime/{flightNumber}/{flightDate}`. There is no background pre-computation. Seasonal and history queries are cached (see [Caching](#缓存--caching)).

服务在三种结果中选择其一：

The service chooses among three outcomes:

| `source` | 含义 / Meaning | `flyingTime` |
|----------|----------------|--------------|
| `HISTORY` | 合格历史样本的整数中位数 / Integer median of qualified historical samples | 分钟 / minutes |
| `SEASONAL` | 历史不足时回退季节计划时长 / Seasonal schedule fallback | 季节配置分钟数 / configured seasonal minutes |
| `NONE` | 无可用估算 / No estimate | `null` |

优先使用合格历史；仅当历史不足**且**季节 `flyingTime` 为正值（`> 0`）时回退季节计划。

Qualified history is preferred whenever enough samples survive filtering. Seasonal `flyingTime` is used only when history is insufficient **and** a positive seasonal duration is configured.

## 前置条件（输入校验）/ Prerequisites (input validation)

访问数据库前分两层校验：

Before any database access, inputs are validated in two layers:

| 层级 / Layer | 位置 / Location | 规则 / Rules |
|--------------|-----------------|--------------|
| HTTP | `EsttController` | 航班号匹配 `estt.validation.flight-number-pattern`（默认 `^[A-Z]{2}[0-9]{3,4}$`，不区分大小写，服务端转大写）；日期严格 `yyMMdd` |
| Service | `EsttService.validateInputs` | 航班号非空、长度 5–6；`flightDate` 在 2000-01-01 之后且不超过当前日期一年 |

日期解析（`EsttService.parseFlightDate`）：恰好六位数字；年份 `00`–`99` 映射为 `2000`–`2099`；非法日历日期拒绝。

Date parsing: exactly six digits; years `00`–`99` → `2000`–`2099`; invalid calendar dates rejected.

## 计算步骤 / Calculation steps

1. **解析季节航班 / Resolve seasonal flight** — 查询当前激活航季（`ACTIVESEASON_FLAG = 1`）中与航班号、目标运营日匹配的记录，并在服务层二次校验（见 [季节航班匹配](#季节航班匹配--seasonal-flight-matching)）。无有效季节航班时立即返回 `source: NONE`（`no seasonal flight for operation day`），**不查历史**。
2. **加载历史候选 / Load historical candidates** — 自 `seasonStart − historyStartOffsetDays`（默认 60 天）至**目标运营日前一天**（`endDate = flightDate − 1`）。计算路径通过 `getArrivalFlightPage` **分页**扫描原始行（块大小 100，按 `flight_date DESC`）。至少扫描 `maxHistoryRows`（默认 300）条原始行；若经时刻偏差过滤后合格样本仍 `< minHistoryFlight`，则**继续分页扫完窗口内剩余数据**，直至合格数足够或数据库无更多行。目标运营日本身**永不**纳入样本。
3. **业务过滤 / Apply eligibility filters** — 剔除未通过数据库预过滤（SQL 已应用）或内存业务规则的行（见 [过滤流水线](#过滤流水线--filter-pipeline)）。
4. **时刻偏差过滤 / Apply schedule-deviation filter** — 在 `FlyingTimeCalculator` 中：早到一律保留；晚到仅当 `actual − scheduled > maxScheduleDeviation` 时剔除（阈值处**含等于**）。
5. **决策 / Decide outcome** — 合格数 ≥ `minHistoryFlight`（默认 20）→ 对**全部**合格样本飞行时长取**整数中位数**（`source: HISTORY`，`confidence: HIGH`）。否则若季节 `flyingTime > 0` → 返回季节时长（`source: SEASONAL`，`sampleSize: 0`）。否则 `flyingTime: null`（`source: NONE`，`sampleSize: 0`）。

## 季节航班匹配 / Seasonal flight matching

使用 `SeasonRepository.getSeasonalArrivalFlight`：

1. **数据库预筛选 / DB prefilter** — `INSTR(OPERATION_DAYS, :operationDay) > 0`（`operationDay` 为 ISO 星期数字 `1`=周一 … `7`=周日）。仅为粗筛，**不等于**逐位匹配（例如运营日 `1` 会误匹配 `"21"`）。
2. **确定性选行 / Deterministic row** — 多行匹配时 SQL 取一行：`ORDER BY seasonal_flight.START_DATE DESC, seasonal_flight.FLIGHT_NUMBER FETCH FIRST 1 ROW ONLY`（优先最新季节段）。
3. **服务层复核 / Service revalidation**（`EsttService.cachedSeasonalFlight`）— 须同时满足：
   - **运营日** — `OperationDays.matches`（逐位集合匹配，非子串）
   - **航季边界** — `seasonStart ≤ flightDate ≤ seasonEnd`（两端含）
4. **激活航季** — 仅关联当前激活航季的计划行。

复核失败则视为无季节航班（`null`），与数据库无记录等同。

If revalidation fails, the seasonal flight is treated as absent (`null`).

## 历史日期窗口 / History date window

```
startDate = seasonalFlight.seasonStart − historyStartOffsetDays
endDate   = flightDate − 1 day
```

| 边界 / Boundary | 是否包含 / Inclusive? |
|-----------------|----------------------|
| `startDate` | 是（SQL `BETWEEN`）/ Yes |
| `endDate`（`flightDate − 1`） | 是 / Yes |
| `flightDate`（目标日） | **排除 / Excluded** |

`startDate` 可早于日历上的航季开始日（设计如此，用于航季初补样本）。

No additional floor on `startDate` (may precede calendar season start by design).

### 行数上限、分页与扩展扫描 / Row cap, paging, and extended scan

计算路径使用 `HistoryFlightRepository.getArrivalFlightPage`：

```sql
ORDER BY flight_date DESC
OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
```

**阶段一 — 预算扫描 / Phase 1 — budget scan：** 分页读取原始行，直至 `rawScanned ≥ maxHistoryRows` 或窗口内无更多行。

**阶段二 — 扩展扫描（按需）/ Phase 2 — extended scan：** 若预算用尽且经时刻偏差过滤后合格样本仍 `< minHistoryFlight`，继续分页扫描窗口剩余部分，直至合格数足够或数据库无更多行。

要点 / Implications:

- `maxHistoryRows` 是**初始原始行预算**，非硬性上限；避免「窗口内仍有合格样本却误判历史不足」。
- 指标/日志 `hitScanLimit`：初始预算已用尽，且完整扫描尝试后合格样本仍 `< minHistoryFlight`。
- 分页历史 API（`GET /estt/history/...`）仍使用**固定** `maxHistoryRows` 扫描、**不**扩展；见 `HistoryFlightProvider.scanPaginatedHistory`。

## 过滤流水线 / Filter pipeline

按顺序应用；每行须通过全部阶段才计入 `minHistoryFlight`。

### 阶段 A — 数据库（`HistoryFlightRepository`）/ Stage A — Database

| 过滤项 / Filter | 规则 / Rule |
|-----------------|-------------|
| 到港 / Arrival | `ARRI_OR_DEPT = 'A'` |
| 非空时刻 / Non-null times | `ACTUAL_DATETIME`、`PRE_DEPT_DATETIME_ACTUAL` 均非空 |
| 时间顺序 / Time order | `PRE_DEPT_DATETIME_ACTUAL < ACTUAL_DATETIME` |
| 日期窗口 / Date window | `flight_date BETWEEN startDate AND endDate` |
| 计划日期 / Scheduled date | `flight_date = TRUNC(SCHEDULED_DATETIME)` |

### 阶段 B — 历史资格（`HistoryFlightProvider.isEligibleHistoryFlight`）/ Stage B — History eligibility

| 过滤项 / Filter | 规则 / Rule |
|-----------------|-------------|
| 运营日 / Operation day | 历史行星期与季节 `operationDays` 逐位匹配（`1`=周一 … `7`=周日） |
| 时间顺序 / Time order | `previousDepartureTime < actualTime`（与 SQL 冗余；分页路径保留） |
| 计划日期 / Scheduled date | `scheduledTime.toLocalDate() == flightDate` |
| 飞行时长容差 / Flying-time tolerance | `\|actualDuration − seasonalFlyingTime\| < maxFlyingTimeDeviation`（严格 `<`）；季节 `flyingTime` 为 `null` 时**跳过** |

`actualDuration` = `Duration.between(previousDepartureTime, actualTime).toMinutes()`。

### 阶段 C — 时刻偏差（`FlyingTimeCalculator`）/ Stage C — Schedule deviation

| 情况 / Case | 规则 / Rule |
|-------------|-------------|
| 早到 / Early (`actual < scheduled`) | 一律保留 / Always kept |
| 准点/晚到 / On-time or late | `(actual − scheduled).toMinutes() ≤ maxScheduleDeviation` 时保留 |

## 边界规则速查 / Boundary rules (quick reference)

| 规则 / Rule | 边界 / Boundary | 代码 / Code |
|-------------|-----------------|-------------|
| 时刻偏差（仅晚到）/ Schedule delay | 含等于：`actual − scheduled ≤ maxScheduleDeviation` | `FlyingTimeCalculator` |
| 飞行时长 vs 季节 / Flying-time vs seasonal | 不含等于：`\|duration − seasonal\| < maxFlyingTimeDeviation` | `HistoryFlightProvider` |
| 历史窗口 / History window | 排除目标运营日 | `historyWindow` |
| 季节回退 / Seasonal fallback | `flyingTime` 须 `> 0` | `FlyingTimeCalculator` |
| 中位数门槛 / Median threshold | 含等于：`count ≥ minHistoryFlight` | `FlyingTimeCalculator` |

## 运营日编码 / Operation day encoding

季节 `operationDays` 为数字串 **1–7**，**1=周一 … 7=周日**（ISO-8601）。

- 示例：`"135"` → 周一、周三、周五。
- `OperationDays.parse` / `matches`：合法数字为集合成员；非法字符忽略；重复数字去重。
- **模型约束**：`SeasonalFlight` 构造时要求全部为 `1..7` 数字。

**注意：** SQL `INSTR` 为子串匹配，可能误命中；最终以 `OperationDays.matches` 为准。

## 中位数规则 / Median rule

当 `qualifiedCount ≥ minHistoryFlight`：

1. 计算每条样本时长并升序排序。
2. **奇数**个：取中间值。
3. **偶数**个：中间两数整数平均 `(mid₁ + mid₂) / 2`（截断除法）。

纳入**全部**合格样本，无截尾、无加权。

## 结果与响应字段 / Outcomes and response fields

| 结果 / Outcome | `source` | `flyingTime` | `sampleSize` | `confidence` |
|----------------|----------|--------------|--------------|--------------|
| 历史中位数 / Historical median | `HISTORY` | 中位数分钟 | 合格数 | `HIGH` |
| 季节回退 / Seasonal fallback | `SEASONAL` | 季节分钟 | `0` | `NONE` |
| 无季节航班 / No seasonal flight | `NONE` | `null` | `0` | `NONE` |
| 历史不足且无季节时长 / Insufficient history | `NONE` | `null` | `0` | `NONE` |

说明 / Notes:

- `message` 仅供运维与日志，**非稳定 API 契约**。
- 已弃用 `history` / `seasonal` 布尔字段；请用 `source`。
- `flyingTime == null` 且 `source == NONE` 表示无估算。
- 计算器内部 `NONE` 路径可能带非零 `qualifiedCount`；HTTP 响应中 `sampleSize` 恒为 `0`。

## 配置参数 / Configuration parameters

绑定于 `EsttCalculationConfig`（`application.yml` / 环境变量）：

| 配置项 / Property | 环境变量 / Env | 默认 / Default | 校验 / Validation |
|-------------------|----------------|----------------|-------------------|
| `max-schedule-deviation` | `MAX_SCHEDULE_DEVIATION` | 120 | > 0 |
| `max-flying-time-deviation` | `MAX_FLYING_TIME_DEVIATION` | 120 | > 0 |
| `min-history-flight` | `MIN_HISTORY` | 20 | > 0 |
| `history-start-offset-days` | `START_MINUS` | 60 | ≥ 0 |
| `max-history-rows` | `MAX_HISTORY_ROWS` | 300 | > 0 |
| `date-format` | — | `yyMMdd` | 非空（仅实现 `yyMMdd`） |

容差单位均为**分钟**。

## 缓存 / Caching

`EsttService` 使用 Micronaut `@Cacheable`：

| 缓存名 / Cache | 方法 / Method | TTL（`application.yml`） | 内容 / Value |
|----------------|---------------|---------------------------|--------------|
| `active-season` | `cachedActiveSeason` | **1 小时 / 1h** | 激活航季元数据 |
| `seasonal-flight` | `cachedSeasonalFlight` | **未声明**（Micronaut 默认） | 校验后的 `SeasonalFlight?` |
| `history-flights` | `cachedHistoryFlights` | **4 小时 / 4h** | 过滤后历史列表 |

计算路径在 `calculateWithSeasonalFlight` 中直接调用 `historyFlightProvider.getHistoryFlights`（不经 `cachedHistoryFlights` 包装）；季节解析仍走季节缓存。

> **维护说明：** `seasonal-flight` 已标注 `@Cacheable` 但 `application.yml` 无 TTL；若需与 `history-flights` 同为 4 小时，请补充配置。

## 可观测性 / Observability

计算路径历史扫描指标（Micrometer）：

| 指标 / Metric | 含义 / Meaning |
|---------------|----------------|
| `estt.history.calc.scan.raw_rows` | 扫描的原始行数 |
| `estt.history.calc.scan.filtered_rows` | 通过阶段 B 的行数 |
| `estt.history.calc.scan.qualified_rows` | 通过阶段 C 的合格数 |
| `estt.history.calc.scan.calls` | 标签 `hit_scan_limit`、`extended_beyond_budget`、`qualified_sufficient` |

当 `hitScanLimit` 且合格数 `< minHistoryFlight` 时，`EsttService` 记录 INFO 日志。

Prometheus：`EstimateSource.SEASONAL` 映射为指标标签 `schedule`（非 `seasonal`）。

## 源码对照 / Source code map

| 组件 / Component | 职责 / Responsibility |
|------------------|----------------------|
| `EsttController` | HTTP、航班号正则、日期解析 |
| `EsttService` | 校验、缓存、编排、MDC/指标、响应映射 |
| `HistoryFlightProvider` | 历史窗口、资格过滤、分页与扩展扫描 |
| `FlyingTimeCalculator` | 时刻偏差、中位数、季节/NONE 分支 |
| `OperationDays` | 运营日逐位解析与匹配 |
| `SeasonRepository` | 季节 SQL；`INSTR` 预筛选；确定性 `ORDER BY` |
| `HistoryFlightRepository` | 到港历史查询与分页 |

## 算例（简化）/ Worked example

目标：`MU9941`，2024-06-05（周三，运营日 `3`）。

1. 季节行 `operationDays = "135"`，`flyingTime = 100`，航季 2024-03-31 … 2024-10-26 → **匹配**。
2. 历史窗口：2024-01-30 … 2024-06-04。
3. 扫描后 22 条合格样本 → 中位数例：`95` 分钟，`source: HISTORY`。
4. 若仅 15 条合格 → 季节 `100`（`SEASONAL`）；若季节时长未配置 → `NONE`。

## 调用方须知 / Consumer notes

- 使用 `source`（`HISTORY` / `SEASONAL` / `NONE`），勿依赖已弃用布尔字段。
- `confidence: HIGH` 仅表示「历史来源且样本数 ≥ 门槛」，不表示统计离散度。
- 分页 API 与计算路径过滤规则相同，但分页**不**做扩展扫描；`hasMore` 可能在触达 `maxHistoryRows` 时为 true。
