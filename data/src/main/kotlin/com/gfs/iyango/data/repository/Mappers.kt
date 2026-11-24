package com.gfs.iyango.data.repository

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.ImportRecord
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.data.db.CourseEntity
import com.gfs.iyango.data.db.ImportRecordEntity
import com.gfs.iyango.data.db.SemesterEntity
import com.gfs.iyango.data.db.TimeSlotEntity
import com.gfs.iyango.data.db.TimetableEntity

/**
 * 实体 ↔ 模型成对映射函数。
 *
 * 约定：
 * - 往返无损：`x.toEntity().toModel() == x` 对全部持久化字段成立（MappersTest 逐模型验证）；
 * - [Course.timeUnconfirmed] 不持久化：`toModel` 提供 [timeUnconfirmed] 参数供读取结果
 *   场景显式注入，默认 false；实体上无对应列。
 */

/** 课表实体 → 模型。 */
fun TimetableEntity.toModel(): Timetable = Timetable(
    id = id,
    name = name,
    source = source,
    schoolTerm = schoolTerm,
    connectionId = connectionId,
    isCurrent = isCurrent,
    lastSyncAt = lastSyncAt,
    groupName = groupName,
    autoEnabled = autoEnabled,
    enabledDays = enabledDays,
    cycleLength = cycleLength,
    cycleWeek = cycleWeek,
)

/** 课表模型 → 实体。 */
fun Timetable.toEntity(): TimetableEntity = TimetableEntity(
    id = id,
    name = name,
    source = source,
    schoolTerm = schoolTerm,
    connectionId = connectionId,
    isCurrent = isCurrent,
    lastSyncAt = lastSyncAt,
    groupName = groupName,
    autoEnabled = autoEnabled,
    enabledDays = enabledDays,
    cycleLength = cycleLength,
    cycleWeek = cycleWeek,
)

/** 学期实体 → 模型。 */
fun SemesterEntity.toModel(): Semester = Semester(
    id = id,
    timetableId = timetableId,
    name = name,
    startDate = startDate,
    totalWeeks = totalWeeks,
    isCurrent = isCurrent,
)

/** 学期模型 → 实体。 */
fun Semester.toEntity(): SemesterEntity = SemesterEntity(
    id = id,
    timetableId = timetableId,
    name = name,
    startDate = startDate,
    totalWeeks = totalWeeks,
    isCurrent = isCurrent,
)

/** 作息实体 → 模型。 */
fun TimeSlotEntity.toModel(): TimeSlot = TimeSlot(
    id = id,
    timetableId = timetableId,
    section = section,
    startTime = startTime,
    endTime = endTime,
    name = name,
    kind = kind,
    hidden = hidden,
    defaultCourseName = defaultCourseName,
    sortOrder = sortOrder,
)

/** 作息模型 → 实体。 */
fun TimeSlot.toEntity(): TimeSlotEntity = TimeSlotEntity(
    id = id,
    timetableId = timetableId,
    section = section,
    startTime = startTime,
    endTime = endTime,
    name = name,
    kind = kind,
    hidden = hidden,
    defaultCourseName = defaultCourseName,
    sortOrder = sortOrder,
)

/**
 * 课程实体 → 模型。
 *
 * @param timeUnconfirmed 官方校区时间待确认标记；实体不持久化该字段，由读取方
 *   （同步/导入场景）显式注入，默认 false。
 *
 * 单次调整列以可空存储（NULL = 非调整记录，见 [CourseEntity] KDoc），映射回模型的
 * 非空 Long 语义（0 = 非调整记录）。
 */
fun CourseEntity.toModel(timeUnconfirmed: Boolean = false): Course = Course(
    id = id,
    timetableId = timetableId,
    name = name,
    teacher = teacher,
    classroom = classroom,
    abbreviation = abbreviation,
    remark = remark,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    sourceStartSection = sourceStartSection,
    sourceEndSection = sourceEndSection,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = weekType,
    selectedWeeks = selectedWeeks,
    color = color,
    credit = credit,
    isOutdoor = isOutdoor,
    enabled = enabled,
    timeMode = timeMode,
    startTime = startTime,
    endTime = endTime,
    date = date,
    timeUnconfirmed = timeUnconfirmed,
    cycleLength = cycleLength,
    cycleWeek = cycleWeek,
    overrideOfId = overrideOfId ?: 0,
    overrideWeek = overrideWeek ?: 0,
    groupKey = groupKey,
    sourceKey = sourceKey,
)

/**
 * 课程模型 → 实体。
 *
 * @param timetableId 所属课表 id（由调用方显式给出，实体与模型的该字段保持一致语义）。
 *   `timeUnconfirmed` 无对应列，不写入。
 *
 * 单次调整列的 0 值（非调整记录）以 **NULL** 落库：SQLite 唯一索引
 * `index_course_override` 把 NULL 视为互不相同，普通课程行因此不参与唯一约束
 * （见 [CourseEntity] KDoc）。非 0 值原样存储。
 */
fun Course.toEntity(timetableId: Long): CourseEntity = CourseEntity(
    id = id,
    timetableId = timetableId,
    name = name,
    teacher = teacher,
    classroom = classroom,
    abbreviation = abbreviation,
    remark = remark,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    sourceStartSection = sourceStartSection,
    sourceEndSection = sourceEndSection,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = weekType,
    selectedWeeks = selectedWeeks,
    color = color,
    credit = credit,
    isOutdoor = isOutdoor,
    enabled = enabled,
    timeMode = timeMode,
    startTime = startTime,
    endTime = endTime,
    date = date,
    cycleLength = cycleLength,
    cycleWeek = cycleWeek,
    overrideOfId = overrideOfId.takeIf { it > 0 },
    overrideWeek = overrideWeek.takeIf { it > 0 },
    groupKey = groupKey,
    sourceKey = sourceKey,
)

/** 导入记录实体 → 模型。 */
fun ImportRecordEntity.toModel(): ImportRecord = ImportRecord(
    id = id,
    timetableId = timetableId,
    source = source,
    importTime = importTime,
    semester = semester,
)

/** 导入记录模型 → 实体。 */
fun ImportRecord.toEntity(): ImportRecordEntity = ImportRecordEntity(
    id = id,
    timetableId = timetableId,
    source = source,
    importTime = importTime,
    semester = semester,
)
