package com.gfs.iyango.core.model

/**
 * 星期几的中文文案。
 *
 * 取值语义：**周一=1 … 周日=7**；越界输入一律返回空字符串。
 */
object DayLabels {

    private val LABELS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    private val SHORT_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

    /** 完整中文星期名（"星期一"…"星期日"）；[day] 不在 1..7 时返回 ""。 */
    fun label(day: Int): String = if (day in 1..7) LABELS[day - 1] else ""

    /** 单字中文星期名（"一"…"日"）；[day] 不在 1..7 时返回 ""。 */
    fun shortLabel(day: Int): String = if (day in 1..7) SHORT_LABELS[day - 1] else ""
}
