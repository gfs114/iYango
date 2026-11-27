package com.gfs.iyango.data.backup

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.SettingsBackupPort
import com.gfs.iyango.core.domain.SettingsBackupReadResult
import com.gfs.iyango.core.domain.WebDavBackupPreview
import com.gfs.iyango.core.model.AdjustmentType
import com.gfs.iyango.core.model.ExamSource
import com.gfs.iyango.core.model.ExamStatus
import com.gfs.iyango.core.model.WeekType
import com.gfs.iyango.data.db.CourseEntity
import com.gfs.iyango.data.db.ExamEntity
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.OccurrenceAdjustmentEntity
import com.gfs.iyango.data.db.SemesterEntity
import com.gfs.iyango.data.db.TemporaryDayEntity
import com.gfs.iyango.data.db.TimeSlotEntity
import com.gfs.iyango.data.db.TimetableEntity
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 全量云备份编解码器（课表、学期、作息、课程、调课/临时整天、考试 + 设置 JSON）。
 *
 * 严格排除敏感凭据、AI 对话历史、学校连接状态、桌面组件宿主与系统日历/闹钟映射。
 */
@Singleton
class FullCloudBackupCodec @Inject constructor(
    private val db: IYangoDatabase,
    private val settingsBackup: SettingsBackupPort,
) {

    @Serializable
    data class CloudBackupDocument(
        val formatVersion: Int = 1,
        val exportedAt: Long = 0L,
        val timetables: List<CloudTimetable> = emptyList(),
        val semesters: List<CloudSemester> = emptyList(),
        val timeSlots: List<CloudTimeSlot> = emptyList(),
        val courses: List<CloudCourse> = emptyList(),
        val adjustments: List<CloudAdjustment> = emptyList(),
        val temporaryDays: List<CloudTemporaryDay> = emptyList(),
        val exams: List<CloudExam> = emptyList(),
        val settingsJson: String = "",
    )

    @Serializable
    data class CloudTimetable(
        val oldId: Long,
        val name: String,
        val source: String = "LOCAL",
        val schoolTerm: String = "",
        val isCurrent: Boolean = false,
        val groupName: String = "",
        val autoEnabled: Boolean = false,
        val enabledDays: List<Int> = emptyList(),
        val cycleLength: Int = 1,
        val cycleWeek: Int = 0,
    )

    @Serializable
    data class CloudSemester(
        val oldTimetableId: Long,
        val name: String,
        val startDate: String,
        val totalWeeks: Int,
        val isCurrent: Boolean,
    )

    @Serializable
    data class CloudTimeSlot(
        val oldTimetableId: Long,
        val section: Int,
        val startTime: String,
        val endTime: String,
        val name: String = "",
        val kind: Int = 0,
        val hidden: Boolean = false,
        val defaultCourseName: String = "",
        val sortOrder: Double = 0.0,
    )

    @Serializable
    data class CloudCourse(
        val oldId: Long,
        val oldTimetableId: Long,
        val name: String,
        val teacher: String = "",
        val classroom: String = "",
        val abbreviation: String = "",
        val remark: String = "",
        val dayOfWeek: Int,
        val startSection: Int,
        val endSection: Int,
        val sourceStartSection: Int = 0,
        val sourceEndSection: Int = 0,
        val startWeek: Int,
        val endWeek: Int,
        val weekType: String = "EVERY",
        val selectedWeeks: List<Int> = emptyList(),
        val color: String = "#0A59F7",
        val credit: Double = 0.0,
        val isOutdoor: Boolean = false,
        val enabled: Boolean = true,
        val timeMode: Int = 0,
        val startTime: String = "",
        val endTime: String = "",
        val date: String = "",
        val cycleLength: Int = 1,
        val cycleWeek: Int = 0,
        val overrideOfOldId: Long = 0L,
        val overrideWeek: Int = 0,
        val groupKey: String = "",
        val sourceKey: String = "",
    )

    @Serializable
    data class CloudAdjustment(
        val oldTimetableId: Long,
        val operationGroupId: String,
        val type: String,
        val sourceCourseOldId: Long,
        val sourceDate: String,
        val targetDate: String,
        val targetDayOfWeek: Int,
        val targetStartSection: Int,
        val targetEndSection: Int,
        val targetStartTime: String,
        val targetEndTime: String,
        val targetClassroom: String,
        val note: String,
        val createdAt: Long,
    )

    @Serializable
    data class CloudTemporaryDay(
        val oldTimetableId: Long,
        val operationGroupId: String,
        val date: String,
        val sourceOldTimetableId: Long,
        val title: String,
        val createdAt: Long,
    )

    @Serializable
    data class CloudExam(
        val source: String = "MANUAL",
        val semester: String = "",
        val subject: String,
        val type: String = "",
        val date: String,
        val endDate: String = "",
        val startTime: String = "",
        val endTime: String = "",
        val timeZone: String = "",
        val location: String = "",
        val seat: String = "",
        val note: String = "",
        val status: String = "SCHEDULED",
        val sourceExamId: String = "",
        val updatedAt: Long = 0L,
    )

    suspend fun exportBytes(): ByteArray {
        val timetables = db.timetableDao().getAll()
        val semesters = db.semesterDao().getAll()
        val timeSlots = timetables.flatMap { table ->
            db.timeSlotDao().getByTimetable(table.id)
        }
        val courses = timetables.flatMap { table ->
            db.courseDao().getAllByTimetable(table.id)
        }
        val adjustments = timetables.flatMap { table ->
            db.occurrenceAdjustmentDao().getByTimetable(table.id)
        }
        val temporaryDays = timetables.flatMap { table ->
            db.temporaryDayDao().getByTimetable(table.id)
        }
        val settingsExport = runCatching {
            settingsBackup.export(includeWallpaper = false).bytes.toString(Charsets.UTF_8)
        }.getOrDefault("")

        val doc = CloudBackupDocument(
            formatVersion = 1,
            exportedAt = System.currentTimeMillis(),
            timetables = timetables.map {
                CloudTimetable(
                    oldId = it.id,
                    name = it.name,
                    source = if (it.source == "YGU") "LOCAL" else it.source,
                    schoolTerm = it.schoolTerm,
                    isCurrent = it.isCurrent,
                    groupName = it.groupName,
                    autoEnabled = it.autoEnabled,
                    enabledDays = it.enabledDays,
                    cycleLength = it.cycleLength,
                    cycleWeek = it.cycleWeek,
                )
            },
            semesters = semesters.map {
                CloudSemester(
                    oldTimetableId = it.timetableId,
                    name = it.name,
                    startDate = it.startDate.toString(),
                    totalWeeks = it.totalWeeks,
                    isCurrent = it.isCurrent,
                )
            },
            timeSlots = timeSlots.map {
                CloudTimeSlot(
                    oldTimetableId = it.timetableId,
                    section = it.section,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    name = it.name,
                    kind = it.kind,
                    hidden = it.hidden,
                    defaultCourseName = it.defaultCourseName,
                    sortOrder = it.sortOrder,
                )
            },
            courses = courses.map {
                CloudCourse(
                    oldId = it.id,
                    oldTimetableId = it.timetableId,
                    name = it.name,
                    teacher = it.teacher,
                    classroom = it.classroom,
                    abbreviation = it.abbreviation,
                    remark = it.remark,
                    dayOfWeek = it.dayOfWeek,
                    startSection = it.startSection,
                    endSection = it.endSection,
                    sourceStartSection = it.sourceStartSection,
                    sourceEndSection = it.sourceEndSection,
                    startWeek = it.startWeek,
                    endWeek = it.endWeek,
                    weekType = it.weekType.name,
                    selectedWeeks = it.selectedWeeks,
                    color = it.color,
                    credit = it.credit,
                    isOutdoor = it.isOutdoor,
                    enabled = it.enabled,
                    timeMode = it.timeMode,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    date = it.date,
                    cycleLength = it.cycleLength,
                    cycleWeek = it.cycleWeek,
                    overrideOfOldId = it.overrideOfId ?: 0L,
                    overrideWeek = it.overrideWeek ?: 0,
                    groupKey = it.groupKey,
                    sourceKey = it.sourceKey,
                )
            },
            adjustments = adjustments.map {
                CloudAdjustment(
                    oldTimetableId = it.timetableId,
                    operationGroupId = it.operationGroupId,
                    type = it.type.name,
                    sourceCourseOldId = it.sourceCourseId,
                    sourceDate = it.sourceDate,
                    targetDate = it.targetDate,
                    targetDayOfWeek = it.targetDayOfWeek,
                    targetStartSection = it.targetStartSection,
                    targetEndSection = it.targetEndSection,
                    targetStartTime = it.targetStartTime,
                    targetEndTime = it.targetEndTime,
                    targetClassroom = it.targetClassroom,
                    note = it.note,
                    createdAt = it.createdAt,
                )
            },
            temporaryDays = temporaryDays.map {
                CloudTemporaryDay(
                    oldTimetableId = it.timetableId,
                    operationGroupId = it.operationGroupId,
                    date = it.date,
                    sourceOldTimetableId = it.sourceTimetableId,
                    title = it.title,
                    createdAt = it.createdAt,
                )
            },
            exams = collectAllExams(),
            settingsJson = settingsExport,
        )
        return jsonFormat.encodeToString(doc).toByteArray(Charsets.UTF_8)
    }

    private suspend fun collectAllExams(): List<CloudExam> {
        val list = db.examDao().observeAll().firstOrNull().orEmpty()
        return list.map {
            CloudExam(
                source = "MANUAL",
                semester = it.semester,
                subject = it.subject,
                type = it.type,
                date = it.date,
                endDate = it.endDate,
                startTime = it.startTime,
                endTime = it.endTime,
                timeZone = it.timeZone,
                location = it.location,
                seat = it.seat,
                note = it.note,
                status = it.status.name,
                sourceExamId = it.sourceExamId,
                updatedAt = it.updatedAt,
            )
        }
    }

    fun preview(fileName: String, bytes: ByteArray): WebDavBackupPreview =
        previewBytes(fileName, bytes)

    suspend fun restoreFromBytes(fileName: String, bytes: ByteArray): WebDavBackupPreview {
        val doc = decodeDocument(bytes)
        db.withTransaction {
            val existingTables = db.timetableDao().getAll()
            existingTables.forEach { db.timetableDao().deleteById(it.id) }
            val existingExams = db.examDao().observeAll().firstOrNull().orEmpty()
            existingExams.forEach { db.examDao().deleteById(it.id) }

            val tableIdMap = HashMap<Long, Long>()
            var currentAssigned = false
            doc.timetables.forEach { table ->
                val isCurrent = table.isCurrent && !currentAssigned
                if (isCurrent) currentAssigned = true
                val newId = db.timetableDao().upsert(
                    TimetableEntity(
                        id = 0L,
                        name = table.name,
                        source = "LOCAL",
                        schoolTerm = table.schoolTerm,
                        connectionId = "",
                        isCurrent = isCurrent,
                        lastSyncAt = "",
                        groupName = table.groupName,
                        autoEnabled = table.autoEnabled,
                        enabledDays = table.enabledDays,
                        cycleLength = table.cycleLength.coerceAtLeast(1),
                        cycleWeek = table.cycleWeek.coerceAtLeast(0),
                    ),
                )
                tableIdMap[table.oldId] = newId
            }
            if (!currentAssigned && doc.timetables.isNotEmpty()) {
                val firstNewId = tableIdMap[doc.timetables.first().oldId]
                if (firstNewId != null) db.timetableDao().setCurrent(firstNewId)
            }

            doc.semesters.forEach { sem ->
                val mappedTableId = tableIdMap[sem.oldTimetableId] ?: return@forEach
                val parsedDate = runCatching { LocalDate.parse(sem.startDate) }.getOrNull() ?: return@forEach
                db.semesterDao().upsert(
                    SemesterEntity(
                        id = 0L,
                        timetableId = mappedTableId,
                        name = sem.name,
                        startDate = parsedDate,
                        totalWeeks = sem.totalWeeks.coerceIn(1, 30),
                        isCurrent = sem.isCurrent,
                    ),
                )
            }

            doc.timeSlots.groupBy { it.oldTimetableId }.forEach { (oldTableId, slots) ->
                val mappedTableId = tableIdMap[oldTableId] ?: return@forEach
                db.timeSlotDao().upsertAll(
                    slots.map {
                        TimeSlotEntity(
                            id = 0L,
                            timetableId = mappedTableId,
                            section = it.section,
                            startTime = it.startTime,
                            endTime = it.endTime,
                            name = it.name,
                            kind = it.kind,
                            hidden = it.hidden,
                            defaultCourseName = it.defaultCourseName,
                            sortOrder = it.sortOrder,
                        )
                    },
                )
            }

            // 先写基础课，再写覆盖课（重映射 overrideOfId）
            val courseIdMap = HashMap<Long, Long>()
            val baseCourses = doc.courses.filter { it.overrideOfOldId <= 0L }
            val overrideCourses = doc.courses.filter { it.overrideOfOldId > 0L }

            suspend fun insertCourse(c: CloudCourse, overrideTargetId: Long?) {
                val mappedTableId = tableIdMap[c.oldTimetableId] ?: return
                val weekType = runCatching { WeekType.valueOf(c.weekType) }.getOrDefault(WeekType.EVERY)
                val newId = db.courseDao().upsert(
                    CourseEntity(
                        id = 0L,
                        timetableId = mappedTableId,
                        name = c.name,
                        teacher = c.teacher,
                        classroom = c.classroom,
                        abbreviation = c.abbreviation,
                        remark = c.remark,
                        dayOfWeek = c.dayOfWeek,
                        startSection = c.startSection,
                        endSection = c.endSection,
                        sourceStartSection = c.sourceStartSection,
                        sourceEndSection = c.sourceEndSection,
                        startWeek = c.startWeek,
                        endWeek = c.endWeek,
                        weekType = weekType,
                        selectedWeeks = c.selectedWeeks,
                        color = c.color,
                        credit = c.credit,
                        isOutdoor = c.isOutdoor,
                        enabled = c.enabled,
                        timeMode = c.timeMode,
                        startTime = c.startTime,
                        endTime = c.endTime,
                        date = c.date,
                        cycleLength = c.cycleLength.coerceAtLeast(1),
                        cycleWeek = c.cycleWeek.coerceAtLeast(0),
                        overrideOfId = overrideTargetId,
                        overrideWeek = c.overrideWeek.takeIf { overrideTargetId != null && it > 0 },
                        groupKey = c.groupKey,
                        sourceKey = c.sourceKey,
                    ),
                )
                courseIdMap[c.oldId] = newId
            }

            baseCourses.forEach { insertCourse(it, null) }
            overrideCourses.forEach { c ->
                val target = courseIdMap[c.overrideOfOldId]
                if (target != null && c.overrideWeek > 0) {
                    insertCourse(c, target)
                }
            }

            doc.adjustments.forEach { adj ->
                val mappedTableId = tableIdMap[adj.oldTimetableId] ?: return@forEach
                val mappedCourseId = courseIdMap[adj.sourceCourseOldId] ?: return@forEach
                val adjType = runCatching { AdjustmentType.valueOf(adj.type) }.getOrNull() ?: return@forEach
                db.occurrenceAdjustmentDao().insert(
                    OccurrenceAdjustmentEntity(
                        id = 0L,
                        timetableId = mappedTableId,
                        operationGroupId = adj.operationGroupId,
                        type = adjType,
                        sourceCourseId = mappedCourseId,
                        sourceDate = adj.sourceDate,
                        targetDate = adj.targetDate,
                        targetDayOfWeek = adj.targetDayOfWeek,
                        targetStartSection = adj.targetStartSection,
                        targetEndSection = adj.targetEndSection,
                        targetStartTime = adj.targetStartTime,
                        targetEndTime = adj.targetEndTime,
                        targetClassroom = adj.targetClassroom,
                        note = adj.note,
                        createdAt = adj.createdAt,
                    ),
                )
            }

            doc.temporaryDays.forEach { day ->
                val mappedTableId = tableIdMap[day.oldTimetableId] ?: return@forEach
                val mappedSourceId = tableIdMap[day.sourceOldTimetableId] ?: return@forEach
                db.temporaryDayDao().insert(
                    TemporaryDayEntity(
                        id = 0L,
                        timetableId = mappedTableId,
                        operationGroupId = day.operationGroupId,
                        date = day.date,
                        sourceTimetableId = mappedSourceId,
                        title = day.title,
                        createdAt = day.createdAt,
                    ),
                )
            }

            if (doc.exams.isNotEmpty()) {
                db.examDao().upsertAll(
                    doc.exams.map { exam ->
                        ExamEntity(
                            id = 0L,
                            source = runCatching { ExamSource.valueOf(exam.source) }.getOrDefault(ExamSource.MANUAL),
                            connectionId = "",
                            semester = exam.semester,
                            subject = exam.subject,
                            type = exam.type,
                            date = exam.date,
                            endDate = exam.endDate,
                            startTime = exam.startTime,
                            endTime = exam.endTime,
                            timeZone = exam.timeZone,
                            location = exam.location,
                            seat = exam.seat,
                            note = exam.note,
                            status = runCatching { ExamStatus.valueOf(exam.status) }.getOrDefault(ExamStatus.SCHEDULED),
                            sourceExamId = exam.sourceExamId,
                            updatedAt = exam.updatedAt,
                        )
                    },
                )
            }
        }

        if (doc.settingsJson.isNotBlank()) {
            val read = settingsBackup.read(doc.settingsJson.toByteArray(Charsets.UTF_8))
            if (read is SettingsBackupReadResult.Ready) {
                settingsBackup.restore(
                    token = read.preview.token,
                    includeWallpaper = false,
                    overwriteCreditPlans = false,
                )
            }
        }

        return WebDavBackupPreview(
            fileName = fileName,
            exportedAt = doc.exportedAt,
            timetableCount = doc.timetables.size,
            courseCount = doc.courses.size,
            examCount = doc.exams.size,
            hasSettings = doc.settingsJson.isNotBlank(),
        )
    }

    internal fun decodeDocument(bytes: ByteArray): CloudBackupDocument {
        val text = bytes.toString(Charsets.UTF_8)
        return jsonFormat.decodeFromString<CloudBackupDocument>(text)
    }

    companion object {
        private val jsonFormat = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun previewBytes(fileName: String, bytes: ByteArray): WebDavBackupPreview {
            val doc = jsonFormat.decodeFromString<CloudBackupDocument>(bytes.toString(Charsets.UTF_8))
            return WebDavBackupPreview(
                fileName = fileName,
                exportedAt = doc.exportedAt,
                timetableCount = doc.timetables.size,
                courseCount = doc.courses.size,
                examCount = doc.exams.size,
                hasSettings = doc.settingsJson.isNotBlank(),
            )
        }
    }
}
