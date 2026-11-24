package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.domain.TimeRailRow
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.SchoolCourseData
import com.gfs.iyango.core.model.TimeSlot

/**
 * 阳光学院（福州校本部）官方上课作息，逐条移植自 HarmonyOS 的 `YguClassTimes`。
 *
 * 数据依据：
 * - 2026-2027-1（南区早作息）：第 1、2、5-11 节两校区相同；
 *   南区 3 节 10:05-10:50、4 节 10:55-11:40，北区 3 节 10:20-11:05、4 节 11:15-12:00；
 * - 2025-2026-2（北区早作息）：南北与上一学期互换。
 *
 * 只对教务同步（YGU 来源）且学期精确命中的课表套用；未知校区、南北冲突、
 * 越界节次返回空时间并标记待确认，绝不默认某一校区。
 */
internal object YguClassTimes {

    const val TERM_2026_2027_1: String = "2026-2027-1"
    const val TERM_2025_2026_2: String = "2025-2026-2"

    const val CAMPUS_SOUTH: String = "S"
    const val CAMPUS_NORTH: String = "N"
    const val CAMPUS_UNKNOWN: String = ""
    /** 南北作息并存（或含未知校区）：差异节并列两套时间。 */
    const val CAMPUS_MIXED: String = "MIXED"

    data class CourseTimeRange(
        val startTime: String,
        val endTime: String,
        val confirmed: Boolean,
    )

    private data class SplitSlot(val start: String, val end: String)

    private val COMMON_START = mapOf(
        1 to "08:10", 2 to "09:00", 5 to "14:00", 6 to "14:50", 7 to "15:55", 8 to "16:45",
        9 to "18:30", 10 to "19:20", 11 to "20:10",
    )
    private val COMMON_END = mapOf(
        1 to "08:55", 2 to "09:45", 5 to "14:45", 6 to "15:35", 7 to "16:40", 8 to "17:30",
        9 to "19:15", 10 to "20:05", 11 to "20:55",
    )

    private val SPLIT_TIMES: Map<String, Map<String, Map<Int, SplitSlot>>> = mapOf(
        TERM_2026_2027_1 to mapOf(
            CAMPUS_SOUTH to mapOf(
                3 to SplitSlot("10:05", "10:50"),
                4 to SplitSlot("10:55", "11:40"),
            ),
            CAMPUS_NORTH to mapOf(
                3 to SplitSlot("10:20", "11:05"),
                4 to SplitSlot("11:15", "12:00"),
            ),
        ),
        TERM_2025_2026_2 to mapOf(
            CAMPUS_SOUTH to mapOf(
                3 to SplitSlot("10:20", "11:05"),
                4 to SplitSlot("11:15", "12:00"),
            ),
            CAMPUS_NORTH to mapOf(
                3 to SplitSlot("10:05", "10:50"),
                4 to SplitSlot("10:55", "11:40"),
            ),
        ),
    )

    private val SOUTH_BUILDINGS = listOf("商德楼", "博文楼", "雅颂楼", "正德楼", "操场")
    private val NORTH_BUILDINGS = listOf("知行楼", "载德楼", "润知楼", "启智楼", "致远楼")

    /** 第 3、4 节是两校区作息唯一不同的节次。 */
    fun isSplitSection(section: Int): Boolean = section == 3 || section == 4

    /** 学期版本：只认 YGU 来源且精确命中的两个已知学期。 */
    fun versionOf(schoolTerm: String, source: String): String {
        if (source != "YGU") return ""
        val term = schoolTerm.trim()
        return if (term == TERM_2026_2027_1 || term == TERM_2025_2026_2) term else ""
    }

    fun versionLabel(version: String): String = when (version) {
        TERM_2026_2027_1 -> "阳光学院 2026-2027-1 作息表"
        TERM_2025_2026_2 -> "阳光学院 2025-2026-2 作息表"
        else -> ""
    }

    fun campusName(campus: String): String = when (campus) {
        CAMPUS_SOUTH -> "南区"
        CAMPUS_NORTH -> "北区"
        else -> ""
    }

    private fun normalized(classroom: String): String = classroom
        .replace(Regex("\\s+"), "")
        .replace("（", "(")
        .replace("）", ")")
        .trim()

    /** 按教室命中的楼名判断校区；南北同时命中视为冲突（未知）。 */
    fun campusOf(classroom: String, version: String): String {
        val text = normalized(classroom)
        if (text.isEmpty()) return CAMPUS_UNKNOWN
        var hit = CAMPUS_UNKNOWN
        for (building in SOUTH_BUILDINGS) {
            if (text.contains(building)) {
                if (hit.isNotEmpty() && hit != CAMPUS_SOUTH) return CAMPUS_UNKNOWN
                hit = CAMPUS_SOUTH
            }
        }
        for (building in NORTH_BUILDINGS) {
            if (text.contains(building)) {
                if (hit.isNotEmpty() && hit != CAMPUS_NORTH) return CAMPUS_UNKNOWN
                hit = CAMPUS_NORTH
            }
        }
        return hit
    }

    fun sectionStart(version: String, campus: String, section: Int): String {
        if (version !in SPLIT_TIMES) return ""
        splitSlot(version, campus, section)?.let { return it.start }
        return if (section == 3 || section == 4) "" else COMMON_START[section].orEmpty()
    }

    fun sectionEnd(version: String, campus: String, section: Int): String {
        if (version !in SPLIT_TIMES) return ""
        splitSlot(version, campus, section)?.let { return it.end }
        return if (section == 3 || section == 4) "" else COMMON_END[section].orEmpty()
    }

    private fun splitSlot(version: String, campus: String, section: Int): SplitSlot? =
        SPLIT_TIMES[version]?.get(campus)?.get(section)

    /**
     * 一门连续节次课程的实际起止；任一涉及节次无法确定时整体留空并标记未确认。
     */
    fun courseRange(version: String, campus: String, startSection: Int, endSection: Int): CourseTimeRange {
        if (startSection < 1 || endSection < startSection) {
            return CourseTimeRange("", "", false)
        }
        if (version !in SPLIT_TIMES) return CourseTimeRange("", "", false)
        for (section in startSection..endSection) {
            if (sectionStart(version, campus, section).isEmpty() ||
                sectionEnd(version, campus, section).isEmpty()
            ) {
                return CourseTimeRange("", "", false)
            }
        }
        return CourseTimeRange(
            startTime = sectionStart(version, campus, startSection),
            endTime = sectionEnd(version, campus, endSection),
            confirmed = true,
        )
    }

    /**
     * 生成固定 11 节展示网格：已知版本使用官方时间；
     * [campus] 为 [CAMPUS_UNKNOWN] 或 `MIXED` 时第 3、4 节留空。
     */
    fun slotsFor(version: String, campus: String): List<TimeSlot> {
        if (version !in SPLIT_TIMES) return emptyList()
        return (1..11).map { section ->
            TimeSlot(
                section = section,
                startTime = sectionStart(version, campus, section),
                endTime = sectionEnd(version, campus, section),
                name = "第${section}节",
                kind = 0,
            )
        }
    }

    /**
     * 课表的时间轴形态：`S`/`N` 表示所有涉及第 3/4 节的课程在同一校区；
     * `MIXED` 表示南北并存或含未知校区；`""` 表示与官方作息无关。
     */
    fun campusModeOf(version: String, courses: List<SchoolCourseData>): String {
        if (version.isEmpty()) return ""
        var mode = ""
        for (course in courses) {
            if (!touchesSplit(course)) continue
            val campus = campusOf(course.classroom, version)
            if (campus == CAMPUS_UNKNOWN) return "MIXED"
            if (mode.isEmpty()) {
                mode = campus
            } else if (mode != campus) {
                return "MIXED"
            }
        }
        return mode.ifEmpty { "MIXED" }
    }

    fun campusModeOfCourses(version: String, courses: List<Course>): String {
        if (version.isEmpty()) return ""
        var mode = ""
        for (course in courses) {
            if (course.timeMode != 0 || course.endSection < 3 || course.startSection > 4) continue
            val campus = campusOf(course.classroom, version)
            if (campus == CAMPUS_UNKNOWN) return "MIXED"
            if (mode.isEmpty()) {
                mode = campus
            } else if (mode != campus) {
                return "MIXED"
            }
        }
        return mode.ifEmpty { "MIXED" }
    }

    private fun touchesSplit(course: SchoolCourseData): Boolean =
        course.endSection >= 3 && course.startSection <= 4

    /**
     * 时间轴行：节名取 [slots] 的节名（"第N节"归一为 N），钟点取该节起止。
     *
     * [mixed] = true 时第 3/4 节不沿用空白的持久化钟点，改为并列南北开始时间；
     * 其余节次与单校区课表一律用 [slots] 原值。
     */
    fun railRows(
        version: String,
        mixed: Boolean,
        slots: List<TimeSlot>,
        sectionCount: Int,
    ): List<TimeRailRow> {
        val bySection = slots.associateBy { it.section }
        return (1..sectionCount).map { section ->
            val slot = bySection[section]
            val label = slot?.name?.trim()?.takeIf { it.isNotEmpty() && it != "第${section}节" }
                ?: section.toString()
            if (mixed && isSplitSection(section)) {
                TimeRailRow(
                    section = section,
                    label = label,
                    southStart = sectionStart(version, CAMPUS_SOUTH, section),
                    northStart = sectionStart(version, CAMPUS_NORTH, section),
                )
            } else {
                TimeRailRow(
                    section = section,
                    label = label,
                    start = slot?.startTime.orEmpty(),
                    end = slot?.endTime.orEmpty(),
                )
            }
        }
    }
}
