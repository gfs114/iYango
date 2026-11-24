package com.gfs.iyango.core.model

import java.time.LocalDate

/**
 * 学期定义。
 *
 * 语义约束：
 * - [startDate] 是教学第 1 周的锚点日期（不必是周一；周计算按"天数差整除 7"进行）；
 * - 周归属计算见 `CourseRules.weekOfDate`：`floorDiv(date - startDate 的天数, 7) + 1`，
 *   结果超出 `1..[totalWeeks]` 记为 0（学期外）。
 *
 * @property id 持久化主键；0 表示尚未持久化。
 * @property timetableId 所属课表 id。
 * @property name 学期名称。
 * @property startDate 学期第 1 周的锚点日期。
 * @property totalWeeks 学期总周数，默认 20。
 * @property isCurrent 是否为当前学期。
 */
data class Semester(
    val id: Long = 0,
    val timetableId: Long = 0,
    val name: String = "",
    val startDate: LocalDate,
    val totalWeeks: Int = 20,
    val isCurrent: Boolean = false,
)
