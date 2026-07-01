package com.gzzn.airport.model

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDate

/**
 * 飞行时长计算接口的响应模型；字段形状属于外部契约，内部重构不得改变。
 * API response model for flying-time calculation; field shape is an external contract and
 * must not change during internal refactoring.
 *
 * @property flyingTime 估算分钟数；当 [source] 为 [EstimateSource.NONE] 时为 `null`。
 * Estimated minutes; `null` when [source] is [EstimateSource.NONE].
 * @property history 兼容旧客户端的历史样本标记；[source] 为 [EstimateSource.HISTORY] 时为 `true`。
 * Deprecated legacy flag; `true` when [source] is [EstimateSource.HISTORY].
 * @property seasonal 兼容旧客户端的航季计划标记；[source] 为 [EstimateSource.SEASONAL] 时为 `true`。
 * Deprecated legacy flag; `true` when [source] is [EstimateSource.SEASONAL].
 * @property message 面向运维和日志的人类可读说明。
 * Human-readable detail for operators and logs.
 * @property source 权威估算来源；使用 `NONE` 判断无可用估算。
 * Authoritative estimate origin. Use `NONE` to detect no estimate.
 * @property sampleSize 参与估算的合格历史样本数；航季回退或无估算时为 `0`。
 * Count of qualified historical flights used in the estimate; `0` for seasonal or none.
 * @property confidence 仅历史样本估算为 `HIGH`，其他情况为 `NONE`。
 * `HIGH` only when [source] is [EstimateSource.HISTORY]; otherwise `NONE`.
 */
@Serdeable
data class FlyingTimeResponse(
    val flightNumber: String,
    val flightDate: LocalDate,
    val flyingTime: Long?,
    @Deprecated("Use source == EstimateSource.HISTORY instead")
    val history: Boolean,
    @Deprecated("Use source == EstimateSource.SEASONAL instead")
    val seasonal: Boolean,
    val message: String,
    val source: EstimateSource,
    val sampleSize: Int,
    val confidence: Confidence,
)
