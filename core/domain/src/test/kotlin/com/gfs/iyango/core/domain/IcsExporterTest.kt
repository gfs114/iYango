package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Course
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ICS 文件导出测试（E04）。
 *
 * 覆盖稳定 UID、UTC 时区、文本转义/中文折行、无效时间跳过、调课说明与默认无闹钟。
 */
class IcsExporterTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 9, 20, 12, 0)

    @Test
    fun `同一命名空间与实例在多次导出间保持稳定 UID`() {
        val occurrence = occurrence(id = "abc123", name = "高等数学")
        val first = IcsExporter.export(listOf(occurrence), zone, "device-1", now)
        val second = IcsExporter.export(listOf(occurrence), zone, "device-1", now)
        assertEquals(first.content, second.content)
        assertTrue(first.content.contains("UID:abc123@device-1"))
        assertEquals(1, first.eventCount)
    }

    @Test
    fun `本地时刻按课程时区转换为 UTC`() {
        val occurrence = occurrence(
            id = "u1",
            startMinute = 8 * 60,
            endMinute = 9 * 60 + 40,
        )
        val result = IcsExporter.export(listOf(occurrence), zone, "ns", now)
        assertTrue(result.content.contains("DTSTART:20260921T000000Z"))
        assertTrue(result.content.contains("DTEND:20260921T014000Z"))
    }

    @Test
    fun `标题中的逗号分号与换行按 RFC5545 转义`() {
        val occurrence = occurrence(id = "u2", name = "数学,高数;实验")
        val content = IcsExporter.export(listOf(occurrence), zone, "ns", now).content
        assertTrue(content.contains("SUMMARY:数学\\,高数\\;实验"))
    }

    @Test
    fun `中文长行按 UTF-8 字节折叠且不拆坏字符`() {
        val longName = "跨学科综合实践与创新创业训练课程设计（含实验报告与展示环节）".repeat(3)
        val content = IcsExporter.export(
            occurrences = listOf(occurrence(id = "u3", name = longName)),
            zoneId = zone,
            namespace = "ns",
            now = now,
        ).content
        content.split("\r\n").forEach { line ->
            val bytes = line.toByteArray(StandardCharsets.UTF_8)
            assertTrue("行超过 75 字节：${bytes.size}", bytes.size <= 75)
            assertFalse("折行不应产生替换字符", line.contains('\uFFFD'))
        }
    }

    @Test
    fun `时间待定的实例跳过并说明原因`() {
        val occurrence = occurrence(id = "u4", startMinute = null, endMinute = null)
        val result = IcsExporter.export(listOf(occurrence), zone, "ns", now)
        assertEquals(0, result.eventCount)
        assertTrue(result.skipped.any { it.contains("时间待定") })
        assertFalse(result.content.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `默认不附加提醒 - 显式开启才输出 VALARM`() {
        val occurrence = occurrence(id = "u5")
        val plain = IcsExporter.export(listOf(occurrence), zone, "ns", now)
        assertFalse(plain.content.contains("BEGIN:VALARM"))
        val withAlarm = IcsExporter.export(
            occurrences = listOf(occurrence),
            zoneId = zone,
            namespace = "ns",
            now = now,
            includeReminder = true,
            reminderMinutes = 15,
        )
        assertTrue(withAlarm.content.contains("BEGIN:VALARM"))
        assertTrue(withAlarm.content.contains("TRIGGER:-PT15M"))
    }

    @Test
    fun `调课与临时整天在描述中标注`() {
        val moved = occurrence(id = "u6").copy(isMoved = true)
        val temporary = occurrence(id = "u7").copy(isTemporary = true)
        val content = IcsExporter.export(listOf(moved, temporary), zone, "ns", now).content
        // 描述行较长时会被折叠，断言前先还原续行。
        val unfolded = content.replace("\r\n ", "")
        assertTrue(unfolded.contains("（由调课产生）"))
        assertTrue(unfolded.contains("（临时整天课表）"))
    }

    private fun occurrence(
        id: String,
        name: String = "高等数学",
        startMinute: Int? = 8 * 60,
        endMinute: Int? = 9 * 60 + 40,
    ): CourseOccurrence {
        val course = Course(
            id = 1,
            timetableId = 1,
            name = name,
            teacher = "陈老师",
            classroom = "A301",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
        )
        return CourseOccurrence(
            occurrenceId = id,
            course = course,
            date = LocalDate.of(2026, 9, 21),
            startMinute = startMinute,
            endMinute = endMinute,
            isOverride = false,
        )
    }
}
