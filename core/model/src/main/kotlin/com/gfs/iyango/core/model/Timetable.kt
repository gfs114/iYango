package com.gfs.iyango.core.model

/**
 * 课表定义。
 *
 * 语义约束：
 * - [enabledDays] 中的取值遵循 **周一=1 … 周日=7**；
 * - [cycleWeek] = 0 表示不启用轮换；启用时取值 1..[cycleLength]，
 *   判定式与课程一致：`(week - 1) % cycleLength + 1 == cycleWeek`；
 * - [autoEnabled] = true 时，某天是否展示本课表由 [enabledDays] 与轮换相位共同决定
 *   （见 `CourseRules.tableApplies`）。
 *
 * @property id 持久化主键；0 表示尚未持久化。
 * @property name 课表名称。
 * @property source 来源："LOCAL"（本地手填）/"YGU"（教务同步）。
 * @property schoolTerm 学年学期字符串（同步用）。
 * @property connectionId 同步连接 id。
 * @property isCurrent 是否为当前课表。
 * @property lastSyncAt 最近同步时间（ISO 文本）。
 * @property groupName 分层分组名（分层自动切换用，空=不参与）。
 * @property autoEnabled 是否启用分层自动切换。
 * @property enabledDays 本课表生效的星期集合（1..7）。
 * @property cycleLength 轮换周期长度（≥1）。
 * @property cycleWeek 轮换相位；0=不启用轮换。
 */
data class Timetable(
    val id: Long = 0,
    val name: String = "",
    val source: String = "LOCAL",
    val schoolTerm: String = "",
    val connectionId: String = "",
    val isCurrent: Boolean = false,
    val lastSyncAt: String = "",
    val groupName: String = "",
    val autoEnabled: Boolean = false,
    val enabledDays: List<Int> = (1..7).toList(),
    val cycleLength: Int = 1,
    val cycleWeek: Int = 0,
)
