package com.gfs.iyango.data.json

import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.repository.toModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一次导出的结果。 */
data class ExportResult(
    /** v1 交换文档 JSON 文本（pretty，2 空格缩进）。 */
    val json: String,
    /** 建议文件名，如 `iYango-我的课表-20261024.json`。 */
    val fileName: String,
    val courseCount: Int,
    val timeSlotCount: Int,
)

/** 紧凑分享导出结果（单行 JSON，不携带显示配置）。 */
data class CompactExportResult(
    val json: String,
    val timetableName: String,
    val courseCount: Int,
    val timeSlotCount: Int,
)

/**
 * 课表 JSON 导出服务（P2-10）。
 *
 * 输入课表 id → 组装 v1 交换文档并序列化：
 * - `meta.app` 固定 "ClassSchedule"（与参考实现互通；即使应用名是 iYango 也保持不变），
 *   `version = 1`，`exportedAt` 为 `yyyy-MM-dd HH:mm:ss`；
 * - 课程字段集合见 [ScheduleJsonCourse]：主动**不导出** `sourceStartSection`/
 *   `sourceEndSection`（展示投影的视图状态）、`timetableId`（接收方重新分配）；
 *   **壁纸路径类字段当前模型不存在**——若未来引入课表背景图等本机资源路径，
 *   导出前必须在此处剔除（壁纸属于设备本地资源，不应进入交换文件）；
 * - `displayConfig` 输出为字符串：调用方传入该课表生效中的显示配置（F09）；
 *   未传时输出空串；
 * - 建议文件名做文件名安全字符替换（`\ / : * ? " < > |` → `-`）。
 *
 * @throws IllegalArgumentException 课表不存在。
 * @throws IllegalStateException 课表没有课程（空课表无法导出）。
 */
@Singleton
class ExportScheduleService @Inject constructor(
    private val db: IYangoDatabase,
) {

    suspend fun export(
        timetableId: Long,
        displayConfig: String = "",
    ): ExportResult {
        val timetable = db.timetableDao().getById(timetableId)
            ?: throw IllegalArgumentException("课表不存在：$timetableId")
        val courses = db.courseDao().getAllByTimetable(timetableId)
        if (courses.isEmpty()) {
            throw IllegalStateException("本机还没有课程，无法导出")
        }
        val semester = db.semesterDao().getCurrent(timetableId)
        val slots = db.timeSlotDao().getByTimetable(timetableId)
        val document = ScheduleJsonDocument(
            meta = ScheduleJsonMeta(exportedAt = ScheduleJsonParser.now()),
            courses = courses.map { it.toModel().toScheduleJsonCourse() },
            semester = semester?.toModel()?.toScheduleJsonSemester(),
            timeSlots = slots.map { it.toModel().toScheduleJsonTimeSlot() },
            displayConfig = displayConfig,
        )
        val json = SCHEDULE_JSON_FORMAT.encodeToString(document)
        return ExportResult(
            json = json,
            fileName = buildFileName(timetable.name),
            courseCount = courses.size,
            timeSlotCount = slots.size,
        )
    }

    /**
     * 导出用于口令/二维码分享的紧凑单行 v1 JSON。
     *
     * 显式保留 `ScheduleJsonParser` 要求的必填调度键（如 `dayOfWeek`、`startSection`、
     * `endSection`、`startWeek`、`endWeek`、`weekType` 及作息 `section`），仅省略可安全
     * 回退的空/零扩展字段与 `displayConfig`。
     */
    suspend fun exportCompactScheduleJson(timetableId: Long): CompactExportResult {
        val timetable = db.timetableDao().getById(timetableId)
            ?: throw IllegalArgumentException("课表不存在：$timetableId")
        val rawCourses = db.courseDao().getAllByTimetable(timetableId)
        if (rawCourses.isEmpty()) {
            throw IllegalStateException("本机还没有课程，无法导出")
        }
        val semester = db.semesterDao().getCurrent(timetableId)?.toModel()?.toScheduleJsonSemester()
        val slots = db.timeSlotDao().getByTimetable(timetableId).map { it.toModel().toScheduleJsonTimeSlot() }
        val jsonCourses = rawCourses.map { it.toModel().toScheduleJsonCourse() }
        val referencedParentIds = jsonCourses.mapNotNull { c -> c.overrideOfId.takeIf { it > 0 } }.toSet()

        val root = buildJsonObject {
            put(
                "meta",
                buildJsonObject {
                    put("app", SCHEDULE_JSON_APP)
                    put("version", SCHEDULE_JSON_VERSION)
                },
            )
            put(
                "courses",
                buildJsonArray {
                    for (course in jsonCourses) {
                        add(compactCourseJson(course, keepId = course.id in referencedParentIds || course.overrideOfId > 0))
                    }
                },
            )
            if (semester != null) {
                put(
                    "semester",
                    buildJsonObject {
                        put("name", semester.name)
                        put("startDate", semester.startDate)
                        if (semester.totalWeeks != 20) put("totalWeeks", semester.totalWeeks)
                        if (semester.isCurrent) put("isCurrent", true)
                    },
                )
            }
            if (slots.isNotEmpty()) {
                put(
                    "timeSlots",
                    buildJsonArray {
                        for (slot in slots) {
                            add(compactSlotJson(slot))
                        }
                    },
                )
            }
        }
        return CompactExportResult(
            json = COMPACT_JSON_FORMAT.encodeToString(JsonObject.serializer(), root),
            timetableName = timetable.name,
            courseCount = rawCourses.size,
            timeSlotCount = slots.size,
        )
    }

    private fun compactCourseJson(course: ScheduleJsonCourse, keepId: Boolean): JsonObject =
        buildJsonObject {
            if (keepId && course.id > 0) put("id", course.id)
            put("name", course.name)
            if (course.teacher.isNotEmpty()) put("teacher", course.teacher)
            if (course.classroom.isNotEmpty()) put("classroom", course.classroom)
            put("dayOfWeek", course.dayOfWeek)
            put("startSection", course.startSection)
            put("endSection", course.endSection)
            put("startWeek", course.startWeek)
            put("endWeek", course.endWeek)
            put("weekType", course.weekType)
            if (course.color != "#0A59F7") put("color", course.color)
            if (course.remark.isNotEmpty()) put("remark", course.remark)
            if (course.groupKey.isNotEmpty()) put("groupKey", course.groupKey)
            if (course.abbreviation.isNotEmpty()) put("abbreviation", course.abbreviation)
            if (course.credit != 0.0) put("credit", course.credit)
            if (course.isOutdoor) put("isOutdoor", true)
            if (!course.enabled) put("enabled", false)
            if (course.selectedWeeks.isNotEmpty()) {
                put("selectedWeeks", JsonArray(course.selectedWeeks.map { JsonPrimitive(it) }))
            }
            if (course.timeMode != 0) put("timeMode", course.timeMode)
            if (course.startTime.isNotEmpty()) put("startTime", course.startTime)
            if (course.endTime.isNotEmpty()) put("endTime", course.endTime)
            if (course.date.isNotEmpty()) put("date", course.date)
            if (course.cycleLength != 1) put("cycleLength", course.cycleLength)
            if (course.cycleWeek != 0) put("cycleWeek", course.cycleWeek)
            if (course.overrideOfId > 0) put("overrideOfId", course.overrideOfId)
            if (course.overrideWeek > 0) put("overrideWeek", course.overrideWeek)
        }

    private fun compactSlotJson(slot: ScheduleJsonTimeSlot): JsonObject =
        buildJsonObject {
            put("section", slot.section)
            put("startTime", slot.startTime)
            put("endTime", slot.endTime)
            if (slot.name.isNotEmpty()) put("name", slot.name)
            if (slot.kind != 0) put("kind", slot.kind)
            if (slot.hidden) put("hidden", true)
            if (slot.defaultCourseName.isNotEmpty()) put("defaultCourseName", slot.defaultCourseName)
            if (slot.sortOrder != 0.0) put("sortOrder", slot.sortOrder)
        }

    /** `iYango-<课表名>-<yyyyMMdd>.json`；课表名中的文件名非法字符替换为 `-`。 */
    private fun buildFileName(timetableName: String): String {
        val safeName = timetableName.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        return "iYango-$safeName-$today.json"
    }

    private companion object {
        val COMPACT_JSON_FORMAT = Json { prettyPrint = false }
    }
}
