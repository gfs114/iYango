package com.gfs.iyango.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.tokens.Dimens

/**
 * 状态标签：小圆角底色标签，用于"正在上课 / 即将开始"等状态。
 *
 * 状态语义由文字本身完整表达，底色只作视觉强调，不单独承载信息（可访问性）。
 *
 * @param text 标签文字。
 * @param modifier 外部布局修饰符。
 */
@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                horizontal = Dimens.spacingM,
                vertical = Dimens.spacingXs,
            ),
        )
    }
}

/** 亮色预览：两种典型状态文案。 */
@Preview(name = "StatusBadge - 亮色", showBackground = true)
@Composable
private fun StatusBadgeLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            StatusBadge(text = "正在上课")
        }
    }
}

/** 暗色预览。 */
@Preview(name = "StatusBadge - 暗色", showBackground = true)
@Composable
private fun StatusBadgeDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            StatusBadge(text = "即将开始")
        }
    }
}
