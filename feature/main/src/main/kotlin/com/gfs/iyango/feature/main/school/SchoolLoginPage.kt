package com.gfs.iyango.feature.main.school

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.lerp
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.gfs.iyango.core.designsystem.component.RefreshNoticeCapsule
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidRadioButton
import com.gfs.iyango.feature.main.isDarkTheme
import kotlinx.coroutines.launch

@Composable
internal fun SchoolLoginPage(
    title: String,
    state: SchoolAccountUiState,
    onAction: (AccountAction) -> Unit,
    onHelp: () -> Unit,
    onWebsite: () -> Unit,
) {
    val dark = isDarkTheme()
    val colors = MaterialTheme.colorScheme.copy(
        primary = if (dark) Color(0xFFFFA566) else Color(0xFFB54A0F),
        onPrimary = if (dark) Color(0xFF482007) else Color.White,
        primaryContainer = if (dark) Color(0xFF4A3024) else Color(0xFFFFE8D6),
        onPrimaryContainer = if (dark) Color(0xFFFFC6A1) else Color(0xFF923D0A),
        background = if (dark) Color(0xFF18191B) else Color(0xFFF8F6F3),
        surface = if (dark) Color(0xFF242528) else Color.White,
        onSurface = if (dark) Color(0xFFF7EFE9) else Color(0xFF2A211C),
        onSurfaceVariant = if (dark) Color(0xFFC4C0BD) else Color(0xFF70665E),
        surfaceContainer = if (dark) Color(0xFF292A2D) else Color(0xFFF6F3EF),
        outlineVariant = if (dark) Color(0xFF555458) else Color(0xFFE5DED7),
    )
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    var chooseMode by rememberSaveable { mutableStateOf(false) }
    var websiteError by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colorScheme = colors) {
        GlassBackdropHost(modifier = Modifier.fillMaxSize().background(colors.background)) {
            BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
                val compact = maxHeight < 540.dp || WindowInsets.ime.getBottom(LocalDensity.current) > 0
                Column(Modifier.fillMaxSize().verticalScroll(scroll),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LoginHeader(compact, dark)
                    Column(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp)
                            .navigationBarsPadding().padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(20.dp),
                            includeShadow = true, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                SchoolLoginForm(title, state, onAction, onHelp)
                                if (state.history.isNotEmpty()) {
                                    TextButton(onClick = { historyExpanded = !historyExpanded }, enabled = !state.busy,
                                        modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                        Text(if (historyExpanded) "收起历史账号" else "历史账号（${state.history.size}）")
                                        Spacer(Modifier.width(5.dp))
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null,
                                            modifier = Modifier.size(18.dp).accountArrow(historyExpanded))
                                    }
                                    AccountDisclosure(historyExpanded) {
                                        Column {
                                            AccountHistory(state, { action ->
                                                if (action is AccountAction.Fill && state.loginEntry != null) {
                                                    historyExpanded = false
                                                    scope.launch { scroll.scrollTo(0) }
                                                }
                                                onAction(action)
                                            }, inline = true)
                                        }
                                    }
                                }
                                (state.problem as? AccountProblem.Action)?.let { AccountError(it.message) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !state.busy) { chooseMode = true }) {
                            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("重登方式", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant)
                                Text(if (state.rememberPassword) "自动重登" else "手动登录",
                                    style = MaterialTheme.typography.bodyMedium, color = colors.primary)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.primary)
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val links = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)
                            TextButton(onClick = { websiteError = runCatching(onWebsite).isFailure },
                                enabled = !state.busy, colors = links) { Text("教务网站") }
                            TextButton(onClick = onHelp, enabled = !state.busy, colors = links) { Text("登录帮助") }
                        }
                        if (websiteError) AccountError("无法打开教务网站，请检查浏览器设置。")
                    }
                }
                RefreshNoticeCapsule(state.message.takeUnless { state.busy }, { onAction(AccountAction.MessageShown) },
                    topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            }
        }
        if (chooseMode) {
            LiquidAlertDialog(onDismissRequest = { chooseMode = false }, title = { Text("重登方式") },
                text = {
                    Column(Modifier.selectableGroup()) {
                        listOf(true, false).forEach { automatic ->
                            val select = {
                                onAction(AccountAction.RememberChanged(automatic))
                                chooseMode = false
                            }
                            Row(Modifier.fillMaxWidth().selectable(selected = automatic == state.rememberPassword,
                                onClick = select, role = Role.RadioButton).padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                LiquidRadioButton(selected = automatic == state.rememberPassword, onClick = null)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(if (automatic) "自动重登" else "手动登录")
                                    Text(if (automatic) "记住本次登录密码。7 天内会话失效时自动重登。"
                                        else "本次登录不保存密码，会话失效后需手动登录。",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { chooseMode = false }) { Text("关闭") } })
        }
    }
}

@Composable
private fun LoginHeader(compact: Boolean, dark: Boolean) {
    val target = if (compact) 1f else 0f
    val fraction = if (LocalMotionEnabled.current) animateFloatAsState(
        target, tween(AppMotion.Content), label = "loginHeader",
    ).value else target
    val iconSize = lerp(120.dp, 60.dp, fraction)
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { 120.dp.roundToPx() }
    val icon = remember(context, pixels) {
        val info = context.applicationInfo
        val drawable = if (info.icon != 0) context.getDrawable(info.icon) else null
        val foreground = (drawable as? AdaptiveIconDrawable)?.foreground
        (foreground ?: drawable ?: info.loadIcon(context.packageManager)).toBitmap(pixels, pixels).asImageBitmap()
    }
    Column(
        Modifier.fillMaxWidth().drawWithCache {
            val height = size.height + 26.dp.toPx()
            val brush = Brush.linearGradient(
                if (dark) listOf(Color(0xFF63331F), Color(0xFF5D3021), Color(0xFF372B2B))
                else listOf(Color(0xFFA94210), Color(0xFFBB5217), Color(0xFFD77B39)),
                start = Offset.Zero, end = Offset(size.width, height),
            )
            val radius = minOf(size.width * .55f, height * .93f)
            val sun = if (dark) Color(0xFF95552F) else Color(0xFFFFBC78)
            onDrawBehind {
                drawRect(brush, size = Size(size.width, height))
                clipRect(bottom = height) {
                    drawCircle(sun, radius = radius, center = Offset(size.width + radius * .16f, height + radius * .30f))
                }
            }
        }.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(top = lerp(48.dp, 12.dp, fraction), bottom = lerp(28.dp, 14.dp, fraction)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(iconSize).clip(RoundedCornerShape(lerp(28.dp, 16.dp, fraction)))
            .background(Color.White), contentAlignment = Alignment.Center) {
            Image(icon, contentDescription = null, modifier = Modifier.requiredSize(iconSize * 1.40f))
        }
        Spacer(Modifier.height(lerp(14.dp, 4.dp, fraction)))
        Text("iYango", fontSize = lerp(22.sp, 16.sp, fraction), lineHeight = lerp(28.sp, 20.sp, fraction),
            fontWeight = FontWeight.Bold, color = Color.White)
    }
}
