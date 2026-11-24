package com.gfs.iyango.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.designsystem.tokens.Dimens

/**
 * 区块标题（ui-baseline §2）：3dp×16dp、圆角 2dp 的强调色竖条
 * （取 [LocalBrand] 的 accent）+ 16sp Bold 标题 + 右侧 trailing 槽，
 * 整行高度对齐 24dp。
 *
 * @param title 区块标题文字。
 * @param modifier 外部布局修饰符。
 * @param trailing 标题右侧动作槽（通常是文字按钮/图标）；为 null 时不展示。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(LocalBrand.current.accent),
        )
        Spacer(modifier = Modifier.width(Dimens.spacingS))
        Text(
            text = title,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** 亮色预览：带动作槽与不带动作槽两种形态。 */
@Preview(name = "SectionHeader - 亮色", showBackground = true)
@Composable
private fun SectionHeaderLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.spacingL),
                verticalArrangement = Arrangement.spacedBy(Dimens.spacingM),
            ) {
                SectionHeader(title = "今日课程")
                SectionHeader(
                    title = "快捷入口",
                    trailing = {
                        TextButton(onClick = {}) { Text(text = "全部") }
                    },
                )
            }
        }
    }
}

/** 暗色预览。 */
@Preview(name = "SectionHeader - 暗色", showBackground = true)
@Composable
private fun SectionHeaderDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SectionHeader(
                title = "明日安排",
                modifier = Modifier.padding(Dimens.spacingL),
            )
        }
    }
}
