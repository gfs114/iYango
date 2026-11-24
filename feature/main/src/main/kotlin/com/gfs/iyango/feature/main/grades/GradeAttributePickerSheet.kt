package com.gfs.iyango.feature.main.grades

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
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDropdownMenu
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.domain.GradeStatisticsCalculator
import com.gfs.iyango.feature.main.LocalMainTabActive

/** Group checkboxes share the same selected record IDs as individual course checkboxes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GradeAttributePickerSheet(
    state: GradesUiState,
    onToggleAttribute: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelectRange: (String) -> Unit,
    onOpenTermPicker: () -> Unit,
) {
    if (!LocalMainTabActive.current || !state.selectionMode) return
    val groups = remember(state.records) { gradeAttributeGroups(state.records) }
    val statistics = remember(state.records, state.selectedRecordIds, state.includeOptional) {
        GradeStatisticsCalculator.calculate(state.statisticsRecords, state.includeOptional)
    }
    LiquidModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .testTag("grades-attribute-picker-content")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassOverlayHeader(
                title = "课程属性",
                contentPadding = PaddingValues(0.dp),
                actions = { TextButton(onClick = onDismiss) { Text("关闭") } },
            )
            AttributeTermSelector(state, onSelectRange, onOpenTermPicker)
            GradeMetricsRow(statistics, showEmptyPlaceholders = state.selectedRecordIds.isEmpty())
            Text("已选 ${state.statisticsRecords.size} 条", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            groups.forEach { group ->
                val selectedCount = group.recordIds.count { it in state.selectedRecordIds }
                val checked = when (selectedCount) {
                    0 -> ToggleableState.Off
                    group.recordIds.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .triStateToggleable(state = checked, role = Role.Checkbox,
                            onClick = { onToggleAttribute(group.value) })
                        .padding(vertical = 4.dp)
                        .testTag("grades-attribute-option-${group.value}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(state = checked, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(group.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text("$selectedCount/${group.recordIds.size} 门", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AttributeTermSelector(
    state: GradesUiState,
    onSelectRange: (String) -> Unit,
    onOpenTermPicker: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        GradesFilterRow(state, onOpenTermPicker = { expanded = true }, selectorTag = "grades-attribute-term-selector")
        LiquidDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text("全部学期") }, onClick = { expanded = false; onSelectRange("") },
                modifier = Modifier.testTag("grades-attribute-term-option-all"))
            state.terms.filter { it.value.isNotBlank() }.distinctBy { it.value }.forEach { term ->
                DropdownMenuItem(text = { Text(term.label.ifBlank { term.value }) },
                    onClick = { expanded = false; onSelectRange(term.value) },
                    modifier = Modifier.testTag("grades-attribute-term-option-${term.value}"))
            }
            DropdownMenuItem(text = { Text("学期范围…") }, onClick = { expanded = false; onOpenTermPicker() },
                modifier = Modifier.testTag("grades-attribute-term-range"))
        }
    }
}
