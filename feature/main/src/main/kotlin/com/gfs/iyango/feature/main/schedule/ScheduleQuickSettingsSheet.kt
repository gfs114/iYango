package com.gfs.iyango.feature.main.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidSlider
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import com.gfs.iyango.core.model.GridLineStyle
import com.gfs.iyango.core.model.ScheduleDisplaySettings
import com.gfs.iyango.core.model.ScheduleViewMode
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 课表半屏快捷设置抽屉，支持高频显示开关与滑杆的实时调节，并可跳转完整显示设置或课表原文页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleQuickSettingsSheet(
    display: ScheduleDisplaySettings,
    onUpdateDisplay: ((ScheduleDisplaySettings) -> ScheduleDisplaySettings) -> Unit,
    onOpenFullDisplaySettings: () -> Unit,
    onOpenCourseRaw: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    LiquidModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "课表设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenFullDisplaySettings()
                    },
                ) {
                    Text("更多显示设置")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("视图模式", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ScheduleViewMode.GRID to "网格",
                        ScheduleViewMode.TIMELINE to "时间轴",
                    ).forEach { (mode, label) ->
                        LiquidFilterChip(
                            selected = display.viewMode == mode,
                            onClick = { onUpdateDisplay { it.copy(viewMode = mode) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
            QuickSwitchRow(
                title = "显示周末",
                checked = display.showWeekends,
                onCheckedChange = { checked -> onUpdateDisplay { it.copy(showWeekends = checked) } },
            )
            QuickSwitchRow(
                title = "显示网格辅助线",
                checked = display.showGridLines,
                onCheckedChange = { checked -> onUpdateDisplay { it.copy(showGridLines = checked) } },
            )
            if (display.showGridLines) {
                val currentStyle = when {
                    display.gridLineStyle != GridLineStyle.SOLID -> display.gridLineStyle
                    display.gridLineDashed -> GridLineStyle.DASHED
                    else -> GridLineStyle.SOLID
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("网格线样式", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            GridLineStyle.SOLID to "实线",
                            GridLineStyle.DASHED to "虚线",
                            GridLineStyle.DOTTED to "点线",
                        ).forEach { (style, label) ->
                            LiquidFilterChip(
                                selected = currentStyle == style,
                                onClick = {
                                    onUpdateDisplay {
                                        it.copy(
                                            gridLineStyle = style,
                                            gridLineDashed = style == GridLineStyle.DASHED,
                                        )
                                    }
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                QuickSliderRow(
                    title = "网格线宽",
                    value = display.gridLineWidthDp,
                    range = 0.5f..4f,
                    format = { "${String.format(Locale.US, "%.1f", it)}dp" },
                    onValueFinished = { value -> onUpdateDisplay { it.copy(gridLineWidthDp = value) } },
                )
            }

            QuickSwitchRow(
                title = "显示非本周课程",
                checked = display.showOffWeek,
                onCheckedChange = { checked -> onUpdateDisplay { it.copy(showOffWeek = checked) } },
            )
            QuickSwitchRow(
                title = "显示午休分隔",
                checked = display.showLunchBreak,
                onCheckedChange = { checked -> onUpdateDisplay { it.copy(showLunchBreak = checked) } },
            )
            QuickSwitchRow(
                title = "显示晚休分隔",
                checked = display.showEveningBreak,
                onCheckedChange = { checked -> onUpdateDisplay { it.copy(showEveningBreak = checked) } },
            )
            QuickSwitchRow(
                title = "显示教室",
                checked = display.showClassroom,
                onCheckedChange = { checked -> onUpdateDisplay { it.copy(showClassroom = checked) } },
            )
            QuickSwitchRow(
                title = "显示教师",
                checked = display.showTeacher,
                onCheckedChange = { checked -> onUpdateDisplay { it.copy(showTeacher = checked) } },
            )
            QuickSwitchRow(
                title = "显示课程时间",
                checked = display.showTime,
                onCheckedChange = { checked -> onUpdateDisplay { it.copy(showTime = checked) } },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            QuickSliderRow(
                title = "卡片圆角",
                value = display.cornerRadiusDp.toFloat(),
                range = 0f..24f,
                format = { "${it.roundToInt()}dp" },
                onValueFinished = { value ->
                    onUpdateDisplay { it.copy(cornerRadiusDp = value.roundToInt()) }
                },
            )
            QuickSliderRow(
                title = "卡片不透明度",
                value = display.cellAlpha,
                range = 0.2f..1.0f,
                format = { "${(it * 100f).roundToInt()}%" },
                onValueFinished = { value -> onUpdateDisplay { it.copy(cellAlpha = value) } },
            )
            QuickSliderRow(
                title = "课表文字大小",
                value = display.textSizeSp,
                range = 9f..16f,
                format = { "${String.format(Locale.US, "%.1f", it)}sp" },
                onValueFinished = { value -> onUpdateDisplay { it.copy(textSizeSp = value) } },
            )

            if (onOpenCourseRaw != null) {
                Spacer(modifier = Modifier.height(4.dp))
                LiquidOutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenCourseRaw()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_settings_open_raw"),
                ) {
                    Text("查看课表原文 (HTML/JSON)")
                }
            }
        }
    }
}

@Composable
private fun QuickSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        LiquidToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun QuickSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onValueFinished: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = format(draft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LiquidSlider(
            value = draft,
            onValueChange = { draft = it },
            valueRange = range,
            onValueChangeFinished = { onValueFinished(draft) },
        )
    }
}
