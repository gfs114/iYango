package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import kotlinx.coroutines.flow.Flow

/**
 * 课表聚合的仓储端口（实现在 data 层）。
 *
 * 约束：core:domain 只依赖本接口，不依赖任何 Android/Room 类型；实现方负责把
 * Room/Rocket 等具体存储映射到 core:model 的不可变模型。
 */
interface TimetableRepositoryPort {

    /** 观察全部课表（含非当前），数据变化时持续发射。 */
    fun observeAll(): Flow<List<Timetable>>

    /** 观察当前课表；尚无当前课表时发射 null。 */
    fun observeCurrent(): Flow<Timetable?>

    /** 把 [id] 设为当前课表。 */
    suspend fun setCurrent(id: Long)

    /** 新增或更新课表，返回持久化后的 id。 */
    suspend fun upsert(timetable: Timetable): Long

    /** 删除课表（连带其学期/节次/课程数据由实现方约定）。 */
    suspend fun delete(id: Long)

    /**
     * 整包复制课表（学期/作息/课程，单事务），返回新课表 id。
     *
     * 新课表名 = 原名 + "副本"，不继承当前标记；课程 id 归零为新行，
     * 单次调整记录展平为普通课程（不跨表保留 override 引用）。
     */
    suspend fun duplicate(id: Long): Long
}

/**
 * 单个课表的完整数据读取端口（实现在 data 层）。
 *
 * 一个课表的学期、作息与课程都通过本端口按 `timetableId` 读取，
 * 保证"当前课表"与展示数据的一致性由实现方维护。
 */
interface ScheduleDataPort {

    /** 观察指定课表本身；不存在时发射 null。 */
    fun observeTimetable(id: Long): Flow<Timetable?>

    /** 观察指定课表的当前学期；未设置时发射 null。 */
    fun observeSemester(timetableId: Long): Flow<Semester?>

    /** 观察指定课表的全部学期（锚点日期新的在前），供学期管理页列表。 */
    fun observeAllSemesters(timetableId: Long): Flow<List<Semester>>

    /** 观察指定课表的作息时间表。 */
    fun observeTimeSlots(timetableId: Long): Flow<List<TimeSlot>>

    /** 观察指定课表的全部课程（含停用与单次调整记录）。 */
    fun observeCourses(timetableId: Long): Flow<List<Course>>

    /** 新增或更新课程，返回持久化后的 id。 */
    suspend fun saveCourse(course: Course): Long

    /**
     * 一次保存同一课表的多条课程（单事务）。
     *
     * 实现方先校验全部课程（单条合法性 + 单次调整唯一性），任一不合法即抛
     * [IllegalArgumentException] 且不写入任何行；全部合法时在同一事务内写入，
     * 返回与入参顺序一致的持久化 id。
     */
    suspend fun saveCourses(courses: List<Course>): List<Long>

    /** 删除课程。 */
    suspend fun deleteCourse(id: Long)

    /** 在指定课表内，单事务删除整批基础安排及其单次调整；不得影响其他课表。 */
    suspend fun deleteCourses(timetableId: Long, ids: Set<Long>)

    /**
     * 新增或更新学期，返回持久化后的 id。
     *
     * `isCurrent = true` 时实现方须保证同课表内其余学期被取消当前标记
     * （单事务内完成）；`isCurrent = false` 直接落库。
     */
    suspend fun saveSemester(semester: Semester): Long

    /** 删除学期。 */
    suspend fun deleteSemester(id: Long)

    /**
     * 整体替换指定课表的作息时间表（单事务：清旧行后写入 [slots]）。
     *
     * 每行先过 [com.gfs.iyango.core.model.CourseValidator.validateSlot]，
     * 非法时抛 [IllegalArgumentException] 且不落任何改动。
     */
    suspend fun saveTimeSlots(timetableId: Long, slots: List<TimeSlot>)

    /**
     * 生成时间轴行（节号 + 钟点，共 [sectionCount] 行）。
     *
     * 默认取 [slots] 的节名与起止时间；阳光学院南北作息不一致的课表在差异节
     * 并列两套开始时间（[TimeRailRow.southStart]/[TimeRailRow.northStart]）。
     * 纯函数，不读写存储。
     */
    fun timeRailRows(
        timetable: Timetable,
        courses: List<Course>,
        slots: List<TimeSlot>,
        sectionCount: Int,
    ): List<TimeRailRow>
}
