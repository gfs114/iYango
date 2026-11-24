package com.gfs.iyango.feature.main.grades

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDropdownMenu
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.liquidglass.LiquidRadioButton
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.model.GradeSort
import com.gfs.iyango.core.model.GradeTerm
import com.gfs.iyango.feature.main.LocalMainTabActive

/**
 * 成绩页筛选：学期选择行、学期选择面板、列表标题与排序菜单。
 *
 * 学期选择后立即更新列表与统计；下拉刷新更新教务成绩；
 * 排序纯本地生效，不发请求、不清空选择。
 */

internal fun termLabel(terms: List<GradeTerm>, value: String): String {
    if (value.isEmpty()) return "全部学期"
    GradeTermInterval.parse(value)?.let { interval ->
        return "${termLabel(terms, interval.start)} 至 ${termLabel(terms, interval.end)}"
    }
    return terms.firstOrNull { it.value == value }?.label?.ifBlank { value } ?: value
}

/** 整行打开学期选择，标签始终显示当前计算范围。 */
@Composable
internal fun GradesFilterRow(
    state: GradesUiState,
    onOpenTermPicker: () -> Unit,
    selectorTag: String = "grades-term-selector",
) {
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !state.querying, onClick = onOpenTermPicker)
                .padding(4.dp)
                .testTag(selectorTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "学期",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = termLabel(state.terms, state.confirmedRange),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "▾",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 学期选择面板：范围入口 + 全部学期 + 学校列表 + 本机缓存回退项。
 * 选择缓存范围立即用于列表与统计；打开面板本身不触发学校请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TermPickerSheet(
    state: GradesUiState,
    onSelectRange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRangeChange: (String) -> Unit = onSelectRange,
    onQuery: () -> Unit = {},
) {
    if (!LocalMainTabActive.current) return
    LiquidModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("grades-term-picker-content")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GlassOverlayHeader(
                title = "选择学期",
                contentPadding = PaddingValues(0.dp),
                actions = {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                },
            )
            GradeTermRangePicker(state, onRangeChange, onQuery)
            TermOption(
                value = "",
                label = "全部学期",
                state = state,
                onSelectRange = onSelectRange,
            )
            state.terms.filter { it.value.isNotBlank() }.forEach { term ->
                TermOption(
                    value = term.value,
                    label = term.label.ifBlank { term.value },
                    state = state,
                    onSelectRange = onSelectRange,
                )
            }
            val knownValues = state.terms.map { it.value }.toSet() + ""
            listOf(state.draftRange, state.confirmedRange)
                .filter { it.isNotEmpty() && it !in knownValues && !GradeTermInterval.isIntervalValue(it) }
                .distinct()
                .forEach { value ->
                    TermOption(
                        value = value,
                        label = "${termLabel(state.terms, value)}（本机缓存）",
                        state = state,
                        onSelectRange = onSelectRange,
                    )
                }
            if (state.loadingTerms) {
                Text(
                    text = "正在获取学校学期",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.terms.isEmpty() && !state.loadingTerms) {
                Text(
                    text = "暂未取得学校学期列表",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 单个学期选项：radio 语义、48dp 触控行；实际显示范围附「正在显示」标记。 */
@Composable
private fun TermOption(
    value: String,
    label: String,
    state: GradesUiState,
    onSelectRange: (String) -> Unit,
) {
    val selected = state.draftRange == value
    val showing = state.confirmedRange == value && state.draftRange != value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = selected, role = Role.RadioButton, onValueChange = { onSelectRange(value) })
            .padding(horizontal = 4.dp)
            .testTag("grades-term-option-" + value.ifEmpty { "all" }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidRadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (showing) {
            Text(
                text = "正在显示",
                style = MaterialTheme.typography.labelSmall,
                color = LocalBrand.current.accent,
            )
        }
    }
}

/** 课程列表标题行：记录数 + 排序菜单（方案 §6.5）。 */
@Composable
internal fun GradesListHeader(
    recordCount: Int,
    sort: GradeSort,
    sortMenuOpen: Boolean,
    onSortMenuOpenChange: (Boolean) -> Unit,
    onSelectSort: (GradeSort) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "课程记录 $recordCount 条",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(
                onClick = { onSortMenuOpenChange(true) },
                modifier = Modifier.testTag("grades-sort-selector"),
            ) {
                Text(sort.shortLabel())
            }
            LiquidDropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { onSortMenuOpenChange(false) },
            ) {
                GradeSort.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.fullLabel()) },
                        leadingIcon = {
                            if (candidate == sort) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            onSelectSort(candidate)
                            onSortMenuOpenChange(false)
                        },
                    )
                }
            }
        }
    }
}

/** 折叠入口用短标签，展开菜单给完整文案。 */
private fun GradeSort.shortLabel(): String = when (this) {
    GradeSort.SCHOOL_ORDER -> "学校顺序"
    GradeSort.SCORE_DESC -> "分数 ↓"
    GradeSort.CREDIT_DESC -> "学分 ↓"
}

private fun GradeSort.fullLabel(): String = when (this) {
    GradeSort.SCHOOL_ORDER -> "学校顺序"
    GradeSort.SCORE_DESC -> "分数从高到低"
    GradeSort.CREDIT_DESC -> "学分从高到低"
}
