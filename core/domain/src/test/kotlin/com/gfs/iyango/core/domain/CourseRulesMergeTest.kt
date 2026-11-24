package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.WeekType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CourseRules.mergeAdjacent] 相邻合并测试。
 */
class CourseRulesMergeTest {

    /** 满足全部等值条件的两节连续课（第 1、2 节）。 */
    private fun pair(
        firstGroupKey: String = "g1",
        secondGroupKey: String = "g1",
        secondStartSection: Int = 2,
    ): Pair<Course, Course> {
        val first = Course(
            id = 1,
            timetableId = 1,
            name = "高等数学",
            teacher = "王老师",
            classroom = "A101",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 1,
            startWeek = 1,
            endWeek = 16,
            weekType = WeekType.EVERY,
            startTime = "08:00",
            endTime = "08:45",
            groupKey = firstGroupKey,
            sourceStartSection = 1,
            sourceEndSection = 1,
        )
        val second = first.copy(
            id = 2,
            startSection = secondStartSection,
            endSection = secondStartSection,
            startTime = "08:55",
            endTime = "09:40",
            groupKey = secondGroupKey,
            sourceEndSection = secondStartSection,
        )
        return first to second
    }

    @Test
    fun `相邻同组课程合并并扩展末节字段`() {
        val (first, second) = pair()
        val merged = CourseRules.mergeAdjacent(listOf(first, second))
        assertEquals(1, merged.size)
        val joint = merged[0]
        assertEquals(1L, joint.id)
        assertEquals(1, joint.startSection)
        assertEquals(2, joint.endSection)
        // endTime / sourceEndSection 取自后一节。
        assertEquals("09:40", joint.endTime)
        assertEquals(2, joint.sourceEndSection)
        // startTime 不变。
        assertEquals("08:00", joint.startTime)
    }

    @Test
    fun `groupKey 为空不合并`() {
        val (first, second) = pair(firstGroupKey = "", secondGroupKey = "")
        assertEquals(2, CourseRules.mergeAdjacent(listOf(first, second)).size)
    }

    @Test
    fun `groupKey 不同不合并`() {
        val (first, second) = pair(secondGroupKey = "g2")
        assertEquals(2, CourseRules.mergeAdjacent(listOf(first, second)).size)
    }

    @Test
    fun `节次不相邻不合并`() {
        val (first, second) = pair(secondStartSection = 3)
        assertEquals(2, CourseRules.mergeAdjacent(listOf(first, second)).size)
    }

    @Test
    fun `关键字段不等值不合并`() {
        val (first, second) = pair()
        // 教室不同。
        assertEquals(2, CourseRules.mergeAdjacent(listOf(first, second.copy(classroom = "B202"))).size)
        // 周区间不同。
        assertEquals(2, CourseRules.mergeAdjacent(listOf(first, second.copy(endWeek = 17))).size)
        // selectedWeeks 不同。
        assertEquals(2, CourseRules.mergeAdjacent(listOf(first, second.copy(selectedWeeks = listOf(1)))).size)
        // 星期不同。
        assertEquals(2, CourseRules.mergeAdjacent(listOf(first, second.copy(dayOfWeek = 2))).size)
    }

    @Test
    fun `多段合并与跨星期排序`() {
        val (first, second) = pair()
        val third = first.copy(id = 3, dayOfWeek = 2, startSection = 1, endSection = 1)
        val fourth = third.copy(id = 4, startSection = 2, endSection = 2, endTime = "09:40")
        // 输入乱序：先给周二的两节，再给周一的两节。
        val merged = CourseRules.mergeAdjacent(listOf(third, fourth, first, second))
        assertEquals(2, merged.size)
        assertEquals(1, merged[0].dayOfWeek)
        assertEquals(2, merged[0].endSection)
        assertEquals(2, merged[1].dayOfWeek)
        assertEquals(2, merged[1].endSection)
    }

    @Test
    fun `三节连堂链式合并`() {
        val (first, second) = pair()
        val third = second.copy(id = 3, startSection = 3, endSection = 3, endTime = "10:35")
        val merged = CourseRules.mergeAdjacent(listOf(first, second, third))
        assertEquals(1, merged.size)
        assertEquals(3, merged[0].endSection)
        assertEquals("10:35", merged[0].endTime)
    }
}
