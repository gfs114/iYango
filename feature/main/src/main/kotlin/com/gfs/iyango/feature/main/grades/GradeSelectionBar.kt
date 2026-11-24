package com.gfs.iyango.feature.main.grades

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.model.GradeStatistics

/**
 * 自选模式固定计算栏（方案 §5）。
 *
 * 位于 GlassScaffold.bottomBar 层（不录入正文采样层），滚动列表时结果与退出始终可见。
 * 纯状态展示与回调：不查询、不保存第二份选择集合。极矮窗口时栏内容高度封顶并允许栏内滚动，
 * 保证“已选/全选/清空/退出”首行始终可达。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GradeSelectionBar(
    statistics: GradeStatistics,
    selectedCount: Int,
    allSelected: Boolean,
    includeOptional: Boolean,
    onIncludeOptionalChange: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenAttributes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 极矮/横屏/大字号兜底：内容高度上限约为屏幕高度的 40%，超出部分栏内滚动。
    val maxBarHeight = LocalConfiguration.current.screenHeightDp.dp * 0.4f
    GlassSurface(
        material = GlassMaterial.OVERLAY,
        shape = RoundedCornerShape(20.dp),
        allowRefraction = true,
        includeShadow = true,
        modifier = modifier
            .fillMaxWidth()
            .testTag("grades-selection-bar")
            // 实体栏吸收空白处点击，不能点到后面的课程（方案 §5.6 / U23）。
            .pointerInput(Unit) {},
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxBarHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .padding(end = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "已选 $selectedCount 条",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                // 操作保持可见文字；窄屏/大字号由 FlowRow 自动换行，退出始终可达（方案 §5.2）。
                TextButton(onClick = onSelectAll, enabled = !allSelected) { Text("全选") }
                TextButton(onClick = onClearSelection, enabled = selectedCount > 0) { Text("清空") }
                TextButton(onClick = onExitSelection) { Text("退出") }
                TextButton(onClick = onOpenAttributes, modifier = Modifier.testTag("grades-attribute-selector")) {
                    Text("按属性")
                }
            }
            GradeMetricsRow(
                statistics = statistics,
                showEmptyPlaceholders = selectedCount == 0,
            )
            if (selectedCount == 0) {
                Text(
                    text = "请选择课程",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GradeStatisticsOptions(includeOptional, onIncludeOptionalChange, onOpenInfo, infoLabel = "说明")
        }
    }
}
