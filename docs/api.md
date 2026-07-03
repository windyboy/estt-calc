# ESTT API 参考 / ESTT API Reference

基础路径 / Base path: `/estt`。交互文档 / Swagger UI: `http://localhost:8080/swagger-ui`。

算法规则见 [algorithm.md](algorithm.md)。

## 端点 / Endpoints

### `GET /estt/season`

返回当前激活的 `FlightSeason`；无激活航季时 **404**。

Returns the active `FlightSeason`, or **404** if none.

### `GET /estt/seasonal/{flightNumber}/{flightDate}`

- `flightDate`：`yyMMdd`（例 `211231`）
- **200**：`SeasonalFlight` | **404**：未找到 | **400**：非法输入 | **500**：数据库错误

### `GET /estt/history/{flightNumber}/{flightDate}`

经「基本有效」与「与目标航班可比」两道筛选后的到港历史，一次性有界返回（**不含**到港时刻可信筛选；见 [algorithm.md 附录](algorithm.md#附与历史查询接口的差异)）。

Bounded historical arrivals after basic-valid and comparability filtering (**excludes** arrival-time credibility filter; see [algorithm.md appendix](algorithm.md#附与历史查询接口的差异)).

响应 / Response：`HistoryResponse`（`items`、`totalFiltered`、`rawScanned`、`capped`）。

- 每次请求最多扫描 `MAX_HISTORY_ROWS` 条原始记录（单次有界读取，无分页参数）。
- `totalFiltered` 等于 `items` 条数，表示本次扫描内通过阶段 B 的记录数。
- `rawScanned` 为本次从数据库读取的原始行数。
- `capped=true` 表示 `rawScanned` 达到 `MAX_HISTORY_ROWS` 上限，窗口内可能仍有未读记录。

```bash
curl "http://localhost:8080/estt/history/MU9941/211231"
```

### `GET /estt/flyTime/{flightNumber}/{flightDate}` — 核心计算 / core calculation

响应 / Response：`FlyingTimeResponse`

| 字段 / Field | 说明 / Description |
|--------------|-------------------|
| `flyingTime` | 估算分钟数；`source` 为 `NONE` 时为 `null` |
| `source` | `HISTORY`、`SEASONAL` 或 `NONE` |
| `sampleSize` | 使用的合格历史条数；季节/NONE 时为 `0` |
| `confidence` | `source` 为 `HISTORY` 时为 `HIGH`；否则 `NONE` |
| `history` / `seasonal` | 已弃用；与 `source` 对应 |
| `message` | 人类可读；**非稳定**编程契约 |

**基于历史 / History-based:**

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

**季节回退 / Seasonal fallback:**

```json
{
  "flightNumber": "MU9941",
  "flightDate": "2021-12-31",
  "source": "SEASONAL",
  "flyingTime": 90,
  "history": false,
  "seasonal": true,
  "message": "Using seasonal flight flying time due to insufficient historical data",
  "sampleSize": 0,
  "confidence": "NONE"
}
```

**无估算 / No estimate:**

```json
{
  "flightNumber": "MU9941",
  "flightDate": "2021-12-31",
  "source": "NONE",
  "flyingTime": null,
  "history": false,
  "seasonal": false,
  "message": "no seasonal flight for operation day",
  "sampleSize": 0,
  "confidence": "NONE"
}
```

## 校验 / Validation

- 航班号 / Flight number: `[A-Z]{2}[0-9]{3,4}`（不区分大小写，服务端转大写）
- 日期 / Date: 严格 `yyMMdd` 六位；非法日历 → **400**

## 错误响应 / Error responses

| 状态 / Status | 场景 / When |
|---------------|-------------|
| 400 | 非法航班号或日期 |
| 404 | 显式查询端点未找到：`GET /estt/season`、`GET /estt/seasonal/...` |
| 500 | 数据库或计算失败 |

## 健康检查与指标 / Health and metrics

| 路径 / Path | 用途 / Purpose |
|-------------|----------------|
| `GET /health` | 应用健康 |
| `GET /prometheus` | Prometheus 指标 |

历史查询指标：`estt.history.*`（`calls`、`items`、`filtered`、`raw_rows`，标签 `capped`）。计算指标：`estt.calculation.*`（`SEASONAL` 在指标中标记为 `schedule`）。主计算历史扫描：`estt.history.scan.*`（`raw_rows`、`stage_b_rows`、`qualified_rows`、`calls` 标签 `insufficient_after_cap`）。

## 配置 / Configuration

见 [README 配置章节](../README.md#配置-configuration) 与 `application.yml`。主要环境变量：`ORACLE_*`、`MAX_SCHEDULE_DEVIATION`、`MAX_FLYING_TIME_DEVIATION`、`MIN_HISTORY`、`MIN_FLYING_TIME`、`MAX_FLYING_TIME`、`MAX_HISTORY_ROWS`、`FLIGHT_NUMBER_PATTERN`。
