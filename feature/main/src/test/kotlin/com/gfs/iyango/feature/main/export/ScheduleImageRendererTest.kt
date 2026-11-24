package com.gfs.iyango.feature.main.export

import androidx.compose.ui.graphics.toArgb
import com.gfs.iyango.core.designsystem.theme.CourseSwatch
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.TimeSlot
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 离屏周课表渲染器测试（E04）。
 *
 * 使用 Robolectric 原生图形模式真实绘制像素，验证：最后一节不被裁掉、教师/教室开关
 * 生效、长课名扩展列宽、清晰度提升尺寸、周末列按实际课程出现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleImageRendererTest {

    private val weekStart = LocalDate.of(2026, 9, 21)

    @Test
    fun `最后一节课件绘制在画布下半部分 - 不被截断`() {
        val occurrence = occurrence(
            id = 1,
            name = "高等数学",
            day = 5,
            startSection = 11,
            endSection = 12,
            color = "#FF0000",
        )
        val result = ScheduleImageRenderer.render(request(listOf(occurrence)))
        assertEquals(12, result.sectionCount)
        val tint = CourseSwatch.forCourse("#FF0000", 1, dark = false).tint.toArgb()
        val pixels = IntArray(result.widthPx * result.heightPx)
        result.bitmap.getPixels(pixels, 0, result.widthPx, 0, 0, result.widthPx, result.heightPx)
        val bottomQuarter = result.heightPx * 3 / 4
        var tintPixels = 0
        for (y in bottomQuarter until result.heightPx) {
            val row = y * result.widthPx
            for (x in 0 until result.widthPx) {
                if (pixels[row + x] == tint) tintPixels++
            }
        }
        assertTrue("最后一节课程块应出现在画布下四分之一，实际命中 $tintPixels 像素", tintPixels > 100)
    }

    @Test
    fun `教师与教室开关会改变图片内容`() {
        val value = occurrence(id = 2, teacher = "陈老师", classroom = "A301")
        val withTeacher = ScheduleImageRenderer.render(request(listOf(value), showTeacher = true))
        val withoutTeacher = ScheduleImageRenderer.render(request(listOf(value), showTeacher = false))
        val withClassroom = ScheduleImageRenderer.render(request(listOf(value), showClassroom = true))
        val withoutClassroom = ScheduleImageRenderer.render(request(listOf(value), showClassroom = false))
        assertFalse(withTeacher.bitmap.sameAs(withoutTeacher.bitmap))
        assertFalse(withClassroom.bitmap.sameAs(withoutClassroom.bitmap))
    }

    @Test
    fun `长课名按最长文本扩展列宽`() {
        val short = ScheduleImageRenderer.render(
            request(listOf(occurrence(id = 3, name = "高数"))),
        )
        val long = ScheduleImageRenderer.render(
            request(listOf(occurrence(id = 4, name = "大学物理实验（含近代物理实验与数据处理）"))),
        )
        assertTrue("长课名应加宽画布：${long.widthPx} <= ${short.widthPx}", long.widthPx > short.widthPx)
    }

    @Test
    fun `清晰度档位提升输出像素但不改变内容比例`() {
        val value = occurrence(id = 5)
        val standard = ScheduleImageRenderer.render(
            request(listOf(value), resolution = ExportResolution.STANDARD),
        )
        val high = ScheduleImageRenderer.render(
            request(listOf(value), resolution = ExportResolution.HIGH),
        )
        val ultra = ScheduleImageRenderer.render(
            request(listOf(value), resolution = ExportResolution.ULTRA),
        )
        assertTrue(high.widthPx > standard.widthPx)
        assertTrue(ultra.widthPx > high.widthPx)
        val standardRatio = standard.widthPx.toFloat() / standard.heightPx
        val ultraRatio = ultra.widthPx.toFloat() / ultra.heightPx
        assertEquals(standardRatio, ultraRatio, 0.02f)
        assertTrue(ultra.widthPx <= ScheduleImageRenderer.MAX_DIMENSION_PX)
        assertTrue(ultra.heightPx <= ScheduleImageRenderer.MAX_DIMENSION_PX)
        assertTrue(ultra.widthPx.toLong() * ultra.heightPx <= ScheduleImageRenderer.MAX_PIXELS)
    }

    @Test
    fun `周末有课才展示七列`() {
        val weekday = occurrence(id = 6, day = 5)
        val weekend = occurrence(id = 7, day = 6)
        assertEquals(5, ScheduleImageRenderer.visibleDayCount(listOf(weekday)))
        assertEquals(7, ScheduleImageRenderer.visibleDayCount(listOf(weekday, weekend)))
        assertEquals(5, ScheduleImageRenderer.render(request(listOf(weekday))).columnCount)
        assertEquals(7, ScheduleImageRenderer.render(request(listOf(weekday, weekend))).columnCount)
    }

    @Test
    fun `空周仍生成可保存的占位图片`() {
        val result = ScheduleImageRenderer.render(request(emptyList()))
        assertNotNull(result.bitmap)
        assertTrue(result.widthPx > 0)
        assertTrue(result.heightPx > 0)
        assertEquals(0, result.sectionCount)
    }


    @Test fun `official eleven and local long course use distinct bounds`() {
        val official=ScheduleImageRenderer.render(request(listOf(occurrence(30,startSection=11,endSection=11))).copy(source="YGU"))
        val local=ScheduleImageRenderer.render(request(listOf(occurrence(31,startSection=32,endSection=35))).copy(source="LOCAL"))
        assertEquals(11,official.sectionCount);assertEquals(35,local.sectionCount)
        assertTrue(local.heightPx > official.heightPx)
    }
    @Test fun `lunch preference expands image only with explicit noon gap`() {
        val value=request(listOf(occurrence(32,startSection=1,endSection=2))).copy(timeSlots=listOf(
            TimeSlot(section=1,startTime="11:00",endTime="11:45"),TimeSlot(section=2,startTime="13:00",endTime="13:45")))
        val plain=ScheduleImageRenderer.render(value)
        val lunch=ScheduleImageRenderer.render(value.copy(display=value.display.copy(showLunchBreak=true)))
        assertTrue(lunch.heightPx>plain.heightPx)
    }

    @Test fun `fixed official export retains temporary local fourteen and source bells`() {
        val c=occurrence(40,startSection=14,endSection=14).copy(startMinute=630,endMinute=660,isTemporary=true)
        val sourceSlots=listOf(TimeSlot(section=14,startTime="10:00",endTime="11:00"))
        val owner=com.gfs.iyango.core.model.Timetable(id=1,source="YGU")
        val days=(0L..6L).map { day -> com.gfs.iyango.core.domain.EffectiveScheduleDay(weekStart.plusDays(day),owner,
            displayTimeSlots=sourceSlots,displaySource="LOCAL") }
        val value=request(listOf(c)).copy(source="YGU",displayDays=days)
        val image=ScheduleImageRenderer.render(value)
        assertEquals(14,image.sectionCount)
        val changedOwner=ScheduleImageRenderer.render(value.copy(timeSlots=listOf(TimeSlot(section=1,startTime="01:00",endTime="02:00"))))
        assertTrue(image.bitmap.sameAs(changedOwner.bitmap))
        val official=ScheduleImageRenderer.render(value.copy(displayDays=days.map { it.copy(displaySource="YGU") }))
        assertEquals(11,official.sectionCount)
    }
    private fun request(
        occurrences: List<CourseOccurrence>,
        showTeacher: Boolean = true,
        showClassroom: Boolean = true,
        resolution: ExportResolution = ExportResolution.STANDARD,
    ) = ScheduleImageRequest(
        timetableName = "我的课表",
        week = 1,
        weekStart = weekStart,
        occurrences = occurrences,
        timeSlots = (1..12).map { section ->
            TimeSlot(
                timetableId = 1,
                section = section,
                startTime = "%02d:00".format(7 + section),
                endTime = "%02d:45".format(7 + section),
            )
        },
        display = ScheduleDisplaySettings(),
        showTeacher = showTeacher,
        showClassroom = showClassroom,
        resolution = resolution,
    )

    private fun occurrence(
        id: Long,
        name: String = "高等数学",
        day: Int = 1,
        startSection: Int = 1,
        endSection: Int = 2,
        teacher: String = "陈老师",
        classroom: String = "A301",
        color: String = "#3E7BFA",
    ): CourseOccurrence {
        val course = Course(
            id = id,
            timetableId = 1,
            name = name,
            teacher = teacher,
            classroom = classroom,
            dayOfWeek = day,
            startSection = startSection,
            endSection = endSection,
            color = color,
        )
        return CourseOccurrence(
            occurrenceId = "occ-$id",
            course = course,
            date = weekStart.plusDays((day - 1).toLong()),
            startMinute = CourseRules.minutes("%02d:00".format(7 + startSection)),
            endMinute = CourseRules.minutes("%02d:45".format(7 + endSection)),
            isOverride = false,
        )
    }
}
