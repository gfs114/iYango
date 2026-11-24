package com.gfs.iyango.core.model

/**
 * 一条课程定义（持久化行）。
 *
 * 语义约束（与鸿蒙参考实现 CourseModels.ets 逐字段对齐）：
 * - [dayOfWeek] 采用 **周一=1 … 周日=7**，绝不使用 JS `Date.getDay()` 的周日=0 语义；
 * - 节次区间 `[startSection, endSection]` 为闭区间且 start ≤ end；
 * - 周次区间 `[startWeek, endWeek]` 为闭区间且 start ≤ end，教学周从 1 开始；
 * - [selectedWeeks] 为空表示"不额外筛选"，非空时仅这些周上课；
 * - [startTime]/[endTime] 采用 `HH:mm`，仅在 [timeMode]=1 时有效；
 * - [date] 采用 `yyyy-MM-dd`，仅在 [timeMode]=2 时有效；
 * - [timeUnconfirmed] 只用于读取结果（官方校区时间待确认时禁止下游回退到表级时间），
 *   不持久化；
 * - [overrideOfId] > 0 表示本条是"对 [overrideOfId] 课程在 [overrideWeek] 周的单次调整"，
 *   此时 [overrideWeek] 必须 ≥ 1；
 * - [cycleWeek] = 0 表示不启用轮换；启用时取值 1..[cycleLength]。
 *
 * 本类型不可变；[selectedWeeks] 视为只读列表，所有"修改"都通过 [copy] 生成新实例。
 *
 * @property id 持久化主键；0 表示尚未持久化。
 * @property timetableId 所属课表 id。
 * @property name 课程名称（参与冲突提示与稳定实例键）。
 * @property abbreviation 名称缩写。
 * @property remark 备注，最多 300 字。
 * @property color 卡片强调色，默认 "#3E7BFA"。
 * @property credit 学分，非负。
 * @property isOutdoor 是否户外课程。
 * @property enabled 关闭后的课程不参与任何周的发生计算。
 */
data class Course(
    val id: Long = 0,
    val timetableId: Long = 0,
    val name: String = "",
    val teacher: String = "",
    val classroom: String = "",
    val abbreviation: String = "",
    val remark: String = "",
    /** 星期几，1=周一 … 7=周日。 */
    val dayOfWeek: Int = 1,
    /** 起始节次（含）。 */
    val startSection: Int = 1,
    /** 结束节次（含）。 */
    val endSection: Int = 1,
    /** 展示投影前的原始起始节次（0=未投影）。 */
    val sourceStartSection: Int = 0,
    /** 展示投影前的原始结束节次（0=未投影）。 */
    val sourceEndSection: Int = 0,
    /** 起始教学周（含）。 */
    val startWeek: Int = 1,
    /** 结束教学周（含）。 */
    val endWeek: Int = 1,
    val weekType: WeekType = WeekType.EVERY,
    /** 额外指定的上课周列表；空表示不额外筛选。 */
    val selectedWeeks: List<Int> = emptyList(),
    val color: String = "#3E7BFA",
    val credit: Double = 0.0,
    val isOutdoor: Boolean = false,
    val enabled: Boolean = true,
    /** 上课时间模式：0=按节次+时间表，1=自定义钟点，2=单次指定日期。 */
    val timeMode: Int = 0,
    /** 自定义开始时间（HH:mm），timeMode=1（及自定义时间优先场景）时有效。 */
    val startTime: String = "",
    /** 自定义结束时间（HH:mm），timeMode=1（及自定义时间优先场景）时有效。 */
    val endTime: String = "",
    /** 单次指定日期（yyyy-MM-dd），timeMode=2 时有效。 */
    val date: String = "",
    /** 官方校区时间待确认标记；只用于读取结果，不持久化。 */
    val timeUnconfirmed: Boolean = false,
    /** 轮换周期长度（≥1）。 */
    val cycleLength: Int = 1,
    /** 轮换相位；0=不启用轮换，启用时 1..cycleLength。 */
    val cycleWeek: Int = 0,
    /** 被单次调整的原课程 id；0=本条不是调整记录。 */
    val overrideOfId: Long = 0,
    /** 单次调整生效的教学周；overrideOfId > 0 时必须 ≥ 1。 */
    val overrideWeek: Int = 0,
    /** 分组键；非空且相邻字段等值时允许合并相邻节次。 */
    val groupKey: String = "",
    /**
     * 学校稳定来源键（仅教务同步写入）；空表示本机手填/导入的课程。
     *
     * 同步按该键更新基础安排，课程行 id 保持不变，因此引用它的单次调整不会错挂；
     * 学校返回的同一门课在不同学期/校区的记录各不相同，不得只按名称匹配。
     */
    val sourceKey: String = "",
)
