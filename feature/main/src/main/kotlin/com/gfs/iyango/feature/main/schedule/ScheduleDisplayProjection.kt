package com.gfs.iyango.feature.main.schedule

import com.gfs.iyango.core.domain.CourseReminderPlanner
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.feature.main.schedule.WeekScheduleData.GridBlock
import com.gfs.iyango.feature.main.schedule.WeekScheduleData.GridCellGroup

/**
 * F09 展示投影：把冲突分组后的网格块投影为实际绘制单元。
 *
 * 纯函数，不修改 [WeekScheduleData]（数据库行与详情数据保持原样）：
 * - 隐藏周末只过滤绘制列，不删除任何课程；
 * - 合并相邻只把同一天、节次首尾相接、同课程组的单块串成一个绘制块；
 *   [RenderCell.blocks] 保留全部原始块，详情弹层仍能逐条查看。
 */
object ScheduleDisplayProjection {

    /**
     * 一个可绘制的课程块。
     *
     * @property dayOfWeek 星期（1..7）。
     * @property startSection 起始节次。
     * @property rowSpan 跨节数（合并后为整串高度）。
     * @property blocks 该绘制块包含的原始网格块；首块为主块。
     * @property conflictCount 与主块实际区间重叠的真实发生数量（含自身），幽灵不计入。
     */
    data class RenderCell(
        val dayOfWeek: Int,
        val startSection: Int,
        val rowSpan: Int,
        val blocks: List<GridBlock>,
        val conflictCount: Int = 1,
        val laneIndex: Int = 0,
        val laneCount: Int = 1,
        val topRow: Float = (startSection - 1).toFloat(),
        val heightRows: Float = rowSpan.toFloat(),
    ) {
        val primary: GridBlock get() = blocks.first()
    }

    /**
     * 投影绘制单元。
     *
     * @param cells 冲突分组后的网格块（按星期、起始节次升序）。
     * @param mergeAdjacent 是否合并同课程组且节次相接的单块。
     * @param visibleDays 可见天数（5=隐藏周末，7=全部）。
     */
    fun project(cells: List<GridCellGroup>, mergeAdjacent: Boolean, visibleDays: Int,
        timeSlots: List<com.gfs.iyango.core.model.TimeSlot> = emptyList(),
        dayTimeSlots: (Int) -> List<com.gfs.iyango.core.model.TimeSlot> = { timeSlots }): List<RenderCell> {
        val raw = cells.filter { it.dayOfWeek in 1..visibleDays.coerceIn(1, 7) }.flatMap { group ->
            group.blocks.map { block ->
                val start = block.course.startSection
                val span = block.rowSpan.coerceAtLeast(1)
                val mappedTop = block.occurrence?.startMinute?.let { ScheduleAxisGeometry.rowAtMinute(it, dayTimeSlots(group.dayOfWeek)) }
                val mappedBottom = block.occurrence?.endMinute?.let { ScheduleAxisGeometry.rowAtMinute(it, dayTimeSlots(group.dayOfWeek), true) }
                val reliable = mappedTop != null && mappedBottom != null && mappedBottom > mappedTop
                val top = if (reliable) mappedTop!! else (start - 1).toFloat()
                val bottom = if (reliable) mappedBottom!! else (start + span - 1).toFloat()
                RenderCell(group.dayOfWeek, start, span, listOf(block), topRow = top,
                    heightRows = (bottom - top).coerceAtLeast(0.25f))
            }
        }
        val real = raw.filter { !it.primary.isOffWeek }
        // Keep covered ghosts accessible in the real card's detail pager, without allocating conflict lanes.
        val visible = real.map { cell -> cell.copy(blocks = cell.blocks + raw.filter {
            it.primary.isOffWeek && overlaps(cell, it)
        }.flatMap { it.blocks }) } + raw.filter { it.primary.isOffWeek && real.none { r -> overlaps(r, it) } }
        val merged = mutableListOf<RenderCell>()
        visible.sortedWith(compareBy({ it.dayOfWeek }, { it.topRow }, { it.primary.course.id })).forEach { cell ->
            val previous = merged.lastOrNull()
            val canMerge = mergeAdjacent && previous != null && previous.dayOfWeek == cell.dayOfWeek &&
                previous.blocks.all { it.isOffWeek == previous.primary.isOffWeek && sameCourseGroup(it, previous.primary) } && cell.blocks.size == 1 &&
                previous.startSection + previous.rowSpan == cell.startSection && sameCourseGroup(previous.primary, cell.primary) &&
                visible.none { it !== cell && it !== previous && overlaps(it, cell) } &&
                kotlin.math.abs(previous.topRow + previous.heightRows - cell.topRow) < 0.001f
            if (canMerge) merged[merged.lastIndex] = previous!!.copy(rowSpan = previous.rowSpan + cell.rowSpan,
                heightRows = previous.heightRows + cell.heightRows, blocks = previous.blocks + cell.blocks)
            else merged += cell
        }
        return merged.groupBy { it.dayOfWeek }.values.flatMap { day ->
            val result = mutableListOf<RenderCell>()
            var cluster = mutableListOf<RenderCell>()
            var end = -1f
            fun flush() {
                if (cluster.isEmpty()) return
                val laneEnds = mutableListOf<Float>()
                val assigned = cluster.map { cell ->
                    var lane = laneEnds.indexOfFirst { it <= cell.topRow + 0.001f }
                    if (lane < 0) { lane = laneEnds.size; laneEnds += 0f }
                    laneEnds[lane] = cell.topRow + cell.heightRows
                    cell.copy(laneIndex = lane)
                }
                result += assigned.map { cell -> cell.copy(laneCount = laneEnds.size,
                    conflictCount = if (cell.primary.isOffWeek) 1 else cluster.count { !it.primary.isOffWeek && actualOverlap(it, cell) }) }
                cluster = mutableListOf()
            }
            day.sortedWith(compareBy({ it.topRow }, { it.primary.isOffWeek }, { it.primary.course.id })).forEach { cell ->
                if (cell.topRow >= end) { flush(); end = -1f }
                cluster += cell
                end = maxOf(end, cell.topRow + cell.heightRows)
            }
            flush()
            result
        }
    }

    private fun actualOverlap(a: RenderCell, b: RenderCell): Boolean {
        val left = a.primary.occurrence
        val right = b.primary.occurrence
        val start = left?.startMinute
        val end = left?.endMinute
        val otherStart = right?.startMinute
        val otherEnd = right?.endMinute
        return if (start != null && end != null && end > start && otherStart != null && otherEnd != null && otherEnd > otherStart)
            start < otherEnd && otherStart < end else overlaps(a, b)
    }

    private fun overlaps(a: RenderCell, b: RenderCell): Boolean = a.dayOfWeek == b.dayOfWeek &&
        a.topRow < b.topRow + b.heightRows && b.topRow < a.topRow + a.heightRows

    /**
     * 稳定课程组键（配色覆盖用）：显式 groupKey 优先，否则课名 + 教师。
     * 与提醒开关的课程键语义一致；实现下沉到 core:domain，避免多处复制。
     */
    fun courseColorKey(course: Course): String = CourseReminderPlanner.courseKey(course)

    /** 同组判定：非本周标记一致，且显式分组键或课名/教师/教室相同。 */
    private fun sameCourseGroup(a: GridBlock, b: GridBlock): Boolean {
        if (a.isOffWeek != b.isOffWeek) return false
        val left = a.course
        val right = b.course
        if (left.groupKey.isNotBlank() || right.groupKey.isNotBlank()) {
            return left.groupKey == right.groupKey
        }
        return left.name == right.name &&
            left.teacher == right.teacher &&
            left.classroom == right.classroom
    }
}
