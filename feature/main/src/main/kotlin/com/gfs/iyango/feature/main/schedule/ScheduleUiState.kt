package com.gfs.iyango.feature.main.schedule

import androidx.compose.runtime.Immutable
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.TimeRailRow
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课表页状态（ui-baseline §3）。
 *
 * 状态机：FirstLoading → Ready / NoTimetable / NoSemester / Error。
 * 学期未设置（NoSemester）优先于空课程：没有教学周网格无意义。
 */
@Immutable
sealed interface ScheduleUiState {

    /** 首次加载：尚无任何数据。 */
    data object FirstLoading : ScheduleUiState

    /** 没有当前课表：先在「我的」中创建或导入课表。 */
    data object NoTimetable : ScheduleUiState

    /** 学期未设置：没有教学周，无法定位周次。 */
    data object NoSemester : ScheduleUiState

    /**
     * 已有数据（周网格 + 周选择密度）。
     *
     * @param data 本周网格数据。
     */
    @Immutable
    data class Ready(val data: WeekScheduleData, val weeks: List<WeekScheduleData> = listOf(data)) : ScheduleUiState

    /**
     * 错误态。
     *
     * @param message 错误描述（展示给用户）。
     * @param canRetry 是否提供重试入口。
     */
    @Immutable
    data class Error(val message: String, val canRetry: Boolean) : ScheduleUiState
}

/**
 * 周切换的方向语义，驱动网格翻周动效（ui-baseline §3：左右翻周 translate ±28dp +
 * 淡入 260ms；picker 跳周纯淡入）。
 */
enum class WeekFlipDirection {
    /** 经周选择器/回到本周跳转：纯淡入淡出。 */
    JUMP,

    /** 切到下一周：自右向左滑入。 */
    NEXT,

    /** 切到上一周：自左向右滑入。 */
    PREVIOUS,
}

/**
 * 课表页背景（F10）：课表独立壁纸优先，未设置时透出根级全局壁纸。
 *
 * @property path 亮色模式背景绝对路径；null 表示无独立背景。
 * @property darkPath 暗色模式背景绝对路径；null = 跟随 [path]。
 * @property scrim 可读性遮罩不透明度 0..0.8。
 * @property blur 模糊强度 0..1（渲染层映射半径；平台不支持时忽略）。
 */
@Immutable
data class ScheduleWallpaper(
    val path: String? = null,
    val darkPath: String? = null,
    val scrim: Float = 0f,
    val blur: Float = 0f,
    val hasDedicatedWallpaper: Boolean = false,
)

/**
 * 一周的课表网格数据（全部课程实例来自 ResolveScheduleOccurrences，禁止另行推导）。
 *
 * @property timetableName 课表名。
 * @property semester 学期上下文；null 表示未设置学期。
 * @property totalWeeks 学期总周数；0 表示未设置学期。
 * @property currentWeek 今日所在教学周；0 = 学期外/未设置。
 * @property displayedWeek 当前展示的教学周。
 * @property flipDirection 最后一次切周的方向（驱动翻周动效）。
 * @property weekStart 展示周的周一（ViewModel 按学期锚点反推）。
 * @property today 今日日期（ViewModel 用注入 Clock 计算，UI 不得自行取 now）。
 * @property now 当前时刻（每 30 秒刷新，驱动运行状态与时间指示器）。
 * @property sectionCount 网格段数（阳光学院固定11，其余12..20）。
 * @property cells 冲突分组后的网格块（按星期、起始节次升序）。
 * @property timeSlots 作息时间表（节次 → 钟点，详情弹层与时间指示器共用）。
 * @property timeRail 时间轴行（节号 + 钟点，南北作息差异节并列两套）。
 * @property courses 当前主课表的课程快照；冲突检测使用目标日有效实例。
 * @property weekDayCounts 每周每天的课数（weekDayCounts[week-1][day-1]），周选择密度点用。
 * @property timetableId 当前课表主键（临时整天来源选择需排除自身）。
 */
@Immutable
data class WeekScheduleData(
    val timetableName: String,
    val readOnly: Boolean = false,
    val browsedTerm: String? = null,
    val datesKnown: Boolean = true,
    val contextVersion: Long = 0,
    val navigationVersion: Long = 0,
    val timetableId: Long = 0,
    val semester: Semester?,
    val totalWeeks: Int,
    val currentWeek: Int,
    val displayedWeek: Int,
    val flipDirection: WeekFlipDirection,
    val weekStart: LocalDate,
    val today: LocalDate,
    val now: LocalDateTime,
    val sectionCount: Int,
    val cells: List<GridCellGroup>,
    val timeSlots: List<TimeSlot>,
    val courses: List<Course>,
    val weekDayCounts: List<List<Int>>,
    val weekSlotMasks: List<List<Int>> = emptyList(),
    val timeRail: List<TimeRailRow> = emptyList(),
    val effectiveDays: List<com.gfs.iyango.core.domain.EffectiveScheduleDay> = emptyList(),
    val exams: List<ScheduleExam> = emptyList(),
) {
    /** 学期名；未设置学期时为 null。 */
    val semesterName: String? get() = semester?.name

    /** 展示周的周日。 */
    val weekEnd: LocalDate
        get() = weekStart.plusDays(6)

    /** 今日是否在展示周内（今日列 wash 与指示器只在此时绘制）。 */
    val showsToday: Boolean
        get() = datesKnown && currentWeek > 0 && displayedWeek == currentWeek

    /** 今日星期（1=周一 … 7=周日）。 */
    val todayDay: Int
        get() = today.dayOfWeek.value

    /** 网格内是否有任何可画块。 */
    val hasBlocks: Boolean get() = cells.any { it.blocks.isNotEmpty() }

    /**
     * 网格中的一个冲突分组：同 (星期, 起始节次) 的全部块。
     *
     * @property dayOfWeek 星期（1..7）。
     * @property startSection 起始节次。
     * @property blocks 组内块；[GridBlock] 真实块在前、非本周幽灵块在后。
     */
    @Immutable
    data class GridCellGroup(
        val dayOfWeek: Int,
        val startSection: Int,
        val blocks: List<GridBlock>,
    ) {
        /** 组内块数（>1 时画数量徽章并可翻页）。 */
        val conflictCount: Int get() = blocks.size
    }

    /**
     * 网格中一块可画的课程块。
     *
     * @property course 来源课程（详情弹层直接展示）。
     * @property occurrence 真实发生实例；null 表示非本周幽灵块（被周规则跳过）。
     * @property rowSpan 占用节次数。
     * @property isOffWeek 是否非本周幽灵块（38% 透明度降权 + 「非本周」标记）。
     */
    @Immutable
    data class GridBlock(
        val course: Course,
        val occurrence: CourseOccurrence?,
        val rowSpan: Int,
        val isOffWeek: Boolean,
    ) {
        /** 课程块名。 */
        val name: String get() = course.name
    }
}
