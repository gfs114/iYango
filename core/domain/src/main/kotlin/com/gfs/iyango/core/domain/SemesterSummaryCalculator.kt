package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot

/** 单门课程在整学期的课时统计。 */
data class CourseLoadStat(
    val name: String,
    val sessions: Int,
    val sections: Int,
    val colorHex: String,
)

/** 学期统计摘要（供数据卡片页面与液态玻璃海报共用）。 */
data class SemesterSummaryReport(
    val semesterTitle: String,
    val totalWeeks: Int,
    val distinctCourseCount: Int,
    val totalSessions: Int,
    val totalSections: Int,
    val earlyEightDays: Int,
    val fullClassDays: Int,
    val maxSessionsInOneDay: Int,
    val busiestWeekdayIndex: Int,
    val busiestWeekdayLabel: String,
    val topBuilding: String,
    val topBuildingVisits: Int,
    val weekdaySessionCounts: List<Int>,
    val topCourses: List<CourseLoadStat>,
    val earnedCredits: Double? = null,
    val averageGpa: Double? = null,
    val weightedAverage: Double? = null,
    val gradeCourseCount: Int = 0,
)

/**
 * 学期数据统计计算器：基于本机课表、教学周规则与缓存成绩，统计整学期上课节奏与学分绩点。
 */
object SemesterSummaryCalculator {

    private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val BUILDING_BEFORE_ROOM_REGEX = Regex("^([\\u4e00-\\u9fa5A-Za-z0-9()（）]{1,32}(?:楼|馆|区|院|斋|园|堂|中心|场))[-\\s]+([A-Za-z0-9\\u4e00-\\u9fa5-]+)$")
    private val BUILDING_BEFORE_COMPACT_ROOM_REGEX = Regex("^([\\u4e00-\\u9fa5A-Za-z0-9()（）]{1,32}(?:楼|馆|区|院|斋|园|堂|中心|场))([A-Za-z]?\\d+[A-Za-z0-9\\u4e00-\\u9fa5-]*)$")
    private val BUILDING_BEFORE_DIGITS_REGEX = Regex("^([\\u4e00-\\u9fa5A-Za-z0-9()（）]{2,32}?)[-\\s]*[A-Za-z]?\\d+[A-Za-z0-9-]*$")
    private val SPLIT_SEPARATOR_REGEX = Regex("\\s+")
    private val TERM_CODE_REGEX = Regex("(\\d{4})\\s*[-–—~至]\\s*(\\d{4})\\D*([123一二三])")
    private val SEASON_TERM_REGEX = Regex("(\\d{4})\\s*年?\\s*(秋|春)")

    fun calculate(
        timetableName: String,
        semester: Semester?,
        courses: List<Course>,
        timeSlots: List<TimeSlot>,
        gradeRecords: List<GradeRecord> = emptyList(),
        schoolTerm: String = "",
    ): SemesterSummaryReport {
        val semesterTitle = semester?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: timetableName.trim().ifEmpty { "当前课表" }
        val totalWeeks = (semester?.totalWeeks ?: courses.maxOfOrNull { it.endWeek } ?: 20).coerceIn(1, 30)

        var totalSessions = 0
        var totalSections = 0
        var earlyEightDays = 0
        var fullClassDays = 0
        var maxSessionsInOneDay = 0

        val weekdaySessions = IntArray(7)
        val weekdaySections = IntArray(7)
        val distinctCourseNames = linkedSetOf<String>()
        val buildingVisits = linkedMapOf<String, Int>()
        val courseAccumulator = linkedMapOf<String, MutableCourseLoad>()

        for (week in 1..totalWeeks) {
            val weekCourses = CourseRules.forWeek(courses, week, semester)
                .filter { CourseRules.occursInWeek(it, week) && it.dayOfWeek in 1..7 && it.startSection >= 1 }
            if (weekCourses.isEmpty()) continue

            val byDay = weekCourses.groupBy { it.dayOfWeek }
            for ((day, dayCourses) in byDay) {
                val dayIndex = day - 1
                var daySections = 0
                var hasEarlyEight = false

                for (course in dayCourses) {
                    val span = (course.endSection - course.startSection + 1).coerceAtLeast(1)
                    totalSessions += 1
                    totalSections += span
                    daySections += span
                    weekdaySessions[dayIndex] += 1
                    weekdaySections[dayIndex] += span

                    val courseName = course.name.trim().ifEmpty { "未命名课程" }
                    distinctCourseNames += courseName
                    val acc = courseAccumulator.getOrPut(courseName) {
                        MutableCourseLoad(name = courseName, colorHex = course.color)
                    }
                    acc.sessions += 1
                    acc.sections += span

                    val range = CourseRules.timeRange(course, timeSlots)
                    val startMin = CourseRules.minutes(range.startTime)
                    if ((startMin != null && startMin <= 8 * 60 + 30) || (startMin == null && course.startSection == 1)) {
                        hasEarlyEight = true
                    }

                    val building = extractBuilding(course.classroom)
                    if (building.isNotEmpty()) {
                        buildingVisits[building] = (buildingVisits[building] ?: 0) + 1
                    }
                }

                if (dayCourses.size > maxSessionsInOneDay) {
                    maxSessionsInOneDay = dayCourses.size
                }
                if (hasEarlyEight) {
                    earlyEightDays += 1
                }
                if (dayCourses.size >= 4 || daySections >= 8) {
                    fullClassDays += 1
                }
            }
        }

        val busiestIdx = (0 until 7)
            .filter { weekdaySessions[it] > 0 }
            .maxWithOrNull(compareBy<Int> { weekdaySections[it] }.thenBy { weekdaySessions[it] }.thenByDescending { -it })
        val busiestWeekdayIndex = if (busiestIdx != null) busiestIdx + 1 else 0
        val busiestWeekdayLabel = if (busiestIdx != null) WEEKDAY_LABELS[busiestIdx] else "暂无"

        val topBuildingEntry = buildingVisits.entries
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })

        val topCourses = courseAccumulator.values
            .sortedWith(compareByDescending<MutableCourseLoad> { it.sections }.thenByDescending { it.sessions }.thenBy { it.name })
            .take(3)
            .map { CourseLoadStat(name = it.name, sessions = it.sessions, sections = it.sections, colorHex = it.colorHex) }

        val semesterGrades = filterGradesForSemester(
            gradeRecords = gradeRecords,
            schoolTerm,
            semester?.name,
            timetableName,
        )
        val gradeStats = if (semesterGrades.isNotEmpty()) {
            val primary = GradeStatisticsCalculator.calculate(semesterGrades, includeOptional = false)
            if (primary.includedCount > 0) primary else GradeStatisticsCalculator.calculate(semesterGrades, includeOptional = true)
        } else {
            null
        }

        return SemesterSummaryReport(
            semesterTitle = semesterTitle,
            totalWeeks = totalWeeks,
            distinctCourseCount = distinctCourseNames.size,
            totalSessions = totalSessions,
            totalSections = totalSections,
            earlyEightDays = earlyEightDays,
            fullClassDays = fullClassDays,
            maxSessionsInOneDay = maxSessionsInOneDay,
            busiestWeekdayIndex = busiestWeekdayIndex,
            busiestWeekdayLabel = busiestWeekdayLabel,
            topBuilding = topBuildingEntry?.key ?: "暂无记录",
            topBuildingVisits = topBuildingEntry?.value ?: 0,
            weekdaySessionCounts = weekdaySessions.toList(),
            topCourses = topCourses,
            earnedCredits = gradeStats?.takeIf { it.totalCredits > 0.0 }?.totalCredits,
            averageGpa = gradeStats?.averageGradePoint,
            weightedAverage = gradeStats?.weightedAverage,
            gradeCourseCount = gradeStats?.includedCount ?: 0,
        )
    }

    /** 从教室字符串提取完整教学楼名称（如 `"东区第一公共教学楼 A201"` -> `"东区第一公共教学楼"`）。 */
    fun extractBuilding(classroom: String): String {
        val cleaned = classroom.trim().removePrefix("@").trim()
        if (cleaned.isEmpty()) return ""
        BUILDING_BEFORE_ROOM_REGEX.matchEntire(cleaned)?.let { match ->
            return match.groupValues[1].trim()
        }
        BUILDING_BEFORE_COMPACT_ROOM_REGEX.matchEntire(cleaned)?.let { match ->
            return match.groupValues[1].trim()
        }
        BUILDING_BEFORE_DIGITS_REGEX.matchEntire(cleaned)?.let { match ->
            return match.groupValues[1].trim()
        }
        return cleaned.split(SPLIT_SEPARATOR_REGEX).firstOrNull()?.trim().orEmpty()
    }

    /** 将学期名称或编码归一化为标准学期键（如 `"2025年秋"` / `"2025-2026-1"` -> `"2025-2026-1"`）。 */
    fun normalizeSemesterKey(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        TERM_CODE_REGEX.find(text)?.let { match ->
            val y1 = match.groupValues[1]
            val y2 = match.groupValues[2]
            val term = when (match.groupValues[3]) {
                "一", "1" -> "1"
                "二", "2" -> "2"
                "三", "3" -> "3"
                else -> match.groupValues[3]
            }
            return "$y1-$y2-$term"
        }
        SEASON_TERM_REGEX.find(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull()
            if (year != null) {
                return if (match.groupValues[2] == "秋") {
                    "$year-${year + 1}-1"
                } else {
                    "${year - 1}-$year-2"
                }
            }
        }
        return text
    }

    /** 仅保留属于当前所选学期的成绩记录；当全部测试桩记录未标注学期时保持原集合兼容。 */
    fun filterGradesForSemester(
        gradeRecords: List<GradeRecord>,
        vararg semesterCandidates: String?,
    ): List<GradeRecord> {
        if (gradeRecords.isEmpty()) return emptyList()
        if (gradeRecords.all { it.semester.isBlank() }) return gradeRecords
        val targetKeys = semesterCandidates
            .mapNotNull { candidate -> candidate?.trim()?.takeIf { it.isNotEmpty() } }
            .flatMap { candidate ->
                listOf(candidate, normalizeSemesterKey(candidate)).filter { it.isNotEmpty() }
            }
            .toSet()
        if (targetKeys.isEmpty()) return emptyList()
        return gradeRecords.filter { record ->
            val sem = record.semester.trim()
            sem.isNotEmpty() && (sem in targetKeys || normalizeSemesterKey(sem) in targetKeys)
        }
    }

    private data class MutableCourseLoad(
        val name: String,
        val colorHex: String,
        var sessions: Int = 0,
        var sections: Int = 0,
    )
}
