# 源码导航 / Source Code Map

Kotlin 代码库快速索引。非显而易见的业务规则在源码中以**中文在前、英文在后**的注释说明。

Quick reference for navigating the Kotlin codebase. Domain rules are documented inline (Chinese first, English second) at non-obvious decision points.

## 包结构 / Package layout

```text
src/main/kotlin/com/gzzn/airport/
├── Application.kt              # Micronaut 入口 / entry + Api metadata
├── config/EsttCalculationConfig.kt   # 计算阈值配置 / calculation thresholds
├── exception/                  # ErrorCode、异常处理 / handlers
├── health/DatabaseHealthIndicator.kt
├── model/                      # DTO：FlyingTimeResponse、SeasonalFlight、OperationDays …
├── repository/                 # Micronaut Data JDBC + Oracle SQL
├── resource/EsttController.kt  # REST 端点 / endpoints
├── service/
│   ├── EsttService.kt          # 编排、缓存、指标 / orchestration, cache, metrics
│   ├── calculator/FlyingTimeCalculator.kt
│   └── history/
│       ├── HistoryFlightProvider.kt
│       └── HistoryFlightScan.kt
└── util/ResultExtensions.kt    # Result.flatMap 链式调用
```

## 关键文件 / Key files

| 文件 / File | 约行数 | 阅读目的 / Read for |
|-------------|--------|---------------------|
| `EsttService.kt` | ~270 | 端到端：校验 → 季节 → 历史 → 计算 → 响应 |
| `HistoryFlightProvider.kt` | ~240 | 历史窗口、过滤、分页与扩展扫描 |
| `FlyingTimeCalculator.kt` | ~140 | 中位数、时刻偏差、回退分支 |
| `EsttController.kt` | ~240 | HTTP 映射与 OpenAPI |
| `OperationDays.kt` | 小 | 运营日编码与匹配 |
| `SeasonRepository.kt` | 小 | `INSTR` 预筛选；确定性 `ORDER BY` |
| `HistoryFlightRepository.kt` | 小 | 历史查询与分页 SQL |

## 源码中的领域注释 / Domain comments in source

- **EsttService**：`yyMMdd` 解析；`INSTR` + 航季边界复核；`SEASONAL` → 指标标签 `schedule`
- **HistoryFlightProvider**：排除目标日；飞行时长严格 `<` 容差；计算路径扩展扫描
- **FlyingTimeCalculator**：晚到偏差含等于阈值；偶数样本整数中位数
- **OperationDays**：逐位匹配、非法字符处理

## 错误处理 / Error handling

服务层返回 `Result<T>`；控制器 `fold` 为 HTTP 响应：

- 成功但数据为空 → 业务结果（200/404）
- `Result.failure` → 500 + `ErrorCode`

## 测试 / Tests

| 区域 / Area | 测试文件 / Test files |
|-------------|----------------------|
| Service | `EsttServiceTest.kt`、`EsttServiceErrorTest.kt`、`EsttServiceEdgeCaseTest.kt`、`EsttServiceValidationAndMatchingTest.kt`、`EsttServiceTestSupport.kt` |
| Calculator | `FlyingTimeCalculatorTest.kt` |
| History | `HistoryFlightProviderTest.kt` |
| Controller | `EsttControllerTest.kt` |
| Models | `OperationDaysTest.kt`、`ModelTest.kt` |

命令见 [development.md](development.md)。

## 相关文档 / Related docs

- [algorithm.md](algorithm.md) — 业务规则与边界（中英双语）
- [api.md](api.md) — HTTP 契约（中英双语）
- [development.md](development.md) — 构建、测试、排障
