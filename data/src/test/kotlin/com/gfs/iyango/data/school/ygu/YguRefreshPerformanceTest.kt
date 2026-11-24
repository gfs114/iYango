package com.gfs.iyango.data.school.ygu

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YguRefreshPerformanceTest {

    @Test
    fun `timetable fast path uses 1 GET on cached template and falls back on template mismatch`() = runTest {
        val term = "2026-2027-1"
        val templateId = "tmpl-abc"
        val directUrl = YguHttpMetadata.navigateUrl(term, templateId, "")
        val entryUrl = "${YguConfig.COURSE_ENTRY}?viweType=0"

        val header = "<tr><th>节次</th>" +
            listOf("一", "二", "三", "四", "五", "六", "日").joinToString("") { "<th>星期$it</th>" } + "</tr>"
        val emptyRow = "<tr><th>第一节</th>" + "<td></td>".repeat(7) + "</tr>"

        fun pageHtml(tmpl: String, includeTable: Boolean): String = """
            <html><body>
              <form action="/jsxsd/xskb/xskb_list.do">
                <select id="xnxq01id" name="xnxq01id">
                  <option value="$term" selected="selected">2026-2027-1</option>
                </select>
                <select id="kbjcmsid" name="kbjcmsid">
                  <option value="$tmpl" selected="selected">节次模板</option>
                </select>
              </form>
              ${if (includeTable) "<table id=\"kbtable\">$header$emptyRow</table>" else ""}
            </body></html>
        """.trimIndent()

        val requestedUrls = mutableListOf<String>()
        val fastClient = YguTimetableClient { url, _, _ ->
            requestedUrls += url
            YguHttpResponse(200, pageHtml(templateId, includeTable = true), emptyList(), "")
        }
        val cachedMeta = YguTimetableMetadata(
            status = "timetable",
            navigateUrl = directUrl,
            schoolTerm = term,
            templateId = templateId,
            allWeeks = true,
            semesters = listOf(term),
            semesterLabels = mapOf(term to term),
        )
        val fastRead = fastClient.readTimetable("bzb_jsxsd=session-1", term, cachedMeta)
        assertNotNull(fastRead.payload)
        assertEquals(listOf(directUrl), requestedUrls)

        // When school changes templateId on server, fast path misses and falls back to entry + target URL.
        val fallbackUrls = mutableListOf<String>()
        val newTemplateId = "tmpl-new"
        val newTargetUrl = YguHttpMetadata.navigateUrl(term, newTemplateId, "")
        val fallbackClient = YguTimetableClient { url, _, _ ->
            fallbackUrls += url
            when (url) {
                entryUrl -> YguHttpResponse(200, pageHtml(newTemplateId, includeTable = false), emptyList(), "")
                newTargetUrl -> YguHttpResponse(200, pageHtml(newTemplateId, includeTable = true), emptyList(), "")
                else -> YguHttpResponse(200, pageHtml(newTemplateId, includeTable = true), emptyList(), "")
            }
        }
        val fallbackRead = fallbackClient.readTimetable("bzb_jsxsd=session-1", term, cachedMeta)
        assertNotNull(fallbackRead.payload)
        assertEquals(listOf(directUrl, entryUrl, newTargetUrl), fallbackUrls)
    }

    @Test
    fun `modern JSON grades client fetches pages 2 to 4 concurrently and preserves order`() = runTest {
        val formHtml = """
            <html><body>
              <form id="dataSearch" lay-filter="search">
                <select name="kksj"><option value="" selected="selected">全部</option></select>
                <input type="hidden" name="xsfs" value="all" />
                <input type="hidden" name="sfxsbcxq" value="1" />
              </form>
              <table id="cjcx_table" lay-filter="test" data-search-id="search"></table>
            </body></html>
        """.trimIndent()

        fun jsonPage(pageNum: Int, total: Int): String {
            val startIdx = (pageNum - 1) * 20 + 1
            val endIdx = minOf(total, pageNum * 20)
            val rows = (startIdx..endIdx).joinToString(",") { idx ->
                """{"rownum_":$idx,"kc_mc":"课程$idx","zcj":90,"zcjstr":"90","xf":"2.0","xnxqid":"2026-2027-1"}"""
            }
            return """{"code":0,"count":$total,"isXsckpscj":1,"data":[$rows]}"""
        }

        val activeExtraPages = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val client = YguGradesClient(
            get = { url, _, _, ajax ->
                if (!ajax) {
                    YguHttpResponse(200, formHtml, emptyList(), "")
                } else {
                    val pageMatch = Regex("pageNum=(\\d+)").find(url)!!
                    val pageNum = pageMatch.groupValues[1].toInt()
                    if (pageNum > 1) {
                        val active = activeExtraPages.incrementAndGet()
                        maxConcurrent.updateAndGet { maxOf(it, active) }
                        delay(30)
                        activeExtraPages.decrementAndGet()
                    }
                    YguHttpResponse(200, jsonPage(pageNum, total = 80), emptyList(), "")
                }
            },
        )

        val outcome = client.fetch("bzb_jsxsd=session-1", "")
        assertEquals(outcome.message, YguGradesClientStatus.OK, outcome.status)
        assertEquals(80, outcome.records.size)
        assertEquals("课程1", outcome.records.first().courseName)
        assertEquals("课程80", outcome.records.last().courseName)
        assertTrue("Expected concurrent fetches for pages 2..4, got max=$maxConcurrent", maxConcurrent.get() >= 2)
    }
}
