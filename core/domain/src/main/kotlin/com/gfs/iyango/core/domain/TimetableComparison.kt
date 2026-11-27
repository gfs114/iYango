package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.ClassroomOption
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import java.time.LocalDate

/** 单张参与对比的课表在目标周的有效数据快照。 */
data class TimetableComparisonSource(
    val timetable: Timetable,
    val semester: Semester?,
    val timeSlots: List<TimeSlot>,
    val occurrences: List<CourseOccurrence>,
)

/** 某一时段内忙碌的课表课程引用。 */
data class BusyCourseRef(
    val timetableId: Long,
    val timetableName: String,
    val courseName: String,
    val classroom: String,
)

/** 周网格单格对比状态。 */
data class ComparisonSlotCell(
    val dayOfWeek: Int,
    val date: LocalDate,
    val section: Int,
    val busySources: List<BusyCourseRef>,
) {
    val isCommonFree: Boolean get() = busySources.isEmpty()
}

/** 连续共同空闲时段。 */
data class CommonFreeBlock(
    val dayOfWeek: Int,
    val date: LocalDate,
    val startSection: Int,
    val endSection: Int,
) {
    val sectionCount: Int get() = endSection - startSection + 1
}

/** 多课表周对比结果。 */
data class TimetableComparisonResult(
    val weekStartDate: LocalDate,
    val dates: List<LocalDate>,
    val maxSection: Int,
    val selectedCount: Int,
    val cells: Map<Pair<Int, Int>, ComparisonSlotCell>,
    val commonFreeBlocks: List<CommonFreeBlock>,
)

/**
 * 多课表「合表找空」纯领域计算引擎。
 */
object TimetableComparisonCalculator {

    private val sectionRangePattern = Regex("""\d{1,2}""")

    fun compare(
        sources: List<TimetableComparisonSource>,
        weekStartDate: LocalDate,
        defaultMaxSection: Int = 11,
    ): TimetableComparisonResult {
        val dates = (0 until 7).map { weekStartDate.plusDays(it.toLong()) }
        val dateToDay = dates.withIndex().associate { (index, date) -> date to (index + 1) }

        val maxSectionFromSlots = sources.maxOfOrNull { src ->
            src.timeSlots.maxOfOrNull { it.section } ?: 0
        } ?: 0
        val maxSectionFromOccurrences = sources.maxOfOrNull { src ->
            src.occurrences.maxOfOrNull { it.course.endSection } ?: 0
        } ?: 0
        val maxSection = maxOf(defaultMaxSection, maxSectionFromSlots, maxSectionFromOccurrences)
            .coerceIn(1, 20)

        val busyMap = HashMap<Pair<Int, Int>, MutableList<BusyCourseRef>>()

        for (source in sources) {
            val tableName = source.timetable.name.ifBlank { "课表 ${source.timetable.id}" }
            val slotMinutes = source.timeSlots.mapNotNull { slot ->
                val sMin = CourseRules.minutes(slot.startTime) ?: return@mapNotNull null
                val eMin = CourseRules.minutes(slot.endTime) ?: return@mapNotNull null
                if (eMin <= sMin || slot.section !in 1..maxSection) return@mapNotNull null
                Triple(slot.section, sMin, eMin)
            }

            for (occ in source.occurrences) {
                val day = dateToDay[occ.date] ?: continue
                val ref = BusyCourseRef(
                    timetableId = source.timetable.id,
                    timetableName = tableName,
                    courseName = occ.course.name,
                    classroom = occ.course.classroom,
                )
                val coveredSections = resolveCoveredSections(occ, maxSection, slotMinutes)
                for (sec in coveredSections) {
                    val key = day to sec
                    val list = busyMap.getOrPut(key) { mutableListOf() }
                    if (list.none { it.timetableId == ref.timetableId && it.courseName == ref.courseName }) {
                        list += ref
                    }
                }
            }
        }

        val cells = LinkedHashMap<Pair<Int, Int>, ComparisonSlotCell>(7 * maxSection)
        val freeBlocks = mutableListOf<CommonFreeBlock>()

        for (dayIndex in 0 until 7) {
            val day = dayIndex + 1
            val date = dates[dayIndex]
            var freeStart: Int? = null
            for (sec in 1..maxSection) {
                val key = day to sec
                val busy = busyMap[key]?.toList().orEmpty()
                val cell = ComparisonSlotCell(
                    dayOfWeek = day,
                    date = date,
                    section = sec,
                    busySources = busy,
                )
                cells[key] = cell
                if (sources.isNotEmpty() && cell.isCommonFree) {
                    if (freeStart == null) freeStart = sec
                } else if (freeStart != null) {
                    freeBlocks += CommonFreeBlock(
                        dayOfWeek = day,
                        date = date,
                        startSection = freeStart,
                        endSection = sec - 1,
                    )
                    freeStart = null
                }
            }
            if (sources.isNotEmpty() && freeStart != null) {
                freeBlocks += CommonFreeBlock(
                    dayOfWeek = day,
                    date = date,
                    startSection = freeStart,
                    endSection = maxSection,
                )
            }
        }

        return TimetableComparisonResult(
            weekStartDate = weekStartDate,
            dates = dates,
            maxSection = maxSection,
            selectedCount = sources.size,
            cells = cells,
            commonFreeBlocks = freeBlocks,
        )
    }

    /**
     * 将共同空课的起止节次动态匹配到教务空教室查询的节次选项 value。
     */
    fun matchClassroomSectionOption(
        options: List<ClassroomOption>,
        startSection: Int,
        endSection: Int,
    ): String {
        if (options.isEmpty() || startSection <= 0 || endSection < startSection) return ""
        val parsed = options.mapNotNull { option ->
            parseSectionOptionRange(option)?.let { range -> option.value to range }
        }
        // 优先找完全落在空闲区间内的首个节次组（如 3..4 落在 3..6 内）
        parsed.firstOrNull { (_, range) ->
            range.first >= startSection && range.last <= endSection
        }?.let { return it.first }

        // 其次找包含 startSection 或与之重叠的首个节次组
        parsed.firstOrNull { (_, range) ->
            range.first <= endSection && range.last >= startSection
        }?.let { return it.first }

        return ""
    }

    private fun parseSectionOptionRange(option: ClassroomOption): IntRange? {
        val rawValue = option.value.trim()
        if (rawValue.length == 4 && rawValue.all { it.isDigit() }) {
            val start = rawValue.substring(0, 2).toIntOrNull()
            val end = rawValue.substring(2, 4).toIntOrNull()
            if (start != null && end != null && start in 1..20 && end in start..20) {
                return start..end
            }
        }
        val nums = sectionRangePattern.findAll(option.label)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..20 }
            .toList()
        if (nums.size >= 2 && nums[1] >= nums[0]) {
            return nums[0]..nums[1]
        }
        if (nums.size == 1) {
            return nums[0]..nums[0]
        }
        return null
    }

    private fun resolveCoveredSections(
        occ: CourseOccurrence,
        maxSection: Int,
        slotMinutes: List<Triple<Int, Int, Int>>,
    ): IntRange {
        val course = occ.course
        // 按节次或未指定纯钟点区间时直接使用节次区间
        if (course.timeMode == 0 && course.startSection in 1..maxSection) {
            val end = course.endSection.coerceIn(course.startSection, maxSection)
            return course.startSection..end
        }
        val startMin = occ.startMinute
        val endMin = occ.endMinute
        if (startMin != null && endMin != null && endMin > startMin && slotMinutes.isNotEmpty()) {
            val overlapped = slotMinutes
                .filter { (_, slotStart, slotEnd) -> slotStart < endMin && slotEnd > startMin }
                .map { it.first }
            if (overlapped.isNotEmpty()) {
                return overlapped.min()..overlapped.max()
            }
        }
        return if (course.startSection in 1..maxSection) {
            course.startSection..course.endSection.coerceIn(course.startSection, maxSection)
        } else {
            IntRange.EMPTY
        }
    }
}
