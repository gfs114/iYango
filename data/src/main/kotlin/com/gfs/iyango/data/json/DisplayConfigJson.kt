package com.gfs.iyango.data.json

import com.gfs.iyango.core.model.CellTextAlign
import com.gfs.iyango.core.model.CourseInfoField
import com.gfs.iyango.core.model.GridLineStyle
import com.gfs.iyango.core.model.ScheduleHeaderOrder
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 交换文档 `displayConfig` 字符串的结构（F09/F11 往返）。
 *
 * 该字段是"字符串里再套一层 JSON"：本应用导出的即本结构；其他实现携带的对象
 * 只要字段名可解释就吸收，未知字段忽略，绝不因显示配置损坏而阻断课程导入。
 */
@Serializable
data class DisplayConfigJson(
    val version: Int = 1,
    val showWeekends: Boolean = true,
    val showOffWeek: Boolean = true,
    val headerOrder: String = "WEEKDAY_DATE",
    val showExams: Boolean = false,
    val showLunchBreak: Boolean = false,
    val mergeAdjacent: Boolean = false,
    val showTeacher: Boolean = true,
    val showClassroom: Boolean = true,
    val showTime: Boolean = false,
    val courseInfoOrder: List<String> = listOf("CLASSROOM", "TEACHER", "TIME"),
    val rowHeightDp: Int = 0,
    val minRowHeightDp: Int = 56,
    val cornerRadiusDp: Int = 10,
    val cellAlpha: Float = 1f,
    val offWeekAlpha: Float = 0.38f,
    val textSizeSp: Float = 12f,
    val align: String = "CENTER",
    val showGridLines: Boolean = true,
    val headerBackgroundHex: String = "",
    val gridLineHex: String = "",
    val courseColorOverrides: Map<String, String> = emptyMap(),
    val paletteVersion: Int = 1,
    val showAtPrefix: Boolean = true,
    val headerTextSizeSp: Float = 12f,
    val gridLineWidthDp: Float = 1f,
    val gridLineDashed: Boolean = false,
    val gridLineStyle: String = "",
    val textVCenter: Boolean = false,
    val borderWidthDp: Float = 0f,
    val borderColorHex: String = "",
    val borderUseCourseColor: Boolean = true,
    val borderDashed: Boolean = false,
    val backgroundColorHex: String = "",
    val paletteLight: List<String> = emptyList(),
    val paletteDark: List<String> = emptyList(),
    val viewMode: String = "GRID",
    val showEveningBreak: Boolean = false,
) {
    fun toModel(): ScheduleDisplaySettings {
        val resolvedStyle = gridLineStyle.trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { GridLineStyle.valueOf(it) }.getOrNull() }
            ?: if (gridLineDashed) GridLineStyle.DASHED else GridLineStyle.SOLID
        return ScheduleDisplaySettings(
            version = version,
            showWeekends = showWeekends,
            showOffWeek = showOffWeek,
            headerOrder = runCatching { ScheduleHeaderOrder.valueOf(headerOrder) }.getOrDefault(ScheduleHeaderOrder.WEEKDAY_DATE),
            showExams = showExams,
            showLunchBreak = showLunchBreak,
            mergeAdjacent = mergeAdjacent,
            showTeacher = showTeacher,
            showClassroom = showClassroom,
            showTime = showTime,
            courseInfoOrder = courseInfoOrder.mapNotNull { name ->
                runCatching { CourseInfoField.valueOf(name) }.getOrNull()
            },
            rowHeightDp = rowHeightDp,
            minRowHeightDp = minRowHeightDp,
            cornerRadiusDp = cornerRadiusDp,
            cellAlpha = cellAlpha,
            offWeekAlpha = offWeekAlpha,
            textSizeSp = textSizeSp,
            align = runCatching { CellTextAlign.valueOf(align) }.getOrDefault(CellTextAlign.CENTER),
            showGridLines = showGridLines,
            headerBackgroundHex = headerBackgroundHex,
            gridLineHex = gridLineHex,
            courseColorOverrides = courseColorOverrides,
            paletteVersion = paletteVersion,
            showAtPrefix = showAtPrefix,
            headerTextSizeSp = headerTextSizeSp,
            gridLineWidthDp = gridLineWidthDp,
            gridLineDashed = resolvedStyle == GridLineStyle.DASHED,
            gridLineStyle = resolvedStyle,
            textVCenter = textVCenter,
            borderWidthDp = borderWidthDp,
            borderColorHex = borderColorHex,
            borderUseCourseColor = borderUseCourseColor,
            borderDashed = borderDashed,
            backgroundColorHex = backgroundColorHex,
            paletteLight = paletteLight,
            paletteDark = paletteDark,
            viewMode = runCatching { com.gfs.iyango.core.model.ScheduleViewMode.valueOf(viewMode) }
                .getOrDefault(com.gfs.iyango.core.model.ScheduleViewMode.GRID),
            showEveningBreak = showEveningBreak,
        ).sanitized()
    }
}

private val DISPLAY_CONFIG_FORMAT = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

/** 显示设置 → `displayConfig` 字符串。 */
fun encodeDisplayConfig(settings: ScheduleDisplaySettings): String {
    val model = settings.sanitized()
    return DISPLAY_CONFIG_FORMAT.encodeToString(
        DisplayConfigJson.serializer(),
        DisplayConfigJson(
            version = model.version,
            showWeekends = model.showWeekends,
            showOffWeek = model.showOffWeek,
            headerOrder = model.headerOrder.name,
            showExams = model.showExams,
            showLunchBreak = model.showLunchBreak,
            mergeAdjacent = model.mergeAdjacent,
            showTeacher = model.showTeacher,
            showClassroom = model.showClassroom,
            showTime = model.showTime,
            courseInfoOrder = model.courseInfoOrder.map { it.name },
            rowHeightDp = model.rowHeightDp,
            minRowHeightDp = model.minRowHeightDp,
            cornerRadiusDp = model.cornerRadiusDp,
            cellAlpha = model.cellAlpha,
            offWeekAlpha = model.offWeekAlpha,
            textSizeSp = model.textSizeSp,
            align = model.align.name,
            showGridLines = model.showGridLines,
            headerBackgroundHex = model.headerBackgroundHex,
            gridLineHex = model.gridLineHex,
            courseColorOverrides = model.courseColorOverrides,
            paletteVersion = model.paletteVersion,
            showAtPrefix = model.showAtPrefix,
            headerTextSizeSp = model.headerTextSizeSp,
            gridLineWidthDp = model.gridLineWidthDp,
            gridLineDashed = model.gridLineStyle == GridLineStyle.DASHED,
            gridLineStyle = model.gridLineStyle.name,
            textVCenter = model.textVCenter,
            borderWidthDp = model.borderWidthDp,
            borderColorHex = model.borderColorHex,
            borderUseCourseColor = model.borderUseCourseColor,
            borderDashed = model.borderDashed,
            backgroundColorHex = model.backgroundColorHex,
            paletteLight = model.paletteLight,
            paletteDark = model.paletteDark,
            viewMode = model.viewMode.name,
            showEveningBreak = model.showEveningBreak,
        ),
    )
}

/** `displayConfig` 字符串 → 显示设置；空串/损坏返回 null（保留本机配置）。 */
fun decodeDisplayConfig(text: String): ScheduleDisplaySettings? {
    if (text.isBlank()) return null
    return runCatching {
        DISPLAY_CONFIG_FORMAT.decodeFromString(DisplayConfigJson.serializer(), text).toModel()
    }.getOrNull()
}
