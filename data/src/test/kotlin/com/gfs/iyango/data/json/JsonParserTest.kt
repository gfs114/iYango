package com.gfs.iyango.data.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScheduleJsonParser] 宽容解析测试（P2-10）。
 *
 * 覆盖：教务系统风格宽容键名（jsxm/cdmc/xqj/zcd）、displayConfig 只当字符串、
 * 未知字段忽略、根课程数组候选键（含裸数组）、周次文本、节次合并文本与纠正记录。
 */
class JsonParserTest {

    // ---------------------------------------------------------- 宽容键名

    @Test
    fun `教务风格宽容键名可解析`() {
        val text = """
            {"data":[{"courseName":"高等数学","jsxm":"张老师","cdmc":"A101","xqj":1,
            "jcs":"1-2","zcd":"1-16(单)"}]}
        """.trimIndent()
        val result = ScheduleJsonParser.parse(text)
        val course = result.document!!.courses.single()
        assertEquals("高等数学", course.name)
        assertEquals("张老师", course.teacher)
        assertEquals("A101", course.classroom)
        assertEquals(1, course.dayOfWeek)
        // 合并节次文本 "1-2" → 1..2。
        assertEquals(1, course.startSection)
        assertEquals(2, course.endSection)
        // 周次文本来自 zcd（起止缺失 → 文本推断区间与单双周）。
        assertEquals(1, course.startWeek)
        assertEquals(16, course.endWeek)
        assertEquals(1, course.weekType)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `数字型星期与文本型星期都可识别`() {
        val base = """{"courses":[{"name":"课","startSection":1,"endSection":2,%s}]}"""
        assertEquals(3, ScheduleJsonParser.parse(base.format("\"xqj\":3")).document!!.courses[0].dayOfWeek)
        assertEquals(3, ScheduleJsonParser.parse(base.format("\"xqj\":\"周三\"")).document!!.courses[0].dayOfWeek)
        assertEquals(7, ScheduleJsonParser.parse(base.format("\"xqj\":\"周天\"")).document!!.courses[0].dayOfWeek)
        // 缺失 → 记录提示并按星期一。
        val missing = ScheduleJsonParser.parse(base.format("\"remark\":\"\""))
        assertEquals(1, missing.document!!.courses[0].dayOfWeek)
        assertTrue(missing.issues.any { it.message == "未识别到星期，已按星期一处理" })
    }

    @Test
    fun `显式周起止时不从周次文本推断单双周`() {
        // 对齐参考实现：仅当起止周来自文本时才读取"单/双"。
        val text = """
            {"courses":[{"name":"高数","xqj":1,"startSection":1,"endSection":2,
            "startWeek":1,"endWeek":16,"zcd":"1-16(单)"}]}
        """.trimIndent()
        val course = ScheduleJsonParser.parse(text).document!!.courses.single()
        assertEquals(0, course.weekType)
        assertEquals(1, course.startWeek)
        assertEquals(16, course.endWeek)
    }

    // ---------------------------------------------------------- displayConfig

    @Test
    fun `displayConfig 嵌套对象被忽略`() {
        val text = """
            {"displayConfig":{"bgImage":"/x.png","fontSize":14},
             "courses":[{"name":"高数","xqj":1}]}
        """.trimIndent()
        val result = ScheduleJsonParser.parse(text)
        assertEquals("", result.document!!.displayConfig)
    }

    @Test
    fun `displayConfig 字符串形态被保留`() {
        val text = """
            {"displayConfig":"{\"fontSize\":14}",
             "courses":[{"name":"高数","xqj":1}]}
        """.trimIndent()
        val result = ScheduleJsonParser.parse(text)
        assertEquals("""{"fontSize":14}""", result.document!!.displayConfig)
    }

    // ---------------------------------------------------------- 宽容结构

    @Test
    fun `未知字段忽略且扩展项严格按类型读取`() {
        val text = """
            {"meta":{"version":1,"app":"OtherApp","exportedAt":"2026-10-24 08:00:00"},"courses":[
              {"name":"高数","xqj":2,"futureField":123,
               "timeMode":1,"startTime":"19:00","endTime":"20:35",
               "selectedWeeks":[2,4,8],"credit":4.5,"isOutdoor":true,
               "enabled":false,"groupKey":"g1","abbreviation":"高数",
               "cycleLength":2,"cycleWeek":1}]}
        """.trimIndent()
        val result = ScheduleJsonParser.parse(text)
        val course = result.document!!.courses.single()
        assertEquals(1, result.document.meta.version)
        assertEquals("OtherApp", result.document.meta.app)
        assertEquals(1, course.timeMode)
        assertEquals(listOf(2, 4, 8), course.selectedWeeks)
        assertEquals(4.5, course.credit, 0.0)
        assertTrue(course.isOutdoor)
        assertFalse(course.enabled)
        assertEquals("g1", course.groupKey)
        assertEquals(2, course.cycleLength)
        assertEquals(1, course.cycleWeek)
        assertEquals(0, course.id)
    }

    @Test
    fun `根数组候选键与裸数组`() {
        val courseJson = """{"name":"高数","xqj":1,"startSection":1,"endSection":2}"""
        for (root in listOf(
            """{"courses":[$courseJson]}""",
            """{"course":[$courseJson]}""",
            """{"list":[$courseJson]}""",
            """{"lessons":[$courseJson]}""",
            """{"data":[$courseJson]}""",
            "[$courseJson]",
        )) {
            val document = ScheduleJsonParser.parse(root).document
            assertEquals(1, document!!.courses.size)
            assertEquals("高数", document.courses[0].name)
        }
    }

    // ---------------------------------------------------------- 纠正与跳过

    @Test
    fun `缺名称记录跳过并记录`() {
        val text = """
            {"courses":[{"teacher":"张","xqj":1},{"name":"高数","xqj":1}]}
        """.trimIndent()
        val result = ScheduleJsonParser.parse(text)
        assertEquals(1, result.document!!.courses.size)
        assertTrue(result.issues.any { it.message == "跳过缺少课程名称的记录" })
    }

    @Test
    fun `周次文本非法时回退 1-18 并记录`() {
        val text = """{"courses":[{"name":"高数","xqj":1,"zcd":"待定"}]}"""
        val result = ScheduleJsonParser.parse(text)
        val course = result.document!!.courses.single()
        assertEquals(1, course.startWeek)
        assertEquals(18, course.endWeek)
        assertTrue(result.issues.any { it.message == "未识别到周次，已按第 1-18 周处理" })
    }

    @Test
    fun `无效课程被校验器跳过`() {
        // 结束节次字段齐全但 timeMode=1 缺少合法钟点 → 校验失败 → 跳过 → 文件不可用。
        val text = """
            {"courses":[{"name":"自定义课","xqj":1,"timeMode":1,"startTime":"","endTime":""}]}
        """.trimIndent()
        val result = ScheduleJsonParser.parse(text)
        assertNull(result.document)
        assertTrue(result.issues.any { it.message == "跳过无效的课程安排" })
    }

    @Test
    fun `非法 JSON 与空内容返回失败`() {
        assertNull(ScheduleJsonParser.parse("not json").document)
        assertNull(ScheduleJsonParser.parse("   ").document)
        assertNull(ScheduleJsonParser.parse("{}").document)
        // 数字根：无课程可读。
        assertNull(ScheduleJsonParser.parse("42").document)
    }

    // ---------------------------------------------------------- 作息与 meta

    @Test
    fun `作息行解析含扩展项`() {
        val text = """
            {"courses":[{"name":"高数","xqj":1}],
             "timeSlots":[
               {"section":1,"startTime":"08:00","endTime":"08:45"},
               {"section":5,"name":"午休","kind":2,"hidden":true,"defaultCourseName":"自习","sortOrder":4.5},
               {"section":0,"startTime":"09:00","endTime":"09:45"}]}
        """.trimIndent()
        val result = ScheduleJsonParser.parse(text)
        val slots = result.document!!.timeSlots
        assertEquals(2, slots.size)
        assertEquals("08:00", slots[0].startTime)
        assertEquals("午休", slots[1].name)
        assertEquals(2, slots[1].kind)
        assertTrue(slots[1].hidden)
        assertEquals(4.5, slots[1].sortOrder, 0.0)
        assertTrue(result.issues.any { it.message == "跳过不完整的节次时间记录" })
    }

    @Test
    fun `学期信息解析与纠正`() {
        val text = """
            {"courses":[{"name":"高数","xqj":1}],
             "semester":{"name":"2026 秋","startDate":"2026-09-07","totalWeeks":99,"current":true}}
        """.trimIndent()
        val result = ScheduleJsonParser.parse(text)
        val semester = result.document!!.semester!!
        assertEquals("2026 秋", semester.name)
        assertEquals("2026-09-07", semester.startDate)
        assertEquals(20, semester.totalWeeks)
        assertTrue(semester.isCurrent)
        assertTrue(result.issues.any { it.message == "学期总周数超出范围，已按 20 周处理" })
    }

    @Test
    fun `颜色与非颜色回退`() {
        val base = """{"courses":[{"name":"课","xqj":1,%s}]}"""
        assertEquals("#12ABcd", ScheduleJsonParser.parse(base.format("\"color\":\"#12ABcd\"")).document!!.courses[0].color)
        assertEquals("#0A59F7", ScheduleJsonParser.parse(base.format("\"color\":\"blue\"")).document!!.courses[0].color)
    }

}
