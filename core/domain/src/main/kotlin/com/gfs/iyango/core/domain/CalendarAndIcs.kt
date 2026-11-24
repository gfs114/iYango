package com.gfs.iyango.core.domain

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 一条系统日历事件计划。 */
data class CalendarEventPlan(
    val occurrenceId: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val startAtMillis: Long = 0,
    val endAtMillis: Long = 0,
    val timeZone: String = "Asia/Shanghai",
    val payloadHash: String = "",
    val planRevision: Int = 1,
)

/**
 * 日历事件规划（F13）：与提醒共用实例集合，但独立生成。
 *
 * - 开始时间未知或结束时间无效的实例跳过并记录原因；
 * - 事件正文包含课程、教师、教室；
 * - [payloadHash] 用于判断是否需要更新系统事件，避免不断追加。
 */
object CalendarEventPlanner {

    fun plan(
        occurrences: List<CourseOccurrence>,
        zoneId: ZoneId,
        planRevision: Int = 1,
    ): Pair<List<CalendarEventPlan>, List<String>> {
        val events = mutableListOf<CalendarEventPlan>()
        val skipped = mutableListOf<String>()
        occurrences.forEach { occurrence ->
            val start = occurrence.startMinute
            val end = occurrence.endMinute
            if (start == null || end == null) {
                skipped += "${occurrence.course.name}：时间待定，不写入日历"
                return@forEach
            }
            if (end <= start) {
                skipped += "${occurrence.course.name}：结束时间不晚于开始时间"
                return@forEach
            }
            val description = buildString {
                append(occurrence.course.name)
                if (occurrence.course.teacher.isNotBlank()) append("\n教师：").append(occurrence.course.teacher)
                if (occurrence.course.classroom.isNotBlank()) append("\n教室：").append(occurrence.course.classroom)
                if (occurrence.isMoved) append("\n（由调课产生）")
                if (occurrence.isTemporary) append("\n（临时整天课表）")
            }
            val startAt = occurrence.date.atStartOfDay().plusMinutes(start.toLong())
            val endAt = occurrence.date.atStartOfDay().plusMinutes(end.toLong())
            events += CalendarEventPlan(
                occurrenceId = occurrence.occurrenceId,
                title = occurrence.course.name,
                description = description,
                location = occurrence.course.classroom,
                startAtMillis = startAt.atZone(zoneId).toInstant().toEpochMilli(),
                endAtMillis = endAt.atZone(zoneId).toInstant().toEpochMilli(),
                timeZone = zoneId.id,
                payloadHash = hash("${occurrence.occurrenceId}|$startAt|$endAt|$description"),
                planRevision = planRevision,
            )
        }
        return events.sortedBy { it.startAtMillis } to skipped.distinct()
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(16)
}

/**
 * 课表 ICS 导出（E04）。
 *
 * 与 F13 写入系统日历是两种独立能力；本类只生成文件内容。
 *
 * 规则：
 * - 每个实例展开为独立 VEVENT（不生成无限重复规则），单双周/停课/调课直接对应；
 * - 时间待定或结束时间无效的实例跳过并返回原因；
 * - UID 由稳定命名空间 + occurrenceId 组成，多次导出同一事件保持稳定；
 * - 本地时刻按 [zoneId] 转为 UTC（Z）输出；
 * - 文本转义、CRLF 与长行折叠按 RFC 5545；折叠按 UTF-8 字节，不拆坏中文字符。
 */
object IcsExporter {

    private val utcFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC)

    data class Result(
        val content: String = "",
        val eventCount: Int = 0,
        val skipped: List<String> = emptyList(),
    )

    /**
     * @param namespace 稳定的导出命名空间（例如安装实例 id）；不能每次随机生成。
     * @param includeReminder 是否附加 VALARM（默认不附加，避免与应用通知重复）。
     */
    fun export(
        occurrences: List<CourseOccurrence>,
        zoneId: ZoneId,
        namespace: String,
        now: LocalDateTime = LocalDateTime.now(zoneId),
        includeReminder: Boolean = false,
        reminderMinutes: Int = 10,
    ): Result {
        val skipped = mutableListOf<String>()
        val events = mutableListOf<String>()
        val stamp = utcFormat.format(now.atZone(zoneId).toInstant())
        occurrences.forEach { occurrence ->
            val startMinute = occurrence.startMinute
            val endMinute = occurrence.endMinute
            if (startMinute == null || endMinute == null) {
                skipped += "${occurrence.course.name}：时间待定，跳过"
                return@forEach
            }
            if (endMinute <= startMinute) {
                skipped += "${occurrence.course.name}：结束时间不晚于开始时间，跳过"
                return@forEach
            }
            val startAt = occurrence.date.atStartOfDay().plusMinutes(startMinute.toLong())
            val endAt = occurrence.date.atStartOfDay().plusMinutes(endMinute.toLong())
            val startUtc = utcFormat.format(startAt.atZone(zoneId).toInstant())
            val endUtc = utcFormat.format(endAt.atZone(zoneId).toInstant())
            val summary = occurrence.course.name
            val description = buildString {
                append(occurrence.course.name)
                if (occurrence.course.teacher.isNotBlank()) append("\\n教师：").append(occurrence.course.teacher)
                if (occurrence.course.classroom.isNotBlank()) append("\\n教室：").append(occurrence.course.classroom)
                if (occurrence.isMoved) append("\\n（由调课产生）")
                if (occurrence.isTemporary) append("\\n（临时整天课表）")
            }
            events += buildString {
                append("BEGIN:VEVENT\r\n")
                append("UID:").append(occurrence.occurrenceId).append("@").append(namespace)
                    .append("\r\n")
                append("DTSTAMP:").append(stamp).append("\r\n")
                append("DTSTART:").append(startUtc).append("\r\n")
                append("DTEND:").append(endUtc).append("\r\n")
                append("SUMMARY:").append(escape(summary)).append("\r\n")
                if (occurrence.course.classroom.isNotBlank()) {
                    append("LOCATION:").append(escape(occurrence.course.classroom)).append("\r\n")
                }
                append("DESCRIPTION:").append(escape(description.replace("\\n", "\n"))).append("\r\n")
                if (includeReminder) {
                    append("BEGIN:VALARM\r\n")
                    append("ACTION:DISPLAY\r\n")
                    append("DESCRIPTION:").append(escape(summary)).append("\r\n")
                    append("TRIGGER:-PT").append(reminderMinutes.coerceAtLeast(0)).append("M\r\n")
                    append("END:VALARM\r\n")
                }
                append("END:VEVENT\r\n")
            }
        }
        val content = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("VERSION:2.0\r\n")
            append("PRODID:-//iYango//ClassSchedule//ZH-CN\r\n")
            append("CALSCALE:GREGORIAN\r\n")
            append(events.joinToString(separator = ""))
            append("END:VCALENDAR\r\n")
        }
        return Result(
            content = content.split("\r\n").joinToString("\r\n") { fold(it) },
            eventCount = events.size,
            skipped = skipped.distinct(),
        )
    }

    /** RFC 5545 文本转义。 */
    fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    /**
     * 按 75 字节折叠一行；续行以单个空格开头。
     *
     * 使用 UTF-8 字节数而非字符数，且只在字符边界切分，避免把中文拆坏。
     */
    fun fold(line: String, limit: Int = 75): String {
        if (line.toByteArray(Charsets.UTF_8).size <= limit) return line
        val builder = StringBuilder()
        var current = StringBuilder()
        var currentBytes = 0
        var first = true
        line.forEach { ch ->
            val bytes = ch.toString().toByteArray(Charsets.UTF_8).size
            val capacity = if (first) limit else limit - 1
            if (currentBytes + bytes > capacity) {
                builder.append(current)
                builder.append("\r\n ")
                current = StringBuilder()
                currentBytes = 0
                first = false
            }
            current.append(ch)
            currentBytes += bytes
        }
        builder.append(current)
        return builder.toString()
    }
}
