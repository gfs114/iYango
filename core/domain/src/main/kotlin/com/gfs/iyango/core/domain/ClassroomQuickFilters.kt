package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.ClassroomBuilding
import com.gfs.iyango.core.model.ClassroomCellState
import com.gfs.iyango.core.model.ClassroomPeriodGroup
import com.gfs.iyango.core.model.ClassroomResult
import com.gfs.iyango.core.model.ClassroomRoom
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.PinnedClassroomBuilding
import java.time.LocalTime

/** 教室快捷筛选状态。 */
data class ClassroomQuickFilter(
    val availableNowOnly: Boolean = false,
    val minConsecutiveFreeGroups: Int = 1,
) {
    val active: Boolean get() = availableNowOnly || minConsecutiveFreeGroups >= 2
}

/** 当前时刻对应节次槽位解析结果。 */
sealed interface ActiveSlotResolution {
    data class InProgress(
        val groupIndex: Int,
        val label: String,
        val endTime: String,
    ) : ActiveSlotResolution

    data class UpcomingBreak(
        val groupIndex: Int,
        val label: String,
        val startTime: String,
    ) : ActiveSlotResolution

    data object AfterLastPeriod : ActiveSlotResolution
    data object Unavailable : ActiveSlotResolution
}

/** 单间教室经快捷筛选与排序后的展示投影。 */
data class ClassroomRoomProjection(
    val room: ClassroomRoom,
    val highlightedGroupIndex: Int? = null,
    val consecutiveFreeCount: Int = 0,
    val freeUntilTime: String = "",
    val badgeText: String = "",
)

/** 快捷筛选整体输出。 */
data class ClassroomFilterOutput(
    val rooms: List<ClassroomRoomProjection>,
    val activeSlot: ActiveSlotResolution? = null,
    val emptyReason: String = "",
)

/** 结合置顶状态的教学楼展示项。 */
data class OrderedBuilding(
    val building: ClassroomBuilding,
    val isPinned: Boolean,
)

/**
 * 空教室快捷筛选与排序引擎（纯 JVM + 可注入时钟）。
 */
object ClassroomQuickFilterEngine {

    private data class PeriodWindow(
        val groupIndex: Int,
        val group: ClassroomPeriodGroup,
        val startMinutes: Int,
        val endMinutes: Int,
        val startText: String,
        val endText: String,
    )

    private data class FreeRun(
        val startIndex: Int,
        val endIndex: Int,
        val endTime: String,
    ) {
        val length: Int get() = endIndex - startIndex + 1
    }

    /** 将 [nowTime] 解析为当前正在上课或下一待开始的节次槽位。 */
    fun resolveActiveSlot(
        periodGroups: List<ClassroomPeriodGroup>,
        nowTime: LocalTime,
    ): ActiveSlotResolution {
        val windows = periodGroups.mapIndexedNotNull { index, group ->
            val startText = group.startTime.takeIf { CourseValidator.isTimeValid(it) }
                ?: group.sourceStartTime.takeIf { CourseValidator.isTimeValid(it) }
                ?: return@mapIndexedNotNull null
            val endText = group.endTime.takeIf { CourseValidator.isTimeValid(it) }
                ?: group.sourceEndTime.takeIf { CourseValidator.isTimeValid(it) }
                ?: return@mapIndexedNotNull null
            val startMin = toMinutes(startText)
            val endMin = toMinutes(endText)
            if (endMin < startMin) return@mapIndexedNotNull null
            PeriodWindow(
                groupIndex = index,
                group = group,
                startMinutes = startMin,
                endMinutes = endMin,
                startText = startText,
                endText = endText,
            )
        }
        if (windows.isEmpty()) return ActiveSlotResolution.Unavailable

        val nowMin = nowTime.hour * 60 + nowTime.minute
        windows.firstOrNull { nowMin in it.startMinutes..it.endMinutes }?.let { active ->
            return ActiveSlotResolution.InProgress(
                groupIndex = active.groupIndex,
                label = active.group.label.ifBlank { active.group.rawCode },
                endTime = active.endText,
            )
        }
        windows.firstOrNull { it.startMinutes > nowMin }?.let { upcoming ->
            return ActiveSlotResolution.UpcomingBreak(
                groupIndex = upcoming.groupIndex,
                label = upcoming.group.label.ifBlank { upcoming.group.rawCode },
                startTime = upcoming.startText,
            )
        }
        return ActiveSlotResolution.AfterLastPeriod
    }

    /** 对原始查询结果执行本地快捷筛选、连空计算与稳定排序。 */
    fun project(
        result: ClassroomResult,
        filter: ClassroomQuickFilter,
        nowTime: LocalTime,
    ): ClassroomFilterOutput {
        if (!filter.active) {
            return ClassroomFilterOutput(
                rooms = result.rooms.map { ClassroomRoomProjection(room = it) },
                activeSlot = null,
                emptyReason = "",
            )
        }

        val dayOfWeek = result.query.dayOfWeek
        val periodGroups = result.periodGroups
        val activeSlot = if (filter.availableNowOnly) {
            resolveActiveSlot(periodGroups, nowTime)
        } else {
            null
        }

        if (filter.availableNowOnly) {
            when (activeSlot) {
                ActiveSlotResolution.AfterLastPeriod -> return ClassroomFilterOutput(
                    rooms = emptyList(),
                    activeSlot = activeSlot,
                    emptyReason = "今日课程已结束",
                )
                ActiveSlotResolution.Unavailable -> return ClassroomFilterOutput(
                    rooms = emptyList(),
                    activeSlot = activeSlot,
                    emptyReason = "缺少节次时段信息",
                )
                else -> Unit
            }
        }

        val targetIndex = when (activeSlot) {
            is ActiveSlotResolution.InProgress -> activeSlot.groupIndex
            is ActiveSlotResolution.UpcomingBreak -> activeSlot.groupIndex
            else -> null
        }

        val minRequired = filter.minConsecutiveFreeGroups.coerceAtLeast(1)
        val selectedSectionIndex = result.query.sectionCode.takeIf { it.isNotEmpty() }?.let { code ->
            periodGroups.indexOfFirst { it.rawCode == code }.takeIf { it >= 0 }
        }

        data class RankedRoom(
            val projection: ClassroomRoomProjection,
            val freeUntilMin: Int,
        )

        val matched = result.rooms.mapNotNull { room ->
            val runs = findFreeRuns(room, dayOfWeek, periodGroups)
            if (targetIndex != null) {
                val anchorRun = runs.firstOrNull { targetIndex in it.startIndex..it.endIndex }
                    ?: return@mapNotNull null
                val consecutiveFromAnchor = anchorRun.endIndex - targetIndex + 1
                if (consecutiveFromAnchor < minRequired) return@mapNotNull null
                val untilTime = anchorRun.endTime
                val badge = buildString {
                    if (consecutiveFromAnchor >= 2) {
                        append("连空 $consecutiveFromAnchor 节")
                    }
                    if (untilTime.isNotEmpty()) {
                        if (isNotEmpty()) append(" ")
                        append("空至 $untilTime")
                    }
                }
                RankedRoom(
                    projection = ClassroomRoomProjection(
                        room = room,
                        highlightedGroupIndex = targetIndex,
                        consecutiveFreeCount = consecutiveFromAnchor,
                        freeUntilTime = untilTime,
                        badgeText = badge,
                    ),
                    freeUntilMin = if (untilTime.isNotEmpty()) toMinutes(untilTime) else -1,
                )
            } else if (selectedSectionIndex != null) {
                val anchorRun = runs.firstOrNull { selectedSectionIndex in it.startIndex..it.endIndex }
                    ?: return@mapNotNull null
                val consecutive = anchorRun.endIndex - selectedSectionIndex + 1
                if (consecutive < minRequired) return@mapNotNull null
                val badge = buildString {
                    append("连空 $consecutive 节")
                    if (anchorRun.endTime.isNotEmpty()) append(" 空至 ${anchorRun.endTime}")
                }
                RankedRoom(
                    projection = ClassroomRoomProjection(
                        room = room,
                        highlightedGroupIndex = selectedSectionIndex,
                        consecutiveFreeCount = consecutive,
                        freeUntilTime = anchorRun.endTime,
                        badgeText = badge,
                    ),
                    freeUntilMin = if (anchorRun.endTime.isNotEmpty()) toMinutes(anchorRun.endTime) else -1,
                )
            } else {
                val bestRun = runs.filter { it.length >= minRequired }
                    .maxWithOrNull(compareBy<FreeRun> { it.length }.thenBy { it.endIndex })
                    ?: return@mapNotNull null
                val badge = buildString {
                    append("连空 ${bestRun.length} 节")
                    if (bestRun.endTime.isNotEmpty()) append(" 空至 ${bestRun.endTime}")
                }
                RankedRoom(
                    projection = ClassroomRoomProjection(
                        room = room,
                        highlightedGroupIndex = null,
                        consecutiveFreeCount = bestRun.length,
                        freeUntilTime = bestRun.endTime,
                        badgeText = badge,
                    ),
                    freeUntilMin = if (bestRun.endTime.isNotEmpty()) toMinutes(bestRun.endTime) else -1,
                )
            }
        }

        val sorted = matched.sortedWith(
            compareByDescending<RankedRoom> { it.projection.consecutiveFreeCount }
                .thenByDescending { it.freeUntilMin }
                .thenWith { a, b -> naturalCompare(a.projection.room.name, b.projection.room.name) },
        ).map { it.projection }

        val emptyReason = if (sorted.isEmpty()) "当前条件下暂无符合要求的空闲教室" else ""
        return ClassroomFilterOutput(
            rooms = sorted,
            activeSlot = activeSlot,
            emptyReason = emptyReason,
        )
    }

    /** 将教学楼选项按「本校区已置顶优先 → 原顺序」重排。 */
    fun orderBuildingsWithPins(
        campusCode: String,
        buildings: List<ClassroomBuilding>,
        pinned: List<PinnedClassroomBuilding>,
    ): List<OrderedBuilding> {
        if (campusCode.isEmpty() || buildings.isEmpty()) {
            return buildings.map { OrderedBuilding(it, isPinned = false) }
        }
        val pinnedRank = HashMap<String, Int>()
        pinned.filter { it.campusCode == campusCode }.forEachIndexed { idx, pin ->
            pinnedRank.putIfAbsent(pin.buildingCode, idx)
        }
        if (pinnedRank.isEmpty()) {
            return buildings.map { OrderedBuilding(it, isPinned = false) }
        }
        return buildings.withIndex()
            .sortedWith(
                compareBy<IndexedValue<ClassroomBuilding>> { pinnedRank[it.value.code] ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
            .map { OrderedBuilding(it.value, isPinned = it.value.code in pinnedRank) }
    }

    private fun findFreeRuns(
        room: ClassroomRoom,
        dayOfWeek: Int,
        periodGroups: List<ClassroomPeriodGroup>,
    ): List<FreeRun> {
        val runs = mutableListOf<FreeRun>()
        var runStart: Int? = null
        for (i in periodGroups.indices) {
            val state = room.cell(dayOfWeek, i)?.state
            if (state == ClassroomCellState.FREE) {
                if (runStart == null) runStart = i
            } else {
                if (runStart != null) {
                    val endIdx = i - 1
                    runs += FreeRun(runStart, endIdx, resolvedEndTime(periodGroups[endIdx]))
                    runStart = null
                }
            }
        }
        if (runStart != null) {
            val endIdx = periodGroups.lastIndex
            runs += FreeRun(runStart, endIdx, resolvedEndTime(periodGroups[endIdx]))
        }
        return runs
    }

    private fun resolvedEndTime(group: ClassroomPeriodGroup): String =
        group.endTime.takeIf { CourseValidator.isTimeValid(it) }
            ?: group.sourceEndTime.takeIf { CourseValidator.isTimeValid(it) }
            ?: ""

    private fun toMinutes(time: String): Int =
        time.substring(0, 2).toInt() * 60 + time.substring(3, 5).toInt()

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var numA = 0L
                while (i < a.length && a[i].isDigit()) {
                    numA = (numA * 10 + (a[i] - '0')).coerceAtMost(Int.MAX_VALUE.toLong())
                    i++
                }
                var numB = 0L
                while (j < b.length && b[j].isDigit()) {
                    numB = (numB * 10 + (b[j] - '0')).coerceAtMost(Int.MAX_VALUE.toLong())
                    j++
                }
                if (numA != numB) return numA.compareTo(numB)
            } else {
                val cmp = ca.compareTo(cb)
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return a.length.compareTo(b.length)
    }

    private fun <T> Comparator<T>.thenWith(other: Comparator<T>): Comparator<T> =
        Comparator { x, y ->
            val first = this.compare(x, y)
            if (first != 0) first else other.compare(x, y)
        }
}
