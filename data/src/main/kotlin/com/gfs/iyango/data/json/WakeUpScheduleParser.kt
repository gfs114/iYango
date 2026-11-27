package com.gfs.iyango.data.json

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.WeekType
import java.nio.charset.Charset
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * WakeUp `.wakeup_schedule`（5 行 JSON）与通用 CSV 课表解析器。
 */
object WakeUpScheduleParser {

    /**
     * 解析 WakeUp 课表导出的 5 行 JSON 文本：
     * - 第 1 行：作息时间数组
     * - 第 2 行：学期配置对象（startDate / maxWeek / tableName）
     * - 第 3 行：表配置对象（可忽略）
     * - 第 4 行：课程定义数组（id / courseName / color / credit / note）
     * - 第 5 行：上课安排数组（id / day / startNode / step / startWeek / endWeek / type / room / teacher）
     */
    fun parseWakeUpFile(text: String): ScheduleJsonParser.ParseResult {
        val issues = mutableListOf<ImportIssue>()
        val lines = text.removePrefix("\uFEFF")
            .split(Regex("\r?\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.size < 5) {
            issues += ImportIssue("WakeUp 文件格式不完整（不足 5 行）")
            return ScheduleJsonParser.ParseResult(null, issues)
        }

        val timeSlotsEl = parseJsonSafe(lines[0])
        val configEl = parseJsonSafe(lines[1])
        val defsEl = parseJsonSafe(lines[3])
        val itemsEl = parseJsonSafe(lines[4])

        if (defsEl !is JsonArray || itemsEl !is JsonArray) {
            issues += ImportIssue("WakeUp 课程结构解析失败")
            return ScheduleJsonParser.ParseResult(null, issues)
        }

        val timeSlots = (timeSlotsEl as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val sec = obj["node"]?.jsonPrimitive?.intOrNull ?: obj["section"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            if (sec !in 1..ScheduleJsonParser.MAX_SECTION) return@mapNotNull null
            val start = obj["startTime"]?.jsonPrimitive?.content.orEmpty().trim()
            val end = obj["endTime"]?.jsonPrimitive?.content.orEmpty().trim()
            if (start.isEmpty() || end.isEmpty()) return@mapNotNull null
            ScheduleJsonTimeSlot(section = sec, startTime = start, endTime = end)
        }.sortedBy { it.section }

        val configObj = configEl as? JsonObject
        val tableName = configObj?.get("tableName")?.jsonPrimitive?.content?.trim().orEmpty()
        val startDate = configObj?.get("startDate")?.jsonPrimitive?.content?.trim()
            ?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
        val maxWeek = (configObj?.get("maxWeek")?.jsonPrimitive?.intOrNull
            ?: configObj?.get("totalWeeks")?.jsonPrimitive?.intOrNull
            ?: 20).coerceIn(1, ScheduleJsonParser.MAX_WEEK)

        val defsById = HashMap<Int, JsonObject>()
        defsEl.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            defsById[id] = obj
        }

        val courses = mutableListOf<ScheduleJsonCourse>()
        itemsEl.forEachIndexed { idx, el ->
            val obj = el as? JsonObject ?: return@forEachIndexed
            val courseId = obj["id"]?.jsonPrimitive?.intOrNull
            val def = courseId?.let(defsById::get)
            val name = def?.get("courseName")?.jsonPrimitive?.content
                ?: obj["courseName"]?.jsonPrimitive?.content
                ?: def?.get("name")?.jsonPrimitive?.content
                ?: ""
            if (name.isBlank()) {
                issues += ImportIssue("WakeUp 第 ${idx + 1} 条排课缺少课程名，已跳过")
                return@forEachIndexed
            }
            val day = (obj["day"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 7)
            val startNode = (obj["startNode"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, ScheduleJsonParser.MAX_SECTION)
            val step = (obj["step"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
            val endSection = (startNode + step - 1).coerceIn(startNode, ScheduleJsonParser.MAX_SECTION)
            val startWeek = (obj["startWeek"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, ScheduleJsonParser.MAX_WEEK)
            val endWeek = (obj["endWeek"]?.jsonPrimitive?.intOrNull ?: maxWeek).coerceIn(startWeek, ScheduleJsonParser.MAX_WEEK)
            val rawType = obj["type"]?.jsonPrimitive?.intOrNull ?: 0
            val weekType = when (rawType) {
                1 -> WeekType.ODD.code
                2 -> WeekType.EVEN.code
                else -> WeekType.EVERY.code
            }
            val room = obj["room"]?.jsonPrimitive?.content.orEmpty().trim()
            val teacher = obj["teacher"]?.jsonPrimitive?.content.orEmpty().trim()
            val color = def?.get("color")?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.startsWith("#") && (it.length == 7 || it.length == 9) }
                ?: "#0A59F7"
            val normalizedColor = if (color.length == 9) "#" + color.takeLast(6) else color
            val credit = def?.get("credit")?.jsonPrimitive?.doubleOrNull?.coerceAtLeast(0.0) ?: 0.0
            val note = def?.get("note")?.jsonPrimitive?.content.orEmpty().trim()

            val candidate = ScheduleJsonCourse(
                id = (courses.size + 1).toLong(),
                name = name.trim(),
                teacher = teacher,
                classroom = room,
                dayOfWeek = day,
                startSection = startNode,
                endSection = endSection,
                startWeek = startWeek,
                endWeek = endWeek,
                weekType = weekType,
                color = normalizedColor,
                credit = credit,
                remark = note,
            )
            val error = validateJsonCourse(candidate)
            if (error != null) {
                issues += ImportIssue("已跳过「${candidate.name}」", error)
            } else {
                courses += candidate
            }
        }

        if (courses.isEmpty()) {
            issues += ImportIssue("WakeUp 文件中没有可用课程")
            return ScheduleJsonParser.ParseResult(null, issues)
        }

        val semester = startDate?.let {
            ScheduleJsonSemester(
                name = tableName.ifBlank { "WakeUp 导入学期" },
                startDate = it,
                totalWeeks = maxWeek,
            )
        }
        return ScheduleJsonParser.ParseResult(
            ScheduleJsonDocument(
                courses = courses,
                semester = semester,
                timeSlots = timeSlots,
            ),
            issues,
        )
    }

    /**
     * 解析 UTF-8（含 BOM）或 GBK 编码的通用 CSV 课表文件。
     */
    fun parseCsv(bytes: ByteArray): ScheduleJsonParser.ParseResult {
        val issues = mutableListOf<ImportIssue>()
        var text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (text.contains('\uFFFD')) {
            val gbk = runCatching { String(bytes, Charset.forName("GBK")) }.getOrNull()
            if (gbk != null && !gbk.contains('\uFFFD')) {
                text = gbk.removePrefix("\uFEFF")
            }
        }

        val rows = parseCsvRecords(text).filter { row -> row.any { it.isNotBlank() } }
        if (rows.size < 2) {
            issues += ImportIssue("CSV 文件至少需要表头与一行课程数据")
            return ScheduleJsonParser.ParseResult(null, issues)
        }

        val headers = rows.first().map { normalizeHeader(it) }
        val nameCol = findCol(headers, "课程名称", "课程名", "课程", "name", "course", "subject")
        val dayCol = findCol(headers, "星期", "周几", "day", "weekday", "dayofweek")
        val startSecCol = findCol(headers, "开始节数", "开始节次", "起始节", "节次", "节", "startsection", "startnode")
        val endSecCol = findCol(headers, "结束节数", "结束节次", "终止节", "endsection", "endnode")
        val stepCol = findCol(headers, "节数", "step", "span")
        val weeksCol = findCol(headers, "周数", "周次", "上课周", "weeks", "week")
        val startWeekCol = findCol(headers, "起始周", "开始周", "startweek")
        val endWeekCol = findCol(headers, "结束周", "终止周", "endweek")
        val teacherCol = findCol(headers, "老师", "教师", "任课教师", "teacher")
        val roomCol = findCol(headers, "地点", "教室", "上课地点", "classroom", "room", "location")
        val creditCol = findCol(headers, "学分", "credit")

        if (nameCol < 0 || dayCol < 0 || startSecCol < 0) {
            issues += ImportIssue("CSV 缺少必需列（课程名称 / 星期 / 开始节次）")
            return ScheduleJsonParser.ParseResult(null, issues)
        }

        val courses = mutableListOf<ScheduleJsonCourse>()
        rows.drop(1).forEachIndexed { rowIdx, row ->
            val lineNum = rowIdx + 2
            val name = row.getOrNull(nameCol)?.trim().orEmpty()
            if (name.isEmpty()) {
                issues += ImportIssue("CSV 第 $lineNum 行缺少课程名称，已跳过")
                return@forEachIndexed
            }
            val day = parseDayOfWeek(row.getOrNull(dayCol).orEmpty())
            if (day == null) {
                issues += ImportIssue("CSV 第 $lineNum 行「$name」星期格式无法识别，已跳过")
                return@forEachIndexed
            }
            val startRaw = row.getOrNull(startSecCol)?.trim().orEmpty()
            val secNumbers = Regex("""\d+""").findAll(startRaw).mapNotNull { it.value.toIntOrNull() }.toList()
            val startSec = secNumbers.firstOrNull()?.coerceIn(1, ScheduleJsonParser.MAX_SECTION)
            if (startSec == null) {
                issues += ImportIssue("CSV 第 $lineNum 行「$name」节次无效，已跳过")
                return@forEachIndexed
            }
            val explicitEnd = endSecCol.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim()?.toIntOrNull() }
            val step = stepCol.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim()?.toIntOrNull() }
            val endSec = (explicitEnd ?: secNumbers.getOrNull(1) ?: step?.let { startSec + it - 1 } ?: startSec)
                .coerceIn(startSec, ScheduleJsonParser.MAX_SECTION)

            val teacher = teacherCol.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim() }.orEmpty()
            val room = roomCol.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim() }.orEmpty()
            val credit = creditCol.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim()?.toDoubleOrNull() } ?: 0.0

            val weekSegments = when {
                weeksCol >= 0 && !row.getOrNull(weeksCol).isNullOrBlank() ->
                    parseWeekSegments(row[weeksCol])
                startWeekCol >= 0 -> {
                    val sw = (row.getOrNull(startWeekCol)?.trim()?.toIntOrNull() ?: 1).coerceIn(1, ScheduleJsonParser.MAX_WEEK)
                    val ew = (endWeekCol.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim()?.toIntOrNull() } ?: sw)
                        .coerceIn(sw, ScheduleJsonParser.MAX_WEEK)
                    listOf(Triple(sw, ew, WeekType.EVERY.code))
                }
                else -> listOf(Triple(1, 16, WeekType.EVERY.code))
            }

            weekSegments.forEach { (sw, ew, wt) ->
                val candidate = ScheduleJsonCourse(
                    id = (courses.size + 1).toLong(),
                    name = name,
                    teacher = teacher,
                    classroom = room,
                    dayOfWeek = day,
                    startSection = startSec,
                    endSection = endSec,
                    startWeek = sw,
                    endWeek = ew,
                    weekType = wt,
                    credit = credit.coerceAtLeast(0.0),
                )
                val err = validateJsonCourse(candidate)
                if (err != null) {
                    issues += ImportIssue("CSV 第 $lineNum 行「$name」已跳过", err)
                } else {
                    courses += candidate
                }
            }
        }

        if (courses.isEmpty()) {
            issues += ImportIssue("CSV 文件中没有可用课程")
            return ScheduleJsonParser.ParseResult(null, issues)
        }
        return ScheduleJsonParser.ParseResult(ScheduleJsonDocument(courses = courses), issues)
    }

    private fun parseJsonSafe(line: String): JsonElement? =
        runCatching { Json.parseToJsonElement(line) }.getOrNull()

    private fun validateJsonCourse(item: ScheduleJsonCourse): String? {
        val check = Course(
            name = item.name,
            teacher = item.teacher,
            classroom = item.classroom,
            dayOfWeek = item.dayOfWeek,
            startSection = item.startSection,
            endSection = item.endSection,
            startWeek = item.startWeek,
            endWeek = item.endWeek,
            weekType = WeekType.entries.first { it.code == item.weekType },
            color = item.color,
            credit = item.credit,
            remark = item.remark,
        )
        return CourseValidator.validate(check)
    }

    private fun parseCsvRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i += 1
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == ',' -> {
                    currentRow += field.toString()
                    field.setLength(0)
                }
                ch == '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') i += 1
                    currentRow += field.toString()
                    field.setLength(0)
                    records += currentRow.toList()
                    currentRow.clear()
                }
                ch == '\n' -> {
                    currentRow += field.toString()
                    field.setLength(0)
                    records += currentRow.toList()
                    currentRow.clear()
                }
                else -> field.append(ch)
            }
            i += 1
        }
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += field.toString()
            records += currentRow.toList()
        }
        return records
    }

    private fun normalizeHeader(raw: String): String =
        raw.trim().lowercase().replace(" ", "").replace("_", "").replace("-", "")

    private fun findCol(headers: List<String>, vararg aliases: String): Int {
        val normAliases = aliases.map(::normalizeHeader)
        return headers.indexOfFirst { h -> normAliases.any { h == it || h.contains(it) } }
    }

    private fun parseDayOfWeek(raw: String): Int? {
        val s = raw.trim()
        s.toIntOrNull()?.takeIf { it in 1..7 }?.let { return it }
        return when {
            s.contains("一") || s.equals("mon", true) || s.equals("monday", true) -> 1
            s.contains("二") || s.equals("tue", true) || s.equals("tuesday", true) -> 2
            s.contains("三") || s.equals("wed", true) || s.equals("wednesday", true) -> 3
            s.contains("四") || s.equals("thu", true) || s.equals("thursday", true) -> 4
            s.contains("五") || s.equals("fri", true) || s.equals("friday", true) -> 5
            s.contains("六") || s.equals("sat", true) || s.equals("saturday", true) -> 6
            s.contains("日") || s.contains("天") || s.equals("sun", true) || s.equals("sunday", true) -> 7
            else -> Regex("""\d+""").find(s)?.value?.toIntOrNull()?.takeIf { it in 1..7 }
        }
    }

    private fun parseWeekSegments(raw: String): List<Triple<Int, Int, Int>> {
        val parts = raw.split(',', '，', '、', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<Triple<Int, Int, Int>>()
        for (part in parts) {
            val wt = when {
                part.contains("单") || part.contains("odd", true) -> WeekType.ODD.code
                part.contains("双") || part.contains("even", true) -> WeekType.EVEN.code
                else -> WeekType.EVERY.code
            }
            val nums = Regex("""\d+""").findAll(part).mapNotNull { it.value.toIntOrNull() }.toList()
            if (nums.isEmpty()) continue
            val sw = nums.first().coerceIn(1, ScheduleJsonParser.MAX_WEEK)
            val ew = (nums.getOrNull(1) ?: sw).coerceIn(sw, ScheduleJsonParser.MAX_WEEK)
            result += Triple(sw, ew, wt)
        }
        return result.ifEmpty { listOf(Triple(1, 16, WeekType.EVERY.code)) }
    }
}
