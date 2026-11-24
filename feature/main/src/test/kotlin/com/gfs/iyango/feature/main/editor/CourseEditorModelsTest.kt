package com.gfs.iyango.feature.main.editor

import com.gfs.iyango.core.model.WeekType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildCourses] / [firstValidationError] / [weekSummary] 纯 JVM 测试。
 */
class CourseEditorModelsTest {

    @Test
    fun `多条安排共享基本信息且各成一行`() {
        val form = CourseEditorForm(
            name = "高等数学",
            abbreviation = "高数",
            credit = "4",
            color = "#3E7BFA",
            arrangements = listOf(
                ArrangementDraft(teacher = "张三", classroom = "A101", dayOfWeek = 1, startSection = 1, endSection = 2),
                ArrangementDraft(teacher = "张三", classroom = "B202", dayOfWeek = 3, startSection = 3, endSection = 4),
            ),
        )
        val courses = buildCourses(form, timetableId = 7)
        assertEquals(2, courses.size)
        courses.forEach { course ->
            assertEquals("高等数学", course.name)
            assertEquals("高数", course.abbreviation)
            assertEquals(4.0, course.credit, 1e-9)
            assertEquals("#3E7BFA", course.color)
            assertEquals(7, course.timetableId)
            assertEquals("张三", course.teacher)
        }
        assertEquals(1, courses[0].dayOfWeek)
        assertEquals(3, courses[1].dayOfWeek)
        assertEquals(0, courses[0].id)
    }

    @Test
    fun `编辑模式第0条保留原id其余为新行`() {
        val form = CourseEditorForm(
            name = "体育",
            arrangements = listOf(
                ArrangementDraft(dayOfWeek = 2, startSection = 5, endSection = 6),
                ArrangementDraft(dayOfWeek = 4, startSection = 5, endSection = 6),
            ),
        )
        val courses = buildCourses(form, timetableId = 1, baseId = 42)
        assertEquals(42, courses[0].id)
        assertEquals(0, courses[1].id)
    }

    @Test
    fun `编辑模式保留同步来源字段`() {
        val form = CourseEditorForm(
            name = "大学英语",
            groupKey = "ygu:abcdef0123456789",
            arrangements = listOf(
                ArrangementDraft(
                    dayOfWeek = 1,
                    startSection = 3,
                    endSection = 4,
                    sourceStartSection = 3,
                    sourceEndSection = 4,
                    sourceKey = "ygu:src1",
                ),
            ),
        )
        val edited = buildCourses(form, timetableId = 1, baseId = 42).single()
        assertEquals("ygu:abcdef0123456789", edited.groupKey)
        assertEquals("ygu:src1", edited.sourceKey)
        assertEquals(3, edited.sourceStartSection)
        assertEquals(4, edited.sourceEndSection)
    }

    @Test
    fun `编辑单次调整记录保留override引用`() {
        val form = CourseEditorForm(
            name = "临时调课",
            arrangements = listOf(
                ArrangementDraft(dayOfWeek = 2, overrideOfId = 9, overrideWeek = 5),
            ),
        )
        val edited = buildCourses(form, timetableId = 1, baseId = 77).single()
        assertEquals(9, edited.overrideOfId)
        assertEquals(5, edited.overrideWeek)
    }

    @Test
    fun `新增行不带来源键与override引用`() {
        val form = CourseEditorForm(
            name = "体育",
            groupKey = "ygu:abcdef0123456789",
            arrangements = listOf(
                ArrangementDraft(sourceKey = "ygu:src1", overrideOfId = 3, overrideWeek = 2),
                ArrangementDraft(dayOfWeek = 4),
            ),
        )
        val courses = buildCourses(form, timetableId = 1, baseId = 42)
        assertEquals("ygu:src1", courses[0].sourceKey)
        assertEquals("", courses[1].sourceKey)
        assertEquals(0, courses[1].overrideOfId)
        assertEquals(0, courses[1].overrideWeek)
        assertEquals("ygu:abcdef0123456789", courses[1].groupKey)
    }

    @Test
    fun `无分组键时整组回退到调用方生成的组键`() {
        val courses = buildCourses(
            CourseEditorForm(
                name = "高数",
                arrangements = listOf(ArrangementDraft(), ArrangementDraft(dayOfWeek = 3)),
            ),
            timetableId = 1,
            fallbackGroupKey = "course-1-2",
        )
        courses.forEach { course -> assertEquals("course-1-2", course.groupKey) }
    }

    @Test
    fun `表单已带分组键时忽略回退值`() {
        val courses = buildCourses(
            CourseEditorForm(name = "高数", groupKey = "course-9-9"),
            timetableId = 1,
            fallbackGroupKey = "course-1-2",
        )
        assertEquals("course-9-9", courses.single().groupKey)
    }

    @Test
    fun `timeMode 决定时间字段落值`() {
        val custom = buildCourses(
            CourseEditorForm(
                name = "讲座",
                arrangements = listOf(ArrangementDraft(timeMode = 1, startTime = "18:30", endTime = "20:00", date = "2026-10-01")),
            ),
            timetableId = 1,
        ).single()
        assertEquals("18:30", custom.startTime)
        assertEquals("20:00", custom.endTime)
        assertEquals("", custom.date)

        val dated = buildCourses(
            CourseEditorForm(
                name = "运动会",
                arrangements = listOf(ArrangementDraft(timeMode = 2, date = "2026-11-01")),
            ),
            timetableId = 1,
        ).single()
        assertEquals("", dated.startTime)
        assertEquals("2026-11-01", dated.date)
    }

    @Test
    fun `学分非法文本按零处理`() {
        val courses = buildCourses(
            CourseEditorForm(name = "X", credit = "abc"),
            timetableId = 1,
        )
        assertEquals(0.0, courses.single().credit, 1e-9)
    }

    @Test
    fun `名称为空时校验报错`() {
        val courses = buildCourses(
            CourseEditorForm(name = "  "),
            timetableId = 1,
        )
        val problem = firstValidationError(courses)
        assertNotNull(problem)
    }

    @Test
    fun `指定日期模式缺日期时校验报错`() {
        val courses = buildCourses(
            CourseEditorForm(
                name = "单次课",
                arrangements = listOf(ArrangementDraft(timeMode = 2, date = "")),
            ),
            timetableId = 1,
        )
        assertNotNull(firstValidationError(courses))
    }

    @Test
    fun `合法表单校验通过`() {
        val courses = buildCourses(
            CourseEditorForm(
                name = "数据结构",
                arrangements = listOf(
                    ArrangementDraft(
                        dayOfWeek = 3,
                        startSection = 6,
                        endSection = 7,
                        startWeek = 1,
                        endWeek = 16,
                        weekType = WeekType.EVERY,
                    )
                ),
            ),
            timetableId = 1,
        )
        assertNull(firstValidationError(courses))
    }

    @Test
    fun `weekSummary 三种形态`() {
        assertTrue(
            weekSummary(ArrangementDraft(startWeek = 1, endWeek = 16, weekType = WeekType.EVERY))
                .startsWith("1-16 周"),
        )
        assertTrue(
            weekSummary(ArrangementDraft(startWeek = 1, endWeek = 16, weekType = WeekType.ODD))
                .endsWith("单周"),
        )
        assertTrue(
            weekSummary(ArrangementDraft(selectedWeeks = listOf(4, 2, 8)))
                .contains("2、4、8"),
        )
    }

    @Test
    fun `轮换信息附加在周次摘要尾部`() {
        val summary = weekSummary(
            ArrangementDraft(startWeek = 1, endWeek = 15, cycleLength = 3, cycleWeek = 1),
        )
        assertTrue(summary.endsWith("每 3 周第 1 周"))
    }
}
