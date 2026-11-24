package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.ClassroomCourseInfo
import com.gfs.iyango.core.model.ClassroomDetailRequest
import com.gfs.iyango.core.model.ClassroomOccupancyKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * jsjy_jszyqk 详情解析，行为对齐 HarmonyOS 的 `YguClassroomCourseParser`。
 *
 * 只解读截图核对过的 24 槽「正常上课 / 借用」行；返回必须与当前教室、
 * 学期、周次、星期和原始节次组一致，否则判为错配而不是展示他人数据。
 * 第十一节接受 `1112`、`1111` 与 `day*100+11` 三类别名，但仍要求
 * 同日含第 11 节且时间覆盖，不能只凭包含 “11” 就放行。
 */
internal object YguClassroomCourseDetail {

    private const val MAX_BODY_LENGTH = 2_000_000
    private const val MAX_ROWS = 500
    private const val SLOT_COUNT = 5
    private const val ROW_LENGTH = 24
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    private const val MESSAGE_INVALID = "学校返回的详情格式暂无法识别，请重试"
    private const val MESSAGE_UNSUPPORTED = "此时段含尚未支持的占用详情，暂不能完整展示"
    private const val MESSAGE_EMPTY = "学校未返回此时段详情（占用状态仍以使用情况为准）"

    private val CLOCK = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val WEEK = Regex("^,?\\d+(?:-\\d+)?(?:,\\d+(?:-\\d+)?)*,?$")
    private val INTEGER = Regex("^-?\\d+$")

    fun parse(body: String, request: ClassroomDetailRequest): YguClassroomDetailResult {
        if (body.isEmpty() || body.length > MAX_BODY_LENGTH) return invalid()
        val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray
            ?: return invalid()
        if (parsed.size != SLOT_COUNT) return invalid()
        for (index in 0 until SLOT_COUNT) {
            val slot = parsed[index] as? JsonArray ?: return invalid()
            if (index > 0 && slot.isNotEmpty()) {
                return YguClassroomDetailResult(
                    status = YguClassroomDetailStatus.UNSUPPORTED,
                    message = MESSAGE_UNSUPPORTED,
                )
            }
        }
        val rows = parsed[0] as JsonArray
        if (rows.size > MAX_ROWS) return invalid()
        val expectedPeriod = request.dayOfWeek.toString() + request.rawSectionCode
        val courses = mutableListOf<ClassroomCourseInfo>()
        for (element in rows) {
            val row = element as? JsonArray ?: return invalid()
            if (row.size != ROW_LENGTH) return invalid()
            val roomId = request.roomId
            if (row[0].stringOrNull() != roomId || row[1].stringOrNull() != roomId ||
                row[4].stringOrNull() != request.termValue
            ) {
                return mismatch("返回详情与当前教室或学期不符，请重新查询")
            }
            if (!includesWeek(row[5], request.week) || !includesWeek(row[15], request.week)) {
                return mismatch("返回详情与当前周次不符，请重新查询")
            }
            val sections = row[6].stringOrNull()?.split(',') ?: emptyList()
            val originalPeriod = row[10].stringOrNull() == expectedPeriod &&
                sections.contains(request.dayOfWeek.toString() + rawSectionPrefix(request.rawSectionCode)) &&
                sections.contains(request.dayOfWeek.toString() + rawSectionSuffix(request.rawSectionCode))
            val shortEleventh = row[10].stringOrNull() == request.dayOfWeek.toString() + "11" ||
                row[10].integerOrNull() == request.dayOfWeek.toLong() * 100 + 11
            val singleEleventh = request.rawSectionCode == "1112" &&
                (row[10].stringOrNull() == expectedPeriod ||
                    row[10].stringOrNull() == request.dayOfWeek.toString() + "1111" ||
                    shortEleventh) &&
                sections.contains(request.dayOfWeek.toString() + "11")
            if (!originalPeriod && !singleEleventh) {
                return mismatch("返回详情与当前星期或节次不符，请重新查询")
            }
            val type = row[12].stringOrNull()
            if (type != "正常上课" && type != "借用") {
                return YguClassroomDetailResult(
                    status = YguClassroomDetailStatus.UNSUPPORTED,
                    message = "此时段的占用类型尚未支持课程详情",
                )
            }
            val textIndexes = if (type == "借用") {
                intArrayOf(13, 14, 17, 19, 21, 22, 23)
            } else {
                intArrayOf(13, 14, 21, 22, 23)
            }
            for (index in textIndexes) {
                if (row[index] !is JsonNull && row[index].stringOrNull() == null) {
                    return mismatch("学校返回的详情字段格式暂无法识别，请重试")
                }
            }
            val time = row[22].stringOrNull()
            if ((!originalPeriod && singleEleventh && time == null) ||
                (time != null && !containsTime(time, request.sectionStartTime, request.sectionEndTime))
            ) {
                return mismatch("返回详情时间未覆盖当前时段，请重新查询")
            }
            courses += ClassroomCourseInfo(
                kind = if (type == "借用") ClassroomOccupancyKind.BORROWED else ClassroomOccupancyKind.COURSE,
                courseName = row[13].stringOrNull().orEmpty(),
                roomName = row[14].stringOrNull().orEmpty(),
                teacherName = row[21].stringOrNull().orEmpty(),
                className = row[23].stringOrNull().orEmpty(),
                time = time.orEmpty(),
                applicant = if (type == "借用") row[19].stringOrNull().orEmpty() else "",
                remark = if (type == "借用") row[17].stringOrNull().orEmpty() else "",
            )
        }
        return if (courses.isEmpty()) {
            YguClassroomDetailResult(YguClassroomDetailStatus.EMPTY, MESSAGE_EMPTY)
        } else {
            YguClassroomDetailResult(YguClassroomDetailStatus.OK, courses = courses)
        }
    }

    private fun rawSectionPrefix(raw: String): String = raw.take(2)

    private fun rawSectionSuffix(raw: String): String = raw.drop(2)

    private fun includesWeek(raw: JsonElement, week: Int): Boolean {
        val value = raw.stringOrNull() ?: return false
        if (!WEEK.matches(value)) return false
        var includes = false
        for (token in value.split(',')) {
            if (token.isEmpty()) continue
            val bounds = token.split('-')
            val start = bounds[0].toLongOrNull() ?: return false
            val end = if (bounds.size == 2) bounds[1].toLongOrNull() ?: return false else start
            if (start < 1 || end < start || start > MAX_SAFE_INTEGER || end > MAX_SAFE_INTEGER) return false
            if (start <= week && week <= end) includes = true
        }
        return includes
    }

    private fun containsTime(value: String, start: String, end: String): Boolean {
        val range = value.split('-')
        if (range.size != 2 || !CLOCK.matches(range[0]) || !CLOCK.matches(range[1]) ||
            !CLOCK.matches(start) || !CLOCK.matches(end)
        ) {
            return false
        }
        return range[0] < range[1] && start < end && range[0] <= start && range[1] >= end
    }

    private fun JsonElement.stringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun JsonElement.integerOrNull(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString || !INTEGER.matches(primitive.content)) return null
        val value = primitive.content.toLongOrNull() ?: return null
        return value.takeIf { it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER }
    }

    private fun mismatch(message: String): YguClassroomDetailResult =
        YguClassroomDetailResult(YguClassroomDetailStatus.INVALID, message)

    private fun invalid(): YguClassroomDetailResult =
        YguClassroomDetailResult(YguClassroomDetailStatus.INVALID, MESSAGE_INVALID)
}
