package com.gfs.iyango.feature.main.grades

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.model.GradeTerm

/** 起止学期调整立即用于计算；面板保留展开状态，允许连续调整。 */
@Composable
internal fun GradeTermRangePicker(
    state: GradesUiState,
    onSelectRange: (String) -> Unit,
    onQuery: () -> Unit,
) {
    val terms = remember(state.terms) { GradeTermInterval.ordered(state.terms) }
    val selected = GradeTermInterval.parse(state.confirmedRange)
    var expanded by remember(state.accountKey) { mutableStateOf(selected != null) }
    var start by remember(state.accountKey, terms) { mutableStateOf(
        terms.firstOrNull { it.value == (selected?.start ?: state.confirmedRange) }?.value ?: terms.firstOrNull()?.value.orEmpty(),
    ) }
    var end by remember(state.accountKey, terms) { mutableStateOf(
        terms.firstOrNull { it.value == (selected?.end ?: state.confirmedRange) }?.value ?: terms.lastOrNull()?.value.orEmpty(),
    ) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = terms.isNotEmpty(), role = Role.Button) {
                expanded = !expanded
                if (expanded) GradeTermInterval.between(start, end)?.let { onSelectRange(it.value) }
            }
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .padding(horizontal = 4.dp).testTag("grades-range-toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("学期范围", style = MaterialTheme.typography.bodyMedium,
            color = if (terms.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
    }
    if (expanded && terms.isNotEmpty()) {
        val interval = GradeTermInterval.between(start, end)
        val included = terms.filter { interval?.contains(it.value) == true }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EndpointSelector("起始学期", "start", start, terms) { value ->
                start = value
                if (GradeTermInterval.between(value, end) == null) end = value
                GradeTermInterval.between(start, end)?.let { onSelectRange(it.value) }
            }
            EndpointSelector("结束学期", "end", end,
                terms.filter { GradeTermInterval.between(start, it.value) != null }) { value ->
                end = value
                GradeTermInterval.between(start, end)?.let { onSelectRange(it.value) }
            }
            Text("包含 ${included.size} 个学期", style = MaterialTheme.typography.bodyMedium)
            LiquidButton(
                onClick = onQuery,
                enabled = state.loggedIn && !state.querying && interval != null && included.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("grades-range-query"),
            ) {
                Text(if (state.querying) "查询中…" else "查询")
            }
        }
    }
}

@Composable
private fun EndpointSelector(
    label: String,
    key: String,
    value: String,
    terms: List<GradeTerm>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button) { open = true }.padding(vertical = 4.dp)
            .testTag("grades-range-$key"), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(termLabel(terms, value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        LiquidDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            terms.forEach { term ->
                DropdownMenuItem(text = { Text(term.label.ifBlank { term.value }) },
                    onClick = { open = false; onSelect(term.value) },
                    modifier = Modifier.testTag("grades-range-$key-option-${term.value}"))
            }
        }
    }
}
