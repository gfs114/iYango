package com.gfs.iyango.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.mapLatest
import com.gfs.iyango.core.domain.ApplyOccurrenceAdjustments
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.EffectiveScheduleDay
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.EffectiveTimetableSelector
import com.gfs.iyango.core.domain.ResolveScheduleOccurrences
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TemporaryDay
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.data.db.IYangoDatabase
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [EffectiveSchedulePort] 的 Room 实现。
 *
 * 所有展示面（今日、课表、提醒、日历、组件）都应经过本实现，统一叠加：
 * 1. F07 自动选表（按日期选出生效课表）；
 * 2. 基础实例解析（[ResolveScheduleOccurrences]）；
 * 3. F08 临时调整（取消/跨周移动/临时整天）。
 *
 * 临时整天来源使用**来源课表当日基础实例**，不再递归应用来源课表的临时整天，
 * 避免两张课表互相引用时无限递归。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RoomEffectiveSchedule @Inject constructor(
    private val db: IYangoDatabase,
) : EffectiveSchedulePort {

    private fun applies(table: Timetable, semester: Semester?, date: LocalDate): Boolean =
        !table.autoEnabled || (com.gfs.iyango.core.domain.CourseRules.weekOfDate(semester, date).let { week ->
            week > 0 && com.gfs.iyango.core.domain.CourseRules.tableApplies(table, date.dayOfWeek.value, week)
        })

    override fun observeSelectedRange(range: ClosedRange<LocalDate>): Flow<List<EffectiveScheduleDay>> =
        db.invalidationTracker.createFlow(
            "timetable", "semester", "course", "time_slot", "occurrence_adjustment", "temporary_day",
        ).mapLatest { resolveSelectedRange(range) }

    override suspend fun resolveSelectedRange(range: ClosedRange<LocalDate>): List<EffectiveScheduleDay> = db.withTransaction {
        val all = db.timetableDao().getAll().map { it.toModel() }
        val current = all.firstOrNull { it.isCurrent }
        val semesters = db.semesterDao().getAll().map { it.toModel() }
        val dates = generateSequence(range.start) { it.plusDays(1) }
            .takeWhile { it <= range.endInclusive }.toList()
        val selected = dates.associateWith { date ->
            EffectiveTimetableSelector.selectForDate(date, current, all) { currentSemester(semesters, it) }
        }
        val daysByTable = selected.values.filterNotNull().distinctBy { it.id }.associate { table ->
            table.id to readTableDays(table,currentSemester(semesters,table.id),range,true).associateBy { it.date }

        }
        dates.map { date -> selected[date]?.let { daysByTable[it.id]?.get(date) } ?: EffectiveScheduleDay(date) }
    }

    override fun observeDay(date: LocalDate): Flow<EffectiveScheduleDay> =
        observeSelectedRange(date..date).map { it.first() }

    override fun observeRange(
        timetableId: Long,
        range: ClosedRange<LocalDate>,
    ): Flow<List<CourseOccurrence>> =
        db.timetableDao().observeById(timetableId).flatMapLatest { tableEntity ->
            if (tableEntity == null) {
                return@flatMapLatest flowOf(emptyList())
            }
            val timetable = tableEntity.toModel()
            combine(
                db.semesterDao().observeCurrent(timetableId),
                db.timeSlotDao().observeByTimetable(timetableId),
                db.courseDao().observeByTimetable(timetableId),
                db.occurrenceAdjustmentDao().observeByTimetable(timetableId),
                db.temporaryDayDao().observeByTimetable(timetableId),
            ) { semester, slots, courses, adjustments, temporaryDays ->
                resolveTable(
                    timetable = timetable,
                    semester = semester?.toModel(),
                    timeSlots = slots.map { it.toModel() },
                    courses = courses.map { it.toModel() },
                    adjustments = adjustments.map { it.toModel() },
                    temporaryDays = temporaryDays.map { it.toModel() },
                    range = range,
                )
            }
        }

    override suspend fun resolveRange(
        timetableId: Long,
        range: ClosedRange<LocalDate>,
    ): List<CourseOccurrence> {
        val timetable = db.timetableDao().getById(timetableId)?.toModel() ?: return emptyList()
        return resolveTable(
            timetable = timetable,
            semester = db.semesterDao().getCurrent(timetableId)?.toModel(),
            timeSlots = db.timeSlotDao().getByTimetable(timetableId).map { it.toModel() },
            courses = db.courseDao().getAllByTimetable(timetableId).map { it.toModel() },
            adjustments = db.occurrenceAdjustmentDao().getByTimetable(timetableId).map { it.toModel() },
            temporaryDays = db.temporaryDayDao().getByTimetable(timetableId).map { it.toModel() },
            range = range,
        )
    }

    override suspend fun resolveDay(date: LocalDate): EffectiveScheduleDay =
        resolveSelectedRange(date..date).first()

    override suspend fun timetableForDate(date: LocalDate): Timetable? {
        val current = db.timetableDao().observeCurrent().first()?.toModel()
        val all = db.timetableDao().observeAll().first().map { it.toModel() }
        val semesters = db.semesterDao().getAll().map { it.toModel() }
        return EffectiveTimetableSelector.selectForDate(date, current, all) { id ->
            currentSemester(semesters, id)
        }
    }

    override suspend fun resolveTimetableRange(
        timetableId: Long,
        range: ClosedRange<LocalDate>,
    ): List<CourseOccurrence> {
        val timetable = db.timetableDao().getById(timetableId)?.toModel() ?: return emptyList()
        val semester = db.semesterDao().getCurrent(timetableId)?.toModel()
        val timeSlots = db.timeSlotDao().getByTimetable(timetableId).map { it.toModel() }
        val courses = db.courseDao().getAllByTimetable(timetableId).map { it.toModel() }
        return resolveTable(
            timetable = timetable,
            semester = semester,
            timeSlots = timeSlots,
            courses = courses,
            adjustments = db.occurrenceAdjustmentDao().getByTimetable(timetableId).map { it.toModel() },
            temporaryDays = db.temporaryDayDao().getByTimetable(timetableId).map { it.toModel() },
            range = range,
        )
    }

    override suspend fun resolveTimetableDays(timetableId: Long, range: ClosedRange<LocalDate>): List<EffectiveScheduleDay> = db.withTransaction {
        val table = db.timetableDao().getById(timetableId)?.toModel() ?: return@withTransaction emptyList()
        readTableDays(table,db.semesterDao().getCurrent(timetableId)?.toModel(),range,false)
    }

    /** Shared metadata assembly; the caller alone decides whether automatic applicability is enforced. */
    private suspend fun readTableDays(table: Timetable, semester: Semester?, range: ClosedRange<LocalDate>,
        enforceApplicability: Boolean): List<EffectiveScheduleDay> {
        val slots = db.timeSlotDao().getByTimetable(table.id).map { it.toModel() }
        val temporary = db.temporaryDayDao().getByTimetable(table.id).map { it.toModel() }
        val courses = db.courseDao().getAllByTimetable(table.id).map { it.toModel() }
        val adjustments = db.occurrenceAdjustmentDao().getByTimetable(table.id).map { it.toModel() }
        val occurrences = resolveTable(table,semester,slots,courses,adjustments,temporary,range)
        val displaySources = temporary.associate { day ->
            day.date to if (day.sourceTimetableId == table.id) {
                table.source
            } else {
                db.timetableDao().getById(day.sourceTimetableId)?.source.orEmpty()
            }
        }
        val displaySlots = temporary.associate { day ->
            day.date to if (day.sourceTimetableId == table.id) {
                slots
            } else {
                db.timeSlotDao().getByTimetable(day.sourceTimetableId).map { it.toModel() }
            }
        }
        return generateSequence(range.start) { it.plusDays(1) }.takeWhile { it <= range.endInclusive }.map { date ->
            val isTemporary = temporary.any { it.date == date.toString() }
            EffectiveScheduleDay(date,table,semester,
                occurrences.filter { it.date == date && (!enforceApplicability || applies(table,semester,date) || isTemporary) },
                isTemporary,slots,courses,adjustments,displaySlots[date.toString()] ?: slots,displaySources[date.toString()] ?: table.source)
        }.toList()
    }

    private suspend fun resolveTable(
        timetable: Timetable,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        courses: List<Course>,
        adjustments: List<OccurrenceAdjustment>,
        temporaryDays: List<TemporaryDay>,
        range: ClosedRange<LocalDate>,
    ): List<CourseOccurrence> {
        val base = ResolveScheduleOccurrences.invoke(timetable, semester, timeSlots, courses, range)
        if (adjustments.isEmpty() && temporaryDays.isEmpty()) {
            return base
        }
        val sources = temporaryDaySources(timetable, temporaryDays, range)
        return ApplyOccurrenceAdjustments.apply(
            base = base,
            courses = courses,
            timeSlots = timeSlots,
            adjustments = adjustments,
            temporaryDays = temporaryDays,
            temporaryDaySources = sources,
            range = range,
        )
    }

    /** 临时整天来源课表在对应日期的基础实例（来源时刻固定，不递归临时整天；同表支持按星期调休补课）。 */
    private suspend fun temporaryDaySources(
        target: Timetable,
        temporaryDays: List<TemporaryDay>,
        range: ClosedRange<LocalDate>,
    ): Map<String, List<CourseOccurrence>> {
        val result = mutableMapOf<String, List<CourseOccurrence>>()
        temporaryDays.forEach { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@forEach
            if (date !in range) return@forEach
            val makeupWeekday = com.gfs.iyango.core.domain.HolidayMakeupCodec.parseWeekday(day.title)
            if (makeupWeekday != null && day.sourceTimetableId == target.id) {
                val targetMonday = date.minusDays((date.dayOfWeek.value - 1).toLong())
                val sourceDate = targetMonday.plusDays((makeupWeekday - 1).toLong())
                val sourceOccurrences = ResolveScheduleOccurrences.invoke(
                    timetable = target,
                    semester = db.semesterDao().getCurrent(target.id)?.toModel(),
                    timeSlots = db.timeSlotDao().getByTimetable(target.id).map { it.toModel() },
                    courses = db.courseDao().getAllByTimetable(target.id).map { it.toModel() },
                    range = sourceDate..sourceDate,
                )
                result[day.date] = sourceOccurrences.map { occ ->
                    occ.copy(
                        occurrenceId = "temp-makeup-${target.id}-${day.date}-${occ.course.id}-${occ.course.startSection}",
                        date = date,
                        anchorDate = date,
                        isTemporary = true,
                        course = occ.course.copy(dayOfWeek = date.dayOfWeek.value),
                    )
                }
                return@forEach
            }
            if (day.sourceTimetableId <= 0 || day.sourceTimetableId == target.id) {
                return@forEach
            }
            val source = db.timetableDao().getById(day.sourceTimetableId)?.toModel()
                ?: return@forEach
            result[day.date] = ResolveScheduleOccurrences.invoke(
                timetable = source,
                semester = db.semesterDao().getCurrent(source.id)?.toModel(),
                timeSlots = db.timeSlotDao().getByTimetable(source.id).map { it.toModel() },
                courses = db.courseDao().getAllByTimetable(source.id).map { it.toModel() },
                range = date..date,
            )
        }
        return result
    }

    private fun currentSemester(semesters: List<Semester>, timetableId: Long): Semester? =
        semesters.firstOrNull { it.timetableId == timetableId && it.isCurrent }
            ?: semesters.firstOrNull { it.timetableId == timetableId }
}
