package com.gfs.iyango.data.db

import androidx.room.TypeConverter
import java.time.LocalDate

/**
 * Room 类型转换器。
 *
 * 覆盖两类跨类型字段（其余字段均为 Room 原生支持的标量类型）：
 * - `LocalDate` ↔ TEXT（ISO-8601 `yyyy-MM-dd`，即 [LocalDate.toString] 的格式）；
 * - `List<Int>` ↔ 逗号分隔文本（如 `"1,3,5"`，用于课表生效星期与课程所选周）。
 *
 * 转换必须无信息损失：实体列里存什么就还原什么，保证映射层往返（MappersTest）
 * 与持久化往返一致。
 */
class Converters {

    /** [LocalDate] → TEXT；null 原样透传（实体字段为非空时不会出现 null 输入）。 */
    @TypeConverter
    fun localDateToText(date: LocalDate?): String? = date?.toString()

    /** TEXT → [LocalDate]；空文本/null 归一为 null。 */
    @TypeConverter
    fun textToLocalDate(text: String?): LocalDate? =
        text?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }

    /**
     * `List<Int>` → 逗号分隔文本；null 与空列表统一落库为空文本。
     *
     * 元素直接十进制拼接，不补零、不带空格，保证 "1,3,5" 这类文本可精确还原。
     */
    @TypeConverter
    fun intListToText(values: List<Int>?): String? =
        values?.joinToString(separator = ",") { it.toString() }

    /**
     * 逗号分隔文本 → `List<Int>`；null/空文本归一为空列表。
     *
     * 逐段 [String.toIntOrNull] 容错跳过非法段（防御脏数据，正常路径不会触发）。
     */
    @TypeConverter
    fun textToIntList(text: String?): List<Int>? =
        text?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
}
