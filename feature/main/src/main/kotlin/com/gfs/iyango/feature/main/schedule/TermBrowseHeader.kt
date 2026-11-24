package com.gfs.iyango.feature.main.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidDropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** School term controls live inside the unified semester/week chooser. */
@Composable
fun TermBrowseHeader(
    state: TermBrowseController.State,
    onLoad: () -> Unit,
    onSelect: (String) -> Unit,
    onReturn: () -> Unit,
    onLogin: () -> Unit,
    onRetry: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val label = state.terms.firstOrNull { it.value == state.selected }
        ?.label?.takeIf { it.isNotBlank() }
        ?: state.selected
        ?: "选择学期"
    Column(Modifier.fillMaxWidth()) {
        Text("学校学期", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.fillMaxWidth()) {
            Box {
                TextButton(
                    modifier = Modifier.testTag("school-term-selector"),
                    onClick = {
                        expanded = true
                        if (state.terms.isEmpty() && !state.listLoading) onLoad()
                    },
                ) {
                    Text(label, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                LiquidDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (state.account == null || state.loginRequired) {
                        DropdownMenuItem(
                            text = { Text(if (state.account == null) "请先登录教务账号" else "重新登录教务账号") },
                            onClick = { expanded = false; onLogin() },
                        )
                    }
                    if (state.listLoading) {
                        DropdownMenuItem(text = { Text("正在读取学期…") }, enabled = false, onClick = {})
                    }
                    state.terms.forEach { term ->
                        DropdownMenuItem(
                            text = { Text(term.label.ifBlank { term.value }) },
                            onClick = { expanded = false; onSelect(term.value) },
                        )
                    }
                    if (!state.listLoading && state.account != null && state.terms.isEmpty() && state.listError == null) {
                        DropdownMenuItem(text = { Text("学校暂无可选学期") }, enabled = false, onClick = {})
                    }
                    state.listError?.let { error ->
                        DropdownMenuItem(text = { Text("$error 重试") }, onClick = onLoad)
                    }
                    if (state.account != null) {
                        DropdownMenuItem(text = { Text("刷新学期列表") }, onClick = onLoad)
                    }
                }
            }
            if (state.selected != null) {
                TextButton(modifier = Modifier.testTag("return-current-timetable"), onClick = onReturn) {
                    Text("返回当前课表")
                }
            }
        }
        if (state.selected != null) {
            val status = when {
                state.loading -> "正在读取学校课表…"
                state.data?.courses?.isEmpty() == true -> "该学期暂无课程 只读浏览"
                else -> "学校课表 只读浏览"
            }
            Text(status, style = MaterialTheme.typography.labelSmall)
            state.error?.let {
                Text(
                    it + if (state.data != null) " 已保留缓存" else " 请重试",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (state.error != null && !state.loading && !state.loginRequired) {
                TextButton(onClick = onRetry) { Text("重试读取课表") }
            }
            if (state.loginRequired) {
                TextButton(onClick = onLogin) { Text("重新登录教务账号") }
            }
        }
    }
}
