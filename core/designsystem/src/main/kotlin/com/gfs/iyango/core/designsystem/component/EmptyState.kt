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
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.tokens.Dimens

/**
 * 预览用"信息"占位图标：模块不依赖 material-icons，因此用 ImageVector 手绘。
 * 业务侧传入真实图标即可，本图标仅服务于 @Preview。
 */
private val PreviewInfoIcon: ImageVector = ImageVector.Builder(
    name = "PreviewInfo",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.EvenOdd,
    ) {
        // 外圈圆形
        moveTo(12f, 2f)
        curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
        curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
        curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
        curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
        close()
        // 信息点与信息杆（EvenOdd 规则下挖空）
        moveTo(11f, 7f)
        lineTo(13f, 7f)
        lineTo(13f, 9f)
        lineTo(11f, 9f)
        close()
        moveTo(11f, 11f)
        lineTo(13f, 11f)
        lineTo(13f, 17f)
        lineTo(11f, 17f)
        close()
    }
}.build()

/**
 * 居中空态：图标 + 主文案 + 可选浅色提示。
 *
 * 用于"没有课程 / 没有数据"等占位场景。图标为装饰性元素（contentDescription 置空），
 * 空态的实际语义由 title 文字承担，满足可访问性要求。
 *
 * @param icon 空态图标。
 * @param title 主文案，例如"今天没有课程"。
 * @param hint 辅助提示，弱化颜色显示；为 null 时不展示。
 * @param modifier 外部布局修饰符。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String? = null,
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
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        if (hint != null) {
            Spacer(modifier = Modifier.height(Dimens.spacingXs))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 亮色预览：带提示文案。 */
@Preview(name = "EmptyState - 亮色", showBackground = true)
@Composable
private fun EmptyStateLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            EmptyState(
                icon = PreviewInfoIcon,
                title = "今天没有课程",
                hint = "好好休息一天，或者去查看整周课表",
            )
        }
    }
}

/** 暗色预览：不带提示文案。 */
@Preview(name = "EmptyState - 暗色", showBackground = true)
@Composable
private fun EmptyStateDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            EmptyState(
                icon = PreviewInfoIcon,
                title = "暂无数据",
            )
        }
    }
}
