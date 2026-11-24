@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.school

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.component.SectionHeader
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidTonalButton
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val SchoolConnection.accountDisplayName: String
    get() = profile.displayName.trim().ifEmpty { maskedAccount.ifBlank { "教务账号" } }

@Composable
internal fun AccountCard(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(shape = RoundedCornerShape(20.dp), material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun AccountEntry(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    GlassSurface(shape = RoundedCornerShape(16.dp), material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AccountOverview(state: SchoolAccountUiState, onSync: () -> Unit = {},
    onAction: (AccountAction) -> Unit) {
    val connection = state.active ?: return
    var managementExpanded by rememberSaveable(connection.id) { mutableStateOf(false) }
    AccountCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(connection.accountDisplayName, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connection.maskedAccount.isNotBlank()) Text(connection.maskedAccount, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AccountStatus(connection.status)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            AccountProfileDetails(connection)
            TextButton(onClick = { onAction(AccountAction.Logout) }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("退出登录", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    GlassSurface(shape = RoundedCornerShape(16.dp), material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { managementExpanded = !managementExpanded }
            .semantics { stateDescription = if (managementExpanded) "已展开" else "已收起" }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("账号管理", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.accountArrow(managementExpanded),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    AccountDisclosure(managementExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AccountManagement(state, onAction)
        }
    }
}

@Composable
private fun AccountProfileDetails(connection: SchoolConnection) {
        val values = listOf(
            "学号" to connection.profile.studentNo.ifBlank { connection.maskedAccount },
            "学院" to connection.profile.college,
            "专业" to connection.profile.major,
            "班级" to connection.profile.className,
        ).filter { it.second.isNotBlank() }
        values.forEachIndexed { index, (label, value) ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
}

@Composable
internal fun AccountManagement(state: SchoolAccountUiState, onAction: (AccountAction) -> Unit) {
    LiquidButton(onClick = { onAction(AccountAction.OpenLogin) }, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("使用其他账号登录") }
    SectionHeader("历史账号")
    AccountHistory(state, onAction)
}

@Composable
internal fun AccountHistory(state: SchoolAccountUiState, onAction: (AccountAction) -> Unit, inline: Boolean = false) {
    val history = state.history
    if (history.isEmpty()) {
        Text("暂无历史账号", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        history.forEach { connection ->
            val isCurrent = connection.id == state.active?.id
            val details: @Composable ColumnScope.() -> Unit = {
                Text(connection.accountDisplayName, style = MaterialTheme.typography.titleMedium)
                if (connection.maskedAccount != connection.accountDisplayName) Text(connection.maskedAccount,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isCurrent) Text("当前使用", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                else AccountStatus(connection.status)
                Text(if (connection.rememberPassword) "已保存密码" else "需输入密码",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isCurrent && connection.rememberPassword) {
                        LiquidTonalButton(enabled = !state.busy, onClick = { onAction(AccountAction.Restore(connection.id)) }) {
                            Text(if (state.restoringId == connection.id) "正在恢复教务会话…" else "切换账号")
                        }
                    }
                    if (!isCurrent) TextButton(enabled = !state.busy, onClick = { onAction(AccountAction.Fill(connection)) }) { Text("填写账号") }
                    TextButton(enabled = !state.busy, onClick = { onAction(AccountAction.Delete(connection)) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                val problem = state.problem as? AccountProblem.Restore
                if (problem?.connectionId == connection.id) AccountError(problem.message)
            }
            if (inline) {
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp), content = details)
            } else {
                AccountCard(details)
            }
        }
        if (history.any { it.id != state.active?.id }) {
            TextButton(enabled = !state.busy, onClick = { onAction(AccountAction.ClearHistory) }, modifier = Modifier.fillMaxWidth()) {
                Text("清空历史账号", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AccountStatus(status: SchoolConnectionStatus) {
    val label = when (status) {
        SchoolConnectionStatus.ACTIVE -> "已登录"
        SchoolConnectionStatus.LOGGED_OUT -> "未登录"
        SchoolConnectionStatus.VERIFYING -> "验证中"
        SchoolConnectionStatus.EXPIRED -> "会话已过期"
        SchoolConnectionStatus.FAILED -> "连接暂不可用"
    }
    Text(label, style = MaterialTheme.typography.labelLarge, color = when (status) {
        SchoolConnectionStatus.EXPIRED, SchoolConnectionStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    })
}

@Composable
internal fun AccountError(message: String) {
    GlassSurface(shape = RoundedCornerShape(12.dp), material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f))) {
            Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun AccountHelp() {
    AccountCard {
        Text("使用教务账号", style = MaterialTheme.typography.titleMedium)
        Text("输入阳光学院教务系统的学号和密码。忘记密码时，请前往教务网站或联系学校处理。")
        Text("记住密码", style = MaterialTheme.typography.titleMedium)
        Text("从最近一次主动登录或成功切换账号起，7 天内会话失效时会尝试用已保存的密码自动重登。自动重登不会延长这 7 天。超过期限后，会话失效时需要手动登录。")
        Text("主动退出后不会自动重登。退出时可以选择清除已保存的密码。")
        Text("教务网站", style = MaterialTheme.typography.titleMedium)
        Text("将在浏览器中打开，浏览器的登录状态不会自动同步到此应用。")
    }
}
