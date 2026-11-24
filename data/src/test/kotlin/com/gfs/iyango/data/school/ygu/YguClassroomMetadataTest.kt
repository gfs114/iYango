package com.gfs.iyango.data.school.ygu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教室查询条件页解析测试（F04）。
 *
 * 覆盖：真实 select/checkbox 读取、隐藏控件忽略、第十一节标签校正、
 * 登录页识别与教学楼字典容错。
 */
class YguClassroomMetadataTest {

    @Test
    fun `读取学期校区时间模式周次星期与节次`() {
        val result = YguClassroomMetadata.parse(sampleHtml())
        assertEquals(YguClassroomMetadataStatus.OK, result.status)
        val options = result.options
        assertEquals(2, options.semesters.size)
        assertEquals("2025-1", options.semesters.first().value)
        assertTrue(options.semesters.first().selected)
        assertEquals("01", options.campuses.single().value)
        assertEquals("按周", options.timeModes.single().label)
        assertEquals("8", options.weeks.single().value)
        assertEquals("1", options.days.single().value)
        assertTrue(options.periods.any { it.value == "12" })
        assertTrue(options.periods.any { it.value == "1112" })
    }

    @Test
    fun `第十一节复选框标签统一校正`() {
        val result = YguClassroomMetadata.parse(sampleHtml())
        val eleventh = result.options.periods.first { it.value == "1112" }
        assertEquals("第十一节", eleventh.label)
    }

    @Test
    fun `隐藏和禁用控件被忽略`() {
        val result = YguClassroomMetadata.parse(sampleHtml())
        assertTrue(result.options.roomTypes.isEmpty())
        assertTrue(result.options.buildings.isEmpty())
    }

    @Test
    fun `没有可用条件时判定不支持`() {
        val result = YguClassroomMetadata.parse("<html><body><p>空页面</p></body></html>")
        assertEquals(YguClassroomMetadataStatus.UNSUPPORTED, result.status)
    }

    @Test
    fun `登录页返回登录状态`() {
        val result = YguClassroomMetadata.parse(
            "<html><head><title>登录</title></head><body></body></html>",
        )
        assertEquals(YguClassroomMetadataStatus.LOGIN, result.status)
    }

    @Test
    fun `教学楼字典读取代码与名称`() {
        val result = YguClassroomMetadata.parseBuildings(
            """[{"dm":"B1","dmmc":"第一教学楼"},{"dm":"B2","dmmc":"实验楼"}]""",
        )
        assertEquals(2, result?.size)
        assertEquals("B1", result?.get(0)?.value)
        assertEquals("第一教学楼", result?.get(0)?.label)
    }

    @Test
    fun `教学楼空数组是成功但无楼`() {
        val result = YguClassroomMetadata.parseBuildings("[]")
        assertTrue(result != null && result.isEmpty())
    }

    @Test
    fun `教学楼字典结构异常返回 null`() {
        assertNull(YguClassroomMetadata.parseBuildings("""[{"dm":"B1"}]"""))
        assertNull(YguClassroomMetadata.parseBuildings("""[{"dm":"B1","dmmc":"A"},{"dm":"B1","dmmc":"B"}]"""))
    }

    @Test
    fun `周次只接受 1 到 60`() {
        assertEquals(8, YguClassroomMetadata.parsedWeek("8"))
        assertEquals(0, YguClassroomMetadata.parsedWeek("0"))
        assertEquals(0, YguClassroomMetadata.parsedWeek("61"))
        assertEquals(0, YguClassroomMetadata.parsedWeek("第8周"))
    }

    private fun sampleHtml(): String = """
        <html><body><form>
        <select name="xnxqh">
          <option value="2025-1" selected>2025-2026学年第1学期</option>
          <option value="2024-2">2024-2025学年第2学期</option>
        </select>
        <select name="xqbh"><option value="01" selected>主校区</option></select>
        <select name="kbjcmsid"><option value="1" selected>按周</option></select>
        <select name="selectZc"><option value="8" selected>第8周</option></select>
        <select name="selectXq"><option value="1" selected>星期一</option></select>
        <select name="selectJc"><option value="12">第1-2节</option></select>
        <select name="jslx" hidden><option value="x">多媒体教室</option></select>
        <select name="jxlbh" disabled><option value="B1">第一教学楼</option></select>
        <ul>
          <li><i>第一节</i><input type="checkbox" name="jc" value="12" checked></li>
          <li><i>第十一十二节</i><input type="checkbox" name="jc" value="1112"></li>
        </ul>
        </form></body></html>
    """.trimIndent()
}
