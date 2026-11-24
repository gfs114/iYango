package com.gfs.iyango.data.school.ygu

import com.gfs.iyango.core.model.ClassroomCellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教室占用响应解析测试（F04）。
 *
 * 覆盖：状态格语义、整包长度校验、节次组重复/时钟/顺序校验、
 * 第十一节显示校正与原始时钟保留。
 */
class YguClassroomParserTest {

    @Test
    fun `空教室行返回空结果并保留节次组`() {
        val body = """[[["12","第1-2节","08:00","09:40"]],[],[],[],[]]"""
        val result = YguClassroomParser.parse(body)
        assertEquals(YguClassroomParseStatus.EMPTY, result.status)
        assertEquals(1, result.periodGroups.size)
        assertTrue(result.rooms.isEmpty())
    }

    @Test
    fun `状态格按标记分类且空值表示空闲`() {
        val row = roomRow(
            listOf(
                null,
                "shangke",
                "jieyong",
                "已排考",
                null,
                null,
                null,
            ),
        )
        val result = YguClassroomParser.parse(body(groups(1), row))
        assertEquals(YguClassroomParseStatus.OK, result.status)
        val room = result.rooms.single()
        assertEquals(ClassroomCellState.FREE, room.cell(1, 0)?.state)
        assertEquals(ClassroomCellState.COURSE, room.cell(2, 0)?.state)
        assertEquals(ClassroomCellState.BORROWED, room.cell(3, 0)?.state)
        assertEquals(ClassroomCellState.UNKNOWN, room.cell(4, 0)?.state)
        assertEquals("A101", room.identifier)
        assertEquals("60座", room.seatInfo)
        assertEquals("普通教室", room.roomType)
    }

    @Test
    fun `行长度不等于七倍节次组加四时整包不可读`() {
        val shortRow = """["A101",null,null,null,null,null,null,"A101","60座","普通教室"]"""
        val result = YguClassroomParser.parse(body(groups(1), shortRow))
        assertEquals(YguClassroomParseStatus.INVALID, result.status)
    }

    @Test
    fun `非字符串状态格不可读`() {
        val row = """["A101",1,null,null,null,null,null,null,"A101","60座","普通教室"]"""
        val result = YguClassroomParser.parse(body(groups(1), row))
        assertEquals(YguClassroomParseStatus.INVALID, result.status)
    }

    @Test
    fun `重复节次组编码不可读`() {
        val duplicate = """[["12","第1-2节","08:00","09:40"],["12","第1-2节","08:00","09:40"]]"""
        val result = YguClassroomParser.parse(body(duplicate, ""))
        assertEquals(YguClassroomParseStatus.INVALID, result.status)
    }

    @Test
    fun `时钟格式或顺序异常不可读`() {
        val badClock = """[["12","第1-2节","8:00","09:40"]]"""
        assertEquals(
            YguClassroomParseStatus.INVALID,
            YguClassroomParser.parse(body(badClock, "")).status,
        )
        val reversed = """[["12","第1-2节","08:00","09:40"],["34","第3-4节","07:00","08:30"]]"""
        assertEquals(
            YguClassroomParseStatus.INVALID,
            YguClassroomParser.parse(body(reversed, "")).status,
        )
    }

    @Test
    fun `第十一节统一显示并保留原始时钟`() {
        val eleventh = """[["1112","第十一十二节","19:00","21:30"]]"""
        val result = YguClassroomParser.parse(body(eleventh, ""))
        assertEquals(YguClassroomParseStatus.EMPTY, result.status)
        val group = result.periodGroups.single()
        assertEquals("第十一节", group.label)
        assertEquals("20:10", group.startTime)
        assertEquals("20:55", group.endTime)
        assertEquals("19:00", group.sourceStartTime)
        assertEquals("21:30", group.sourceEndTime)
        assertTrue(group.isEleventh)
    }

    @Test
    fun `学校对象格式节次组可解析`() {
        val groups = """[{"jcb1":"01","jcb2":"02","djmc":"第1-2节","kssj":"08:00","jssj":"09:40"}]"""
        val result = YguClassroomParser.parse(body(groups, ""))
        assertEquals(YguClassroomParseStatus.EMPTY, result.status)
        assertEquals("0102", result.periodGroups.single().rawCode)
        assertEquals("第1-2节", result.periodGroups.single().label)
    }

    @Test
    fun `尾部文本解码 HTML 实体`() {
        val row = """["A101",null,null,null,null,null,null,null,"A&amp;B","60座","普通教室"]"""
        val result = YguClassroomParser.parse(body(groups(1), row))
        assertEquals(YguClassroomParseStatus.OK, result.status)
        assertEquals("A&B", result.rooms.single().identifier)
    }

    @Test
    fun `登录页返回登录状态`() {
        val result = YguClassroomParser.parse(
            """<html><head><title>登录</title></head><body>请先登录系统</body></html>""",
        )
        assertEquals(result.message, YguClassroomParseStatus.LOGIN, result.status)
    }

    private fun groups(count: Int): String {
        val starts = listOf("08:00", "10:00", "14:00", "16:00")
        return (0 until count).joinToString(prefix = "[", postfix = "]") { index ->
            """["${index + 1}${index + 1}","第${index * 2 + 1}-${index * 2 + 2}节","${starts[index]}","09:40"]"""
        }
    }

    private fun roomRow(cells: List<String?>): String {
        val states = cells.joinToString(",") { value ->
            if (value == null) "null" else "\"$value\""
        }
        return """["A101",$states,"A101","60座","普通教室"]"""
    }

    private fun body(groupsJson: String, rows: String): String =
        "[$groupsJson,[],[],[],[${rows.ifBlank { "" }}]]"
}
