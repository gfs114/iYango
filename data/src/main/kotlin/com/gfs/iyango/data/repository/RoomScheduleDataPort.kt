package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.TimeRailRow
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.school.ygu.YguClassTimes
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ScheduleDataPort] 的 Room 实现：单课表的学期/作息/课程读写。
 *
 * 全部 observe 直接返回对应 DAO Flow 的 `map(模型)` 投影，Room 的 InvalidationTracker
 * 保证数据变化时持续发射；写路径经由 DAO 的 upsert/delete。
 *
 * DAO 层的 Room 行为验证（CRUD/级联/事务）留给 P2 的 Robolectric/仪器测试。
 */
@Singleton
class RoomScheduleDataPort @Inject constructor(
    private val db: IYangoDatabase,
) : ScheduleDataPort {

    /** 观察指定课表本身；不存在时发射 null。 */
    override fun observeTimetable(id: Long): Flow<Timetable?> =
        db.timetableDao().observeById(id).map { it?.toModel() }

    /** 观察指定课表的当前学期；未设置时发射 null。 */
    override fun observeSemester(timetableId: Long): Flow<Semester?> =
        db.semesterDao().observeCurrent(timetableId).map { it?.toModel() }

    /** 观察指定课表的全部学期（锚点日期新的在前）。 */
    override fun observeAllSemesters(timetableId: Long): Flow<List<Semester>> =
        db.semesterDao().observeByTimetable(timetableId).map { rows -> rows.map { it.toModel() } }

    /** 观察指定课表的作息时间表（按节次号升序）。 */
    override fun observeTimeSlots(timetableId: Long): Flow<List<TimeSlot>> =
        db.timeSlotDao().observeByTimetable(timetableId).map { rows -> rows.map { it.toModel() } }

    /** 观察指定课表的全部课程（含停用与单次调整记录），按星期、起始节次升序。 */
    override fun observeCourses(timetableId: Long): Flow<List<Course>> =
        db.courseDao().observeByTimetable(timetableId).map { rows -> rows.map { it.toModel() } }

    /**
     * 新增或更新课程，返回持久化后的 id。
     *
     * 单条保存即 [saveCourses] 的单元素特例；校验规则与事务语义见该方法。
     *
     * @throws IllegalArgumentException 课程数据非法或与既有单次调整记录冲突。
     */
    @Throws(IllegalArgumentException::class)
    override suspend fun saveCourse(course: Course): Long = saveCourses(listOf(course)).single()

    /**
     * 单事务保存同一课表的多条课程。
     *
     * 写库前先完成全部校验，避免部分行落库：
     * 1. 共享校验器 [CourseValidator.validate]（单条数据合法性）；
     * 2. [CourseValidator.validateOverrideUniqueness] 与库内既有记录的冲突；
     * 3. 同一批新增行之间的 `(overrideOfId, overrideWeek)` 重复（id 尚未生成，
     *    由本方法按键去重拦截）。
     *
     * 数据库侧唯一索引 `index_course_override` 仍是最后兜底（见 [CourseEntity] KDoc）。
     *
     * @throws IllegalArgumentException 任一课程数据非法或单次调整记录冲突；此时不落任何改动。
     */
    @Throws(IllegalArgumentException::class)
    override suspend fun saveCourses(courses: List<Course>): List<Long> {
        if (courses.isEmpty()) return emptyList()
        val timetableId = courses.first().timetableId
        require(courses.all { it.timetableId == timetableId }) { "一次只能保存同一课表的课程" }
        return db.withTransaction {
            val existing = db.courseDao().getAllByTimetable(timetableId).map { it.toModel() }
            val batchOverrideKeys = mutableSetOf<Pair<Long, Int>>()
            for (course in courses) {
                val problem = CourseValidator.validate(course)
                require(problem == null) { "课程校验失败：$problem" }
                val duplicate = CourseValidator.validateOverrideUniqueness(course, existing)
                if (duplicate != null) {
                    throw IllegalArgumentException(duplicate)
                }
                // 新增行的 id 尚未生成，同批重复需单独拦截；更新行由库内唯一索引兜底。
                if (course.id == 0L && course.overrideOfId > 0 &&
                    !batchOverrideKeys.add(course.overrideOfId to course.overrideWeek)
                ) {
                    throw IllegalArgumentException("该周已有针对此课程的调整")
                }
            }
            courses.map { course ->
                val entity = course.toEntity(course.timetableId)
                // @Upsert 走更新分支时返回 -1，此时持久化 id 即实体自身主键。
                val rowId = db.courseDao().upsert(entity)
                if (rowId > 0) rowId else entity.id
            }
        }
    }

    /** 删除课程（单次调整记录与基础课互不影响）。 */
    override suspend fun deleteCourse(id: Long) {
        db.courseDao().deleteById(id)
    }

    override suspend fun deleteCourses(timetableId: Long, ids: Set<Long>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            val rows = db.courseDao().getAllByTimetable(timetableId)
            val baseIds = rows.filter { it.id in ids && (it.overrideOfId ?: 0L) == 0L }.map { it.id }.toSet()
            db.occurrenceAdjustmentDao().getByTimetable(timetableId)
                .filter { it.sourceCourseId in baseIds }
                .forEach { db.occurrenceAdjustmentDao().deleteAnchor(timetableId, it.sourceCourseId, it.sourceDate) }
            rows.filter { it.overrideOfId in baseIds }.forEach { db.courseDao().deleteById(it.id) }
            baseIds.forEach { db.courseDao().deleteById(it) }
        }
    }

    /**
     * 新增或更新学期；`isCurrent = true` 时同事务内先清同课表其余当前标记，
     * 保证"当前学期至多一个"不变式。
     */
    override suspend fun saveSemester(semester: Semester): Long = db.withTransaction {
        if (semester.isCurrent) {
            db.semesterDao().clearCurrent(semester.timetableId)
        }
        val entity = semester.toEntity()
        val rowId = db.semesterDao().upsert(entity)
        if (rowId > 0) rowId else entity.id
    }

    /** 删除学期。 */
    override suspend fun deleteSemester(id: Long) {
        db.semesterDao().deleteById(id)
    }

    /**
     * 整体替换作息时间表：先逐行校验（非法即抛，未落任何改动），
     * 再单事务清旧写新。写入行的 id 归零，防止复用旧行主键造成错位更新。
     */
    @Throws(IllegalArgumentException::class)
    override suspend fun saveTimeSlots(timetableId: Long, slots: List<TimeSlot>) {
        slots.forEach { slot ->
            val problem = CourseValidator.validateSlot(slot)
            require(problem == null) { "作息校验失败：$problem" }
        }
        db.withTransaction {
            db.timeSlotDao().deleteByTimetable(timetableId)
            db.timeSlotDao().upsertAll(slots.map { it.copy(id = 0, timetableId = timetableId).toEntity() })
        }
    }

    /**
     * 时间轴行：节名（"第N节"归一为 N）+ 该节起止。
     *
     * 只有阳光学院南北作息并存的课表，第 3/4 节才并列两套开始时间；
     * 单校区或非教务来源直接沿用持久化的 [slots]。
     */
    override fun timeRailRows(
        timetable: Timetable,
        courses: List<Course>,
        slots: List<TimeSlot>,
        sectionCount: Int,
    ): List<TimeRailRow> {
        val version = YguClassTimes.versionOf(timetable.schoolTerm, timetable.source)
        val mixed = version.isNotEmpty() &&
            YguClassTimes.campusModeOfCourses(version, courses) == YguClassTimes.CAMPUS_MIXED
        return YguClassTimes.railRows(version, mixed, slots, sectionCount)
    }
}
