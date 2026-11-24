package com.gfs.iyango.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.tokens.Dimens

/**
 * 预览用"警告"占位图标：模块不依赖 material-icons，因此用 ImageVector 手绘。
 * 业务侧传入真实图标即可，本图标仅服务于 @Preview。
 */
private val PreviewWarningIcon: ImageVector = ImageVector.Builder(
    name = "PreviewWarning",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.EvenOdd,
    ) {
        // 警告三角
        moveTo(12f, 3f)
        lineTo(22f, 20f)
        lineTo(2f, 20f)
        close()
        // 感叹号（EvenOdd 规则下挖空）
        moveTo(11f, 10f)
        lineTo(13f, 10f)
        lineTo(13f, 15f)
        lineTo(11f, 15f)
        close()
        moveTo(11f, 17f)
        lineTo(13f, 17f)
        lineTo(13f, 19f)
        lineTo(11f, 19f)
        close()
    }
}.build()

/**
 * 错误状态：图标 + 标题 + 详情文字 + 可选"重试"按钮，整体居中。
 *
 * 可访问性要求：错误含义必须通过 title 与 detail 文字完整表达，
 * 颜色只作视觉强调，不单独承载信息。
 *
 * @param title 错误主文案，例如"加载失败"。
 * @param detail 错误详情，说明发生了什么以及可以做什么。
 * @param onRetry 重试回调；为 null 时不展示重试按钮。
 * @param modifier 外部布局修饰符。
 */
@Composable
fun ErrorState(
    title: String,
    detail: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimens.spacingXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PreviewWarningIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.height(Dimens.spacingM))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Dimens.spacingXs))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(Dimens.spacingL))
            LiquidButton(onClick = onRetry) {
                Text(text = "重试")
            }
        }
    }
}

/** 亮色预览：带重试按钮。 */
@Preview(name = "ErrorState - 亮色", showBackground = true)
@Composable
private fun ErrorStateLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            ErrorState(
                title = "加载失败",
                detail = "网络连接不可用，请检查网络后重试",
                onRetry = {},
            )
        }
    }
}

/** 暗色预览：不提供重试操作。 */
@Preview(name = "ErrorState - 暗色", showBackground = true)
@Composable
private fun ErrorStateDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            ErrorState(
                title = "数据同步失败",
                detail = "本地展示已缓存数据，稍后会自动重新同步",
            )
        }
    }
}
