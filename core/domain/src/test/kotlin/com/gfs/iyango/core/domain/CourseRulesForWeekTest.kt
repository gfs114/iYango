package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [CourseRules.forWeek] 的替换集合语义测试。
 */
class CourseRulesForWeekTest {

    /** 锚点为周一 2026-03-02 的学期。 */
    private val semester = Semester(
        id = 1,
        name = "2026 春",
        startDate = LocalDate.of(2026, 3, 2),
        totalWeeks = 20,
    )

    private fun base(id: Long = 1): Course = Course(
        id = id,
        name = "高等数学",
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
        startWeek = 1,
        endWeek = 20,
    )

    private fun override(id: Long, overrideOfId: Long, overrideWeek: Int): Course = Course(
        id = id,
        name = "高等数学(调)",
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
        startWeek = 1,
        endWeek = 20,
        overrideOfId = overrideOfId,
        overrideWeek = overrideWeek,
    )

    @Test
    fun `本周 override 替换基础课`() {
        val courses = listOf(base(), override(id = 2, overrideOfId = 1, overrideWeek = 3))
        val week3 = CourseRules.forWeek(courses, 3, semester)
        assertEquals(listOf(2L), week3.map { it.id })
        // 基础课被压制，只剩调整课。
        assertTrue(week3.none { it.id == 1L })
    }

    @Test
    fun `非目标周基础课正常出现`() {
        val courses = listOf(base(), override(id = 2, overrideOfId = 1, overrideWeek = 3))
        val week2 = CourseRules.forWeek(courses, 2, semester)
        assertEquals(listOf(1L), week2.map { it.id })
    }

    @Test
    fun `override 本体只在目标周出现且只在目标周压制基础课`() {
        // override 指向第 5 周：第 3 周 override 不出现、基础课正常出现；第 5 周反过来。
        val courses = listOf(base(), override(id = 2, overrideOfId = 1, overrideWeek = 5))
        assertEquals(listOf(1L), CourseRules.forWeek(courses, 3, semester).map { it.id })
        assertEquals(listOf(2L), CourseRules.forWeek(courses, 5, semester).map { it.id })
    }

    @Test
    fun `基础课超出该周范围时结果只剩 override`() {
        // 基础课只在前两周；第 3 周基础课本就不在范围，结果只有 override。
        val shortBase = base(id = 1).copy(startWeek = 1, endWeek = 2)
        val courses = listOf(shortBase, override(id = 2, overrideOfId = 1, overrideWeek = 3))
        assertEquals(listOf(2L), CourseRules.forWeek(courses, 3, semester).map { it.id })
    }

    @Test
    fun `timeMode2 的日期课映射星期并收敛周次`() {
        val dateCourse = Course(
            id = 2,
            name = "单次活动",
            // 故意填错的 dayOfWeek，应被 date 的星期覆盖（2026-03-06 是周五=5）。
            dayOfWeek = 1,
            startSection = 1,
            endSection = 1,
            startWeek = 1,
            endWeek = 20,
            timeMode = 2,
            date = "2026-03-06",
        )
        val result = CourseRules.forWeek(listOf(dateCourse), 1, semester)
        assertEquals(1, result.size)
        val copy = result[0]
        assertNotSame(dateCourse, copy)
        assertEquals(5, copy.dayOfWeek)
        assertEquals(1, copy.startWeek)
        assertEquals(1, copy.endWeek)
        assertEquals(listOf(1), copy.selectedWeeks)
        // 原对象不被修改。
        assertEquals(1, dateCourse.dayOfWeek)
        assertEquals(1, dateCourse.startWeek)
        assertEquals(20, dateCourse.endWeek)
        assertTrue(dateCourse.selectedWeeks.isEmpty())
    }

    @Test
    fun `timeMode2 日期课只在 date 所属周出现`() {
        val dateCourse = Course(
            id = 2,
            name = "单次活动",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 1,
            startWeek = 1,
            endWeek = 20,
            timeMode = 2,
            date = "2026-03-06",
        )
        // 第 2 周（2026-03-09 起）不含该日期课。
        assertEquals(emptyList<Long>(), CourseRules.forWeek(listOf(dateCourse), 2, semester).map { it.id })
        assertEquals(listOf(2L), CourseRules.forWeek(listOf(dateCourse), 1, semester).map { it.id })
    }

    @Test
    fun `无学期时 timeMode2 课程不出现`() {
        val dateCourse = Course(
            id = 2,
            name = "单次活动",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 1,
            startWeek = 1,
            endWeek = 20,
            timeMode = 2,
            date = "2026-03-06",
        )
        assertEquals(emptyList<Course>(), CourseRules.forWeek(listOf(dateCourse), 1, null))
    }

    @Test
    fun `停用的 override 仍会压制其基础课`() {
        // 对齐参考实现：replaced 集合的收集不检查 enabled，停用的调整课同样吞掉基础课。
        val courses = listOf(
            base(),
            override(id = 2, overrideOfId = 1, overrideWeek = 3).copy(enabled = false),
        )
        assertEquals(emptyList<Course>(), CourseRules.forWeek(courses, 3, semester))
    }
}
