package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.ReminderSettings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F12/F13 纯规划器测试：输出必须确定、可解释且不猜测未知时间。 */
class ReminderCalendarPlannerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day: LocalDate = LocalDate.of(2026, 10, 26)

    private fun course(
        id: Long = 1,
        name: String = "高等数学",
        teacher: String = "张老师",
        classroom: String = "A101",
        enabled: Boolean = true,
        groupKey: String = "",
    ): Course = Course(
        id = id,
        timetableId = 1,
        name = name,
        teacher = teacher,
        classroom = classroom,
        enabled = enabled,
        groupKey = groupKey,
    )

    private fun occurrence(
        course: Course = course(),
        date: LocalDate = day,
        startMinute: Int? = 9 * 60,
        endMinute: Int? = 10 * 60,
        moved: Boolean = false,
        temporary: Boolean = false,
    ): CourseOccurrence = CourseOccurrence(
        occurrenceId = CourseOccurrence.stableId(course, date),
        course = course,
        date = date,
        startMinute = startMinute,
        endMinute = endMinute,
        isOverride = false,
        isMoved = moved,
        isTemporary = temporary,
    )

    @Test
    fun `关闭总开关时不排任何提醒`() {
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(occurrence()),
            settings = ReminderSettings(enabled = false),
            now = LocalDateTime.of(2026, 10, 26, 8, 0),
            zoneId = zone,
        )
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.skipped.any { it.contains("总开关") })
    }

    @Test
    fun `触发时刻为开始时间减提前量且正文含教室与开始时间`() {
        val occurrence = occurrence()
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(occurrence),
            settings = ReminderSettings(enabled = true, leadMinutes = 10),
            now = LocalDateTime.of(2026, 10, 26, 8, 0),
            zoneId = zone,
        )
        assertEquals(1, plan.items.size)
        val item = plan.items.single()
        val expected = LocalDateTime.of(2026, 10, 26, 8, 50).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, item.triggerAtMillis)
        // 两行清晰排版契约：title 只放课程名，body 第一行为「老师 HH:mm - HH:mm」，第二行放教室。
        assertEquals("高等数学", item.title)
        assertEquals("张老师 09:00 - 10:00\nA101", item.body)
        assertEquals("张老师", item.teacher)
        assertEquals(16, item.payloadHash.length)
        assertEquals(occurrence.occurrenceId, item.occurrenceId)
        // 展示上下文：默认课程色与当天节次（单节时 1/1）。
        assertEquals("#3E7BFA", item.accentColor)
        assertEquals(1, item.dayIndex)
        assertEquals(1, item.dayTotal)
    }

    @Test
    fun `恰好进入提前十分钟窗口仍生成提醒`() {
        val plan = CourseReminderPlanner.plan(
            listOf(occurrence()), ReminderSettings(enabled = true, leadMinutes = 10),
            day.atTime(8, 50), zone,
        )
        assertEquals(1, plan.items.size)
        assertEquals(day.atTime(8, 50).atZone(zone).toInstant().toEpochMilli(), plan.items.single().triggerAtMillis)
    }

    @Test
    fun `提前窗口内首次开启仍保留原触发时间供平台补发`() {
        val plan = CourseReminderPlanner.plan(
            listOf(occurrence()), ReminderSettings(enabled = true, leadMinutes = 10),
            day.atTime(8, 55), zone,
        )
        assertEquals(1, plan.items.size)
        assertEquals(day.atTime(8, 50).atZone(zone).toInstant().toEpochMilli(), plan.items.single().triggerAtMillis)
    }

    @Test
    fun `上课中的有效实例保留以支持重启恢复`() {
        val plan = CourseReminderPlanner.plan(
            listOf(occurrence()), ReminderSettings(enabled = true), day.atTime(9, 15), zone,
        )
        assertEquals(1, plan.items.size)
        assertEquals(day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), plan.items.single().endAtMillis)
    }

    @Test
    fun `无结束时间的实例仅保留到开始后三分钟`() {
        val settings = ReminderSettings(enabled = true)
        val courses = listOf(occurrence(endMinute = null))
        assertEquals(1, CourseReminderPlanner.plan(courses, settings, day.atTime(9, 2), zone).items.size)
        assertTrue(CourseReminderPlanner.plan(courses, settings, day.atTime(9, 3), zone).items.isEmpty())
    }

    @Test
    fun `当天节次上下文按有效实例排序且纳入 payloadHash`() {
        val first = occurrence(
            course = course(id = 1, name = "第一节课"),
            startMinute = 8 * 60,
            endMinute = 9 * 60,
        )
        val second = occurrence(
            course = course(id = 2, name = "第二节课"),
            startMinute = 10 * 60,
            endMinute = 11 * 60,
        )
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(second, first),
            settings = ReminderSettings(enabled = true, leadMinutes = 10),
            now = LocalDateTime.of(2026, 10, 26, 7, 0),
            zoneId = zone,
        )
        assertEquals(2, plan.items.size)
        val firstItem = plan.items.first { it.courseName == "第一节课" }
        val secondItem = plan.items.first { it.courseName == "第二节课" }
        assertEquals(1, firstItem.dayIndex)
        assertEquals(2, firstItem.dayTotal)
        assertEquals(2, secondItem.dayIndex)
        assertEquals(2, secondItem.dayTotal)
        // 配色覆盖改变 accentColor 时 payloadHash 必须变化，通知才能原地更新。
        val recolored = CourseReminderPlanner.plan(
            occurrences = listOf(first),
            settings = ReminderSettings(enabled = true, leadMinutes = 10),
            now = LocalDateTime.of(2026, 10, 26, 7, 0),
            zoneId = zone,
            colorOf = { "#00FF00" },
        ).items.single()
        assertEquals("#00FF00", recolored.accentColor)
        assertNotEquals(firstItem.payloadHash, recolored.payloadHash)
    }

    @Test
    fun `时间待定与已过去的实例不排提醒`() {
        val unknown = occurrence(course = course(id = 2, name = "待定课"), startMinute = null)
        val past = occurrence(
            course = course(id = 3, name = "早课"),
            startMinute = 7 * 60,
            endMinute = 8 * 60,
        )
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(unknown, past),
            settings = ReminderSettings(enabled = true, leadMinutes = 10),
            now = LocalDateTime.of(2026, 10, 26, 8, 0),
            zoneId = zone,
        )
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.skipped.any { it.contains("待定课") && it.contains("时间待定") })
    }

    @Test
    fun `单课程关闭后跳过并记录原因`() {
        val target = course(id = 1, name = "体育", teacher = "李老师", groupKey = "ygu:1")
        val sibling = course(id = 2, name = "体育", teacher = "李老师", groupKey = "ygu:2")
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(occurrence(target), occurrence(sibling, startMinute = 14 * 60, endMinute = 15 * 60)),
            settings = ReminderSettings(
                enabled = true,
                disabledCourseKeys = setOf(CourseReminderPlanner.reminderKey(target)),
            ),
            now = LocalDateTime.of(2026, 10, 26, 8, 0),
            zoneId = zone,
        )
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.skipped.any { it.contains("体育") })
    }

    @Test
    fun `停用课程与调课标记正确反映`() {
        val disabled = occurrence(course = course(id = 4, enabled = false))
        val moved = occurrence(
            course = course(id = 5, name = "调课"),
            moved = true,
            temporary = true,
        )
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(disabled, moved),
            settings = ReminderSettings(enabled = true),
            now = LocalDateTime.of(2026, 10, 26, 8, 0),
            zoneId = zone,
        )
        assertEquals(1, plan.items.size)
        assertTrue(plan.items.single().body.contains("临时调整"))
        assertTrue(plan.items.single().body.contains("已调课"))
    }

    @Test
    fun `日历计划跳过时间无效的实例并带时区与摘要`() {
        val valid = occurrence()
        val unknown = occurrence(course = course(id = 2, name = "待定课"), startMinute = null)
        val inverted = occurrence(
            course = course(id = 3, name = "错时课"),
            startMinute = 10 * 60,
            endMinute = 10 * 60,
        )
        val (events, skipped) = CalendarEventPlanner.plan(
            occurrences = listOf(valid, unknown, inverted),
            zoneId = zone,
        )
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("高等数学", event.title)
        assertEquals("A101", event.location)
        assertEquals(zone.id, event.timeZone)
        assertTrue(event.description.contains("张老师"))
        assertTrue(event.endAtMillis > event.startAtMillis)
        assertNotNull(event.payloadHash)
        assertEquals(16, event.payloadHash.length)
        assertEquals(2, skipped.size)
        assertFalse(skipped.any { it.contains("高等数学") })
    }

    @Test
    fun `计划携带开始时刻与有效结束时刻`() {
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(occurrence(startMinute = 9 * 60, endMinute = 10 * 60)),
            settings = ReminderSettings(enabled = true, leadMinutes = 10),
            now = LocalDateTime.of(2026, 10, 26, 8, 0),
            zoneId = zone,
        )
        val item = plan.items.single()
        assertEquals(
            LocalDateTime.of(2026, 10, 26, 9, 0).atZone(zone).toInstant().toEpochMilli(),
            item.startAtMillis,
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 26, 10, 0).atZone(zone).toInstant().toEpochMilli(),
            item.endAtMillis,
        )
        assertTrue(item.endAtMillis!! > item.startAtMillis)
    }

    @Test
    fun `结束时间缺失或与开始重合时结束时刻为空`() {
        val unknown = occurrence(course = course(id = 2, name = "无尾课"), endMinute = null)
        val sameTime = occurrence(
            course = course(id = 3, name = "重合课"),
            startMinute = 10 * 60,
            endMinute = 10 * 60,
        )
        val inverted = occurrence(
            course = course(id = 4, name = "倒置课"),
            startMinute = 10 * 60,
            endMinute = 9 * 60,
        )
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(unknown, sameTime, inverted),
            settings = ReminderSettings(enabled = true),
            now = LocalDateTime.of(2026, 10, 26, 8, 0),
            zoneId = zone,
        )
        assertEquals(3, plan.items.size)
        assertTrue(plan.items.all { it.startAtMillis > 0 })
        assertTrue(plan.items.all { it.endAtMillis == null })
    }

    @Test
    fun `考试计划携带结束时刻并在缺失时为空`() {
        val settings = ReminderSettings(enabled = true, leadMinutes = 10)
        val withEnd = Exam(
            id = 1,
            subject = "高数期末",
            date = "2026-10-26",
            startTime = "14:00",
            endTime = "16:00",
            location = "B202",
            seat = "12",
        )
        val withoutEnd = withEnd.copy(id = 2, endTime = "")
        val items = ExamReminderPlanner.plan(
            exams = listOf(withEnd, withoutEnd),
            settings = settings,
            now = LocalDateTime.of(2026, 10, 26, 8, 0),
            zoneId = zone,
        )
        assertEquals(2, items.size)
        val ended = items.first { it.occurrenceId == "exam:1" }
        assertEquals(
            LocalDateTime.of(2026, 10, 26, 14, 0).atZone(zone).toInstant().toEpochMilli(),
            ended.startAtMillis,
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 26, 16, 0).atZone(zone).toInstant().toEpochMilli(),
            ended.endAtMillis,
        )
        assertTrue(ended.endAtMillis!! > ended.startAtMillis)
        val openEnded = items.first { it.occurrenceId == "exam:2" }
        assertEquals(ended.startAtMillis, openEnded.startAtMillis)
        assertNull(openEnded.endAtMillis)
    }

    @Test
    fun `只修改结束时间时 payloadHash 会变化`() {
        val settings = ReminderSettings(enabled = true, leadMinutes = 10)
        val now = LocalDateTime.of(2026, 10, 26, 8, 0)
        val early = CourseReminderPlanner.plan(
            occurrences = listOf(occurrence(endMinute = 10 * 60)),
            settings = settings,
            now = now,
            zoneId = zone,
        ).items.single()
        val late = CourseReminderPlanner.plan(
            occurrences = listOf(occurrence(endMinute = 10 * 60 + 30)),
            settings = settings,
            now = now,
            zoneId = zone,
        ).items.single()
        assertEquals(early.startAtMillis, late.startAtMillis)
        assertNotEquals(early.body, late.body)
        assertNotEquals(early.payloadHash, late.payloadHash)

        val examEarly = Exam(
            id = 1,
            subject = "英语四级",
            date = "2026-10-26",
            startTime = "14:00",
            endTime = "16:00",
        )
        val examLate = examEarly.copy(endTime = "16:30")
        val examHashes = listOf(examEarly, examLate).map { exam ->
            ExamReminderPlanner.plan(listOf(exam), settings, now, zone).single().payloadHash
        }
        assertNotEquals(examHashes[0], examHashes[1])
    }

    @Test
    fun `相邻不同教学楼且课间不超过二十分钟时提醒正文附带换楼预警`() {
        val settings = ReminderSettings(enabled = true, leadMinutes = 10)
        val now = LocalDateTime.of(2026, 10, 26, 7, 30)
        val first = occurrence(
            course = course(id = 1, name = "高等数学", classroom = "明理楼 A101"),
            startMinute = 8 * 60,
            endMinute = 9 * 60 + 40,
        )
        val second = occurrence(
            course = course(id = 2, name = "大学物理", classroom = "信息楼 B202"),
            startMinute = 10 * 60,
            endMinute = 11 * 60 + 40,
        )
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(first, second),
            settings = settings,
            now = now,
            zoneId = zone,
        )
        assertEquals(2, plan.items.size)
        val firstItem = plan.items.first { it.courseName == "高等数学" }
        val secondItem = plan.items.first { it.courseName == "大学物理" }
        assertEquals("", firstItem.transferWarning)
        assertEquals("换楼：明理楼 → 信息楼", secondItem.transferWarning)
        assertTrue(secondItem.body.contains("换楼：明理楼 → 信息楼"))
        assertFalse(secondItem.body.contains(" · "))
        assertEquals(2, secondItem.body.lines().size)

        val sameBuildingPlan = CourseReminderPlanner.plan(
            occurrences = listOf(
                first,
                occurrence(
                    course = course(id = 2, name = "大学物理", classroom = "明理楼 B202"),
                    startMinute = 10 * 60,
                    endMinute = 11 * 60 + 40,
                ),
            ),
            settings = settings,
            now = now,
            zoneId = zone,
        )
        val sameItem = sameBuildingPlan.items.first { it.courseName == "大学物理" }
        assertEquals("", sameItem.transferWarning)
        assertNotEquals(secondItem.payloadHash, sameItem.payloadHash)
    }

    @Test
    fun `无教师时时间在第一行且教室楼栋单独占第二行`() {
        val plan = CourseReminderPlanner.plan(
            occurrences = listOf(
                occurrence(
                    course = course(id = 9, name = "实验课", teacher = "", classroom = "知行楼 B305"),
                    startMinute = 9 * 60,
                    endMinute = 10 * 60,
                ),
            ),
            settings = ReminderSettings(enabled = true, leadMinutes = 10),
            now = LocalDateTime.of(2026, 10, 26, 8, 0),
            zoneId = zone,
        )
        assertEquals("09:00 - 10:00\n知行楼 B305", plan.items.single().body)
    }
}
