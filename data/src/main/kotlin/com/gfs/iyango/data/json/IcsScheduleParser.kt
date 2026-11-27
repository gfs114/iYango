package com.gfs.iyango.data.json

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.WeekType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * RFC 5545 `.ics` 日历文件解析器。
 *
 * 从 `VEVENT` 提取课程名、地点、备注、起止时间及 `RRULE`（`FREQ=WEEKLY` / `INTERVAL` / `COUNT` / `UNTIL` / `BYDAY`），
 * 自动推算学期首周一与节次时间表，输出标准 [ScheduleJsonParser.ParseResult]。
 */
object IcsScheduleParser {

    private data class RawEvent(
        val summary: String,
        val location: String,
        val description: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val freqWeekly: Boolean,
        val interval: Int,
        val count: Int?,
        val until: LocalDate?,
        val byDays: List<Int>,
    )

    private val basicDateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val shortDateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")
    private val basicDate = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val hmFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun parse(text: String): ScheduleJsonParser.ParseResult {
        val issues = mutableListOf<ImportIssue>()
        val clean = text.removePrefix("\uFEFF")
        val unfolded = clean
            .replace("\r\n ", "")
            .replace("\r\n\t", "")
            .replace("\n ", "")
            .replace("\n\t", "")
        val lines = unfolded.split(Regex("\r?\n"))

        val events = mutableListOf<RawEvent>()
        var inEvent = false
        var summary = ""
        var location = ""
        var description = ""
        var dtStartRaw: String? = null
        var dtEndRaw: String? = null
        var rruleRaw: String? = null

        fun flushEvent(index: Int) {
            val name = unescapeIcs(summary).trim()
            if (name.isEmpty()) {
                issues += ImportIssue("第 $index 个日程缺少课程名称，已跳过")
                return
            }
            val start = dtStartRaw?.let(::parseIcsDateTime)
            val end = dtEndRaw?.let(::parseIcsDateTime)
            if (start == null || end == null || !end.isAfter(start) || start.toLocalDate() != end.toLocalDate()) {
                issues += ImportIssue("日程「$name」缺少有效当日时间，已跳过")
                return
            }
            val rule = parseRRule(rruleRaw.orEmpty())
            val teacher = extractTeacher(unescapeIcs(description))
            val cleanDesc = unescapeIcs(description).trim().take(200)
            events += RawEvent(
                summary = name,
                location = unescapeIcs(location).trim(),
                description = if (teacher.isNotEmpty()) teacher else cleanDesc,
                start = start,
                end = end,
                freqWeekly = rule.freqWeekly,
                interval = rule.interval,
                count = rule.count,
                until = rule.until,
                byDays = rule.byDays,
            )
        }

        var eventCounter = 0
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    inEvent = true
                    eventCounter += 1
                    summary = ""
                    location = ""
                    description = ""
                    dtStartRaw = null
                    dtEndRaw = null
                    rruleRaw = null
                }
                line.equals("END:VEVENT", ignoreCase = true) -> {
                    if (inEvent) flushEvent(eventCounter)
                    inEvent = false
                }
                inEvent -> {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val propPart = line.substring(0, colon)
                    val value = line.substring(colon + 1)
                    val propName = propPart.substringBefore(';').uppercase()
                    when (propName) {
                        "SUMMARY" -> summary = value
                        "LOCATION" -> location = value
                        "DESCRIPTION" -> description = value
                        "DTSTART" -> dtStartRaw = value.trim()
                        "DTEND" -> dtEndRaw = value.trim()
                        "RRULE" -> rruleRaw = value.trim()
                    }
                }
            }
        }

        if (events.isEmpty()) {
            issues += ImportIssue("ICS 文件中没有可用课程日程")
            return ScheduleJsonParser.ParseResult(null, issues)
        }

        val earliestMonday = events.minOf { it.start.toLocalDate() }
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        // 按实际时钟区间聚类节次时间表（升序最多 20 节）
        val slotPairs = events
            .map { it.start.toLocalTime().format(hmFormatter) to it.end.toLocalTime().format(hmFormatter) }
            .distinct()
            .sortedBy { it.first }
            .take(ScheduleJsonParser.MAX_SECTION)

        val timeSlots = slotPairs.mapIndexed { idx, (startHm, endHm) ->
            ScheduleJsonTimeSlot(
                section = idx + 1,
                startTime = startHm,
                endTime = endHm,
            )
        }
        val slotIndexByRange = slotPairs.withIndex().associate { (idx, pair) -> pair to (idx + 1) }

        val courses = mutableListOf<ScheduleJsonCourse>()
        var maxWeekSeen = 1

        events.forEachIndexed { idx, event ->
            val startHm = event.start.toLocalTime().format(hmFormatter)
            val endHm = event.end.toLocalTime().format(hmFormatter)
            val section = slotIndexByRange[startHm to endHm] ?: 1

            val firstWeek = (ChronoUnit.DAYS.between(earliestMonday, event.start.toLocalDate()) / 7L).toInt() + 1
            val startWeek = firstWeek.coerceIn(1, ScheduleJsonParser.MAX_WEEK)
            val endWeek = when {
                !event.freqWeekly -> startWeek
                event.count != null -> (startWeek + (event.count - 1) * event.interval)
                    .coerceIn(startWeek, ScheduleJsonParser.MAX_WEEK)
                event.until != null -> {
                    val uWeek = (ChronoUnit.DAYS.between(earliestMonday, event.until) / 7L).toInt() + 1
                    uWeek.coerceIn(startWeek, ScheduleJsonParser.MAX_WEEK)
                }
                else -> (startWeek + 15).coerceAtMost(ScheduleJsonParser.MAX_WEEK)
            }
            maxWeekSeen = maxOf(maxWeekSeen, endWeek)

            val weekType = when {
                event.interval == 2 && startWeek % 2 == 1 -> WeekType.ODD.code
                event.interval == 2 && startWeek % 2 == 0 -> WeekType.EVEN.code
                else -> WeekType.EVERY.code
            }
            val days = if (event.byDays.isNotEmpty() && event.freqWeekly) {
                event.byDays
            } else {
                listOf(event.start.dayOfWeek.value)
            }

            days.forEachIndexed { dayIdx, dayOfWeek ->
                val item = ScheduleJsonCourse(
                    id = (courses.size + 1).toLong(),
                    name = event.summary,
                    classroom = event.location,
                    remark = if (idx >= 0 && dayIdx >= 0) event.description else "",
                    dayOfWeek = dayOfWeek,
                    startSection = section,
                    endSection = section,
                    startWeek = startWeek,
                    endWeek = endWeek,
                    weekType = weekType,
                    timeMode = 1,
                    startTime = startHm,
                    endTime = endHm,
                )
                val check = Course(
                    name = item.name,
                    classroom = item.classroom,
                    remark = item.remark,
                    dayOfWeek = item.dayOfWeek,
                    startSection = item.startSection,
                    endSection = item.endSection,
                    startWeek = item.startWeek,
                    endWeek = item.endWeek,
                    weekType = WeekType.entries.first { it.code == item.weekType },
                    timeMode = item.timeMode,
                    startTime = item.startTime,
                    endTime = item.endTime,
                )
                val error = CourseValidator.validate(check)
                if (error != null) {
                    issues += ImportIssue("已跳过「${item.name}」", error)
                } else {
                    courses += item
                }
            }
        }

        if (courses.isEmpty()) {
            issues += ImportIssue("ICS 文件中没有校验通过的课程")
            return ScheduleJsonParser.ParseResult(null, issues)
        }

        val doc = ScheduleJsonDocument(
            courses = courses,
            semester = ScheduleJsonSemester(
                name = "${earliestMonday.year} ICS 导入学期",
                startDate = earliestMonday.toString(),
                totalWeeks = maxWeekSeen.coerceIn(1, ScheduleJsonParser.MAX_WEEK),
            ),
            timeSlots = timeSlots,
        )
        return ScheduleJsonParser.ParseResult(doc, issues)
    }

    private data class ParsedRRule(
        val freqWeekly: Boolean = false,
        val interval: Int = 1,
        val count: Int? = null,
        val until: LocalDate? = null,
        val byDays: List<Int> = emptyList(),
    )

    private fun parseRRule(raw: String): ParsedRRule {
        if (raw.isBlank()) return ParsedRRule()
        var freqWeekly = false
        var interval = 1
        var count: Int? = null
        var until: LocalDate? = null
        var byDays: List<Int> = emptyList()

        raw.split(';').forEach { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            val key = part.substring(0, eq).trim().uppercase()
            val value = part.substring(eq + 1).trim().uppercase()
            when (key) {
                "FREQ" -> freqWeekly = value == "WEEKLY"
                "INTERVAL" -> interval = value.toIntOrNull()?.coerceAtLeast(1) ?: 1
                "COUNT" -> count = value.toIntOrNull()?.takeIf { it > 0 }
                "UNTIL" -> until = parseIcsDateTime(value)?.toLocalDate()
                    ?: runCatching { LocalDate.parse(value.take(8), basicDate) }.getOrNull()
                "BYDAY" -> byDays = value.split(',').mapNotNull { token ->
                    when (token.takeLast(2)) {
                        "MO" -> 1
                        "TU" -> 2
                        "WE" -> 3
                        "TH" -> 4
                        "FR" -> 5
                        "SA" -> 6
                        "SU" -> 7
                        else -> null
                    }
                }.distinct()
            }
        }
        return ParsedRRule(
            freqWeekly = freqWeekly,
            interval = interval,
            count = count,
            until = until,
            byDays = byDays,
        )
    }

    private fun parseIcsDateTime(raw: String): LocalDateTime? {
        val text = raw.trim()
        val isUtc = text.endsWith("Z", ignoreCase = true)
        val clean = if (isUtc) text.dropLast(1) else text
        val parsed = runCatching { LocalDateTime.parse(clean, basicDateTime) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(clean, shortDateTime) }.getOrNull()
            ?: return null
        return if (isUtc) {
            parsed.atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        } else {
            parsed
        }
    }

    private fun extractTeacher(desc: String): String {
        val match = Regex("""(?:教师|老师|Teacher)\s*[:：]\s*([^\n,;]+)""", RegexOption.IGNORE_CASE).find(desc)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun unescapeIcs(value: String): String =
        value.replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
}
