package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.ClassroomCell
import com.gfs.iyango.core.model.ClassroomCellState
import com.gfs.iyango.core.model.ClassroomPeriodGroup
import com.gfs.iyango.core.model.ClassroomRoom
import com.gfs.iyango.core.model.EleventhSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * jsjy_query2 响应解析器，行为对齐 HarmonyOS 的 `YguClassroomParser`。
 *
 * 外层数组第 0 项是节次组、第 4 项是教室行；教室行只承认
 * 「名称 + 7×组数 状态格 + 尾部三项」这一种已验证布局。任何长度、
 * 类型或节次组异常都整包判不可读，不按假设补映射。
 */
internal object YguClassroomParser {

    private const val MAX_BODY_LENGTH = 1_048_576
    private const val MIN_OUTER_LENGTH = 5
    private const val MAX_GROUPS = 24
    private const val MAX_ROWS = 1_000
    private const val DAY_COUNT = 7
    private const val ROW_NAME_COUNT = 1
    private const val ROW_TAIL_COUNT = 3
    private const val MARKER_CLASS = "shangke"
    private const val MARKER_BORROW = "jieyong"
    private const val MESSAGE_INVALID = "暂时无法识别教务返回的数据"
    private const val MESSAGE_LOGIN = "登录状态已失效，请返回教务登录后重试"
    private const val MESSAGE_EMPTY = "所选条件下没有符合要求的教室"

    private val CLOCK = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val TWO_DIGITS = Regex("^\\d{2}$")
    private val NUMBER = Regex("^-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$")

    fun parse(body: String): YguClassroomParseResult {
        if (body.isEmpty() || body.length > MAX_BODY_LENGTH) return invalid()
        val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        val outer = parsed as? JsonArray
        if (outer == null) {
            return if (YguSession.isLoginPage(body)) {
                YguClassroomParseResult(YguClassroomParseStatus.LOGIN, MESSAGE_LOGIN)
            } else {
                invalid()
            }
        }
        if (outer.size < MIN_OUTER_LENGTH) return invalid()
        val groups = readGroups(outer[0]) ?: return invalid()
        val rowItems = outer[4] as? JsonArray ?: return invalid()
        if (rowItems.size > MAX_ROWS) return invalid()
        if (rowItems.isEmpty()) {
            return YguClassroomParseResult(
                status = YguClassroomParseStatus.EMPTY,
                message = MESSAGE_EMPTY,
                periodGroups = groups,
            )
        }
        val rooms = readRows(rowItems, groups.size) ?: return invalid()
        return YguClassroomParseResult(
            status = YguClassroomParseStatus.OK,
            periodGroups = groups,
            rooms = rooms,
        )
    }

    private fun readGroups(raw: JsonElement): List<ClassroomPeriodGroup>? {
        val items = raw as? JsonArray ?: return null
        if (items.isEmpty() || items.size > MAX_GROUPS) return null
        val groups = mutableListOf<ClassroomPeriodGroup>()
        for (item in items) {
            val group = when (item) {
                is JsonArray -> readLegacyGroup(item) ?: return null
                is JsonObject -> readObjectGroup(item) ?: return null
                else -> return null
            }
            groups += group
        }
        if (groups.map { it.rawCode }.toSet().size != groups.size) return null
        for (index in 1 until groups.size) {
            if (groups[index].startTime < groups[index - 1].startTime) return null
        }
        return groups.map { group ->
            val sourceStart = group.startTime
            val sourceEnd = group.endTime
            if (EleventhSection.isExact(group.rawCode) || EleventhSection.matchesLabel(group.label)) {
                group.copy(
                    label = EleventhSection.LABEL,
                    startTime = EleventhSection.START_TIME,
                    endTime = EleventhSection.END_TIME,
                    sourceStartTime = sourceStart,
                    sourceEndTime = sourceEnd,
                )
            } else {
                group.copy(sourceStartTime = sourceStart, sourceEndTime = sourceEnd)
            }
        }
    }

    private fun readLegacyGroup(parts: JsonArray): ClassroomPeriodGroup? {
        if (parts.size != 4) return null
        val code = readGroupCode(parts[0]) ?: return null
        val label = readGroupLabel(parts[1]) ?: return null
        val start = readGroupClock(parts[2]) ?: return null
        val end = readGroupClock(parts[3]) ?: return null
        return ClassroomPeriodGroup(
            rawCode = code,
            label = label,
            startTime = start,
            endTime = end,
        )
    }

    private fun readObjectGroup(item: JsonObject): ClassroomPeriodGroup? {
        val schoolShape = item.containsKey("jcb1") || item.containsKey("jcb2") ||
            item.containsKey("djmc")
        if (!schoolShape && item.size != 4) return null
        val code = if (schoolShape) readSchoolGroupCode(item) else readGroupCode(item["code"])
        val label = readGroupLabel(item[if (schoolShape) "djmc" else "name"])
        val start = readGroupClock(item[if (schoolShape) "kssj" else "start"])
        val end = readGroupClock(item[if (schoolShape) "jssj" else "end"])
        if (code == null || label == null || start == null || end == null) return null
        return ClassroomPeriodGroup(
            rawCode = code,
            label = label,
            startTime = start,
            endTime = end,
        )
    }

    private fun readSchoolGroupCode(record: JsonObject): String? {
        val first = record["jcb1"].stringOrNull() ?: return null
        val last = record["jcb2"].stringOrNull() ?: return null
        if (!TWO_DIGITS.matches(first) || !TWO_DIGITS.matches(last)) return null
        return first + last
    }

    private fun readGroupCode(raw: JsonElement?): String? {
        val primitive = raw as? JsonPrimitive ?: return null
        if (primitive.isString) {
            return primitive.content.trim().takeIf { it.isNotEmpty() }
        }
        if (primitive is JsonNull) return null
        val content = primitive.content
        if (!NUMBER.matches(content)) return null
        return content.takeIf { content.toDoubleOrNull()?.isFinite() == true }
    }

    private fun readGroupLabel(raw: JsonElement?): String? {
        val primitive = raw as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.trim().takeIf { it.isNotEmpty() }
    }

    private fun readGroupClock(raw: JsonElement?): String? {
        val primitive = raw as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.takeIf { CLOCK.matches(it) }
    }

    private fun readRows(rowItems: JsonArray, groupCount: Int): List<ClassroomRoom>? {
        val expectedLength = DAY_COUNT * groupCount + ROW_NAME_COUNT + ROW_TAIL_COUNT
        val intervals = DAY_COUNT * groupCount
        val rooms = mutableListOf<ClassroomRoom>()
        for (rowValue in rowItems) {
            val parts = rowValue as? JsonArray ?: return null
            if (parts.size != expectedLength) return null
            val name = parts[0].stringOrNull()?.trim() ?: return null
            if (name.isEmpty()) return null
            val cells = mutableListOf<ClassroomCell>()
            for (day in 1..DAY_COUNT) {
                for (groupIndex in 0 until groupCount) {
                    val state = readCellState(parts[(day - 1) * groupCount + groupIndex + 1]) ?: return null
                    cells += ClassroomCell(dayOfWeek = day, groupIndex = groupIndex, state = state)
                }
            }
            val identifier = readTailValue(parts[intervals + 1]) ?: return null
            val seatInfo = readTailValue(parts[intervals + 2]) ?: return null
            val roomType = readTailValue(parts[intervals + 3]) ?: return null
            rooms += ClassroomRoom(
                name = name,
                identifier = identifier,
                seatInfo = seatInfo,
                roomType = roomType,
                cells = cells,
            )
        }
        return rooms
    }

    private fun readCellState(raw: JsonElement): ClassroomCellState? = when {
        raw is JsonNull -> ClassroomCellState.FREE
        raw is JsonPrimitive && raw.isString -> when {
            raw.content.contains(MARKER_CLASS) -> ClassroomCellState.COURSE
            raw.content.contains(MARKER_BORROW) -> ClassroomCellState.BORROWED
            else -> ClassroomCellState.UNKNOWN
        }
        else -> null
    }

    private fun readTailValue(raw: JsonElement): String? = when {
        raw is JsonNull -> ""
        raw is JsonPrimitive && raw.isString -> YguHtml.decode(raw.content)
        else -> null
    }

    private fun invalid(): YguClassroomParseResult =
        YguClassroomParseResult(YguClassroomParseStatus.INVALID, MESSAGE_INVALID)

    private fun JsonElement?.stringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
