package com.gfs.iyango.feature.main.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.feature.main.textTertiaryColor
import com.gfs.iyango.feature.main.LocalMainTabActive

/**
 * 周选择半模态（ui-baseline §3〔参 WeekPickerSheet〕）。
 *
 * 每周一个紧凑芯片，按 [ScheduleGridMath.weekPickerColumnsFor] 换列；三种状态一眼可辨：
 * 选中周 = accent 实底 + onBrand 墨、本周（未选中）= brandSoft 洗色 + 1dp accent 描边、
 * 其余 = surfaceAlt。芯片内为周号 + 「本周」标记行（恒占位防抖动）+ 7×4 密度点阵，
 * 密度取 [WeekScheduleData.weekDayCounts]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekPickerSheet(
    data: WeekScheduleData?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onBackToCurrentWeek: () -> Unit = { data?.let { onPick(it.currentWeek) } },
    termBrowsing: TermBrowseController.State = TermBrowseController.State(),
    onLoadTerms: () -> Unit = {},
    onBrowseTerm: (String) -> Unit = {},
    onReturnToCurrent: () -> Unit = {},
    onOpenSchool: () -> Unit = {},
    scheduleState: ScheduleUiState? = null,
    onRetry: () -> Unit = {},
) {
    if (!LocalMainTabActive.current) return
    LiquidModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassOverlayHeader(
                title = "选择学期与周次",
                contentPadding = PaddingValues(0.dp),
            )
            TermBrowseHeader(termBrowsing, onLoadTerms, onBrowseTerm, onReturnToCurrent, onOpenSchool, onRetry)
            if (termBrowsing.error == null && scheduleState is ScheduleUiState.Error) {
                Text(scheduleState.message, style = MaterialTheme.typography.bodyMedium)
                if (scheduleState.canRetry) TextButton(onClick = onRetry) { Text("重试读取课表") }
            }
            if (!termBrowsing.loading && scheduleState == ScheduleUiState.FirstLoading) {
                Text("正在准备周次…", style = MaterialTheme.typography.bodyMedium)
            }
            if (data != null) {
                Text(data.semesterName ?: "当前课表", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = onBackToCurrentWeek,
                    enabled = data.currentWeek in 1..data.totalWeeks,
                ) { Text("回到本周") }
                WeekPicker(data = data, onPick = onPick, scrollable = false)
            } else if (!termBrowsing.loading) {
                Text(
                    if (termBrowsing.selected == null) "请选择学校学期，或先设置当前课表学期" else "课表读取成功后可选择周次",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Shared by the narrow sheet and the permanent wide-screen sidebar. */
@Composable
fun WeekPicker(data: WeekScheduleData, onPick: (Int) -> Unit, modifier: Modifier = Modifier, scrollable: Boolean = true) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = ScheduleGridMath.weekPickerColumnsFor(maxWidth.value)
        Column(Modifier.fillMaxWidth().then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            (1..data.totalWeeks.coerceAtMost(ScheduleGridMath.WEEK_PICKER_MAX_WEEKS)).chunked(columns).forEach { weeks ->
                Row { weeks.forEach { WeekChip(it, data, onPick) } }
            }
        }
    }
}

/** 单个周芯片（56×68，R12），间距由格宽 64 与行高 76 预留。 */
@Composable
fun WeekChip(
    week: Int,
    data: WeekScheduleData,
    onPick: (Int) -> Unit,
) {
    val brand = LocalBrand.current
    val isSelected = week == data.displayedWeek
    val isCurrent = data.currentWeek > 0 && week == data.currentWeek
    val adjusted = androidx.compose.runtime.remember(week, data.semester, data.effectiveDays) {
        val start = data.semester?.startDate?.plusWeeks(week - 1L)
        val days = data.effectiveDays.filter { start != null && it.date in start..start.plusDays(6) }
        days.any { it.isTemporaryDay } || adjustmentsToRestore(days.flatMap { it.adjustments }, days).isNotEmpty()
    }

    val fill = when {
        isSelected -> brand.accent
        isCurrent -> brand.brandSoft
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val ink = if (isSelected) brand.onBrand else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .size(
                width = ScheduleGridMath.WEEK_PICKER_CHIP_WIDTH_DP.dp,
                height = ScheduleGridMath.WEEK_PICKER_CHIP_HEIGHT_DP.dp,
            )
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .then(
                if (isCurrent && !isSelected) {
                    Modifier.border(1.dp, brand.accent, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .clickable { onPick(week) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = week.toString(),
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = ink,
            )
            // 「本周」标记行：恒定占位，选中切换不引起行高变化。
            Box(modifier = Modifier.height(13.dp), contentAlignment = Alignment.Center) {
                if (isCurrent || adjusted) {
                    Text(
                        text = listOfNotNull("本周".takeIf { isCurrent }, "调整".takeIf { adjusted }).joinToString(" "),
                        fontSize = 10.sp,
                        color = if (isSelected) brand.onBrand else brand.accent,
                        maxLines = 1,
                    )
                }
            }
            DensityMatrix(
                dayCounts = data.countsOfWeek(week),
                slotMasks = data.slotMasksOfWeek(week),
                isSelected = isSelected,
            )
        }
    }
}

/** 芯片内 7×5 时段点阵：行对应五大节时段（1-2/3-4/5-6/7-8/9+），列对应周一至周日（单 Canvas 绘制，避免大量子节点开销）。 */
@Composable
private fun DensityMatrix(
    dayCounts: List<Int>,
    slotMasks: List<Int> = emptyList(),
    isSelected: Boolean,
) {
    val brand = LocalBrand.current
    val dotColor = if (isSelected) brand.onBrand else brand.accent
    val emptyColor = dotColor.copy(alpha = if (isSelected) 0.22f else 0.14f)
    val dotSizeDp = ScheduleGridMath.WEEK_PICKER_DOT_SIZE_DP.dp
    val gapDp = 1.dp
    val totalWidth = dotSizeDp * 7 + gapDp * 6
    val totalHeight = dotSizeDp * 5 + gapDp * 4
    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(width = totalWidth, height = totalHeight),
    ) {
        val dotPx = dotSizeDp.toPx()
        val stepPx = dotPx + gapDp.toPx()
        val radiusPx = 1.5.dp.toPx()
        val corner = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx)
        val dotSize = androidx.compose.ui.geometry.Size(dotPx, dotPx)
        val useMasks = slotMasks.isNotEmpty()
        for (day in 0 until 7) {
            val x = day * stepPx
            val mask = slotMasks.getOrNull(day) ?: 0
            val count = (dayCounts.getOrNull(day) ?: 0).coerceAtMost(5)
            for (row in 0 until 5) {
                val occupied = if (useMasks) (mask and (1 shl row)) != 0 else row < count
                drawRoundRect(
                    color = if (occupied) dotColor else emptyColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x, row * stepPx),
                    size = dotSize,
                    cornerRadius = corner,
                )
            }
        }
    }
}

/** 第 [week] 周的每日课数（越界安全回退空表）。 */
private fun WeekScheduleData.countsOfWeek(week: Int): List<Int> =
    weekDayCounts.getOrNull(week - 1) ?: emptyList()

/** 第 [week] 周的每日 5 大节时段掩码（越界安全回退空表）。 */
private fun WeekScheduleData.slotMasksOfWeek(week: Int): List<Int> =
    weekSlotMasks.getOrNull(week - 1) ?: emptyList()
