package com.gfs.iyango

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.os.Bundle
import android.appwidget.AppWidgetManager
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.WidgetConfig
import com.gfs.iyango.core.model.WidgetLayout
import com.gfs.iyango.platform.android.ScheduleWidgets
import com.gfs.iyango.platform.android.WidgetPresentation
import java.time.LocalDate
import android.view.View
import androidx.core.graphics.ColorUtils
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real drawable rasterization; exported previews are local composites, not device screenshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGlassDrawableTest {
    @Test fun dayGlassTransmitsWallpaperPattern() = renderAndCheck("day")

    @Test @Config(qualifiers = "night-mdpi")
    fun nightGlassTransmitsWallpaperPattern() = renderAndCheck("night")

    private fun renderAndCheck(mode: String) {
        val context = RuntimeEnvironment.getApplication()
        val drawable = context.getDrawable(R.drawable.widget_background_transparent)!!
        val bitmap = Bitmap.createBitmap(340, 180, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        val wallpapers = listOf(Color.BLACK, Color.WHITE, Color.rgb(255, 0, 128))
        val contrastBackgrounds = wallpapers + listOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.YELLOW)
        val textColors = listOf(R.color.widget_text_primary, R.color.widget_text_secondary, R.color.widget_accent)
        val minimumContrast = textColors.associateWith { Double.POSITIVE_INFINITY }.toMutableMap()
        for (y in 20 until 160) {
            val pixel = bitmap.getPixel(170, y)
            assertTrue("Glass body alpha must stay within 10%-35% at row $y",
                Color.alpha(pixel) / 255.0 in 0.10..0.35)
            val light = ColorUtils.compositeColors(pixel, Color.WHITE)
            val dark = ColorUtils.compositeColors(pixel, Color.BLACK)
            val retainedPattern = (Color.red(light) - Color.red(dark)) / 255.0
            assertTrue("Glass must preserve at least 60% of pattern amplitude", retainedPattern >= 0.60)
            for (wallpaper in contrastBackgrounds) {
                val surface = ColorUtils.compositeColors(pixel, wallpaper)
                for (text in textColors) {
                    val contrast = ColorUtils.calculateContrast(context.getColor(text), surface)
                    minimumContrast[text] = minOf(minimumContrast.getValue(text), contrast)
                }
            }
        }
        assertTrue("Glass should have a tonal gradient", bitmap.getPixel(170, 20) != bitmap.getPixel(170, 159))
        minimumContrast.forEach { (text, contrast) ->
            println("Diagnostic only; arbitrary wallpaper readability is not guaranteed: $mode ${context.resources.getResourceEntryName(text)} minimum contrast=$contrast")
        }

        val layouts = listOf(R.layout.widget_preview, R.layout.widget_wakeup_classic_shell,
            R.layout.widget_today_4x4, R.layout.widget_ruler_4x4)
        val preview = Bitmap.createBitmap(1360, 1032, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview)
        canvas.drawColor(Color.rgb(30, 30, 35))
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f }
        listOf("明亮图案 原壁纸", "明亮图案 组件覆盖", "深色图案 原壁纸", "深色图案 组件覆盖")
            .forEachIndexed { column, text -> canvas.drawText(text, column * 340f + 12, 22f, labelPaint) }
        layouts.forEachIndexed { row, layout ->
            repeat(4) { column ->
                val height = if (row < 2) 180 else 320
                val top = if (row < 2) row * 180 else 360 + (row - 2) * 320
                canvas.save()
                canvas.translate(column * 340f, top + 32f)
                canvas.clipRect(0, 0, 340, height)
                drawPattern(canvas, height, dark = column >= 2)
                if (column % 2 == 0) {
                    canvas.restore()
                    return@repeat
                }
                val remote = renderRemote(layout, height)
                val view = remote.apply(context, FrameLayout(context))
                view.measure(View.MeasureSpec.makeMeasureSpec(340, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 340, height)
                view.draw(canvas)
                canvas.restore()
            }
        }
        val output = File("build/reports/widget-glass-preview/$mode.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val comparison = Bitmap.createBitmap(1020, 352, Bitmap.Config.ARGB_8888)
        val compareCanvas = Canvas(comparison)
        compareCanvas.drawColor(Color.rgb(30, 30, 35))
        listOf(0 to "全透明 0%", 25 to "轻玻璃 25%", 50 to "柔雾 50%").forEachIndexed { index, (opacity, label) ->
            compareCanvas.save()
            compareCanvas.translate(index * 340f, 0f)
            compareCanvas.drawText(label, 12f, 22f, labelPaint)
            compareCanvas.translate(0f, 32f)
            drawPattern(compareCanvas, 320, mode == "night")
            val view = renderRemote(R.layout.widget_today_4x4, 320, opacity).apply(context, FrameLayout(context))
            view.measure(View.MeasureSpec.makeMeasureSpec(340, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 340, 320)
            view.draw(compareCanvas)
            compareCanvas.restore()
        }
        File(output.parentFile, "opacity-$mode.png").outputStream().use { comparison.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun renderRemote(layoutRes: Int, height: Int, opacity: Int = 25): RemoteViews {
        val context = RuntimeEnvironment.getApplication()
        val date = LocalDate.of(2026, 11, 5)
        val names = listOf("高等数学", "大学英语", "程序设计", "大学物理", "数据结构", "体育")
        val courses = (1..14).map { index -> CourseOccurrence(
            "preview-$index", Course(id = index.toLong(), name = names[(index - 1) % names.size], teacher = "张老师", classroom = "教学楼 30${index % 6 + 1}"),
            date, 480 + index * 30, 525 + index * 30, false,
        ) }
        val layout = when (layoutRes) {
            R.layout.widget_preview -> WidgetLayout.PREVIEW_2X2
            R.layout.widget_wakeup_classic_shell -> WidgetLayout.TODAY_2X4
            R.layout.widget_ruler_4x4 -> WidgetLayout.RULER_4X4
            else -> WidgetLayout.TODAY_4X4
        }
        // v6 背景：透明度档位映射为 bgColor ARGB（与 MIGRATION_5_6 的推导一致）。
        val bgColor = when (opacity) {
            0 -> 0x00000000
            50 -> 0x80FFFFFF.toInt()
            else -> 0x40FFFFFF.toInt()
        }
        val state = WidgetPresentation.SemesterState(0, 5, 20, ended = false)
        val emptyMessage = WidgetPresentation.emptyMessage(state, date, date, modern = layout != WidgetLayout.TODAY_2X4, weekView = false)
        return ScheduleWidgets.present(
            context, 91, layout, WidgetConfig(bgColor = bgColor), state,
            "本地课表 更新 07:30", date, date.atTime(7, 30),
            hasCourses = courses.isNotEmpty(), emptyMessage = emptyMessage, background = null,
        )
    }

    private fun drawPattern(canvas: Canvas, height: Int, dark: Boolean) {
        val colors = if (dark) intArrayOf(0xFF101B42.toInt(), 0xFF702155.toInt(), 0xFF125A58.toInt())
            else intArrayOf(0xFFFFDF85.toInt(), 0xFF80DEE5.toInt(), 0xFFFF9CBF.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 340f, height.toFloat(), colors, null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 340f, height.toFloat(), paint)
        paint.shader = null
        paint.color = if (dark) 0xAA021125.toInt() else 0xAAFFFFFF.toInt()
        for (x in -80..400 step 80) canvas.drawCircle(x.toFloat(), height * 0.60f, 30f, paint)
        paint.color = if (dark) 0x996ACCC3.toInt() else 0x996C5BC8.toInt()
        canvas.drawRect(0f, height * 0.75f, 340f, height * 0.91f, paint)
    }
}
