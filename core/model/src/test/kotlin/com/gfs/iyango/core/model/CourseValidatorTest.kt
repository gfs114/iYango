package com.gfs.iyango.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CourseValidator] 校验语义测试：消息逐条对齐参考实现。
 */
class CourseValidatorTest {

    /** 一条默认合法的课程，各用例在此基础上构造非法变体。 */
    private fun validCourse(): Course = Course(
        id = 1,
        timetableId = 1,
        name = "高等数学",
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
        startWeek = 1,
        endWeek = 20,
        startTime = "08:00",
        endTime = "09:00",
    )

    @Test
    fun `合法课程返回 null`() {
        assertNull(CourseValidator.validate(validCourse()))
    }

    @Test
    fun `名称为空或不全空白报错`() {
        assertEquals("课程名称不能为空", CourseValidator.validate(validCourse().copy(name = "")))
        assertEquals("课程名称不能为空", CourseValidator.validate(validCourse().copy(name = "   ")))
    }

    @Test
    fun `星期必须为 1 到 7`() {
        assertEquals("星期取值需在 1-7 之间", CourseValidator.validate(validCourse().copy(dayOfWeek = 0)))
        assertEquals("星期取值需在 1-7 之间", CourseValidator.validate(validCourse().copy(dayOfWeek = 8)))
        assertNull(CourseValidator.validate(validCourse().copy(dayOfWeek = 7)))
    }

    @Test
    fun `节次区间非法报错`() {
        assertEquals(
            "开始节次不能大于结束节次",
            CourseValidator.validate(validCourse().copy(startSection = 3, endSection = 2)),
        )
        assertEquals(
            "开始节次不能大于结束节次",
            CourseValidator.validate(validCourse().copy(startSection = 0, endSection = 2)),
        )
    }

    @Test
    fun `周次区间非法报错`() {
        assertEquals(
            "开始周不能大于结束周",
            CourseValidator.validate(validCourse().copy(startWeek = 3, endWeek = 2)),
        )
        assertEquals(
            "开始周不能大于结束周",
            CourseValidator.validate(validCourse().copy(startWeek = 0, endWeek = 2)),
        )
    }

    @Test
    fun `学分必须非负且有限`() {
        assertEquals("学分必须为非负数", CourseValidator.validate(validCourse().copy(credit = -0.5)))
        assertEquals("学分必须为非负数", CourseValidator.validate(validCourse().copy(credit = Double.NaN)))
        assertEquals("学分必须为非负数", CourseValidator.validate(validCourse().copy(credit = Double.POSITIVE_INFINITY)))
        assertNull(CourseValidator.validate(validCourse().copy(credit = 3.5)))
    }

    @Test
    fun `备注最多 300 字`() {
        assertEquals("备注最多 300 字", CourseValidator.validate(validCourse().copy(remark = "字".repeat(301))))
        assertNull(CourseValidator.validate(validCourse().copy(remark = "字".repeat(300))))
    }

    @Test
    fun `所选周必须落在周次范围内`() {
        assertEquals(
            "所选周次需在上课周次范围内",
            CourseValidator.validate(validCourse().copy(startWeek = 1, endWeek = 3, selectedWeeks = listOf(4))),
        )
        assertNull(
            CourseValidator.validate(validCourse().copy(selectedWeeks = listOf(1, 5, 20))),
        )
    }

    @Test
    fun `轮换规则非法报错`() {
        assertEquals(
            "轮换周次规则无效",
            CourseValidator.validate(validCourse().copy(cycleLength = 0, cycleWeek = 0)),
        )
        assertEquals(
            "轮换周次规则无效",
            CourseValidator.validate(validCourse().copy(cycleLength = 3, cycleWeek = 4)),
        )
        assertNull(CourseValidator.validate(validCourse().copy(cycleLength = 3, cycleWeek = 3)))
        assertNull(CourseValidator.validate(validCourse().copy(cycleLength = 3, cycleWeek = 0)))
    }

    @Test
    fun `时间模式必须为 0 到 2`() {
        assertEquals("上课时间模式无效", CourseValidator.validate(validCourse().copy(timeMode = 3)))
        assertEquals("上课时间模式无效", CourseValidator.validate(validCourse().copy(timeMode = -1)))
    }

    @Test
    fun `自定义钟点必须合法且开始早于结束`() {
        assertEquals(
            "开始时间必须早于结束时间",
            CourseValidator.validate(validCourse().copy(timeMode = 1, startTime = "8:00", endTime = "09:00")),
        )
        assertEquals(
            "开始时间必须早于结束时间",
            CourseValidator.validate(validCourse().copy(timeMode = 1, startTime = "09:00", endTime = "08:00")),
        )
        assertEquals(
            "开始时间必须早于结束时间",
            CourseValidator.validate(validCourse().copy(timeMode = 1, startTime = "09:00", endTime = "09:00")),
        )
        assertNull(
            CourseValidator.validate(validCourse().copy(timeMode = 1, startTime = "08:30", endTime = "09:15")),
        )
    }

    @Test
    fun `指定日期模式必须携带严格合法日期`() {
        assertEquals(
            "上课日期无效",
            CourseValidator.validate(validCourse().copy(timeMode = 2, date = "2026-02-30")),
        )
        assertEquals(
            "上课日期无效",
            CourseValidator.validate(validCourse().copy(timeMode = 2, date = "2026-2-3")),
        )
        assertEquals(
            "上课日期无效",
            CourseValidator.validate(validCourse().copy(timeMode = 2, date = "")),
        )
        assertNull(
            CourseValidator.validate(validCourse().copy(timeMode = 2, date = "2026-03-06")),
        )
    }

    @Test
    fun `单次调整来源必须带有效周次`() {
        assertEquals(
            "临时换课来源或周次无效",
            CourseValidator.validate(validCourse().copy(overrideOfId = 1, overrideWeek = 0)),
        )
        assertEquals(
            "临时换课来源或周次无效",
            CourseValidator.validate(validCourse().copy(overrideOfId = -1, overrideWeek = 1)),
        )
        assertNull(CourseValidator.validate(validCourse().copy(overrideOfId = 1, overrideWeek = 3)))
        assertNull(CourseValidator.validate(validCourse().copy(overrideOfId = 0, overrideWeek = 0)))
    }

    // ---------------------------------------------------------- isDateValid

    @Test
    fun `isDateValid 严格校验日历合法性`() {
        assertFalse(CourseValidator.isDateValid("2026-02-30"))
        assertFalse(CourseValidator.isDateValid("2026-13-01"))
        assertFalse(CourseValidator.isDateValid("2026-00-10"))
        assertFalse(CourseValidator.isDateValid("2026-2-03"))
        assertFalse(CourseValidator.isDateValid("26-02-03"))
        assertFalse(CourseValidator.isDateValid("2026/02/03"))
        assertFalse(CourseValidator.isDateValid(""))
        assertFalse(CourseValidator.isDateValid("0000-01-01"))
        assertFalse(CourseValidator.isDateValid("2026-02-29"))
        assertTrue(CourseValidator.isDateValid("2024-02-29"))
        assertTrue(CourseValidator.isDateValid("2026-12-31"))
    }

    @Test
    fun `isTimeValid 严格校验两位小时与分钟`() {
        assertTrue(CourseValidator.isTimeValid("00:00"))
        assertTrue(CourseValidator.isTimeValid("23:59"))
        assertTrue(CourseValidator.isTimeValid("08:05"))
        assertFalse(CourseValidator.isTimeValid("7:30"))
        assertFalse(CourseValidator.isTimeValid("24:00"))
        assertFalse(CourseValidator.isTimeValid("08:60"))
        assertFalse(CourseValidator.isTimeValid("08:00 "))
        assertFalse(CourseValidator.isTimeValid(""))
    }

    @Test
    fun `isCycleValid 边界`() {
        assertTrue(CourseValidator.isCycleValid(1, 0))
        assertTrue(CourseValidator.isCycleValid(1, 1))
        assertFalse(CourseValidator.isCycleValid(0, 0))
        assertFalse(CourseValidator.isCycleValid(3, 4))
        assertFalse(CourseValidator.isCycleValid(3, -1))
    }

    // ---------------------------------------------------------- validateSlot

    @Test
    fun `validateSlot 逐条对齐`() {
        assertEquals("节次必须为正整数", CourseValidator.validateSlot(TimeSlot(section = 0)))
        assertEquals("时间节点类型无效", CourseValidator.validateSlot(TimeSlot(section = 1, kind = 4)))
        assertEquals("时间节点类型无效", CourseValidator.validateSlot(TimeSlot(section = 1, kind = -1)))
        // 休息/占位节点允许没有钟点。
        assertNull(CourseValidator.validateSlot(TimeSlot(section = 1, kind = 2)))
        assertNull(
            CourseValidator.validateSlot(TimeSlot(section = 1, startTime = "08:00", endTime = "08:45")),
        )
        assertEquals(
            "开始时间必须早于结束时间",
            CourseValidator.validateSlot(TimeSlot(section = 1, startTime = "08:45", endTime = "08:00")),
        )
        assertEquals(
            "开始时间必须早于结束时间",
            CourseValidator.validateSlot(TimeSlot(section = 1, kind = 2, startTime = "08:00", endTime = "")),
        )
    }
}
