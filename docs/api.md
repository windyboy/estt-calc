# ESTT API 参考 / ESTT API Reference

基础路径 / Base path: `/estt`。交互文档 / Swagger UI: `http://localhost:8080/swagger-ui`。

算法规则见 [algorithm.md](algorithm.md)（中英双语）。

## 端点 / Endpoints

### `GET /estt/season`

返回当前激活的 `FlightSeason`；无激活航季时 **404**。

Returns the active `FlightSeason`, or **404** if none.

### `GET /estt/seasonal/{flightNumber}/{flightDate}`

- `flightDate`：`yyMMdd`（例 `211231`）
- **200**：`SeasonalFlight` | **404**：未找到 | **400**：非法输入 | **500**：数据库错误

### `GET /estt/history/{flightNumber}/{flightDate}{?limit,offset}`

经业务过滤后的到港历史，分页返回。

Paginated historical arrivals after business filtering.

| 参数 / Query | 默认 / Default | 约束 / Constraint |
|--------------|----------------|-------------------|
| `limit` | 100 | 1–1000 |
| `offset` | 0 | ≥ 0 |

响应 / Response：`PaginatedHistoryResponse`（`items`、`totalFiltered`、`offset`、`limit`、`hasMore`）。

- 每次请求最多扫描 `MAX_HISTORY_ROWS` 条原始记录。
- `totalFiltered` 为扫描窗口内的尽力计数。
- `hasMore=true` 时以 `offset + limit` 请求下一页。

```bash
curl "http://localhost:8080/estt/history/MU9941/211231?limit=5&offset=10"
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
  "source": "SEASONAL",
  "flyingTime": 90,
  "sampleSize": 0,
  "confidence": "NONE"
}
```

**无估算 / No estimate:**

```json
{
  "source": "NONE",
  "flyingTime": null,
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
| 404 | 航季或季节航班未找到（业务空结果） |
| 500 | 数据库或计算失败 |

## 健康检查与指标 / Health and metrics

| 路径 / Path | 用途 / Purpose |
|-------------|----------------|
| `GET /health` | 应用健康 |
| `GET /prometheus` | Prometheus 指标 |

分页指标：`estt.history.pagination.*`。计算指标：`estt.calculation.*`（`SEASONAL` 在指标中标记为 `schedule`）。历史扫描：`estt.history.calc.scan.*`。

## 配置 / Configuration

见 [README 配置章节](../README.md#配置-configuration) 与 `application.yml`。主要环境变量：`ORACLE_*`、`MAX_SCHEDULE_DEVIATION`、`MAX_FLYING_TIME_DEVIATION`、`MIN_HISTORY`、`START_MINUS`、`MAX_HISTORY_ROWS`、`FLIGHT_NUMBER_PATTERN`。
