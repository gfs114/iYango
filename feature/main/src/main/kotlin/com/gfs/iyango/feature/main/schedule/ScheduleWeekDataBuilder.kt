package com.gfs.iyango.feature.main.schedule
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.model.*
import java.time.LocalDateTime

/** 组装一周网格：真实实例 + 非本周幽灵块 + 冲突分组 + 周密度统计。 */
fun buildScheduleWeekData(
    timetable: Timetable,
    semester: Semester,
    slots: List<TimeSlot>,
    courses: List<Course>,
    timeRailRows: (Timetable, List<Course>, List<TimeSlot>, Int) -> List<com.gfs.iyango.core.domain.TimeRailRow>,
    direction: WeekFlipDirection,
    now: LocalDateTime,
    days: List<com.gfs.iyango.core.domain.EffectiveScheduleDay>,
    selectedWeek: Int?,
    weekDayCounts: List<List<Int>> = ScheduleEffectiveProjection.weekDayCounts(days, semester.startDate, semester.totalWeeks),
    weekSlotMasks: List<List<Int>> = ScheduleEffectiveProjection.weekSlotMasks(days, semester.startDate, semester.totalWeeks),
): WeekScheduleData {
    val today = now.toLocalDate()
    val currentWeek = CourseRules.weekOfDate(semester, today)
    val displayedWeek = selectedWeek
        ?: currentWeek.takeIf { it > 0 }
        ?: 1
    val monday = semester.startDate.plusDays((displayedWeek - 1) * 7L)

    // 唯一数据来源：有效课表查询（基础解析 + F08 临时调整 + 临时整天）。
    val occurrences = days.filter { it.date in monday..monday.plusDays(6) }.flatMap { it.occurrences }

    // 非本周幽灵块：周区间命中但被周规则跳过（单双周/轮换/所选周），
    // 仍占位绘制并 38% 降权（ui-baseline §3〔参 offWeekOpacity〕）。
    val ghostCourses = ScheduleEffectiveProjection.offWeekCourses(days.filter { it.date in monday..monday.plusDays(6) })

    val realBlocks = occurrences.map { occurrence ->
        WeekScheduleData.GridBlock(
            course = occurrence.course,
            occurrence = occurrence,
            rowSpan = occurrence.course.endSection - occurrence.course.startSection + 1,
            isOffWeek = false,
        )
    }
    val ghostBlocks = ghostCourses.map { course ->
        WeekScheduleData.GridBlock(
            course = course,
            occurrence = null,
            rowSpan = course.endSection - course.startSection + 1,
            isOffWeek = true,
        )
    }

    // 保留原始分组；展示投影对每条真实发生独立分列，真实块在前。
    val order = compareBy<WeekScheduleData.GridBlock> { it.isOffWeek }
        .thenBy { it.occurrence?.startMinute ?: Int.MAX_VALUE }
        .thenBy { it.course.startSection }
    val cells = CellGrouping.group(
        realBlocks + ghostBlocks,
        dayOf = { it.course.dayOfWeek },
        startOf = { it.course.startSection },
        order = order,
    ).map { group ->
        WeekScheduleData.GridCellGroup(
            dayOfWeek = group.dayOfWeek,
            startSection = group.startSection,
            blocks = group.items,
        )
    }

    // 官方课表固定11节；其他课表随课程/作息扩展，封顶20。
    val maxCourseSection = cells.maxOfOrNull { group ->
        group.blocks.maxOf { it.course.endSection }
    } ?: 0
    val weekDays = days.filter { it.date in monday..monday.plusDays(6) }
    val sectionCount = ScheduleSectionPolicy.forDays(timetable.source,weekDays,maxCourseSection,slots,20)

    val timeRail = timeRailRows(timetable, courses, slots, sectionCount)

    return WeekScheduleData(
        timetableName = timetable.name,
        timetableId = timetable.id,
        semester = semester,
        totalWeeks = semester.totalWeeks,
        currentWeek = currentWeek,
        displayedWeek = displayedWeek,
        flipDirection = direction,
        weekStart = monday,
        today = today,
        now = now,
        sectionCount = sectionCount,
        cells = cells,
        timeSlots = slots,
        courses = courses,
        weekDayCounts = weekDayCounts,
        weekSlotMasks = weekSlotMasks,
        effectiveDays = days,
        timeRail = timeRail,
    )
}

