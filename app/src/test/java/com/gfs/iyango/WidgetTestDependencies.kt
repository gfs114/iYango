package com.gfs.iyango

import android.widget.RemoteViews
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.EffectiveScheduleDay
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.ExamRepositoryPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.domain.WidgetConfigPort
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WidgetConfig
import com.gfs.iyango.platform.android.WidgetEntryPoint
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 桌面组件测试共用的假端口与 RemoteViews 动作反射工具。 */

class FakeWidgetConfigs(private val configs: List<WidgetConfig> = emptyList()) : WidgetConfigPort {
    override suspend fun all() = configs
    override suspend fun get(widgetId: Int) =
        configs.firstOrNull { it.widgetId == widgetId } ?: configs.firstOrNull()
    override suspend fun save(config: WidgetConfig) = Unit
    override suspend fun delete(widgetId: Int) = Unit
}

class FakeTimetables(
    private val tables: List<Timetable> = emptyList(),
    private val current: Timetable? = null,
) : TimetableRepositoryPort {
    override fun observeAll(): Flow<List<Timetable>> = flowOf(tables)
    override fun observeCurrent(): Flow<Timetable?> = flowOf(current)
    override suspend fun setCurrent(id: Long) = Unit
    override suspend fun upsert(timetable: Timetable) = timetable.id
    override suspend fun delete(id: Long) = Unit
    override suspend fun duplicate(id: Long) = id
}

class FakeSchedule(
    private val dayOccurrences: (LocalDate) -> List<CourseOccurrence> = { emptyList() },
    private val dayTable: (LocalDate) -> Timetable? = { null },
    private val rangeOccurrences: (Long, ClosedRange<LocalDate>) -> List<CourseOccurrence> = { _, _ -> emptyList() },
    private val beforeQuery: suspend (LocalDate) -> Unit = {},
    val semester: Semester? = null,
) : EffectiveSchedulePort {
    val selectedDates = mutableListOf<LocalDate>()
    val fixedIds = mutableListOf<Long>()
    override suspend fun resolveDay(date: LocalDate): EffectiveScheduleDay {
        beforeQuery(date)
        selectedDates += date
        return EffectiveScheduleDay(date, dayTable(date), semester, dayOccurrences(date))
    }
    override fun observeSelectedRange(range: ClosedRange<LocalDate>): Flow<List<EffectiveScheduleDay>> = flowOf(emptyList())
    override fun observeDay(date: LocalDate): Flow<EffectiveScheduleDay> = flowOf(EffectiveScheduleDay(date))
    override fun observeRange(timetableId: Long, range: ClosedRange<LocalDate>): Flow<List<CourseOccurrence>> = flowOf(emptyList())
    override suspend fun resolveRange(timetableId: Long, range: ClosedRange<LocalDate>): List<CourseOccurrence> {
        fixedIds += timetableId
        return rangeOccurrences(timetableId, range)
    }
    override suspend fun timetableForDate(date: LocalDate) = dayTable(date)
    override suspend fun resolveTimetableDays(timetableId: Long, range: ClosedRange<LocalDate>): List<EffectiveScheduleDay> =
        if (range.isEmpty()) emptyList() else listOf(EffectiveScheduleDay(range.start, semester = semester))
    override suspend fun resolveTimetableRange(timetableId: Long, range: ClosedRange<LocalDate>) = emptyList<CourseOccurrence>()
}

class FakeExams(private val exams: List<Exam> = emptyList()) : ExamRepositoryPort {
    override fun observeAll(): Flow<List<Exam>> = flowOf(exams)
    override fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<Exam>> = flowOf(exams)
    override suspend fun get(id: Long): Exam? = exams.firstOrNull { it.id == id }
    override suspend fun save(exam: Exam): Long = exam.id
    override suspend fun delete(id: Long) = Unit
    override suspend fun replaceSchoolExams(connectionId: String, semester: String, exams: List<Exam>) = Unit
}

class FakeEntry(
    private val configs: FakeWidgetConfigs,
    private val timetables: FakeTimetables,
    private val schedule: FakeSchedule,
    private val exams: FakeExams = FakeExams(),
) : WidgetEntryPoint {
    override fun timetableRepository() = timetables
    override fun effectiveSchedule() = schedule
    override fun widgetConfig() = configs
    override fun examRepository() = exams
}

/** 反射读取 RemoteViews 内部动作列表（Robolectric 加载的是真实 framework 类）。 */
internal fun RemoteViews.actionClassNames(): List<String> = runCatching {
    val field = RemoteViews::class.java.getDeclaredField("mActions")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val actions = field.get(this) as? List<Any> ?: emptyList()
    actions.map { it.javaClass.simpleName }
}.getOrDefault(emptyList())
