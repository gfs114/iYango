package com.gfs.iyango.data.json

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.WeekType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 导出 JSON 文档的版本号；仅在出现不兼容变更时提升（对齐参考实现 ScheduleJson.ets）。 */
const val SCHEDULE_JSON_VERSION = 1

/** 交换文档的 app 标识：保持 "ClassSchedule" 以与参考实现（鸿蒙端）导出的文件互通。 */
const val SCHEDULE_JSON_APP = "ClassSchedule"

/** 导出/读取共用配置。 */
val SCHEDULE_JSON_FORMAT: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** 文档头。 */
@Serializable
data class ScheduleJsonMeta(
    val app: String = SCHEDULE_JSON_APP,
    val version: Int = SCHEDULE_JSON_VERSION,
    /** 导出时间，格式 `yyyy-MM-dd HH:mm:ss`。 */
    val exportedAt: String = "",
)

/**
 * 交换文档中的单条课程记录。
 *
 * 字段与参考实现 `ScheduleJsonCourse`（继承 Course 后再由 CourseExtras 填充扩展项）的
 * 实际导出集合对齐：基础字段 + [CourseExtras 携带的扩展项]（selectedWeeks/timeMode/
 * startTime/endTime/date/cycleLength/cycleWeek/overrideOfId/overrideWeek/groupKey/
 * abbreviation/credit/isOutdoor/enabled）。
 *
 * **不导出**：`sourceStartSection/sourceEndSection`（展示投影前的原始节次，属于本机
 * 视图状态而非课程数据）、`timetableId`（导入时重新分配）、`timeUnconfirmed`
 * （读取态标记，不持久化）。
 *
 * [weekType] 沿用参考实现的数字编码（0=每周 1=单周 2=双周）而非枚举名，保持与
 * 鸿蒙端导出文件互通。
 */
@Serializable
data class ScheduleJsonCourse(
    /** 文件内的课程 id；导入时经 ID 映射重编为新库 id，0 表示文件未携带 id。 */
    val id: Long = 0,
    val name: String = "",
    val teacher: String = "",
    val classroom: String = "",
    /** 星期几，1=周一 … 7=周日。 */
    val dayOfWeek: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 1,
    val startWeek: Int = 1,
    val endWeek: Int = 18,
    val weekType: Int = 0,
    /** 卡片强调色（#RRGGBB）。 */
    val color: String = "#0A59F7",
    val remark: String = "",
    /** 分组键（相邻合并用）。 */
    val groupKey: String = "",
    val abbreviation: String = "",
    val credit: Double = 0.0,
    val isOutdoor: Boolean = false,
    /** 关闭后的课程不参与任何周的发生计算。 */
    val enabled: Boolean = true,
    /** 额外指定的上课周列表；空表示不额外筛选。 */
    val selectedWeeks: List<Int> = emptyList(),
    /** 上课时间模式：0=按节次+时间表，1=自定义钟点，2=单次指定日期。 */
    val timeMode: Int = 0,
    val startTime: String = "",
    val endTime: String = "",
    /** 单次指定日期（yyyy-MM-dd），timeMode=2 时有效。 */
    val date: String = "",
    val cycleLength: Int = 1,
    val cycleWeek: Int = 0,
    /** 被单次调整的原课程 id（文件内旧 id，导入时重映射）；0=非调整记录。 */
    val overrideOfId: Long = 0,
    /** 单次调整生效的教学周。 */
    val overrideWeek: Int = 0,
)

/** 交换文档中的学期记录；可选，缺失时按"仅课程文件"处理。 */
@Serializable
data class ScheduleJsonSemester(
    val name: String = "",
    /** 学期第 1 周锚点日期（yyyy-MM-dd）。 */
    val startDate: String = "",
    val totalWeeks: Int = 20,
    val isCurrent: Boolean = false,
)

/**
 * 交换文档中的作息行，含参考实现 CourseExtras 携带的节次扩展项
 * （name/kind/hidden/defaultCourseName/sortOrder）。
 */
@Serializable
data class ScheduleJsonTimeSlot(
    val section: Int = 1,
    val startTime: String = "",
    val endTime: String = "",
    val name: String = "",
    /** 节点类型：0=上课节次，2=休息/占位。 */
    val kind: Int = 0,
    val hidden: Boolean = false,
    val defaultCourseName: String = "",
    val sortOrder: Double = 0.0,
)

/**
 * 课表交换文档的根结构。
 *
 * [displayConfig] 是**课表显示配置字符串**（其自身可能是另一个应用的 JSON）：
 * - 只接受字符串形态；嵌套对象一律忽略，绝不尝试按对象解析（参考实现的宽容约定）；
 * - 缺失/无法应用时保留本机设置。
 *
 * @SerialName 统一为小驼峰，与参考实现字段名一致，保证跨端文件互通。
 */
@Serializable
data class ScheduleJsonDocument(
    val meta: ScheduleJsonMeta = ScheduleJsonMeta(),
    val courses: List<ScheduleJsonCourse> = emptyList(),
    /** 文件携带的学期；缺失表示纯课程文件。 */
    val semester: ScheduleJsonSemester? = null,
    /** 文件携带的作息时间表；可为空。 */
    val timeSlots: List<ScheduleJsonTimeSlot> = emptyList(),
    /** 课表显示配置字符串（可为空字符串）。 */
    val displayConfig: String = "",
)

/** 模型 → 交换课程记录（导出用；不携带 sourceStart/sourceEnd/timetableId）。 */
fun Course.toScheduleJsonCourse(): ScheduleJsonCourse = ScheduleJsonCourse(
    id = id,
    name = name,
    teacher = teacher,
    classroom = classroom,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = weekType.code,
    color = color,
    remark = remark,
    groupKey = groupKey,
    abbreviation = abbreviation,
    credit = credit,
    isOutdoor = isOutdoor,
    enabled = enabled,
    selectedWeeks = selectedWeeks,
    timeMode = timeMode,
    startTime = startTime,
    endTime = endTime,
    date = date,
    cycleLength = cycleLength,
    cycleWeek = cycleWeek,
    overrideOfId = overrideOfId,
    overrideWeek = overrideWeek,
)

/** 模型 → 交换学期记录（导出用）。 */
fun Semester.toScheduleJsonSemester(): ScheduleJsonSemester = ScheduleJsonSemester(
    name = name,
    startDate = startDate.toString(),
    totalWeeks = totalWeeks,
    isCurrent = isCurrent,
)

/** 模型 → 交换作息行（导出用）。 */
fun TimeSlot.toScheduleJsonTimeSlot(): ScheduleJsonTimeSlot = ScheduleJsonTimeSlot(
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
 * 交换课程记录 → 模型（导入用）。
 *
 * [timetableId] 由导入服务在写入时给定；[Course.id] 固定置 0（导入总是生成新库 id，
 * 文件内旧 id 由导入服务另行维护映射）。
 *
 * @throws IllegalArgumentException [ScheduleJsonCourse.weekType] 不是 0/1/2 时抛出
 *   （解析层已归一化，此防线针对直接构造 DTO 的调用方）。
 */
internal fun ScheduleJsonCourse.toCoreCourse(timetableId: Long): Course {
    val weekType = WeekType.entries.firstOrNull { it.code == weekType }
        ?: throw IllegalArgumentException("周次类型无效：$weekType")
    return Course(
        id = 0,
        timetableId = timetableId,
        name = name,
        teacher = teacher,
        classroom = classroom,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        selectedWeeks = selectedWeeks,
        color = color,
        remark = remark,
        groupKey = groupKey,
        abbreviation = abbreviation,
        credit = credit,
        isOutdoor = isOutdoor,
        enabled = enabled,
        timeMode = timeMode,
        startTime = startTime,
        endTime = endTime,
        date = date,
        cycleLength = cycleLength,
        cycleWeek = cycleWeek,
        overrideOfId = overrideOfId,
        overrideWeek = overrideWeek,
    )
}
