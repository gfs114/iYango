package com.gfs.iyango.feature.main.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表网格几何数学测试（ui-baseline §3〔参 ScheduleLayoutMetrics〕）。
 *
 * 全部为纯 JVM 断言：列宽、行高、课程块定位、冲突分组、时间指示器插值与
 * 周选择器换列，逐一对照契约数值。
 */
class ScheduleGridMathTest {

    // ---------------- 常量对照（ui-baseline §3） ----------------

    @Test
    fun `契约常量 - 时间轴列宽40 表头高45 默认段数12 上限20`() {
        assertEquals(40f, ScheduleGridMath.TIME_RAIL_WIDTH_DP)
        assertEquals(45f, ScheduleGridMath.HEADER_HEIGHT_DP)
        assertEquals(12, ScheduleGridMath.DEFAULT_SECTION_COUNT)
        assertEquals(20, ScheduleGridMath.MAX_SECTION_COUNT)
    }

    @Test
    fun `契约常量 - 行高范围72-80 回退96 块间隙1 内边距4 圆角4`() {
        // [放大格子] 只加高不加宽：行高下限 44→72、自适应上限 77→112、回退 77→96。
        assertEquals(72f, ScheduleGridMath.MIN_SECTION_HEIGHT_DP)
        assertEquals(80f, ScheduleGridMath.MAX_SECTION_HEIGHT_DP)
        assertEquals(112f, ScheduleGridMath.AUTO_ROW_CAP_DP)
        assertEquals(96f, ScheduleGridMath.FALLBACK_SECTION_HEIGHT_DP)
        assertEquals(1f, ScheduleGridMath.CARD_GAP_DP)
        assertEquals(4f, ScheduleGridMath.CARD_PADDING_DP)
        assertEquals(4f, ScheduleGridMath.CARD_RADIUS_DP)
    }

    @Test
    fun `契约常量 - 非本周块透明度38百分比`() {
        assertEquals(0.38f, ScheduleGridMath.OFF_WEEK_OPACITY)
    }

    // ---------------- 段数收敛 ----------------

    @Test
    fun `clampSectionCount - 0回退默认12`() {
        assertEquals(12, ScheduleGridMath.clampSectionCount(0))
        assertEquals(12, ScheduleGridMath.clampSectionCount(-3))
    }

    @Test
    fun `clampSectionCount - 超上限截断到20`() {
        assertEquals(20, ScheduleGridMath.clampSectionCount(25))
    }

    @Test
    fun `clampSectionCount - 合法值原样保留`() {
        assertEquals(15, ScheduleGridMath.clampSectionCount(15))
    }

    // ---------------- 列宽 ----------------

    @Test
    fun `dayColumnWidthFor - 宽容器均分剩余宽度`() {
        val width = ScheduleGridMath.dayColumnWidthFor(containerWidth = 592f, railWidth = 40f)
        assertEquals((592f - 40f) / 7f, width, 0.001f)
    }

    @Test
    fun `dayColumnWidthFor - 窄容器保底最小40`() {
        val width = ScheduleGridMath.dayColumnWidthFor(containerWidth = 240f, railWidth = 40f)
        assertEquals(40f, width)
    }

    @Test
    fun `dayColumnWidthFor - 容器宽度不可用时回退最小40`() {
        assertEquals(40f, ScheduleGridMath.dayColumnWidthFor(0f, 40f))
    }

    // ---------------- 行高 ----------------

    @Test
    fun `sectionHeightFor - 未测量高度回退默认96`() {
        assertEquals(96f, ScheduleGridMath.sectionHeightFor(0f, 12, 45f))
        assertEquals(96f, ScheduleGridMath.sectionHeightFor(-1f, 12, 45f))
    }

    @Test
    fun `sectionHeightFor - 足够高的容器封顶112`() {
        val height = ScheduleGridMath.sectionHeightFor(containerHeight = 2000f, sectionCount = 12, headerHeight = 45f)
        assertEquals(112f, height)
    }

    @Test
    fun `sectionHeightFor - 压缩容器保底72`() {
        val height = ScheduleGridMath.sectionHeightFor(containerHeight = 500f, sectionCount = 12, headerHeight = 45f)
        assertEquals(72f, height)
    }

    @Test
    fun `sectionHeightFor - 中等容器低于下限时保底72`() {
        // (700 - 45) / 12 = 54.58 < 72 下限 → 取 72（加高后允许纵向滚动）。
        val height = ScheduleGridMath.sectionHeightFor(containerHeight = 700f, sectionCount = 12, headerHeight = 45f)
        assertEquals(72f, height)
    }

    // ---------------- 度量与课程块定位 ----------------

    /** 标准度量：容器 592×1000、12 段（行高自适应填满 = (1000-45)/12，列宽 (592-40)/7）。 */
    private fun standardMetrics(): ScheduleGridMetrics =
        ScheduleGridMetrics(containerWidth = 592f, containerHeight = 1000f, sectionCount = 12)

    @Test
    fun `metrics - 列宽行高与总尺寸`() {
        val metrics = standardMetrics()
        val colW = (592f - 40f) / 7f
        assertEquals(colW, metrics.dayColumnWidth, 0.001f)
        val expectedRow = (1000f - 45f) / 12f
        assertEquals(expectedRow, metrics.sectionHeight, 0.001f)
        assertEquals(40f + colW * 7f, metrics.totalWidth, 0.01f)
        assertEquals(45f + expectedRow * 12f, metrics.totalHeight, 0.001f)
        assertFalse(metrics.scrollsHorizontally)
    }

    @Test
    fun `metrics - 课程块定位 x等于40加天偏移加0点5`() {
        val metrics = standardMetrics()
        val colW = metrics.dayColumnWidth
        // ui-baseline：x = 40 + (day-1)*colW + 0.5。
        assertEquals(40f + 0 * colW + 0.5f, metrics.cardLeft(1), 0.001f)
        assertEquals(40f + 2 * colW + 0.5f, metrics.cardLeft(3), 0.001f)
        assertEquals(40f + 6 * colW + 0.5f, metrics.cardLeft(7), 0.001f)
        // 越界天收敛到第 1 天。
        assertEquals(metrics.cardLeft(1), metrics.cardLeft(0), 0.001f)
    }

    @Test
    fun `metrics - 课程块定位 y等于起始节次偏移加0点5`() {
        val metrics = standardMetrics()
        // ui-baseline：y = (startSection-1)*rowH + 0.5。
        assertEquals(0.5f, metrics.cardTop(1), 0.001f)
        assertEquals(1f * metrics.sectionHeight + 0.5f, metrics.cardTop(2), 0.001f)
        assertEquals(4f * metrics.sectionHeight + 0.5f, metrics.cardTop(5), 0.001f)
    }

    @Test
    fun `metrics - 块宽等于列宽减1 跨节块高等于行高乘span减1`() {
        val metrics = standardMetrics()
        assertEquals(metrics.dayColumnWidth - 1f, metrics.cardWidth, 0.001f)
        assertEquals(metrics.sectionHeight - 1f, metrics.cardHeight(1), 0.001f)
        assertEquals(2f * metrics.sectionHeight - 1f, metrics.cardHeight(2), 0.001f)
        // 跨度 <1 收敛为 1 节。
        assertEquals(metrics.cardHeight(1), metrics.cardHeight(0), 0.001f)
    }

    @Test
    fun `metrics - 窄容器需要水平滚动`() {
        val metrics = ScheduleGridMetrics(containerWidth = 240f, containerHeight = 900f, sectionCount = 12)
        assertTrue(metrics.scrollsHorizontally)
        assertEquals(40f + 40f * 7f, metrics.totalWidth, 0.001f)
    }

    // ---------------- 密度档位 ----------------

    @Test
    fun `densityFor - 窄列仅课名`() {
        assertEquals(CourseCardDensity.NAME_ONLY, ScheduleGridMath.densityFor(70f))
    }

    @Test
    fun `densityFor - 88起名称加教室`() {
        assertEquals(CourseCardDensity.COMPACT, ScheduleGridMath.densityFor(88f))
        assertEquals(CourseCardDensity.COMPACT, ScheduleGridMath.densityFor(131.9f))
    }

    @Test
    fun `densityFor - 132起完整信息`() {
        assertEquals(CourseCardDensity.FULL, ScheduleGridMath.densityFor(132f))
    }

    @Test
    fun `nameMaxLines - 最窄列3行 其余2行`() {
        assertEquals(3, ScheduleGridMath.nameMaxLines(CourseCardDensity.NAME_ONLY))
        assertEquals(2, ScheduleGridMath.nameMaxLines(CourseCardDensity.COMPACT))
        assertEquals(2, ScheduleGridMath.nameMaxLines(CourseCardDensity.FULL))
    }

    // ---------------- 教室行预算 ----------------

    @Test
    fun `roomMaxLines - 单节压缩行保底2行`() {
        val lines = ScheduleGridMath.roomMaxLines(
            cardHeight = 44f, cardPadding = 4f, nameFontSize = 13, metaFontSize = 10, rivalLines = 0,
        )
        assertEquals(2, lines)
    }

    @Test
    fun `roomMaxLines - 高块封顶6行`() {
        val lines = ScheduleGridMath.roomMaxLines(
            cardHeight = 2f * 77f - 1f, cardPadding = 4f, nameFontSize = 13, metaFontSize = 10, rivalLines = 0,
        )
        assertEquals(6, lines)
    }

    @Test
    fun `roomMaxLines - 竞争行挤占教室行预算`() {
        // 双节块高度下预算充足，竞争行（教师/周标记）每行都会挤占一格。
        val noRival = ScheduleGridMath.roomMaxLines(2f * 77f - 1f, 4f, 13, 10, rivalLines = 0)
        val twoRivals = ScheduleGridMath.roomMaxLines(2f * 77f - 1f, 4f, 13, 10, rivalLines = 2)
        assertTrue(twoRivals < noRival)
        assertTrue(twoRivals >= 2)
    }

    // ---------------- 状态词 ----------------

    @Test
    fun `showsStatusWord - 跨节且行高足够才显示`() {
        // 阈值 = 行高下限 72 + 12 = 84dp（随 [放大格子] 行高下限上调）。
        assertTrue(ScheduleGridMath.showsStatusWord(rowSpan = 2, sectionHeight = 84f))
        assertFalse(ScheduleGridMath.showsStatusWord(rowSpan = 1, sectionHeight = 112f))
        assertFalse(ScheduleGridMath.showsStatusWord(rowSpan = 2, sectionHeight = 72f))
    }

    // ---------------- 当前时间指示器 ----------------

    @Test
    fun `nowLineOffset - 节次内按分钟线性插值`() {
        // 第 1 节 08:00-08:40，行高 50：08:20 → 半行。
        val offset = ScheduleGridMath.nowLineOffset(
            nowMinute = 500,
            startMinutes = listOf(480, 560),
            endMinutes = listOf(520, 600),
            sectionHeight = 50f,
        )
        assertEquals(25f, offset!!, 0.001f)
    }

    @Test
    fun `nowLineOffset - 第二节取第二行偏移`() {
        val offset = ScheduleGridMath.nowLineOffset(
            nowMinute = 580,
            startMinutes = listOf(480, 560),
            endMinutes = listOf(520, 600),
            sectionHeight = 50f,
        )
        // (1 + (580-560)/40) * 50 = 75。
        assertEquals(75f, offset!!, 0.001f)
    }

    @Test
    fun `nowLineOffset - 节次间隙不绘制`() {
        val offset = ScheduleGridMath.nowLineOffset(
            nowMinute = 540,
            startMinutes = listOf(480, 560),
            endMinutes = listOf(520, 600),
            sectionHeight = 50f,
        )
        assertNull(offset)
    }

    @Test
    fun `nowLineOffset - 课前与课后不绘制`() {
        val starts = listOf(480, 560)
        val ends = listOf(520, 600)
        assertNull(ScheduleGridMath.nowLineOffset(470, starts, ends, 50f))
        assertNull(ScheduleGridMath.nowLineOffset(650, starts, ends, 50f))
    }

    @Test
    fun `nowLineOffset - 结束分钟当刻仍绘制（含结束分钟语义）`() {
        val offset = ScheduleGridMath.nowLineOffset(
            nowMinute = 520,
            startMinutes = listOf(480),
            endMinutes = listOf(520),
            sectionHeight = 50f,
        )
        assertEquals(50f, offset!!, 0.001f)
    }

    @Test
    fun `nowLineOffset - 坏数据行跳过`() {
        // 第 1 节 end<=start（坏数据），当前时刻落在第 2 节内仍能定位。
        val offset = ScheduleGridMath.nowLineOffset(
            nowMinute = 580,
            startMinutes = listOf(520, 560),
            endMinutes = listOf(520, 600),
            sectionHeight = 50f,
        )
        assertEquals(75f, offset!!, 0.001f)
    }

    @Test
    fun `nowLineOffset - 输入为空或长度不一致不绘制`() {
        assertNull(ScheduleGridMath.nowLineOffset(500, emptyList(), emptyList(), 50f))
        assertNull(ScheduleGridMath.nowLineOffset(500, listOf(480), listOf<Int?>(), 50f))
    }

    // ---------------- 冲突分组 ----------------

    private data class Entry(val name: String, val day: Int, val section: Int)

    @Test
    fun `CellGrouping - 同天同起始节归入同组`() {
        val groups = CellGrouping.group(
            listOf(
                Entry("A", day = 3, section = 5),
                Entry("B", day = 3, section = 5),
                Entry("C", day = 3, section = 6),
                Entry("D", day = 1, section = 1),
            ),
            dayOf = { it.day },
            startOf = { it.section },
        )
        assertEquals(3, groups.size)
        // 输出按星期、起始节次升序。
        assertEquals(1 to 1, groups[0].dayOfWeek to groups[0].startSection)
        assertEquals(3 to 5, groups[1].dayOfWeek to groups[1].startSection)
        assertEquals(3 to 6, groups[2].dayOfWeek to groups[2].startSection)
        assertEquals(listOf("A", "B"), groups[1].items.map { it.name })
        assertEquals(2, groups[1].items.size)
    }

    @Test
    fun `CellGrouping - 组内按给定排序键排列（真实块在前）`() {
        val groups = CellGrouping.group(
            listOf(
                Entry("ghost", day = 2, section = 3),
                Entry("real", day = 2, section = 3),
            ),
            dayOf = { it.day },
            startOf = { it.section },
            order = compareBy { it.name != "real" },
        )
        assertEquals(1, groups.size)
        assertEquals("real", groups[0].items.first().name)
        assertEquals("ghost", groups[0].items.last().name)
    }

    @Test
    fun `CellGrouping - 空输入返回空列表`() {
        assertTrue(CellGrouping.group<Entry>(emptyList(), { it.day }, { it.section }).isEmpty())
    }

    // ---------------- 周选择器布局 ----------------

    @Test
    fun `weekPickerColumnsFor - 参考窗口宽度换列`() {
        // usable = 344-32 = 312 → floor(312/64) = 4。
        assertEquals(4, ScheduleGridMath.weekPickerColumnsFor(344f))
        assertEquals(5, ScheduleGridMath.weekPickerColumnsFor(400f))
    }

    @Test
    fun `weekPickerColumnsFor - 极窄回退单列`() {
        assertEquals(1, ScheduleGridMath.weekPickerColumnsFor(40f))
        assertEquals(1, ScheduleGridMath.weekPickerColumnsFor(32f))
    }

    @Test
    fun `weekPickerRows - 按列数向上取整`() {
        assertEquals(5, ScheduleGridMath.weekPickerRows(totalWeeks = 20, columns = 4))
        assertEquals(1, ScheduleGridMath.weekPickerRows(totalWeeks = 0, columns = 4))
        assertEquals(1, ScheduleGridMath.weekPickerRows(totalWeeks = 1, columns = 4))
    }

    @Test
    fun `weekPickerRows - 周数截到选择器上限30`() {
        assertEquals(6, ScheduleGridMath.weekPickerRows(totalWeeks = 40, columns = 5))
    }
}
