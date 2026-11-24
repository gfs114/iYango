package com.gfs.iyango.data.json

import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.WeekType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 一条导入提示/纠正记录（message 为主文案，detail 为补充说明，可空）。 */
data class ImportIssue(
    val message: String,
    val detail: String = "",
) {
    /** 合成给上层（UI/预览列表）展示的单行文本。 */
    fun display(): String = if (detail.isEmpty()) message else "$message：$detail"
}

/**
 * 课表 JSON 交换文档的宽容解析器（P2-10）。
 *
 * 语义逐条移植自参考实现 `ScheduleJsonParser.ets`：
 * - **手写宽松解析**：遍历 `JsonObject` + 候选键查找（[ScheduleJsonFields]），而非强类型
 *   反序列化——其他应用的文件字段名五花八门，强类型会把"差一个名字"变成整份文件失败；
 * - 每一处纠正或跳过都以 [ImportIssue] 记录，绝不静默套用；
 * - 数字字段接受 JSON 数字或"可解析出整数前缀"的字符串（如 `"3节"` → 3，
 *   等价 JS `parseInt`）；周次/节次文本（`"1-16(单)"`）取其中全部数字段推断区间，
 *   "单/双"字样推断单双周；
 * - [ScheduleJsonDocument.displayConfig] 只接受字符串形态，嵌套对象一律忽略
 *   （参考实现同款约定：兄弟应用的显示配置字段名是它自己的事）；
 * - 课程条目缺名称跳过；校验（[CourseValidator.validate]）不过跳过并记录；
 * - 根结构接受对象（候选键找课程数组）或**裸数组**（视为课程数组）。
 */
object ScheduleJsonParser {

    /** 节次号上限（与参考实现一致）。 */
    const val MAX_SECTION = 20

    /** 教学周上限（与参考实现一致）。 */
    const val MAX_WEEK = 30

    private val DIGITS = Regex("\\d+")

    /** 当前本地时间，格式 `yyyy-MM-dd HH:mm:ss`（导出时间戳用）。 */
    fun now(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    /**
     * 解析结果：[document] 为 null 表示整份文件不可用（此时 [issues] 首条为致命原因）。
     */
    data class ParseResult(
        val document: ScheduleJsonDocument?,
        val issues: List<ImportIssue>,
    )

    /**
     * 解析 [text]；整份文件不可用时返回 `document = null`。
     */
    fun parse(text: String): ParseResult {
        val issues = mutableListOf<ImportIssue>()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            issues += ImportIssue("文件内容为空")
            return ParseResult(null, issues)
        }
        val root: JsonElement = try {
            kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
        } catch (_: Exception) {
            issues += ImportIssue("文件不是合法的 JSON", "请确认选择的是导出的课程 JSON 文件")
            return ParseResult(null, issues)
        }
        val document = when (root) {
            // 裸数组也被接受：一些应用直接导出课程数组。
            is JsonArray -> ScheduleJsonDocument(courses = readCourseArray(root, issues))
            is JsonObject -> ScheduleJsonDocument(
                meta = readMeta(root),
                courses = readCourseArray(findArray(root, ScheduleJsonFields.ROOT_COURSE_ARRAYS), issues),
                semester = readSemester(root, issues),
                timeSlots = readTimeSlots(findArray(root, ScheduleJsonFields.TIME_SLOT_ARRAYS), issues),
                displayConfig = readDisplayConfig(root),
            )
            // 数字/字符串/null 根：无课程可读，走"没有可用课程"的统一出口。
            else -> ScheduleJsonDocument()
        }
        if (document.courses.isEmpty()) {
            issues += ImportIssue(
                "文件中没有可用的课程",
                "支持的字段名：name/teacher/classroom/dayOfWeek/startSection/endSection/startWeek/endWeek",
            )
            return ParseResult(null, issues)
        }
        return ParseResult(document, issues)
    }

    // ------------------------------------------------------------------ meta

    private fun readMeta(root: JsonObject): ScheduleJsonMeta {
        val meta = root.obj("meta") ?: return ScheduleJsonMeta()
        return ScheduleJsonMeta(
            version = meta.num(listOf("version")) ?: 1,
            exportedAt = meta.text(listOf("exportedAt", "exportTime", "time")) ?: "",
            app = meta.text(listOf("app", "application")) ?: SCHEDULE_JSON_APP,
        )
    }

    // ------------------------------------------------------------------ courses

    private fun readCourseArray(items: JsonArray, issues: MutableList<ImportIssue>): List<ScheduleJsonCourse> =
        items.mapNotNull { element -> (element as? JsonObject)?.let { readCourse(it, issues) } }

    private fun readCourse(item: JsonObject, issues: MutableList<ImportIssue>): ScheduleJsonCourse? {
        val name = item.text(ScheduleJsonFields.NAME)?.trim().orEmpty()
        if (name.isEmpty()) {
            issues += ImportIssue("跳过缺少课程名称的记录")
            return null
        }
        val teacher = item.text(ScheduleJsonFields.TEACHER)?.trim().orEmpty()
        val classroom = item.text(ScheduleJsonFields.CLASSROOM)?.trim().orEmpty()

        // 星期：数字优先，越界/缺失时回退到星期文本（"周三"等），仍失败按星期一。
        var day = item.num(ScheduleJsonFields.DAY) ?: 0
        if (day < 1 || day > 7) {
            day = dayFromText(item.text(ScheduleJsonFields.DAY).orEmpty())
        }
        if (day < 1 || day > 7) {
            issues += ImportIssue("未识别到星期，已按星期一处理", name)
            day = 1
        }

        // 节次：显式起止对优先，回退到合并形式（"1-2"、"[01-02]"）。
        var start = item.num(ScheduleJsonFields.START_SECTION) ?: 0
        var end = item.num(ScheduleJsonFields.END_SECTION) ?: 0
        if (start <= 0) {
            val numbers = numbersIn(item.text(ScheduleJsonFields.SECTION).orEmpty())
            when {
                numbers.size >= 2 -> {
                    start = numbers[0]
                    end = numbers[1]
                }
                numbers.size == 1 -> {
                    start = numbers[0]
                    end = numbers[0]
                }
            }
        }
        if (start <= 0) {
            issues += ImportIssue("未识别到节次，已按第 1 节处理", name)
            start = 1
            end = 1
        }
        if (end <= 0) {
            end = start
        }
        if (end < start) {
            issues += ImportIssue("结束节次小于开始节次，已自动修正", name)
            end = start
        }
        if (start > MAX_SECTION) {
            start = MAX_SECTION
        }
        if (end > MAX_SECTION) {
            end = MAX_SECTION
        }

        // 周次：显式起止对 + 类型优先；缺起止时回退到周次文本（"1-16(单)"），
        // 且仅当区间来自文本时才从文本推断单双周（对齐参考实现）。
        var weekType = normalizeWeekType(item.num(ScheduleJsonFields.WEEK_TYPE) ?: 0)
        var weekStart = item.num(ScheduleJsonFields.START_WEEK) ?: 0
        var weekEnd = item.num(ScheduleJsonFields.END_WEEK) ?: 0
        val weekText = item.text(ScheduleJsonFields.WEEKS).orEmpty()
        if (weekStart <= 0 && weekText.isNotEmpty()) {
            val numbers = numbersIn(weekText)
            when {
                numbers.size >= 2 -> {
                    weekStart = numbers[0]
                    weekEnd = numbers[1]
                }
                numbers.size == 1 -> {
                    weekStart = numbers[0]
                    weekEnd = numbers[0]
                }
            }
            if (weekType == WeekType.EVERY.code) {
                weekType = weekTypeFromText(weekText)
            }
        }
        if (weekStart <= 0) {
            issues += ImportIssue("未识别到周次，已按第 1-18 周处理", name)
            weekStart = 1
            weekEnd = 18
        }
        if (weekEnd <= 0) {
            weekEnd = weekStart
        }
        if (weekStart < 1) {
            weekStart = 1
        }
        if (weekEnd < weekStart) {
            issues += ImportIssue("结束周小于开始周，已自动修正", name)
            weekEnd = weekStart
        }
        if (weekEnd > MAX_WEEK) {
            weekEnd = MAX_WEEK
        }

        val color = item.text(ScheduleJsonFields.COLOR).orEmpty()
        val extras = readCourseExtras(item)
        val course = ScheduleJsonCourse(
            id = item.num(listOf("id"))?.toLong() ?: 0L,
            name = name,
            teacher = teacher,
            classroom = classroom,
            dayOfWeek = day,
            startSection = start,
            endSection = end,
            startWeek = weekStart,
            endWeek = weekEnd,
            weekType = weekType,
            color = if (isColor(color)) color else "#0A59F7",
            remark = item.text(ScheduleJsonFields.REMARK)?.trim().orEmpty(),
            groupKey = extras.groupKey,
            abbreviation = extras.abbreviation,
            credit = extras.credit,
            isOutdoor = extras.isOutdoor,
            enabled = extras.enabled,
            selectedWeeks = extras.selectedWeeks,
            timeMode = extras.timeMode,
            startTime = extras.startTime,
            endTime = extras.endTime,
            date = extras.date,
            cycleLength = extras.cycleLength,
            cycleWeek = extras.cycleWeek,
            overrideOfId = extras.overrideOfId,
            overrideWeek = extras.overrideWeek,
        )

        // 与参考实现一致：扩展项已附加后用共享校验器复核整条记录，不合法即跳过并记录。
        val validation = CourseValidator.validate(course.toCoreCourse(timetableId = 0))
        if (validation != null) {
            issues += ImportIssue("跳过无效的课程安排", "$name：$validation")
            return null
        }
        return course
    }

    // ------------------------------------------------------------------ semester / slots

    private fun readSemester(root: JsonObject, issues: MutableList<ImportIssue>): ScheduleJsonSemester? {
        val source = root.obj(ScheduleJsonFields.SEMESTER_OBJECTS[0])
            ?: root.obj(ScheduleJsonFields.SEMESTER_OBJECTS[1])
            ?: return null
        val name = source.text(ScheduleJsonFields.SEMESTER_NAME)?.trim().orEmpty()
        if (name.isEmpty()) {
            issues += ImportIssue("学期信息缺少名称，已忽略")
            return null
        }
        var totalWeeks = source.num(ScheduleJsonFields.SEMESTER_WEEKS) ?: 20
        if (totalWeeks < 1 || totalWeeks > MAX_WEEK) {
            issues += ImportIssue("学期总周数超出范围，已按 20 周处理", name)
            totalWeeks = 20
        }
        return ScheduleJsonSemester(
            name = name,
            startDate = source.text(ScheduleJsonFields.SEMESTER_START)?.trim().orEmpty(),
            totalWeeks = totalWeeks,
            isCurrent = source.bool(ScheduleJsonFields.SEMESTER_CURRENT) ?: false,
        )
    }

    private fun readTimeSlots(items: JsonArray, issues: MutableList<ImportIssue>): List<ScheduleJsonTimeSlot> {
        val slots = mutableListOf<ScheduleJsonTimeSlot>()
        for (element in items) {
            val item = element as? JsonObject ?: continue
            val section = item.num(ScheduleJsonFields.SLOT_SECTION) ?: 0
            val start = item.text(ScheduleJsonFields.SLOT_START)?.trim().orEmpty()
            val end = item.text(ScheduleJsonFields.SLOT_END)?.trim().orEmpty()
            // 节次扩展项（参考实现 CourseExtras.readSlot）：严格按类型读取。
            val name = item.strictString("name").orEmpty()
            val kind = item.strictNumber("kind")?.toInt() ?: 0
            val hidden = item.strictBool("hidden") ?: false
            val defaultCourseName = item.strictString("defaultCourseName").orEmpty()
            val sortOrder = item.strictNumber("sortOrder") ?: 0.0
            if (section <= 0 || (kind != 2 && (start.isEmpty() || end.isEmpty()))) {
                issues += ImportIssue("跳过不完整的节次时间记录")
                continue
            }
            slots += ScheduleJsonTimeSlot(
                section = section,
                startTime = start,
                endTime = end,
                name = name,
                kind = kind,
                hidden = hidden,
                defaultCourseName = defaultCourseName,
                sortOrder = sortOrder,
            )
        }
        return slots
    }

    /**
     * 显示配置只接受字符串形态；嵌套对象/缺失一律返回空串（保留本机设置）。
     */
    private fun readDisplayConfig(root: JsonObject): String =
        (root["displayConfig"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    // ------------------------------------------------------------------ value helpers

    private fun findArray(root: JsonObject, keys: List<String>): JsonArray {
        for (key in keys) {
            (root[key] as? JsonArray)?.let { return it }
        }
        return JsonArray(emptyList())
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** 候选键读取文本：字符串原样，数字按 JS `Number.toString()` 近似（整数去掉小数尾巴）。 */
    private fun JsonObject.text(keys: List<String>): String? {
        for (key in keys) {
            val primitive = this[key] as? JsonPrimitive ?: continue
            if (primitive.isString) {
                return primitive.content
            }
            primitive.doubleOrNull?.let { value ->
                return if (value == Math.floor(value) && !value.isInfinite()) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
            }
        }
        return null
    }

    /** 候选键读取整数：JSON 数字取整，字符串取整数前缀（等价 JS parseInt）。 */
    private fun JsonObject.num(keys: List<String>): Int? {
        for (key in keys) {
            val primitive = this[key] as? JsonPrimitive ?: continue
            if (!primitive.isString) {
                primitive.doubleOrNull?.let { return it.toInt() }
            } else {
                leadingInt(primitive.content)?.let { return it }
            }
        }
        return null
    }

    /** 候选键读取布尔：布尔原样，数字非零为真，字符串仅 "true"/"1" 为真（对齐参考实现）。 */
    private fun JsonObject.bool(keys: List<String>): Boolean? {
        for (key in keys) {
            val primitive = this[key] as? JsonPrimitive ?: continue
            if (primitive.isString) {
                return primitive.content == "true" || primitive.content == "1"
            }
            primitive.booleanOrNull?.let { return it }
            primitive.doubleOrNull?.let { return it != 0.0 }
        }
        return null
    }

    /** 文本中的全部数字段（"1-16(单)" → [1, 16]）。 */
    private fun numbersIn(text: String): List<Int> =
        DIGITS.findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()

    /** 整数前缀解析（等价 JS `parseInt`："3节" → 3；"1-16" → 1）。 */
    private fun leadingInt(text: String): Int? =
        Regex("^\\s*([+-]?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 星期文本解析：识别"星期/周/礼拜 + 一~日（含'天'）"与 1..7 的数字。
     *
     * 注：参考实现的 `'天'` 分支因下标取模缺陷把"周天"映射到周一，此处修正为周日。
     */
    fun dayFromText(text: String): Int {
        val compact = text.replace(Regex("\\s+"), "")
        val patterns = listOf('一', '二', '三', '四', '五', '六', '日', '天')
        for ((index, char) in patterns.withIndex()) {
            if (compact.contains("星期$char") || compact.contains("周$char") || compact.contains("礼拜$char")) {
                return if (char == '天') 7 else index + 1
            }
        }
        val numbers = numbersIn(compact)
        if (numbers.isNotEmpty() && numbers[0] in 1..7) {
            return numbers[0]
        }
        return 0
    }

    /** 周次文本推断单双周："单"→单周，"双"→双周，其余每周。 */
    fun weekTypeFromText(text: String): Int = when {
        text.contains("单") -> WeekType.ODD.code
        text.contains("双") -> WeekType.EVEN.code
        else -> WeekType.EVERY.code
    }

    /** 周类型归一化：仅 0/1/2 合法。 */
    fun normalizeWeekType(value: Int): Int =
        if (value == WeekType.ODD.code || value == WeekType.EVEN.code) value else WeekType.EVERY.code

    /** 是否为 `#RRGGBB` 颜色文本。 */
    fun isColor(text: String): Boolean =
        text.length == 7 && text[0] == '#' && Regex("^[0-9A-Fa-f]{6}$").matches(text.substring(1))
}

/** 严格字符串（CourseExtras 语义：类型不符即回退默认值）。 */
private fun JsonObject.strictString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 严格数字（CourseExtras 语义）。 */
private fun JsonObject.strictNumber(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** 严格布尔（CourseExtras 语义）。 */
private fun JsonObject.strictBool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/**
 * CourseExtras 携带的课程扩展项（从课程对象的同名字段读取，严格按类型回退默认值）。
 *
 * 与参考实现 `CourseExtras.readCourse` 的默认值逐项一致。
 */
private data class CourseExtras(
    val groupKey: String,
    val abbreviation: String,
    val credit: Double,
    val isOutdoor: Boolean,
    val enabled: Boolean,
    val selectedWeeks: List<Int>,
    val timeMode: Int,
    val startTime: String,
    val endTime: String,
    val date: String,
    val cycleLength: Int,
    val cycleWeek: Int,
    val overrideOfId: Long,
    val overrideWeek: Int,
)

/** 从课程 JSON 对象读取扩展项（严格类型，缺失/类型不符回退默认值）。 */
private fun readCourseExtras(item: JsonObject): CourseExtras {
    val weeks = (item["selectedWeeks"] as? JsonArray)?.let { array ->
        val numbers = array.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        }
        // 任一元素不是数字则整体放弃（对齐参考实现的 CourseExtras.numbers）。
        if (numbers.size == array.size) numbers.map { it.toInt() } else emptyList()
    }.orEmpty()
    return CourseExtras(
        groupKey = item.strictString("groupKey").orEmpty(),
        abbreviation = item.strictString("abbreviation").orEmpty(),
        credit = item.strictNumber("credit") ?: 0.0,
        isOutdoor = item.strictBool("isOutdoor") ?: false,
        enabled = item.strictBool("enabled") ?: true,
        selectedWeeks = weeks,
        timeMode = item.strictNumber("timeMode")?.toInt() ?: 0,
        startTime = item.strictString("startTime").orEmpty(),
        endTime = item.strictString("endTime").orEmpty(),
        date = item.strictString("date").orEmpty(),
        cycleLength = item.strictNumber("cycleLength")?.toInt() ?: 1,
        cycleWeek = item.strictNumber("cycleWeek")?.toInt() ?: 0,
        overrideOfId = item.strictNumber("overrideOfId")?.toLong() ?: 0,
        overrideWeek = item.strictNumber("overrideWeek")?.toInt() ?: 0,
    )
}
