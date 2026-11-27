package com.gfs.iyango.core.domain

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 单日法定节假日与调休信息。
 *
 * @property date 日期
 * @property name 节日名称（如「国庆节」「春节」）
 * @property isOffDay true 表示放假，false 表示调休补班
 * @property makeupWeekday 调休补班推荐补课星期（1=周一..7=周日）；null 时由用户在页面手选
 */
data class HolidayDayInfo(
    val date: LocalDate,
    val name: String,
    val isOffDay: Boolean,
    val makeupWeekday: Int? = null,
)

/**
 * 临时调休补课标题编解码：复用既有 `TemporaryDay.title`，无需修改数据库表结构。
 */
object HolidayMakeupCodec {
    private const val PREFIX = "__makeup_weekday_"
    private const val SEPARATOR = "__|"

    fun encodeTitle(weekday: Int, label: String): String {
        val safeWeekday = weekday.coerceIn(1, 7)
        val cleanLabel = label.trim().ifBlank { "调休补课" }
        return "$PREFIX$safeWeekday$SEPARATOR$cleanLabel"
    }

    fun parseWeekday(title: String): Int? {
        if (!title.startsWith(PREFIX)) return null
        val sepIndex = title.indexOf(SEPARATOR, startIndex = PREFIX.length)
        if (sepIndex <= PREFIX.length) return null
        return title.substring(PREFIX.length, sepIndex).toIntOrNull()?.takeIf { it in 1..7 }
    }

    fun displayTitle(title: String): String {
        if (!title.startsWith(PREFIX)) return title
        val sepIndex = title.indexOf(SEPARATOR, startIndex = PREFIX.length)
        if (sepIndex <= PREFIX.length) return title
        return title.substring(sepIndex + SEPARATOR.length)
    }
}

/**
 * 内置 2026 / 2027 年中国法定节假日与调休基准数据（离线可用，可由在线拉取覆盖叠加）。
 */
object BuiltInHolidayData {

    private fun off(year: Int, month: Int, day: Int, name: String): HolidayDayInfo =
        HolidayDayInfo(LocalDate.of(year, month, day), name, isOffDay = true)

    private fun work(year: Int, month: Int, day: Int, name: String, makeupWeekday: Int? = null): HolidayDayInfo =
        HolidayDayInfo(LocalDate.of(year, month, day), name, isOffDay = false, makeupWeekday = makeupWeekday)

    private val entries: Map<LocalDate, HolidayDayInfo> by lazy {
        val list = buildList {
            // 2026 年
            add(off(2026, 1, 1, "元旦"))
            add(off(2026, 1, 2, "元旦"))
            add(off(2026, 1, 3, "元旦"))
            add(work(2026, 1, 4, "元旦", makeupWeekday = 5))

            add(work(2026, 2, 14, "春节", makeupWeekday = 1))
            (15..23).forEach { add(off(2026, 2, it, "春节")) }
            add(work(2026, 2, 28, "春节", makeupWeekday = 5))

            (4..6).forEach { add(off(2026, 4, it, "清明节")) }

            (1..5).forEach { add(off(2026, 5, it, "劳动节")) }
            add(work(2026, 5, 9, "劳动节", makeupWeekday = 2))

            (19..21).forEach { add(off(2026, 6, it, "端午节")) }

            (25..27).forEach { add(off(2026, 9, it, "中秋节")) }
            add(work(2026, 9, 20, "国庆节", makeupWeekday = 4))
            (1..7).forEach { add(off(2026, 10, it, "国庆节")) }
            add(work(2026, 10, 10, "国庆节", makeupWeekday = 5))

            // 2027 年基础节假日（联网更新可补充精确调休）
            add(off(2027, 1, 1, "元旦"))
            (6..11).forEach { add(off(2027, 2, it, "春节")) }
            add(off(2027, 4, 5, "清明节"))
            (1..5).forEach { add(off(2027, 5, it, "劳动节")) }
            add(off(2027, 6, 9, "端午节"))
            add(off(2027, 9, 15, "中秋节"))
            (1..7).forEach { add(off(2027, 10, it, "国庆节")) }
        }
        list.associateBy { it.date }
    }

    fun all(): Map<LocalDate, HolidayDayInfo> = entries
}

/**
 * 纯 Kotlin 解析 `NateScarlet/holiday-cn` 格式 JSON。
 */
object HolidayCnJsonParser {

    private val dayObjectRegex = Regex("""\{[^{}]*\}""")
    private val nameRegex = Regex(""""name"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val dateRegex = Regex(""""date"\s*:\s*"(\d{4}-\d{2}-\d{2})"""")
    private val offDayRegex = Regex(""""isOffDay"\s*:\s*(true|false)""")

    fun parse(jsonText: String): Map<LocalDate, HolidayDayInfo> {
        if (!jsonText.contains("\"days\"")) return emptyMap()
        val result = LinkedHashMap<LocalDate, HolidayDayInfo>()
        dayObjectRegex.findAll(jsonText).forEach { match ->
            val obj = match.value
            val dateStr = dateRegex.find(obj)?.groupValues?.getOrNull(1) ?: return@forEach
            val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@forEach
            val isOffDay = offDayRegex.find(obj)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull() ?: return@forEach
            val rawName = nameRegex.find(obj)?.groupValues?.getOrNull(1).orEmpty()
            val name = decodeEscapes(rawName).ifBlank { if (isOffDay) "节假日" else "调休补班" }
            val fallbackMakeup = if (!isOffDay) BuiltInHolidayData.all()[date]?.makeupWeekday else null
            result[date] = HolidayDayInfo(
                date = date,
                name = name,
                isOffDay = isOffDay,
                makeupWeekday = fallbackMakeup,
            )
        }
        return result
    }

    private fun decodeEscapes(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\")
}

interface HolidayRepositoryPort {
    val holidays: StateFlow<Map<LocalDate, HolidayDayInfo>>

    fun infoFor(date: LocalDate): HolidayDayInfo? = holidays.value[date]

    fun isHolidayOffDay(date: LocalDate): Boolean = infoFor(date)?.isOffDay == true

    suspend fun refreshYear(year: Int): Result<Int>
}

object NoopHolidayRepository : HolidayRepositoryPort {
    override val holidays: StateFlow<Map<LocalDate, HolidayDayInfo>> =
        MutableStateFlow(BuiltInHolidayData.all())

    override suspend fun refreshYear(year: Int): Result<Int> =
        Result.success(holidays.value.count { it.key.year == year })
}
