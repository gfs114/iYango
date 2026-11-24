package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import java.time.LocalDate

/**
 * 把课表解析为一段日期内的课程发生实例（P1 核心用例）。
 *
 * 结果是今日页、周课表、提醒、日历与桌面组件的**唯一数据来源**：这些展示面必须
 * 通过本用例获取"某天有哪些课、几点上"，不得各自用 [Course] 重新推导，
 * 否则周规则/单次调整/时间待定等语义会出现分叉。
 *
 * 解析算法（对范围内每一天）：
 * 1. 用 [CourseRules.weekOfDate] 定位教学周；学期缺失或日期落在学期外（周为 0）时
 *    该日不产生实例；
 * 2. 用 [CourseRules.forWeek] 做替换集合过滤（基础课被 override 压制、override 收敛到
 *    目标周、timeMode=2 的单次日期课映射星期并收敛周次）；
 * 3. 取 `dayOfWeek == date 星期`（周一=1…周日=7）的课程；
 * 4. 用 [CourseRules.occursInWeek] 复核（单双周/轮换/所选周/启用开关在此收敛）；
 * 5. 用 [CourseRules.timeRange] + [CourseRules.minutes] 解析展示钟点；未知时间输出
 *    null，**不静默套默认作息**（调用方必须显式呈现"时间待定"）。
 *
 * 排序：按日期升序，其次 [CourseOccurrence.startMinute]（null 排最后），
 * 再次 [Course.startSection] 升序（稳定排序，同键时保持课程输入顺序）。
 *
 * @param timetable 所属课表；本用例当前仅将其作为上下文保留（分层自动切换由上层
 *   用例处理），不据此过滤课程。
 */
object ResolveScheduleOccurrences {

    /**
     * 解析 [range]（闭区间，含两端）内每一天的课程发生实例。
     *
     * @param semester 学期；null 表示无学期上下文，返回空列表。
     * @param timeSlots 作息时间表，用于节次到钟点的回退解析。
     * @param courses 该课表下的全部课程（含单次调整记录）。
     */
    fun invoke(
        timetable: Timetable,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        courses: List<Course>,
        range: ClosedRange<LocalDate>,
    ): List<CourseOccurrence> {
        val result = mutableListOf<CourseOccurrence>()
        var date = range.start
        val end = range.endInclusive
        while (!date.isAfter(end)) {
            val week = CourseRules.weekOfDate(semester, date)
            if (week > 0) {
                val weekCourses = CourseRules.forWeek(courses, week, semester)
                for (course in weekCourses) {
                    if (course.dayOfWeek != date.dayOfWeek.value) {
                        continue
                    }
                    if (!CourseRules.occursInWeek(course, week)) {
                        continue
                    }
                    val timeRange = CourseRules.timeRange(course, timeSlots)
                    result += CourseOccurrence(
                        occurrenceId = CourseOccurrence.stableId(course, date),
                        course = course,
                        date = date,
                        startMinute = CourseRules.minutes(timeRange.startTime),
                        endMinute = CourseRules.minutes(timeRange.endTime),
                        isOverride = course.overrideOfId > 0,
                    )
                }
            }
            date = date.plusDays(1)
        }
        return result.sortedWith(
            compareBy(
                { it.date },
                { it.startMinute ?: Int.MAX_VALUE },
                { it.course.startSection },
            ),
        )
    }
}
