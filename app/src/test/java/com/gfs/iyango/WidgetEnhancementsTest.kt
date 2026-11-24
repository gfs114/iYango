package com.gfs.iyango

import android.app.Application
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.gfs.iyango.core.model.WidgetConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WidgetEnhancementsTest {
    @Test fun configurationContainsBackwardCompatibleDisplayDefaults() {
        val config = WidgetConfig()
        assertEquals(25, config.glassOpacityPercent)
        assertEquals(13, config.textSizeSp)
        assertEquals(false, config.showTeacher)
        assertEquals(true, config.showLocation)
    }

    /** 文档 3.2：v6 WakeUp 样式字段默认值严格取自 3.1 字节码实测值。 */
    @Test fun configurationCarriesWakeUpStyleDefaults() {
        val config = WidgetConfig()
        assertEquals(true, config.showBg)
        assertEquals(0xD8FFFFFF.toInt(), config.bgColor)
        assertEquals("", config.backgroundUri)
        assertEquals(true, config.showHeaderArea)
        assertEquals(true, config.showDate)
        assertEquals(true, config.showButton)
        assertEquals(true, config.showColor)
        assertEquals(0xFF000000.toInt(), config.textColor)
        assertEquals(0xFFFFFFFF.toInt(), config.courseTextColor)
        assertEquals(0x80FFFFFF.toInt(), config.strokeColor)
        assertEquals(false, config.textColorCompose)
        assertEquals(false, config.strokeColorCompose)
        assertEquals(12, config.headerTextSizeSp)
        assertEquals(64, config.itemHeightDp)
        assertEquals(4, config.radiusDp)
        assertEquals(50, config.itemAlpha)
        assertEquals(50, config.otherWeekCourseAlpha)
        assertEquals(false, config.itemCenterHorizontal)
        assertEquals(false, config.itemCenterVertical)
        assertEquals(false, config.showTime)
        assertEquals(true, config.showRoomPrefix)
        assertEquals(true, config.showTimeBar)
        assertEquals(true, config.showSat)
        assertEquals(true, config.showSun)
        assertEquals(true, config.showOtherWeekCourse)
        assertEquals(false, config.showGrid)
        assertEquals(false, config.useDottedLine)
    }

    @Test fun listShellsHaveAdjustButtonAndNavigationAndHeaderControls() {
        val context = RuntimeEnvironment.getApplication()
        for (layout in listOf(R.layout.widget_preview, R.layout.widget_wakeup_classic_shell, R.layout.widget_today_4x4, R.layout.widget_ruler_4x4)) {
            val root = RemoteViews(context.packageName, layout).apply(context, FrameLayout(context))
            for (name in listOf("iv_adjust_style", "widget_prev_day", "widget_next_day", "widget_today", "tv_date", "tv_table_name", "tv_empty")) {
                val id = context.resources.getIdentifier(name, "id", context.packageName)
                assertTrue("Missing host control $name in $layout", id != 0 && root.findViewById<android.view.View>(id) != null)
            }
        }
    }

    @Test fun classicShellCarriesSubtitleAndWeekHeaderColumns() {
        val context = RuntimeEnvironment.getApplication()
        val root = RemoteViews(context.packageName, R.layout.widget_wakeup_classic_shell).apply(context, FrameLayout(context))
        for (name in listOf("tv_week_day", "ll_week_header", "tv_month", "tv_weekday0", "tv_weekday5", "tv_weekday6", "iv_bg_pic", "iv_bg_color")) {
            val id = context.resources.getIdentifier(name, "id", context.packageName)
            assertTrue("Missing classic shell control $name", id != 0 && root.findViewById<android.view.View>(id) != null)
        }
        // 周表头默认隐藏（仅周视图卡片可见，文档 4.1）。
        assertEquals(android.view.View.GONE, root.findViewById<android.view.View>(R.id.ll_week_header).visibility)
    }

    @Test fun modernItemLayoutHasColorBarAndDetailLines() {
        val context = RuntimeEnvironment.getApplication()
        val root = RemoteViews(context.packageName, R.layout.item_widget_today_modern).apply(context, FrameLayout(context))
        assertNotNull(root.findViewById<android.view.View>(R.id.iv_color_bar))
        assertNotNull(root.findViewById<android.view.View>(R.id.tv_section_badge))
        assertEquals("高等数学", root.findViewById<TextView>(R.id.tv_course_name).apply { text = "高等数学" }.text.toString())
        assertNotNull(root.findViewById<android.view.View>(R.id.tv_time_range))
        assertNotNull(root.findViewById<android.view.View>(R.id.tv_room))
        assertEquals(android.view.View.GONE, root.findViewById<android.view.View>(R.id.tv_teacher).visibility)
    }

    @Test fun todayLargeShellExposesDualColumnHeadersDividerAndIndependentNextDayEmptyView() {
        val context = RuntimeEnvironment.getApplication()
        val root = RemoteViews(context.packageName, R.layout.widget_today_4x4).apply(context, FrameLayout(context))
        for (name in listOf("tv_col_today_title", "tv_col_today_meta", "tv_col_next_title", "iv_column_divider", "empty_next_day", "tv_empty_next_day")) {
            val id = context.resources.getIdentifier(name, "id", context.packageName)
            assertTrue("Missing dual-column control $name in widget_today_4x4", id != 0 && root.findViewById<android.view.View>(id) != null)
        }
    }

    @Test fun allWidgetProvidersExposeSpecificCardPreviewImageAndLayout() {
        val context = RuntimeEnvironment.getApplication()
        val androidNs = "http://schemas.android.com/apk/res/android"
        val specs = listOf(
            R.xml.widget_preview_info to R.drawable.widget_preview_card_preview,
            R.xml.widget_today_small_info to R.drawable.widget_preview_card_today_small,
            R.xml.widget_today_large_info to R.drawable.widget_preview_card_today_large,
            R.xml.widget_ruler_info to R.drawable.widget_preview_card_ruler,
            R.xml.widget_week_info to R.drawable.widget_preview_card_week,
        )
        for ((xmlRes, expectedDrawable) in specs) {
            context.resources.getXml(xmlRes).use { parser ->
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "appwidget-provider") {
                        val previewImage = parser.getAttributeResourceValue(androidNs, "previewImage", 0)
                        val previewLayout = parser.getAttributeResourceValue(androidNs, "previewLayout", 0)
                        assertEquals("Provider $xmlRes must bind dedicated previewImage", expectedDrawable, previewImage)
                        assertTrue("Provider $xmlRes must bind dedicated previewLayout", previewLayout != 0)
                        val drawable = context.getDrawable(previewImage)
                        assertNotNull("Preview drawable must load for $xmlRes", drawable)
                        assertTrue("Preview drawable must have valid bounds", drawable!!.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
                        val previewRoot = RemoteViews(context.packageName, previewLayout).apply(context, FrameLayout(context))
                        assertNotNull("Preview layout must contain iv_widget_preview", previewRoot.findViewById<android.view.View>(R.id.iv_widget_preview))
                    }
                }
            }
        }
    }
}
