package com.gfs.iyango.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import kotlinx.coroutines.delay

/**
 * 下拉刷新结果顶部玻璃胶囊：放在 GlassScaffold.overlay 中，采样页面而不录入自身。
 * 主题色着色，文字位于光学效果之上；展示后自动收起。
 *
 * @param message 当前要展示的提示文案；为空或 null 时隐藏。
 * @param onDismiss 自动展示结束后的消费回调。
 * @param topPadding 顶栏安全高度（胶囊将在其下方偏移展示）。
 * @param durationMillis 胶囊停留时长（毫秒）。
 * @param modifier 外部布局修饰符。
 */
@Composable
fun RefreshNoticeCapsule(
    message: String?,
    onDismiss: () -> Unit = {},
    topPadding: Dp = 0.dp,
    durationMillis: Long = 3_000L,
    modifier: Modifier = Modifier,
) {
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    var lastText by remember { mutableStateOf("") }
    if (!message.isNullOrEmpty()) {
        lastText = message
    }

    LaunchedEffect(message, durationMillis) {
        if (!message.isNullOrEmpty() && durationMillis > 0L) {
            delay(durationMillis)
            latestOnDismiss()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding + 16.dp, start = 24.dp, end = 24.dp)
            .zIndex(10f),
        contentAlignment = Alignment.TopCenter,
    ) {
        val visible = !message.isNullOrEmpty()
        if (LocalMotionEnabled.current) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(AppMotion.Content)) + slideInVertically(tween(AppMotion.Expand)) { -it / 2 },
                exit = fadeOut(tween(AppMotion.Content)) + slideOutVertically(tween(AppMotion.Expand)) { -it / 2 },
            ) {
                NoticeContent(lastText)
            }
        } else if (visible) {
            NoticeContent(lastText)
        }
    }
}

@Composable
private fun NoticeContent(text: String) {
    GlassSurface(
        modifier = Modifier.widthIn(max = 360.dp).testTag("refresh-notice-capsule")
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        shape = RoundedCornerShape(24.dp),
        material = GlassMaterial.OVERLAY,
        allowRefraction = true,
        includeShadow = true,
        tintColor = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Preview(name = "RefreshNoticeCapsule - 亮色", showBackground = true)
@Composable
private fun RefreshNoticeCapsuleLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        RefreshNoticeCapsule(message = "刷新成功")
    }
}

@Preview(name = "RefreshNoticeCapsule - 暗色", showBackground = true)
@Composable
private fun RefreshNoticeCapsuleDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        RefreshNoticeCapsule(message = "刷新成功")
    }
}
