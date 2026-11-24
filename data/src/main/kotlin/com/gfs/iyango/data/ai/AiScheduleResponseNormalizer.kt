package com.gfs.iyango.data.ai

import com.gfs.iyango.core.domain.AiImportParseResult
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.WeekType
import com.gfs.iyango.data.json.SCHEDULE_JSON_APP
import com.gfs.iyango.data.json.SCHEDULE_JSON_FORMAT
import com.gfs.iyango.data.json.SCHEDULE_JSON_VERSION
import com.gfs.iyango.data.json.ScheduleJsonCourse
import com.gfs.iyango.data.json.ScheduleJsonDocument
import com.gfs.iyango.data.json.ScheduleJsonMeta
import com.gfs.iyango.data.json.ScheduleJsonSemester
import com.gfs.iyango.data.json.ScheduleJsonTimeSlot
import java.net.URI
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

private val THINK_TAG_REGEX = Regex("(?is)<think>.*?</think>")
private val CODE_FENCE_REGEX = Regex("(?is)```(?:json)?\\s*([\\s\\S]*?)```")
private val PRIVATE_172_REGEX = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}$")
private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")
private val SHORT_TIME_REGEX = Regex("^(\\d{1,2}):(\\d{2})(?::\\d{2})?$")
private val DIGITS_REGEX = Regex("\\d+")

private val AI_COURSE_PALETTE = listOf(
    "#3E7BFA",
    "#2BB673",
    "#F2A03D",
    "#F2634E",
    "#8B6BF0",
    "#2BB2B2",
    "#EE5A96",
    "#5E6AD2",
    "#2E9FD9",
    "#7C9B3F",
)

private val LENIENT_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun validateOpenAiBaseUrl(rawBaseUrl: String): Result<Pair<String, URI>> {
    val trimmed = rawBaseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) {
        return Result.failure(IllegalArgumentException("请填写接口 Base URL"))
    }
    if ('{' in trimmed || '}' in trimmed) {
        return Result.failure(IllegalArgumentException("请先将接口地址中的 {…} 占位符替换为实际配置"))
    }
    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: return Result.failure(IllegalArgumentException("接口地址格式无效"))
    val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
    val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT).orEmpty()
    if (host.isEmpty()) {
        return Result.failure(IllegalArgumentException("接口地址缺少主机名"))
    }
    val allowed = when (scheme) {
        "https" -> true
        "http" -> isLocalOrPrivateHost(host)
        else -> false
    }
    if (!allowed) {
        return Result.failure(IllegalArgumentException("仅支持 HTTPS 接口地址（本地调试地址除外）"))
    }
    return Result.success(trimmed to uri)
}

internal fun resolveChatCompletionsUrl(rawBaseUrl: String): Result<String> {
    val (trimmed, uri) = validateOpenAiBaseUrl(rawBaseUrl).getOrElse { return Result.failure(it) }
    val path = uri.path?.trimEnd('/').orEmpty()
    val resolved = if (path.endsWith("/chat/completions", ignoreCase = true)) {
        trimmed
    } else {
        "$trimmed/chat/completions"
    }
    return Result.success(resolved)
}

internal fun resolveModelsUrl(rawBaseUrl: String): Result<String> {
    val (trimmed, uri) = validateOpenAiBaseUrl(rawBaseUrl).getOrElse { return Result.failure(it) }
    val path = uri.path?.trimEnd('/').orEmpty()
    val resolved = when {
        path.endsWith("/models", ignoreCase = true) -> trimmed
        path.endsWith("/chat/completions", ignoreCase = true) -> {
            val base = trimmed.dropLast("/chat/completions".length).trimEnd('/')
            "$base/models"
        }
        else -> "$trimmed/models"
    }
    return Result.success(resolved)
}

internal fun parseModelsResponse(responseBody: String): List<AiModelInfo> {
    if (responseBody.isBlank()) return emptyList()
    val root = runCatching { LENIENT_JSON.parseToJsonElement(responseBody) }.getOrNull() ?: return emptyList()
    val items: JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> firstArray(root, listOf("data", "models", "items")) ?: return emptyList()
        else -> return emptyList()
    }
    return items.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { AiModelInfo(id = it) }
            is JsonObject -> {
                val id = firstText(element, listOf("id", "name", "model"))?.trim().orEmpty()
                if (id.isEmpty()) return@mapNotNull null
                val ownedBy = firstText(element, listOf("owned_by", "ownedBy", "provider")).orEmpty().trim()
                AiModelInfo(id = id, ownedBy = ownedBy)
            }
            else -> null
        }
    }
        .distinctBy { it.id }
        .sortedBy { it.id.lowercase(Locale.ROOT) }
}

private fun isLocalOrPrivateHost(host: String): Boolean {
    if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2") {
        return true
    }
    if (host.startsWith("10.") || host.startsWith("192.168.")) return true
    return PRIVATE_172_REGEX.matches(host)
}

internal fun normalizeModelOutputToScheduleJson(
    rawContent: String,
    exportedAt: String = "",
): AiImportParseResult {
    val stripped = stripReasoningAndFences(rawContent)
    val jsonSlice = extractOutermostJson(stripped)
        ?: return AiImportParseResult.Failed("模型未返回有效 JSON，请重试或换用更清晰的输入")
    val root = runCatching { LENIENT_JSON.parseToJsonElement(jsonSlice) }.getOrNull()
        ?: return AiImportParseResult.Failed("模型返回的 JSON 格式无法解析，请重试")

    val warnings = mutableListOf<String>()
    val courseArray: JsonArray
    var semesterObj: JsonObject? = null
    var slotsArray: JsonArray? = null

    when (root) {
        is JsonArray -> courseArray = root
        is JsonObject -> {
            courseArray = firstArray(root, listOf("courses", "course", "list", "lessons", "data"))
                ?: JsonArray(emptyList())
            semesterObj = (root["semester"] as? JsonObject) ?: (root["term"] as? JsonObject)
            slotsArray = firstArray(root, listOf("timeSlots", "time_slots", "times"))
            (root["warnings"] as? JsonArray)?.forEach { item ->
                val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                if (text.isNotEmpty()) warnings += text
            }
        }
        else -> return AiImportParseResult.Failed("模型返回结构无效")
    }

    var usedDefaultWeeks = false
    val colorByCourseName = linkedMapOf<String, String>()
    val normalizedCourses = mutableListOf<ScheduleJsonCourse>()

    for (element in courseArray) {
        val obj = element as? JsonObject ?: continue
        val name = firstText(obj, listOf("name", "courseName", "course_name", "title"))?.trim().orEmpty()
        if (name.isEmpty()) continue

        val teacher = firstText(obj, listOf("teacher", "teacherName", "tutor")).orEmpty().trim()
        val classroom = firstText(obj, listOf("classroom", "room", "location", "place")).orEmpty().trim()
        val rawRemark = firstText(obj, listOf("remark", "note", "comment")).orEmpty().trim()

        val rawDate = firstText(obj, listOf("date"))?.trim().orEmpty()
        val validDate = rawDate.takeIf { CourseValidator.isDateValid(it) }.orEmpty()

        val rawStartTime = normalizeClockText(firstText(obj, listOf("startTime", "start_time", "start")).orEmpty())
        val rawEndTime = normalizeClockText(firstText(obj, listOf("endTime", "end_time", "end")).orEmpty())
        val hasValidClock = CourseValidator.isTimeValid(rawStartTime) &&
            CourseValidator.isTimeValid(rawEndTime) &&
            rawStartTime < rawEndTime

        var day = parseDayOfWeek(obj)
        if (day !in 1..7 && validDate.isNotEmpty()) {
            day = runCatching { LocalDate.parse(validDate).dayOfWeek.value }.getOrDefault(1)
        }
        if (day !in 1..7) {
            day = 1
            warnings += "部分课程未识别出星期，已按周一处理，请在预览核对"
        }

        var startSection = firstInt(obj, listOf("startSection", "start_section", "sectionStart")) ?: 0
        var endSection = firstInt(obj, listOf("endSection", "end_section", "sectionEnd")) ?: 0
        if (startSection <= 0) {
            val sectionText = firstText(obj, listOf("section", "sections"))
            val nums = sectionText?.let { extractPositiveInts(it) }.orEmpty()
            if (nums.size >= 2) {
                startSection = nums[0]
                endSection = nums[1]
            } else if (nums.size == 1) {
                startSection = nums[0]
                endSection = nums[0]
            }
        }
        val sectionProvided = startSection > 0
        if (startSection <= 0) {
            startSection = 1
            endSection = 1
        }
        if (endSection <= 0) endSection = startSection
        startSection = startSection.coerceIn(1, 20)
        endSection = endSection.coerceIn(1, 20)
        if (endSection < startSection) {
            val tmp = startSection
            startSection = endSection
            endSection = tmp
        }

        val selectedWeeks = parseSelectedWeeks(obj["selectedWeeks"] ?: obj["selected_weeks"])
        var startWeek = firstInt(obj, listOf("startWeek", "start_week", "weekStart")) ?: 0
        var endWeek = firstInt(obj, listOf("endWeek", "end_week", "weekEnd")) ?: 0
        val weeksText = firstText(obj, listOf("weeks", "weekText", "weekRange")).orEmpty()
        var weekType = parseWeekType(obj, weeksText)

        if (startWeek <= 0 && weeksText.isNotEmpty()) {
            val nums = extractPositiveInts(weeksText)
            if (nums.size >= 2) {
                startWeek = nums[0]
                endWeek = nums[1]
            } else if (nums.size == 1) {
                startWeek = nums[0]
                endWeek = nums[0]
            }
        }
        if (startWeek <= 0 && selectedWeeks.isNotEmpty()) {
            startWeek = selectedWeeks.min()
            endWeek = selectedWeeks.max()
        }
        if (startWeek <= 0) {
            usedDefaultWeeks = true
            startWeek = 1
            endWeek = 16
        }
        if (endWeek <= 0) endWeek = startWeek
        startWeek = startWeek.coerceIn(1, 30)
        endWeek = endWeek.coerceIn(1, 30)
        if (endWeek < startWeek) {
            val tmp = startWeek
            startWeek = endWeek
            endWeek = tmp
        }
        if (selectedWeeks.isNotEmpty()) {
            startWeek = minOf(startWeek, selectedWeeks.min())
            endWeek = maxOf(endWeek, selectedWeeks.max())
        }

        val requestedMode = firstInt(obj, listOf("timeMode", "time_mode")) ?: -1
        var timeMode = when {
            validDate.isNotEmpty() -> 2
            requestedMode == 1 && hasValidClock -> 1
            hasValidClock && !sectionProvided -> 1
            else -> 0
        }
        var finalStartTime = if (timeMode > 0 && hasValidClock) rawStartTime else ""
        var finalEndTime = if (timeMode > 0 && hasValidClock) rawEndTime else ""
        var finalDate = if (timeMode == 2) validDate else ""
        var remark = rawRemark

        if (timeMode == 2 && !hasValidClock) {
            // CourseValidator requires HH:mm startTime < endTime whenever timeMode > 0.
            timeMode = 0
            finalDate = ""
            finalStartTime = ""
            finalEndTime = ""
            if (validDate.isNotEmpty() && validDate !in remark) {
                remark = if (remark.isEmpty()) validDate else "$validDate $remark"
            }
            warnings += "部分指定日期安排缺少起止时间，已转为按节次导入"
        }

        val rawColor = firstText(obj, listOf("color"))?.trim().orEmpty()
        val color = if (HEX_COLOR_REGEX.matches(rawColor)) {
            rawColor.uppercase(Locale.ROOT)
        } else {
            colorByCourseName.getOrPut(name) {
                AI_COURSE_PALETTE[abs(name.hashCode()) % AI_COURSE_PALETTE.size]
            }
        }

        val credit = (obj["credit"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0

        val candidate = ScheduleJsonCourse(
            name = name,
            teacher = teacher,
            classroom = classroom,
            dayOfWeek = day,
            startSection = startSection,
            endSection = endSection,
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = weekType,
            color = color,
            remark = remark.take(300),
            credit = credit,
            selectedWeeks = selectedWeeks,
            timeMode = timeMode,
            startTime = finalStartTime,
            endTime = finalEndTime,
            date = finalDate,
        )

        val validationError = validateNormalizedCourse(candidate)
        if (validationError == null) {
            normalizedCourses += candidate
        } else {
            warnings += "已跳过「$name」：$validationError"
        }
    }

    if (usedDefaultWeeks) {
        warnings += "部分课程未识别出周次，已默认填 1-16 周，请在预览核对"
    }

    if (normalizedCourses.isEmpty()) {
        return AiImportParseResult.Failed("未识别出有效课程，请尝试提供更清晰的截图或补充文字说明")
    }

    val normalizedSemester = normalizeSemester(semesterObj)
    val normalizedSlots = normalizeTimeSlots(slotsArray)

    val document = ScheduleJsonDocument(
        meta = ScheduleJsonMeta(
            app = SCHEDULE_JSON_APP,
            version = SCHEDULE_JSON_VERSION,
            exportedAt = exportedAt,
        ),
        courses = normalizedCourses,
        semester = normalizedSemester,
        timeSlots = normalizedSlots,
    )
    val encoded = SCHEDULE_JSON_FORMAT.encodeToString(ScheduleJsonDocument.serializer(), document)
    return AiImportParseResult.Ready(
        scheduleJsonBytes = encoded.toByteArray(Charsets.UTF_8),
        summary = "共识别 ${normalizedCourses.size} 条课程",
        warnings = warnings.distinct(),
    )
}

private fun validateNormalizedCourse(course: ScheduleJsonCourse): String? {
    val weekType = WeekType.entries.firstOrNull { it.code == course.weekType } ?: return "周次类型无效"
    val core = com.gfs.iyango.core.model.Course(
        name = course.name,
        teacher = course.teacher,
        classroom = course.classroom,
        dayOfWeek = course.dayOfWeek,
        startSection = course.startSection,
        endSection = course.endSection,
        startWeek = course.startWeek,
        endWeek = course.endWeek,
        weekType = weekType,
        selectedWeeks = course.selectedWeeks,
        color = course.color,
        remark = course.remark,
        credit = course.credit,
        timeMode = course.timeMode,
        startTime = course.startTime,
        endTime = course.endTime,
        date = course.date,
    )
    return CourseValidator.validate(core)
}

private fun normalizeSemester(obj: JsonObject?): ScheduleJsonSemester? {
    if (obj == null) return null
    val name = firstText(obj, listOf("name", "semesterName", "title"))?.trim().orEmpty()
    val startDate = firstText(obj, listOf("startDate", "start_date", "beginDate"))?.trim().orEmpty()
    if (name.isEmpty() || !CourseValidator.isDateValid(startDate)) return null
    val totalWeeks = (firstInt(obj, listOf("totalWeeks", "total_weeks", "weeks")) ?: 20).coerceIn(1, 30)
    return ScheduleJsonSemester(
        name = name,
        startDate = startDate,
        totalWeeks = totalWeeks,
        isCurrent = true,
    )
}

private fun normalizeTimeSlots(array: JsonArray?): List<ScheduleJsonTimeSlot> {
    if (array == null) return emptyList()
    val slots = mutableListOf<ScheduleJsonTimeSlot>()
    for (element in array) {
        val obj = element as? JsonObject ?: continue
        val section = firstInt(obj, listOf("section", "index", "no")) ?: continue
        val start = normalizeClockText(firstText(obj, listOf("startTime", "start_time", "start")).orEmpty())
        val end = normalizeClockText(firstText(obj, listOf("endTime", "end_time", "end")).orEmpty())
        if (section !in 1..20 || !CourseValidator.isTimeValid(start) || !CourseValidator.isTimeValid(end) || start >= end) {
            continue
        }
        slots += ScheduleJsonTimeSlot(
            section = section,
            startTime = start,
            endTime = end,
            name = firstText(obj, listOf("name"))?.trim().orEmpty(),
        )
    }
    return slots.distinctBy { it.section }.sortedBy { it.section }
}

private fun stripReasoningAndFences(raw: String): String {
    var text = THINK_TAG_REGEX.replace(raw, "").trim()
    val fenced = CODE_FENCE_REGEX.find(text)
    if (fenced != null) {
        text = fenced.groupValues[1].trim()
    }
    return text
}

private fun extractOutermostJson(text: String): String? {
    val startObj = text.indexOf('{')
    val startArr = text.indexOf('[')
    val start = when {
        startObj >= 0 && startArr >= 0 -> minOf(startObj, startArr)
        startObj >= 0 -> startObj
        startArr >= 0 -> startArr
        else -> return null
    }
    val openChar = text[start]
    val closeChar = if (openChar == '{') '}' else ']'
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val ch = text[index]
        if (escaped) {
            escaped = false
            continue
        }
        if (ch == '\\' && inString) {
            escaped = true
            continue
        }
        if (ch == '"') {
            inString = !inString
            continue
        }
        if (inString) continue
        if (ch == openChar) {
            depth++
        } else if (ch == closeChar) {
            depth--
            if (depth == 0) {
                return text.substring(start, index + 1)
            }
        }
    }
    return null
}

private fun parseDayOfWeek(obj: JsonObject): Int {
    val keys = listOf("dayOfWeek", "day_of_week", "weekday", "day")
    firstInt(obj, keys)?.takeIf { it in 1..7 }?.let { return it }
    val text = firstText(obj, keys)?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (text.isEmpty()) return 0
    return when {
        "一" in text || text == "mon" || text == "monday" -> 1
        "二" in text || text == "tue" || text == "tuesday" -> 2
        "三" in text || text == "wed" || text == "wednesday" -> 3
        "四" in text || text == "thu" || text == "thursday" -> 4
        "五" in text || text == "fri" || text == "friday" -> 5
        "六" in text || text == "sat" || text == "saturday" -> 6
        "日" in text || "天" in text || text == "sun" || text == "sunday" -> 7
        else -> 0
    }
}

private fun parseWeekType(obj: JsonObject, weeksText: String): Int {
    val keys = listOf("weekType", "week_type", "oddEven")
    val num = firstInt(obj, keys)
    if (num != null && num in 0..2) return num
    val raw = (firstText(obj, keys).orEmpty() + " " + weeksText).lowercase(Locale.ROOT)
    return when {
        "单" in raw || "odd" in raw -> WeekType.ODD.code
        "双" in raw || "even" in raw -> WeekType.EVEN.code
        else -> WeekType.EVERY.code
    }
}

private fun parseSelectedWeeks(element: JsonElement?): List<Int> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        val primitive = item as? JsonPrimitive ?: return@mapNotNull null
        primitive.intOrNull ?: primitive.content.trim().toIntOrNull()
    }.filter { it in 1..30 }.distinct().sorted()
}

private fun normalizeClockText(raw: String): String {
    val trimmed = raw.trim().replace('：', ':')
    val match = SHORT_TIME_REGEX.matchEntire(trimmed) ?: return trimmed
    val hour = match.groupValues[1].toIntOrNull() ?: return trimmed
    val minute = match.groupValues[2].toIntOrNull() ?: return trimmed
    if (hour !in 0..23 || minute !in 0..59) return trimmed
    return "%02d:%02d".format(Locale.ROOT, hour, minute)
}

private fun extractPositiveInts(text: String): List<Int> =
    DIGITS_REGEX.findAll(text).mapNotNull { it.value.toIntOrNull()?.takeIf { n -> n > 0 } }.toList()

private fun firstArray(obj: JsonObject, keys: List<String>): JsonArray? {
    for (key in keys) {
        (obj[key] as? JsonArray)?.let { return it }
    }
    return null
}

private fun firstText(obj: JsonObject, keys: List<String>): String? {
    for (key in keys) {
        val primitive = obj[key] as? JsonPrimitive ?: continue
        if (primitive.isString) return primitive.content
        primitive.doubleOrNull?.let { value ->
            return if (value == kotlin.math.floor(value) && !value.isInfinite()) {
                value.toLong().toString()
            } else {
                value.toString()
            }
        }
    }
    return null
}

private fun firstInt(obj: JsonObject, keys: List<String>): Int? {
    for (key in keys) {
        val primitive = obj[key] as? JsonPrimitive ?: continue
        if (!primitive.isString) {
            primitive.intOrNull?.let { return it }
            primitive.doubleOrNull?.let { return it.toInt() }
        } else {
            primitive.content.trim().toIntOrNull()?.let { return it }
        }
    }
    return null
}
