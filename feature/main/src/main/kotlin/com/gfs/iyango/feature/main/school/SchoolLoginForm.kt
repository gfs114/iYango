@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.school

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidCheckbox
import com.gfs.iyango.core.designsystem.liquidglass.LiquidTextField
import com.gfs.iyango.feature.main.R

@Composable
internal fun SchoolLoginForm(
    title: String,
    state: SchoolAccountUiState,
    onAction: (AccountAction) -> Unit,
    onHelp: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val passwordFocus = remember { FocusRequester() }
    var passwordVisible by remember(state.busy) { mutableStateOf(false) }
    var submitDispatched by remember(state.account, state.password, state.busy) { mutableStateOf(false) }

    fun submit() {
        if (!state.canSubmit || submitDispatched) return
        submitDispatched = true
        passwordVisible = false
        keyboard?.hide()
        focus.clearFocus()
        onAction(AccountAction.Submit)
    }

    val colors = MaterialTheme.colorScheme
    val fields = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
        focusedLeadingIconColor = colors.primary, unfocusedLeadingIconColor = colors.onSurfaceVariant,
    )
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text("使用阳光学院教务系统的学号与密码登录", style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        val credentialError = state.credentialError
        val formShape = RoundedCornerShape(14.dp)
        GlassSurface(material = GlassMaterial.CONTENT, shape = formShape,
            modifier = Modifier.fillMaxWidth().border(1.dp, colors.outlineVariant, formShape)) {
            Column {
                LiquidTextField(
                    value = state.account,
                    onValueChange = { onAction(AccountAction.AccountChanged(it)) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "学号" },
                    enabled = !state.busy,
                    singleLine = true,
                    leadingIcon = { LoginFieldLabel("学号") },
                    colors = fields,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                )
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = colors.outlineVariant)
                LiquidTextField(
                    value = state.password,
                    onValueChange = { onAction(AccountAction.PasswordChanged(it)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).semantics { contentDescription = "密码" },
                    enabled = !state.busy,
                    singleLine = true,
                    leadingIcon = { LoginFieldLabel("密码") },
                    colors = fields,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.password.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        onAction(AccountAction.PasswordChanged(""))
                                        passwordFocus.requestFocus()
                                        keyboard?.show()
                                    },
                                    enabled = !state.busy,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "清空密码")
                                }
                            }
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                enabled = !state.busy,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (passwordVisible) R.drawable.ic_school_password_hide else R.drawable.ic_school_password_show
                                    ),
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                )
                            }
                        }
                    },
                    isError = credentialError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
        }
        if (credentialError != null) {
            Text(credentialError, Modifier.padding(start = 14.dp, top = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RememberPasswordRow(
                checked = state.rememberPassword, enabled = !state.busy, label = "记住密码",
                modifier = Modifier, round = true,
            ) { onAction(AccountAction.RememberChanged(it)) }
            TextButton(onClick = onHelp, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("忘记密码？", style = MaterialTheme.typography.labelMedium)
            }
        }
        state.formError?.let { AccountError(it) }
        Spacer(Modifier.height(8.dp))
        val enabled = state.canSubmit && !submitDispatched
        LiquidButton(
            onClick = ::submit, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
            tintColor = colors.primary,
            contentColor = colors.onPrimary,
        ) {
            if (state.submitting || submitDispatched) {
                CircularProgressIndicator(Modifier.size(16.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.submitting || submitDispatched) "正在登录…" else "登录", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LoginFieldLabel(label: String) {
    Text(label, Modifier.padding(start = 14.dp, end = 4.dp), style = MaterialTheme.typography.labelLarge)
}

@Composable
internal fun RememberPasswordRow(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    round: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier.heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (round) {
            val color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Box(Modifier.size(18.dp).border(1.dp, color, CircleShape)
                .background(if (checked) color else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                if (checked) Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        } else {
            LiquidCheckbox(checked = checked, enabled = enabled, onCheckedChange = null)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = if (round) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium)
    }
}
