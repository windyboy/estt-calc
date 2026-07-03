# ESTT 飞行时长估算算法 / ESTT Calculation Algorithm

本文是 ESTT 飞行时长的**权威算法规范**。正文仅描述现行规则；实现映射、缓存与指标见文末附录，修订历史见 [`CHANGELOG.md`](../CHANGELOG.md)。

This is the **authoritative algorithm spec** for ESTT flying-time estimation. The body states current rules only; implementation mapping, caching, and metrics are in the appendices; revision history lives in [`CHANGELOG.md`](../CHANGELOG.md).

- HTTP 契约：[api.md](api.md)
- 代码导航：[code-map.md](code-map.md)

---

## 一、核心算法规范 / Part I — Core algorithm

### 1. 概述 / Overview

服务在三种结果中选择其一：

| `source` | 含义 | `flyingTime` |
|----------|------|--------------|
| `HISTORY` | 合格历史样本的整数中位数 | 分钟 |
| `SEASONAL` | 历史不足时回退季节计划时长 | 季节配置分钟数 |
| `NONE` | 无可用估算 | `null` |

**决策优先级：** 合格历史（样本数 ≥ 门槛）→ 季节 `flyingTime > 0` 回退 → `NONE`。

### 2. 输入前提 / Input prerequisites

主计算路径要求：

- 航班号非空，长度 5–6。
- `flightDate` 在 2000-01-01 之后，且不超过当前日期一年。
- 日期为合法日历日（`yyMMdd` 解析规则见 [附录 B](#附录-b--实现说明--appendix-b--implementation)）。

**时区：** 所有日期与时间（`flightDate`、`seasonStart`/`seasonEnd`、历史行的 `scheduledTime`/`actualTime` 等）均按**机场/业务本地时间**解释与比较；本规范不涉及时区转换。

**Timezone:** All dates and times are interpreted in **airport / business local time**; this spec does not define cross-timezone conversion.

### 3. 计算步骤 / Calculation steps

1. **解析季节航班** — 见 [§4](#4-季节航班匹配--seasonal-flight-matching)。无有效季节航班 → `NONE`，**不查历史**。
2. **加载历史候选** — 见 [§5](#5-历史日期窗口与扫描--history-window-and-scan)。若窗口为空则跳过。
3. **阶段 A/B 过滤** — 见 [§6](#6-过滤流水线--filter-pipeline)。
4. **阶段 C 过滤** — 时刻偏差，见 [§6.3](#63-阶段-c--时刻偏差--stage-c--schedule-deviation)。
5. **决策** — 阶段 C 合格数 ≥ `minHistoryFlight` → 对**本次扫描累计**的合格样本取整数中位数（`HISTORY`）；否则季节 `flyingTime > 0` → `SEASONAL`；否则 `NONE`。

### 4. 季节航班匹配 / Seasonal flight matching

**数据库预筛选：**

- `INSTR(OPERATION_DAYS, :operationDay) > 0`（粗筛；最终以 `OperationDays.matches` 为准）。
- `seasonal_flight.START_DATE ≤ :flightDate`（仅已生效段）。
- 关联当前激活航季（`ACTIVESEASON_FLAG = 1`）。

**确定性选行：** 多行时 `ORDER BY seasonal_flight.START_DATE DESC FETCH FIRST 1 ROW ONLY`（已生效段中 `START_DATE` 最新）。

**唯一性假设：** 业务上，同一激活航季内、同一航班号、同一运营日、同一 `START_DATE` **应仅有一条**季节计划。若违反假设出现多行，现行 SQL 无业务级 tie-breaker（`FLIGHT_NUMBER` 对已过滤航班无区分度），取库返回的第一行并继续计算——视为**数据质量问题**；**运维应对重复季节计划告警**（服务主路径**不**做重复检测，由数据治理/运维 SQL 负责），源数据须保证唯一性。现行规范**不**因此自动返回 `NONE`（若未来要求遇重复即拒绝计算，须单独修订）。

**服务层复核（须同时满足）：**

- `OperationDays.matches(operationDays, targetWeekday)`
- `seasonStart ≤ flightDate ≤ seasonEnd`

复核失败视为无季节航班。

### 5. 历史日期窗口与扫描 / History window and scan

#### 5.1 日期窗口

```
startDate = seasonStart
endDate   = flightDate − 1 day
```

| 边界 | 包含？ |
|------|--------|
| `startDate` | 是 |
| `endDate` | 是 |
| `flightDate`（目标日） | **排除** |

若 `startDate > endDate`（**例如目标日为航季首日**：`flightDate = seasonStart`），历史样本为空，直接进入决策。

`startDate` 固定为航季开始日，**不**早于 `seasonStart`，避免跨航季样本。

#### 5.2 分页扫描

从数据库按 `flight_date DESC` 分页读取（块大小 100，实现细节见附录 B）。

**阶段一 — 预算扫描：** `rawScanned < maxHistoryRows` 期间循环拉取，直至满足以下**任一**条件：

- 阶段 C 合格数 ≥ `minHistoryFlight`；
- `rawScanned ≥ maxHistoryRows`；
- 窗口内无更多行。

**阶段二 — 扩展扫描：** 仅当阶段一预算用尽且合格数仍不足时启用；继续直至合格数足够、无更多行、或 `rawScanned ≥ maxHistoryRows × 3`。

**停止时机（精确语义）：**

- 停止判断在**每个分页批次处理完成之后**执行，**不在**批次内逐行提前截断。
- 因此：若某批次处理过程中合格数已达门槛，该批次内后续行仍会纳入本次扫描的 `qualifiedFlights`；`rawScanned` 计整批已拉取行数。
- 达到门槛后**不再发起**新的数据库分页请求。

**扫描结果用于中位数的样本集：** 停止条件触发前、已通过阶段 C 的全部累计合格行（**非**整个历史窗口内所有可能合格行）。

**分页历史 API（`GET /estt/history/...`）：** 仅应用阶段 A/B 过滤（运营日、飞行时长容差/绝对界等），**不**应用阶段 C（时刻偏差），**不**做阶段二扩展扫描。返回的是「历史资格样本」，与主计算用于中位数的「阶段 C 合格样本」不同。

### 6. 过滤流水线 / Filter pipeline

每行须按序通过全部阶段；计入 `minHistoryFlight` 门槛的是阶段 C 合格数。

#### 6.1 阶段 A — 数据库

| 过滤项 | 规则 |
|--------|------|
| 到港 | `ARRI_OR_DEPT = 'A'` |
| 非空时刻 | `ACTUAL_DATETIME`、`PRE_DEPT_DATETIME_ACTUAL` 均非空 |
| 时间顺序 | `PRE_DEPT_DATETIME_ACTUAL < ACTUAL_DATETIME` |
| 日期窗口 | `flight_date BETWEEN startDate AND endDate` |
| 计划日期 | `flight_date = TRUNC(SCHEDULED_DATETIME)` |

#### 6.2 阶段 B — 历史资格

| 过滤项 | 规则 |
|--------|------|
| 运营日 | 历史行星期 **等于** 目标 `flightDate` 星期；且在季节 `operationDays` 内 |
| 时间顺序 | `previousDepartureTime < actualTime` |
| 计划日期 | `scheduledTime.toLocalDate() == historyFlight.flightDate` |
| 飞行时长容差 | 季节 `flyingTime` 非 null：`\|actualDuration − seasonalFlyingTime\| < maxFlyingTimeDeviation`（严格 `<`） |
| 绝对时长界 | 季节 `flyingTime` 为 null：见下 |

`actualDuration` = `Duration.between(previousDepartureTime, actualTime).toMinutes()`。

**季节 `flyingTime` 为 null 时的绝对时长界（弱约束）：**

- 规则：`minFlyingTime ≤ actualDuration ≤ maxFlyingTime`（默认 30–600 分钟）。
- **目的：** 仅剔除明显不合理的极端脏数据，**非**航线标定约束；600 分钟对部分航线可能仍过宽。
- **影响：** 无季节锚点时，阶段 B 区分力较弱；最终若仍走 `HISTORY`，仅表示样本数达标，不代表航线时长可信度高（见 [§8](#8-结果字段--outcome-fields)）。

#### 6.3 阶段 C — 时刻偏差

仅作用于**已通过阶段 A/B** 的样本。

| 情况 | 规则 |
|------|------|
| 早到（`actual < scheduled`） | **一律保留** |
| 准点/晚到 | `(actual − scheduled).toMinutes() ≤ maxScheduleDeviation` 时保留（含等于） |

**早到不设上限 — 业务假设：**

- 到港早于计划是运营上可接受、且对飞行时长估算仍有参考价值的情形。
- 极端早到若实为数据异常，应依赖阶段 A/B（时刻顺序、飞行时长容差/绝对界）拦截，**不**另设 `maxEarlyDeviation`。
- 若未来业务要求限制早到幅度，需新增配置项并修订本规范。

### 7. 运营日编码 / Operation day encoding

`operationDays` 为数字串 **1–7**（1=周一 … 7=周日）。

- 示例：`"135"` → 周一、三、五；估周三航班时，历史只取周三样本。
- `OperationDays.matches`：逐位集合匹配，非子串。

### 8. 中位数规则 / Median rule

当阶段 C 合格数 ≥ `minHistoryFlight`：

1. 取本次扫描累计的合格样本（见 [§5.2 停止时机](#52-分页扫描)）。
2. 计算各样本 `actualDuration`，升序排序。
3. 奇数个：取中间值；偶数个：中间两数整数平均 `(mid₁ + mid₂) / 2`。

无截尾、无加权。

### 9. 结果字段 / Outcome fields

| 结果 | `source` | `flyingTime` | `sampleSize` | `confidence` |
|------|----------|--------------|--------------|--------------|
| 历史中位数 | `HISTORY` | 中位数分钟 | 合格数 | `HIGH`¹ |
| 季节回退 | `SEASONAL` | 季节分钟 | `0` | `NONE` |
| 无季节航班 | `NONE` | `null` | `0` | `NONE` |
| 历史不足且无季节时长 | `NONE` | `null` | `0` | `NONE` |

¹ **`confidence: HIGH` 不代表统计置信度高。** 仅表示 `source = HISTORY` 且合格样本数 ≥ `minHistoryFlight`（样本数达标）。不反映离散度、标准差或季节锚定强度。调用方勿将其等同于统计学意义上的「高置信」。

- `message` 非稳定 API 契约。
- 使用 `source`，勿依赖已弃用 `history` / `seasonal` 布尔字段。

### 10. 算法参数 / Algorithm parameters

| 参数 | 环境变量 | 默认 | 校验 | 作用 |
|------|----------|------|------|------|
| `max-schedule-deviation` | `MAX_SCHEDULE_DEVIATION` | 120 | > 0 | 阶段 C 晚到上限（分钟） |
| `max-flying-time-deviation` | `MAX_FLYING_TIME_DEVIATION` | 120 | > 0 | 阶段 B 相对季节时长（分钟） |
| `min-history-flight` | `MIN_HISTORY` | 20 | > 0 | 历史中位数最低合格样本数 |
| `max-history-rows` | `MAX_HISTORY_ROWS` | 300 | > 0 | 阶段一 raw 预算；扩展硬顶为 ×3 |
| `min-flying-time` | `MIN_FLYING_TIME` | 30 | > 0 | 阶段 B 弱下界（仅 `flyingTime` 为 null） |
| `max-flying-time` | `MAX_FLYING_TIME` | 600 | > `minFlyingTime` | 阶段 B 弱上界（仅 `flyingTime` 为 null） |

单位均为分钟。扩展扫描硬顶 `maxHistoryRows × 3` 暂非独立配置项。

### 11. 边界规则速查 / Boundary quick reference

| 规则 | 边界 |
|------|------|
| 时刻偏差（晚到） | 含等于：`actual − scheduled ≤ maxScheduleDeviation` |
| 飞行时长 vs 季节 | 不含等于：`\|duration − seasonal\| < maxFlyingTimeDeviation` |
| 历史窗口 | 排除目标日；`startDate = seasonStart` |
| 历史星期 | 须等于目标 `flightDate` 星期 |
| 季节段 | `START_DATE ≤ flightDate`；多段取最新 `START_DATE` |
| 季节回退 | `flyingTime > 0` |
| 中位数门槛 | 含等于：`qualifiedCount ≥ minHistoryFlight` |

### 12. 算例 / Worked example

目标：`MU9941`，2024-06-05（周三），季节 `operationDays = "135"`，`flyingTime = 100`，`seasonStart = 2024-03-31`，`seasonEnd = 2024-10-26`。

1. 季节匹配 → 有效。
2. 窗口：`startDate = 2024-03-31`，`endDate = 2024-06-04`；仅周三历史行。
3. 扫描累计 22 条阶段 C 合格样本 → 中位数 `95` 分钟，`HISTORY`。
4. 若仅 15 条合格 → `SEASONAL` 100；若季节时长未配置 → `NONE`。

---

## 附录 A — 实现说明 / Appendix A — Implementation

### 输入校验路径

| 路径 | 校验 |
|------|------|
| `GET /estt/flyTime/...` | HTTP 航班号正则 + `EsttService.parseFlightDate` + `validateInputs` |
| 直接调用 `getSeasonalFlight` / `getPaginatedHistoryFlights` 等 | **不**经过 `validateInputs` |

日期解析（`EsttService.parseFlightDate`）：恰好六位数字；`00`–`99` → `2000`–`2099`；非法日历拒绝。

### 缓存

| 缓存 | 内容 | 主计算 | 分页历史 |
|------|------|--------|----------|
| `seasonal-flight` | 校验后季节行 | ✓ | ✓ |

主计算与分页历史：`cachedSeasonalFlight` → `historyFlightProvider`（不经历史列表缓存）。

### 源码对照

| 组件 | 职责 |
|------|------|
| `EsttController` | HTTP、航班号正则；日期委托 `parseFlightDate` |
| `EsttService` | 解析、校验、缓存、编排、响应映射 |
| `HistoryFlightProvider` | 历史窗口；主计算路径阶段 B/C 筛选与扫描停止；分页路径仅阶段 B |
| `ScheduleDeviation` | 阶段 C 时刻偏差判定 |
| `FlyingTimeCalculator` | 样本数判断、中位数、`HISTORY` / `SEASONAL` / `NONE` 决策 |
| `OperationDays` | 运营日匹配 |
| `SeasonRepository` | 季节 SQL |
| `HistoryFlightRepository` | 历史 SQL 与分页 |

### 扫描元数据（实现）

| 字段 | 含义 |
|------|------|
| `qualifiedFlights` | 通过阶段 C 的样本 |
| `stageBRows` | 通过阶段 B 的行数 |
| `qualifiedRows` | 通过阶段 C 的行数（与 `qualifiedFlights.size` 相同） |
| `insufficientAfterBudget` | 阶段一预算用尽后合格样本仍不足（含扩展扫描后仍不足） |
| `extendedScanUsed` | 阶段二**已启用**（不表示一定扫到硬顶） |

---

## 附录 B — 可观测性 / Appendix B — Observability

| 指标 | 打点位置 | 含义 |
|------|----------|------|
| `estt.history.scan.raw_rows` | `HistoryFlightProvider` | 扫描原始行数 |
| `estt.history.scan.stage_b_rows` | `HistoryFlightProvider` | 通过阶段 B 行数 |
| `estt.history.scan.qualified_rows` | `HistoryFlightProvider` | 阶段 C 合格数 |
| `estt.history.scan.extended_used` | `HistoryFlightProvider` | 是否启用扩展扫描 |
| `estt.history.scan.calls` | `HistoryFlightProvider` | 标签：`insufficient_after_budget` |

`EstimateSource.SEASONAL` 在 Prometheus 中映射为标签 `schedule`。
