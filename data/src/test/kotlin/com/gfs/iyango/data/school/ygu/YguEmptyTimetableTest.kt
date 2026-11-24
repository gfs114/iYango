package com.gfs.iyango.data.school.ygu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class YguEmptyTimetableTest {
    private val header = "<tr><th>节次</th>" +
        listOf("一", "二", "三", "四", "五", "六", "日").joinToString("") { "<th>星期$it</th>" } + "</tr>"

    private fun row(label: String = "第一节", first: String = "", cells: Int = 7) =
        "<tr><th>$label</th><td>$first</td>" + "<td></td>".repeat(cells - 1) + "</tr>"

    @Test fun `complete empty grid is a successful parse`() {
        val result = YguTimetableParser.parse("<table>$header${row()}${row("第二节")}</table>")
        assertTrue(result.courses.isEmpty())
        assertTrue(result.issues.toString(), result.issues.isEmpty())
    }

    @Test fun `header alone cannot establish an empty timetable`() {
        assertTrue(YguTimetableParser.parse("<table>$header</table>").issues.isNotEmpty())
    }

    @Test fun `missing day cell cannot establish an empty timetable`() {
        assertTrue(YguTimetableParser.parse("<table>$header${row(cells = 6)}</table>").issues.isNotEmpty())
    }

    @Test fun `missing period label cannot establish an empty timetable`() {
        assertTrue(YguTimetableParser.parse("<table>$header${row(label = "")}</table>").issues.isNotEmpty())
    }

    @Test fun `malformed merged cells cannot establish an empty timetable`() {
        val html = "<table>$header<tr><td>第一节</td><td colspan='0'></td></tr></table>"
        assertTrue(YguTimetableParser.parse(html).issues.isNotEmpty())
    }

    @Test fun `unsupported course stays a failure instead of empty`() {
        val result = YguTimetableParser.parse("<table>$header${row(first = "未知课程格式")}</table>")
        assertTrue(result.courses.isEmpty())
        assertTrue(result.issues.isNotEmpty())
    }

    @Test fun `normal course retains period and week parsing`() {
        val result = YguTimetableParser.parse("<table>$header${row(first = "高等数学<br>周次：1-16周")}</table>")
        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals("高等数学", result.courses.single().name)
        assertEquals(1, result.courses.single().startSection)
        assertEquals(16, result.courses.single().endWeek)
    }

    @Test fun `unrecognized table remains a failure`() {
        assertTrue(YguTimetableParser.parse("<table><tr><td>暂无数据</td></tr></table>").issues.isNotEmpty())
    }
    @Test fun `empty read returns a payload through client selection`() = runTest {
        val html = "<table>$header${row()}</table>"
        val result = selectTable(html)
        assertEquals("", result.errorMessage)
        assertEquals(html, result.payload?.body)
        assertTrue(YguTimetableParser.parse(result.payload!!.body).verifiedEmpty)
    }

    @Test fun `client rejects a malformed second table beside empty grid`() = runTest {
        val result = selectTable("<table>$header${row()}</table><table>$header</table>")
        assertTrue(result.errorMessage.isNotEmpty())
        assertEquals(null, result.payload)
    }

    @Test fun `client rejects conflicting empty and populated grids`() = runTest {
        val html = "<table>$header${row()}</table>" +
            "<table>$header${row(first = "高等数学<br>周次：1-16周")}</table>"
        assertTrue(selectTable(html).errorMessage.isNotEmpty())
    }

    @Test fun `missing middle period cannot establish empty timetable`() {
        val result = YguTimetableParser.parse("<table>$header${row()}${row("第三节")}</table>")
        assertTrue(result.issues.isNotEmpty())
    }

    @Test fun `weekday looking prose cannot establish empty timetable`() {
        val result = YguTimetableParser.parse("<table>${header.replace("星期一", "星期一课程说明")}${row()}</table>")
        assertTrue(result.issues.isNotEmpty())
    }

    // Exercise local table selection without a school HTTP request or a production test hook.
    private suspend fun selectTable(html: String): YguDirectRead =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = YguTimetableClient::class.java.getDeclaredMethod(
                "readFramedTable", YguHttpResponse::class.java, String::class.java,
                YguTimetableMetadata::class.java, Int::class.javaPrimitiveType,
                Continuation::class.java,
            )
            method.isAccessible = true
            method.invoke(
                YguTimetableClient(), YguHttpResponse(200, html, emptyList(), ""),
                "https://jwgl.ygu.edu.cn/jsxsd/xskb/xskb_list.do",
                YguTimetableMetadata(), 0, continuation,
            )
        }

}
