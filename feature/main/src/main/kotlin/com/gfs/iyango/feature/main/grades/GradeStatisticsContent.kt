package com.gfs.iyango.feature.main.grades

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.gfs.iyango.core.designsystem.motion.motionReveal
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import com.gfs.iyango.core.domain.GradeStatisticsCalculator
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeStatistics
import com.gfs.iyango.feature.main.LocalMainTabActive
import java.util.Locale

/**
 * 成绩页共享统计内容（方案 §7）：指标行、普通统计卡、自选提示条、统计说明面板与格式化函数。
 *
 * 统计结果在页面公共父层只计算一次，普通卡与固定计算栏共用 [GradeMetricsRow]；
 * 完整口径在 [StatisticsInfoSheet]，卡片正文不再常驻展开。
 */

/** 成绩页格式化：均分/绩点两位小数；学分为整数或一位小数。 */
internal fun formatNumber(value: Double): String =
    String.format(Locale.US, "%.2f", value)

internal fun gradePointLabel(record: GradeRecord): String =
    if (record.gradePoint == null && GradeStatisticsCalculator.resolvedGradePoint(record) != null) {
        "换算绩点"
    } else {
        "绩点"
    }

internal fun gradePointText(record: GradeRecord): String? =
    GradeStatisticsCalculator.resolvedGradePoint(record)?.let { "${gradePointLabel(record)} ${formatNumber(it)}" }

internal fun formatCredit(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }

/** 三项指标：加权平均 / 平均绩点 / 统计学分；[showEmptyPlaceholders] 用于“已选 0 条”。 */
@Composable
internal fun GradeMetricsRow(
    statistics: GradeStatistics,
    showEmptyPlaceholders: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().motionReveal(statistics to showEmptyPlaceholders)) {
        GradeMetricItem(
            label = "加权平均",
            value = if (showEmptyPlaceholders) "—" else statistics.weightedAverage?.let { formatNumber(it) } ?: "—",
            modifier = Modifier.weight(1f),
        )
        GradeMetricItem(
            label = "平均绩点",
            value = if (showEmptyPlaceholders) "—" else statistics.averageGradePoint?.let { formatNumber(it) } ?: "—",
            modifier = Modifier.weight(1f),
        )
        GradeMetricItem(
            label = "统计学分",
            value = if (showEmptyPlaceholders) "—" else formatCredit(statistics.totalCredits),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GradeMetricItem(label: String, value: String, modifier: Modifier = Modifier) {
    val description = if (value == "—") "$label 暂无数据" else "$label $value"
    Column(
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 普通模式统计卡：标题 + 自选入口 + 三项指标 + 设置行；纳入排除数量位于说明面板。 */
@Composable
internal fun GradeStatisticsCard(
    statistics: GradeStatistics,
    includeOptional: Boolean,
    onIncludeOptionalChange: (Boolean) -> Unit,
    onBeginSelection: () -> Unit,
    onOpenInfo: () -> Unit,
    beginEnabled: Boolean,
) {
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "成绩统计",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBeginSelection, enabled = beginEnabled) {
                    Text("自选算分")
                }
            }
            GradeMetricsRow(statistics = statistics, showEmptyPlaceholders = false)
            GradeStatisticsOptions(includeOptional, onIncludeOptionalChange, onOpenInfo)
        }
    }
}

/** Keep the elective switch beside its label and the statistics help at the trailing edge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GradeStatisticsOptions(
    includeOptional: Boolean,
    onIncludeOptionalChange: (Boolean) -> Unit,
    onOpenInfo: () -> Unit,
    infoLabel: String = "统计说明",
) {
    val textStyle = MaterialTheme.typography.bodySmall
    val switchHeight = with(LocalDensity.current) { textStyle.lineHeight.toDp() }
    val switchScale = switchHeight / 32.dp
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(modifier = Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                Text("统计包含公选课", style = textStyle)
            }
            // Scale the visible control with the text while keeping a full touch target.
            val toggleWidth = maxOf(48.dp, 64.dp * switchScale)
            Box(
                modifier = Modifier
                    .size(width = toggleWidth, height = maxOf(48.dp, switchHeight))
                    .toggleable(includeOptional, role = Role.Switch, onValueChange = onIncludeOptionalChange)
                    .semantics { contentDescription = "统计包含公选课" },
                contentAlignment = Alignment.Center,
            ) {
                LiquidToggle(
                    checked = includeOptional,
                    onCheckedChange = null,
                    modifier = Modifier.requiredSize(64.dp, 28.dp).scale(switchScale).clearAndSetSemantics {},
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onOpenInfo, modifier = Modifier.testTag("grades-statistics-info")) {
            Text(infoLabel, style = textStyle)
        }
    }
}

/** 统计说明面板：展示本次加入计算的科目列表；返回键只关闭面板，不影响自选模式。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsInfoSheet(
    records: List<GradeRecord>,
    includeOptional: Boolean,
    onDismiss: () -> Unit,
) {
    if (!LocalMainTabActive.current) return
    val included = remember(records, includeOptional) {
        records.filter { GradeStatisticsCalculator.isIncluded(it, includeOptional) }
    }
    LiquidModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassOverlayHeader(
                title = "统计说明",
                contentPadding = PaddingValues(0.dp),
                actions = {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                },
            )
            Text(
                text = "纳入 ${included.size} 条 排除 ${records.size - included.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (included.isEmpty()) {
                Text(
                    text = "暂无科目加入计算",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (included.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items = included, key = { "${it.id}_${it.courseName}_${it.rowOrder}" }) { record ->
                        IncludedSubjectRow(record = record)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
}

@Composable
private fun IncludedSubjectRow(record: GradeRecord) {
    val computedScore = GradeStatisticsCalculator.resolvedScore(record)
    val scoreDisplay = buildString {
        val raw = record.scoreText.ifBlank { computedScore?.let { formatCredit(it) } ?: "—" }
        append(raw)
        if (computedScore != null && (GradeStatisticsCalculator.levelScore(raw) != null ||
                (record.scoreValue == null && raw.toDoubleOrNull() == null))) {
            append(" (${formatCredit(computedScore)})")
        }
    }
    val meta = buildList {
        add("${formatCredit(record.credit)} 学分")
        if (record.attribute.isNotBlank()) add(gradeAttributeLabel(record.attribute))
        gradePointText(record)?.let { add(it) }
    }.joinToString(" ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = record.courseName.ifBlank { "未命名课程" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = scoreDisplay,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
