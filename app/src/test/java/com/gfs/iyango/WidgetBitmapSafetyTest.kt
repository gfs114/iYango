package com.gfs.iyango

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.platform.android.WakeUpCanvasRenderer
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 位图内存防护（文档 8.2.5）：周视图切片与背景图解码后的 allocationByteCount 均小于 1.5MB。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetBitmapSafetyTest {
    private val context = RuntimeEnvironment.getApplication()
    private val binderBudgetBytes = (1.5f * 1024 * 1024).toInt()

    private fun occurrence(index: Int, startSection: Int, endSection: Int) = CourseOccurrence(
        "c$index",
        Course(id = index.toLong(), name = "课程$index", classroom = "A101", startSection = startSection, endSection = endSection),
        LocalDate.of(2026, 11, 2).plusDays((index % 5).toLong()), index * 60, index * 60 + 45, false,
    )

    @Test fun weekGridSlicesStayUnderBinderBudget() {
        // 12 节 × 96dp 行高（density=1 → 1152px）；再加宽画布验证字节预算自适应切片。
        val grid = WakeUpCanvasRenderer.renderWeekGridBitmap(
            widthPx = 720,
            occurrences = (1..12).map { occurrence(it, startSection = it, endSection = it) },
            density = 1f,
            itemHeightDp = 96,
            radiusDp = 4,
            itemAlpha = 50,
            otherWeekCourseAlpha = 50,
            textSizeSp = 12,
            courseTextColor = 0xFFFFFFFF.toInt(),
            strokeColor = 0x80FFFFFF.toInt(),
            showSat = true,
            showSun = true,
            showLocation = true,
            showRoomPrefix = true,
            showTeacher = true,
            showTime = false,
            showGrid = false,
            useDottedLine = false,
            showOtherWeekCourse = false,
        )
        val slices = WakeUpCanvasRenderer.sliceVertical(grid)
        assertTrue("tall grid must be sliced", slices.size >= 2)
        slices.forEachIndexed { index, slice ->
            assertTrue(
                "slice $index is ${slice.allocationByteCount} bytes",
                slice.allocationByteCount < binderBudgetBytes,
            )
        }
    }

    @Test fun compactWeekGridStaysSingleSlice() {
        val grid = WakeUpCanvasRenderer.renderWeekGridBitmap(
            widthPx = 250,
            occurrences = listOf(occurrence(1, 1, 2)),
            density = 1f,
            itemHeightDp = 64,
            radiusDp = 4,
            itemAlpha = 50,
            otherWeekCourseAlpha = 50,
            textSizeSp = 12,
            courseTextColor = 0xFFFFFFFF.toInt(),
            strokeColor = 0x80FFFFFF.toInt(),
            showSat = true,
            showSun = true,
            showLocation = true,
            showRoomPrefix = true,
            showTeacher = true,
            showTime = false,
            showGrid = false,
            useDottedLine = false,
            showOtherWeekCourse = false,
        )
        assertTrue(grid.allocationByteCount < binderBudgetBytes)
        assertEquals(1, WakeUpCanvasRenderer.sliceVertical(grid).size)
    }

    @Test fun customBackgroundIsDownsampledUnderBudget() {
        // 2000×3000 的 ARGB_8888 源图（约 23MB）必须被降采样到预算内。
        val source = Bitmap.createBitmap(2000, 3000, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GRAY)
        Canvas(source).drawColor(Color.rgb(30, 90, 200))
        val file = File(context.cacheDir, "widget_bg_test.jpg")
        FileOutputStream(file).use { output -> source.compress(Bitmap.CompressFormat.JPEG, 95, output) }
        val loaded = WakeUpCanvasRenderer.loadBackgroundBitmap(file)
        assertNotNull(loaded)
        assertTrue(
            "background is ${loaded!!.allocationByteCount} bytes",
            loaded.allocationByteCount <= WakeUpCanvasRenderer.MAX_BACKGROUND_BYTES,
        )
        assertTrue(loaded.width <= WakeUpCanvasRenderer.MAX_BACKGROUND_WIDTH * 2)
        assertTrue(loaded.height <= WakeUpCanvasRenderer.MAX_BACKGROUND_HEIGHT * 2)
        file.delete()
        source.recycle()
    }

    @Test fun missingOrBrokenBackgroundFilesReturnNull() {
        assertNull(WakeUpCanvasRenderer.loadBackgroundBitmap(File(context.cacheDir, "definitely-missing.jpg")))
        val broken = File(context.cacheDir, "widget_bg_broken.jpg")
        broken.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertNull(WakeUpCanvasRenderer.loadBackgroundBitmap(broken))
        broken.delete()
    }
}
