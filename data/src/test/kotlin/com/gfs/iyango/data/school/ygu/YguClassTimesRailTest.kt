package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表时间轴行生成测试。
 *
 * 覆盖：节名归一、单校区直接使用持久化钟点、南北混合课表第 3/4 节并列两套开始时间、
 * 无作息行时仍返回节次号。
 */
class YguClassTimesRailTest {

    private val version = YguClassTimes.TERM_2026_2027_1

    @Test
    fun `单校区沿用持久化钟点并把节名归一为节次号`() {
        val slots = listOf(
            TimeSlot(section = 1, startTime = "08:10", endTime = "08:55", name = "第1节"),
        )
        val rows = YguClassTimes.railRows(version, mixed = false, slots = slots, sectionCount = 2)

        assertEquals(2, rows.size)
        assertEquals("1", rows[0].label)
        assertEquals("08:10", rows[0].start)
        assertEquals("08:55", rows[0].end)
        assertFalse(rows[0].showsCampusSplit)
        // 第 2 节没有作息行：只显示节次号。
        assertEquals("2", rows[1].label)
        assertEquals("", rows[1].start)
    }

    @Test
    fun `南北混合课表差异节并列两套开始时间`() {
        val rows = YguClassTimes.railRows(
            version,
            mixed = true,
            slots = listOf(TimeSlot(section = 3, name = "第3节")),
            sectionCount = 4,
        )

        val third = rows[2]
        assertTrue(third.showsCampusSplit)
        assertEquals("10:05", third.southStart)
        assertEquals("10:20", third.northStart)
        assertEquals("3", third.label)
        // 非差异节不并列。
        assertFalse(rows[0].showsCampusSplit)
    }

    @Test
    fun `自定义节名原样保留`() {
        val rows = YguClassTimes.railRows(
            version = "",
            mixed = false,
            slots = listOf(TimeSlot(section = 1, name = "早读")),
            sectionCount = 1,
        )

        assertEquals("早读", rows.single().label)
    }
}
