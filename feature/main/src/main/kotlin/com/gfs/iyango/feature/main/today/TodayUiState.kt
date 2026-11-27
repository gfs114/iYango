package com.gfs.iyango.feature.main.today

import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.HolidayDayInfo
import com.gfs.iyango.core.domain.PartnerTodaySummary
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import java.time.LocalDate
import java.time.YearMonth

/**
 * 今日页 UI 状态（契约 §2）。
 */
sealed interface TodayUiState {

    /** 首帧加载中。 */
    data object FirstLoading : TodayUiState

    /** 无课表：引导创建。 */
    data object NoTimetable : TodayUiState

    /** 就绪：今日/明日课程、月历、周次上下文齐备。 */
    data class Ready(
        val timetable: Timetable,
        val semester: Semester?,
        /** 今天所在教学周；学期外为 0。 */
        val week: Int,
        val today: LocalDate,
        val todayRows: List<OccurrenceRow>,
        val tomorrow: LocalDate,
        val tomorrowRows: List<OccurrenceRow>,
        /** 月历：本月有课的日期集合（课程密度点）。 */
        val busyDates: Set<LocalDate>,
        /** 展示月份（跟随今天，P2 不做翻月）。 */
        val month: YearMonth,
        val timeSlots: List<TimeSlot>,
        /** 详情弹层候选（当前点击行的课程；冲突翻页由弹层内完成）。 */
        val detailCourses: List<CourseOccurrence>,
        /** 当前课表全部课程；供单次调整弹窗做校验，来源与课表页一致。 */
        val courses: List<Course> = emptyList(),
        /** 近期考试摘要（默认未来 14 天内，最多 3 条；E01）。 */
        val upcomingExams: List<UpcomingExam> = emptyList(),
        val effectiveDays: List<com.gfs.iyango.core.domain.EffectiveScheduleDay> = emptyList(),
        /** 当前教务身份；与课表绑定独立，主动退出后不展示历史账号资料。 */
        val connection: SchoolConnection? = null,
        val now: java.time.LocalDateTime = today.atStartOfDay(),
        val timing: com.gfs.iyango.core.domain.TodayCourseTiming = com.gfs.iyango.core.domain.TodayCourseTiming.project(todayRows.map { it.occurrence }, now),
        val todayHoliday: HolidayDayInfo? = null,
        val isTemporaryDay: Boolean = false,
        val partnerSummary: PartnerTodaySummary? = null,
    ) : TodayUiState {

        /** 详情弹层的展示周（供单次调整默认值）。 */
        val detailWeek: Int
            get() = semester?.let { week.coerceIn(1, it.totalWeeks) } ?: week
    }

    /** 加载失败。 */
    data class Error(val message: String) : TodayUiState
}

/** 今日页考试摘要：倒计时文案已在 ViewModel 按统一时钟计算。 */
data class UpcomingExam(
    val exam: Exam,
    val countdown: String,
)
