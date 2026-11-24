package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.ClassroomDetailRequest
import com.gfs.iyango.core.model.ClassroomOccupancyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教室占用详情解析测试（F04）。
 *
 * 覆盖：教室/学期/周次/星期/节次匹配、跨天跨教室拒绝、借用字段、
 * 第十一节的短编码/时间覆盖兼容与不支持槽位。
 */
class YguClassroomCourseDetailTest {

    @Test
    fun `正常上课返回课程班级教师教室与时间`() {
        val result = YguClassroomCourseDetail.parse(detail(row()), request())
        assertEquals(result.message, YguClassroomDetailStatus.OK, result.status)
        val course = result.courses.single()
        assertEquals(ClassroomOccupancyKind.COURSE, course.kind)
        assertEquals("高等数学", course.courseName)
        assertEquals("计算机2401", course.className)
        assertEquals("李老师", course.teacherName)
        assertEquals("A101", course.roomName)
        assertEquals("08:00-09:40", course.time)
        assertTrue(course.applicant.isEmpty())
        assertTrue(course.remark.isEmpty())
    }

    @Test
    fun `借用返回申请人与备注`() {
        val result = YguClassroomCourseDetail.parse(
            detail(
                row(
                    type = "借用",
                    course = "社团活动",
                    applicant = "学生会",
                    remark = "布置场地",
                ),
            ),
            request(),
        )
        assertEquals(YguClassroomDetailStatus.OK, result.status)
        val info = result.courses.single()
        assertEquals(ClassroomOccupancyKind.BORROWED, info.kind)
        assertEquals("学生会", info.applicant)
        assertEquals("布置场地", info.remark)
        assertEquals("社团活动", info.courseName)
    }

    @Test
    fun `跨教室详情被拒绝`() {
        val result = YguClassroomCourseDetail.parse(
            detail(row(roomId = "B202", room = "B202")),
            request(),
        )
        assertEquals(YguClassroomDetailStatus.INVALID, result.status)
        assertTrue(result.message.contains("教室或学期"))
    }

    @Test
    fun `跨学期详情被拒绝`() {
        val result = YguClassroomCourseDetail.parse(
            detail(row(term = "2024-2")),
            request(),
        )
        assertEquals(YguClassroomDetailStatus.INVALID, result.status)
        assertTrue(result.message.contains("教室或学期"))
    }

    @Test
    fun `跨天详情被拒绝`() {
        val result = YguClassroomCourseDetail.parse(detail(row()), request(day = 2))
        assertEquals(YguClassroomDetailStatus.INVALID, result.status)
        assertTrue(result.message.contains("星期或节次"))
    }

    @Test
    fun `周次不匹配被拒绝`() {
        val result = YguClassroomCourseDetail.parse(
            detail(row(weeks = "1-4")),
            request(week = 8),
        )
        assertEquals(YguClassroomDetailStatus.INVALID, result.status)
        assertTrue(result.message.contains("周次"))
    }

    @Test
    fun `第十一节短编码在时间覆盖时兼容`() {
        val eleventh = request(
            raw = "1112",
            sectionStart = "20:10",
            sectionEnd = "20:55",
        )
        val result = YguClassroomCourseDetail.parse(
            detail(
                row(
                    weeks = "1-16",
                    sections = "111,112",
                    period = "111",
                    time = "20:00-21:00",
                ),
            ),
            eleventh,
        )
        assertEquals(YguClassroomDetailStatus.OK, result.status)
    }

    @Test
    fun `第十一节时间未覆盖仍被拒绝`() {
        val eleventh = request(
            raw = "1112",
            sectionStart = "20:10",
            sectionEnd = "20:55",
        )
        val result = YguClassroomCourseDetail.parse(
            detail(
                row(
                    sections = "111,112",
                    period = "111",
                    time = "08:00-09:40",
                ),
            ),
            eleventh,
        )
        assertEquals(YguClassroomDetailStatus.INVALID, result.status)
        assertTrue(result.message.contains("时间"))
    }

    @Test
    fun `非课程借用的类型暂不支持`() {
        val result = YguClassroomCourseDetail.parse(
            detail(row(type = "考试")),
            request(),
        )
        assertEquals(YguClassroomDetailStatus.UNSUPPORTED, result.status)
    }

    @Test
    fun `其他槽位非空时暂不支持`() {
        val body = "[[${row()}],[[]],[],[],[]]"
        val result = YguClassroomCourseDetail.parse(body, request())
        assertEquals(YguClassroomDetailStatus.UNSUPPORTED, result.status)
    }

    @Test
    fun `无行数据返回空详情`() {
        val result = YguClassroomCourseDetail.parse("[[],[],[],[],[]]", request())
        assertEquals(YguClassroomDetailStatus.EMPTY, result.status)
    }

    @Test
    fun `行长度不对时不可读`() {
        val short = """["A101","A101","","","2025-1","1-16"]"""
        val result = YguClassroomCourseDetail.parse("[[$short],[],[],[],[]]", request())
        assertEquals(YguClassroomDetailStatus.INVALID, result.status)
    }

    @Test
    fun `课程字段非字符串时不可读`() {
        val numericCourse = row().replace("\"高等数学\"", "123")
        val result = YguClassroomCourseDetail.parse(detail(numericCourse), request())
        assertEquals(YguClassroomDetailStatus.INVALID, result.status)
        assertTrue(result.message.contains("字段格式"))
    }

    private fun request(
        day: Int = 1,
        raw: String = "0102",
        week: Int = 8,
        sectionStart: String = "08:00",
        sectionEnd: String = "09:40",
    ) = ClassroomDetailRequest(
        termValue = "2025-1",
        timeModeId = "1",
        week = week,
        dayOfWeek = day,
        campusCode = "01",
        buildingCode = "B1",
        roomId = "A101",
        roomName = "A101",
        rawSectionCode = raw,
        sectionLabel = "第1-2节",
        sectionStartTime = sectionStart,
        sectionEndTime = sectionEnd,
        sourceStartTime = sectionStart,
        sourceEndTime = sectionEnd,
        allSectionCodes = listOf("0102", "0304", "1112"),
        queryVersion = 1,
    )

    private fun row(
        roomId: String = "A101",
        room: String = "A101",
        term: String = "2025-1",
        weeks: String = "1-16",
        sections: String = "101,102",
        period: String = "10102",
        type: String = "正常上课",
        course: String = "高等数学",
        teacher: String = "李老师",
        time: String = "08:00-09:40",
        className: String = "计算机2401",
        applicant: String = "",
        remark: String = "",
    ): String {
        val fields = listOf(
            roomId, roomId, "", "", term, weeks, sections, "", "", "", period, "",
            type, course, room, weeks, "", remark, "", applicant, "", teacher, time, className,
        )
        check(fields.size == 24)
        return fields.joinToString(prefix = "[", postfix = "]") { value ->
            if (value.isEmpty()) "\"\"" else "\"$value\""
        }
    }

    private fun detail(row: String): String = "[[$row],[],[],[],[]]"
}
