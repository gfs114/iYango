package com.gfs.iyango.feature.main.coursedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDialogSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDropdownMenu
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import com.gfs.iyango.core.designsystem.liquidglass.applyWindowBlur
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gfs.iyango.feature.main.LocalMainTabActive
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.DayLabels
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.feature.main.warningColor

/**
 * 课程调整对话框（ui-baseline §4〔参 ScheduleAdjustmentDialog〕）。
 *
 * 目标周 / 星期 / 起止节选择 + "仅本周生效" Switch + 冲突提示（warning 12sp）→ 确认调课。
 *
 * - 开关打开（默认）：写入 override 记录，原安排保留可恢复，受
 *   "每 (overrideOfId, overrideWeek) 至多一条" 唯一约束，由 ViewModel 落库；
 * - 开关关闭：永久调课，直接改写原课程安排（后续所有适用周生效）。跨周目标、
 *   指定日期（timeMode=2）与单次调整记录不允许永久调整（与鸿蒙 singleOnly 一致），
 *   此时开关被强制打开并禁用。
 *
 * @param course 被调整的课程（基础课或某条单次调整记录）。
 * @param displayedWeek 当前展示周（默认目标周）。
 * @param semester 学期上下文（timeMode=2 冲突域过滤用；可为 null）。
 * @param totalWeeks 学期总周数。
 * @param sectionCount 可选节次上限。
 * @param courses 本课表全部课程（冲突检测域）。
 * @param timeSlots 作息时间表（当前安排的时间展示用）。
 * @param onApply 确认回调：(目标周, 星期, 起始节, 结束节, 是否仅本周生效)。
 * @param onDismiss 取消回调。
 */
@Composable
fun ScheduleAdjustmentDialog(
    course: Course,
    displayedWeek: Int,
    semester: Semester?,
    totalWeeks: Int,
    sectionCount: Int,
    timeSlots: List<TimeSlot>,
    sourceOccurrenceId: String? = null,
    queryTargetDay: suspend (java.time.LocalDate) -> com.gfs.iyango.core.domain.EffectiveScheduleDay = { com.gfs.iyango.core.domain.EffectiveScheduleDay(it) },
    onApply: (targetWeek: Int, dayOfWeek: Int, startSection: Int, endSection: Int, temporary: Boolean, targetDay: com.gfs.iyango.core.domain.EffectiveScheduleDay) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!LocalMainTabActive.current) return
    var targetWeek by remember { mutableIntStateOf(displayedWeek.coerceIn(1, totalWeeks.coerceAtLeast(1))) }
    var selectedDay by remember { mutableIntStateOf(course.dayOfWeek.coerceIn(1, 7)) }
    var startSection by remember { mutableIntStateOf(course.startSection) }
    var endSection by remember { mutableIntStateOf(course.endSection) }
    var temporaryOnly by remember { mutableStateOf(true) }

    // 跨周目标、指定日期与单次调整记录只能做本周临时调整（与鸿蒙 singleOnly 一致）。
    val singleOnly = targetWeek != displayedWeek || course.timeMode == 2 || course.overrideWeek > 0

    val weeks = (1..totalWeeks.coerceAtLeast(1)).toList()
    val sections = (1..sectionCount.coerceAtLeast(1)).toList()

    val targetDate = semester?.startDate?.plusDays((targetWeek - 1) * 7L + selectedDay - 1)
    var targetDay by remember { mutableStateOf<com.gfs.iyango.core.domain.EffectiveScheduleDay?>(null) }
    var queryError by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(targetDate, queryTargetDay) {
        targetDay = null
        queryError = false
        if (targetDate != null) try { targetDay = queryTargetDay(targetDate) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { queryError = true }
    }
    val targetWarning = when {
        queryError -> "目标课表读取失败，请重新选择日期"
        targetDay == null -> "正在读取目标日期课表"
        else -> targetDay?.let { com.gfs.iyango.core.domain.AdjustmentTargetPolicy.warning(it, course.timetableId) }
    }
    val targetShape = com.gfs.iyango.core.domain.AdjustmentCourseShape.forSections(course, selectedDay, startSection, endSection)
    val conflicts = com.gfs.iyango.core.domain.OccurrenceConflicts.find(
        targetShape, targetDay?.timeSlots ?: timeSlots, targetDay?.occurrences.orEmpty(), sourceOccurrenceId,
    )
    val conflictNames = conflicts
        .map { it.course.name.trim().ifEmpty { "未命名课程" } }
        .distinct()
        .joinToString("、")

    Dialog(onDismissRequest = onDismiss) {
        val view = LocalView.current
        val blurPx = with(LocalDensity.current) { 32.dp.toPx().toInt() }
        SideEffect { applyWindowBlur(view, blurPx) }

        LiquidDialogSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassOverlayHeader(title = "调整课程时间", contentPadding = PaddingValues(0.dp))
                Text(
                    text = "${course.name}\n当前：第 $displayedWeek 周 ${currentLabel(course, timeSlots)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SelectField(
                    value = "调整到：第 $targetWeek 周",
                    options = weeks.map { "第 $it 周" },
                    selectedIndex = targetWeek - 1,
                    onSelect = { targetWeek = it + 1 },
                )
                SelectField(
                    value = DayLabels.label(selectedDay),
                    options = (1..7).map { DayLabels.label(it) },
                    selectedIndex = selectedDay - 1,
                    onSelect = { selectedDay = it + 1 },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectField(
                        value = "第 $startSection 节",
                        options = sections.map { "第 $it 节" },
                        selectedIndex = startSection - 1,
                        onSelect = { index ->
                            val newStart = index + 1
                            val span = endSection - startSection
                            startSection = newStart
                            endSection = (newStart + span).coerceAtMost(sectionCount)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "至",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    SelectField(
                        value = "第 $endSection 节",
                        options = (startSection..sectionCount).map { "第 $it 节" },
                        selectedIndex = (endSection - startSection).coerceAtLeast(0),
                        onSelect = { index -> endSection = startSection + index },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "仅本周生效",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    LiquidToggle(
                        checked = singleOnly || temporaryOnly,
                        onCheckedChange = { checked -> temporaryOnly = checked },
                        enabled = !singleOnly,
                    )
                }
                if (!singleOnly && !temporaryOnly) {
                    Text(
                        text = "后续所有适用周都改到新时间",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                targetWarning?.let { Text(it, fontSize = 12.sp, color = warningColor()) }
                if (conflictNames.isNotEmpty()) {
                    Text(
                        text = "与「$conflictNames」时间重叠",
                        fontSize = 12.sp,
                        color = warningColor(),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    LiquidButton(
                        enabled = targetWarning == null,
                        onClick = {
                            targetDay?.let { confirmed -> onApply(targetWeek, selectedDay, startSection, endSection, singleOnly || temporaryOnly, confirmed) }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("确认调课") }
                }
            }
        }
    }
}

/** 当前安排的"星期 + 起止时间"描述。 */
private fun currentLabel(course: Course, timeSlots: List<TimeSlot>): String {
    val range = CourseRules.timeRange(course, timeSlots)
    val time = if (range.startTime.isEmpty() || range.endTime.isEmpty()) {
        "时间待定"
    } else {
        "${range.startTime} - ${range.endTime}"
    }
    return "${DayLabels.label(course.dayOfWeek)} $time"
}

/**
 * 下拉选择字段：高 48、surfaceAlt 底、R12（对齐 ui-baseline §5 选择器样式）。
 */
@Composable
private fun SelectField(
    value: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LiquidDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            color = if (index == selectedIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}
