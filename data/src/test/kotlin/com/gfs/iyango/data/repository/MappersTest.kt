package com.gfs.iyango.data.repository

import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.ImportRecord
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WeekType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 实体 ↔ 模型映射纯 JVM 单测：往返必须无损（含列表字段与小数排序权重）。
 */
class MappersTest {

    @Test
    fun `Timetable 实体与模型往返无损`() {
        val model = Timetable(
            id = 3,
            name = "我的课表",
            source = "YGU",
            schoolTerm = "2026-2027-1",
            connectionId = "conn-1",
            isCurrent = true,
            lastSyncAt = "2026-10-24T08:00:00",
            groupName = "分层A",
            autoEnabled = true,
            enabledDays = listOf(1, 3, 5),
            cycleLength = 2,
            cycleWeek = 1,
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun `Semester 实体与模型往返无损`() {
        val model = Semester(
            id = 1,
            timetableId = 3,
            name = "2026 秋季学期",
            startDate = LocalDate.of(2026, 9, 7),
            totalWeeks = 20,
            isCurrent = true,
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun `TimeSlot 实体与模型往返无损`() {
        val model = TimeSlot(
            id = 2,
            timetableId = 3,
            section = 5,
            startTime = "14:00",
            endTime = "14:45",
            name = "第五节",
            kind = 0,
            hidden = true,
            defaultCourseName = "自习",
            sortOrder = 1.5,
        )
        assertEquals(model, model.toEntity().toModel())
    }

    @Test
    fun `Course 实体与模型往返无损`() {
        val model = Course(
            id = 7,
            timetableId = 3,
            name = "高等数学",
            teacher = "张老师",
            classroom = "A101",
            abbreviation = "高数",
            remark = "带教材",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            sourceStartSection = 1,
            sourceEndSection = 3,
            startWeek = 1,
            endWeek = 16,
            weekType = WeekType.ODD,
            selectedWeeks = listOf(2, 4, 8),
            color = "#3E7BFA",
            credit = 4.5,
            isOutdoor = true,
            enabled = false,
            timeMode = 1,
            startTime = "19:00",
            endTime = "20:35",
            date = "2026-11-06",
            cycleLength = 2,
            cycleWeek = 1,
            overrideOfId = 5,
            overrideWeek = 8,
            groupKey = "g1",
        )
        assertEquals(model, model.toEntity(timetableId = model.timetableId).toModel())
    }

    @Test
    fun `Course 的 timeUnconfirmed 不持久化`() {
        // timeUnconfirmed 只用于读取结果：实体无对应列，映射回模型恒为 false。
        val model = Course(name = "待确认课程", timeUnconfirmed = true)
        val restored = model.toEntity(timetableId = 1).toModel()
        assertFalse(restored.timeUnconfirmed)
    }

    @Test
    fun `Course 默认字段往返无损`() {
        val model = Course(timetableId = 2, name = "体育", isOutdoor = true)
        assertEquals(model, model.toEntity(timetableId = 2).toModel())
    }

    @Test
    fun `Course toEntity 使用调用方提供的 timetableId`() {
        val entity = Course(name = "课", timetableId = 1).toEntity(timetableId = 9)
        assertEquals(9L, entity.timetableId)
    }

    @Test
    fun `ImportRecord 实体与模型往返无损`() {
        val model = ImportRecord(
            id = 4,
            timetableId = 3,
            source = "YGU",
            importTime = "2026-10-24T08:00:00",
            semester = "2026-2027-1",
        )
        assertEquals(model, model.toEntity().toModel())
    }
}
