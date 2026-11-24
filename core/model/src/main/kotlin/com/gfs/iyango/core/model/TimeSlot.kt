package com.gfs.iyango.core.model

/**
 * 作息时间表中的一行（某一节的上下课钟点）。
 *
 * 语义约束：
 * - [startTime]/[endTime] 采用 `HH:mm`；[kind]=2（休息/占位）允许两者同时为空；
 * - [section] 为正整数；同一课表内 [section] 用于定位（`timeRange` 取首节开始/末节结束）；
 * - [sortOrder] 为展示排序权重，0 表示回退到 [section]。
 *
 * @property id 持久化主键；0 表示尚未持久化。
 * @property timetableId 所属课表 id。
 * @property section 节次号（≥1）。
 * @property startTime 该节开始时间（HH:mm）。
 * @property endTime 该节结束时间（HH:mm）。
 * @property name 节次名称。
 * @property kind 节点类型：0=上课节次，2=休息/占位（参考实现允许 0..3）。
 * @property hidden 是否在默认视图中隐藏。
 * @property defaultCourseName 该节的默认占位课程名（空=无）。
 * @property sortOrder 展示排序权重；0 回退到 [section]。
 */
data class TimeSlot(
    val id: Long = 0,
    val timetableId: Long = 0,
    val section: Int = 1,
    val startTime: String = "",
    val endTime: String = "",
    val name: String = "",
    val kind: Int = 0,
    val hidden: Boolean = false,
    val defaultCourseName: String = "",
    val sortOrder: Double = 0.0,
)
