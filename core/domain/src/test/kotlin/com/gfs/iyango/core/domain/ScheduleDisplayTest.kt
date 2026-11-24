package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.TimeSlot
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScheduleDisplay] 投影测试（displaySlots / project / sourceSection）。
 *
 * 覆盖规格要求的四类行为：无特殊配置原样返回；hidden 午休行午间显示、早晚隐藏；
 * 自定义钟点课合成边界行；project 的行号映射与 endSection=0 语义。
 */
class ScheduleDisplayTest {

    private val noon = LocalTime.of(12, 30)
    private val morning = LocalTime.of(8, 0)

    /** 常规两节作息 + 隐藏的午休占位行（有钟点）。 */
    private val slotsWithHiddenLunch = listOf(
        TimeSlot(id = 11, section = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(id = 12, section = 2, startTime = "08:55", endTime = "09:40"),
        TimeSlot(
            id = 13, timetableId = 1, section = 3,
            startTime = "12:00", endTime = "13:30",
            name = "午休", kind = 2, hidden = true, sortOrder = 2.5,
        ),
        TimeSlot(id = 14, section = 4, startTime = "14:00", endTime = "14:45"),
    )

    // ---------------------------------------------------------- displaySlots

    @Test
    fun `无特殊配置时原样返回`() {
        val slots = listOf(
            TimeSlot(id = 1, section = 1, startTime = "08:00", endTime = "08:45"),
            TimeSlot(id = 2, section = 2, startTime = "08:55", endTime = "09:40"),
        )
        val courses = listOf(Course(timetableId = 1, name = "高数", startSection = 1, endSection = 2))
        assertEquals(slots, ScheduleDisplay.displaySlots(slots, courses, morning))
    }

    @Test
    fun `隐藏午休行在午间显示`() {
        val display = ScheduleDisplay.displaySlots(slotsWithHiddenLunch, emptyList(), noon)
        // 午间（12:30）：4 行全部可见，重编号后午休排第 3。
        assertEquals(listOf("08:00", "08:55", "12:00", "14:00"), display.map { it.startTime })
        assertEquals(3, display.first { it.name == "午休" }.section)
        // 覆盖行继承原行身份。
        val first = display[0]
        assertEquals(11L, first.id)
        assertEquals("第 1 节", first.name)
        assertEquals(1.0, first.sortOrder, 0.0)
    }

    @Test
    fun `隐藏午休行在早晚隐藏`() {
        val display = ScheduleDisplay.displaySlots(slotsWithHiddenLunch, emptyList(), morning)
        assertEquals(listOf("08:00", "08:55", "14:00"), display.map { it.startTime })
        assertTrue(display.none { it.name == "午休" })
        // 可见行重新连续编号。
        assertEquals(listOf(1, 2, 3), display.map { it.section })
    }

    @Test
    fun `自定义钟点课合成边界行`() {
        val slots = listOf(
            TimeSlot(id = 1, section = 1, startTime = "08:00", endTime = "08:45"),
            TimeSlot(id = 2, section = 2, startTime = "09:40", endTime = "10:25"),
        )
        val custom = Course(
            timetableId = 1, name = "班会", timeMode = 1,
            startSection = 1, endSection = 1,
            startTime = "09:00", endTime = "09:30",
        )
        val display = ScheduleDisplay.displaySlots(slots, listOf(custom), morning)
        assertEquals(listOf("08:00", "09:00", "09:40"), display.map { it.startTime })
        val customRow = display.first { it.name == "自定义" }
        assertEquals("09:00", customRow.startTime)
        assertEquals("09:30", customRow.endTime)
        // 自定义行插在两节之间（sortOrder 1.5 → 展示行 2）。
        assertEquals(2, customRow.section)
        assertEquals(1.5, customRow.sortOrder, 0.0)
    }

    // ---------------------------------------------------------- project

    @Test
    fun `project 把课程节次映射到展示行并保留原始节次`() {
        val slots = listOf(
            TimeSlot(id = 1, section = 1, startTime = "08:00", endTime = "08:45"),
            TimeSlot(id = 2, section = 2, startTime = "08:55", endTime = "09:40"),
            TimeSlot(id = 3, section = 3, startTime = "10:00", endTime = "10:45"),
        )
        val display = ScheduleDisplay.displaySlots(slots, emptyList(), morning)
        val course = Course(
            timetableId = 1, name = "高数",
            startSection = 2, endSection = 3,
        )
        val projected = ScheduleDisplay.project(course, slots, display)
        assertEquals(2, projected.startSection)
        assertEquals(3, projected.endSection)
        assertEquals(2, projected.sourceStartSection)
        assertEquals(3, projected.sourceEndSection)
        assertEquals("08:55", projected.startTime)
        assertEquals("10:45", projected.endTime)
    }

    @Test
    fun `project 全覆盖隐藏节次时 endSection 归零`() {
        val slots = listOf(
            TimeSlot(id = 1, section = 1, startTime = "08:00", endTime = "08:45"),
            TimeSlot(id = 2, section = 2, startTime = "08:55", endTime = "09:40", hidden = true),
            TimeSlot(id = 3, section = 3, startTime = "09:50", endTime = "10:35", hidden = true),
        )
        val display = ScheduleDisplay.displaySlots(slots, emptyList(), morning)
        assertEquals(listOf("08:00"), display.map { it.startTime })
        val course = Course(
            timetableId = 1, name = "被隐藏的课",
            startSection = 2, endSection = 3,
        )
        val projected = ScheduleDisplay.project(course, slots, display)
        assertEquals(0, projected.endSection)
        // startSection 保持原值，便于编辑器回显。
        assertEquals(2, projected.startSection)
    }

    @Test
    fun `project 时间不可用时原样返回`() {
        val course = Course(timetableId = 1, name = "待定课", timeUnconfirmed = true)
        val projected = ScheduleDisplay.project(course, emptyList(), emptyList())
        assertEquals(course, projected)
    }

    // ---------------------------------------------------------- sourceSection

    @Test
    fun `sourceSection 反查基础节次`() {
        val base = listOf(
            TimeSlot(id = 1, section = 1, startTime = "08:00", endTime = "08:45"),
            TimeSlot(id = 2, section = 2, startTime = "08:55", endTime = "09:40"),
        )
        val display = ScheduleDisplay.displaySlots(base, emptyList(), morning)
        assertEquals(2, ScheduleDisplay.sourceSection(display[1], base))
    }

    @Test
    fun `sourceSection 自定义行返回 0`() {
        val slots = listOf(
            TimeSlot(id = 1, section = 1, startTime = "08:00", endTime = "08:45"),
        )
        val custom = Course(
            timetableId = 1, name = "班会", timeMode = 1,
            startSection = 1, endSection = 1,
            startTime = "09:00", endTime = "09:30",
        )
        val display = ScheduleDisplay.displaySlots(slots, listOf(custom), morning)
        val customRow = display.first { it.name == "自定义" }
        assertEquals(0, ScheduleDisplay.sourceSection(customRow, slots))
    }
}
