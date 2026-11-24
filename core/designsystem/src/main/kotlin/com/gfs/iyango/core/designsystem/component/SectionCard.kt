package com.gfs.iyango.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.tokens.Dimens

/**
 * 分区卡片：标题 + 可选右侧动作槽 + 内容。
 *
 * 卡片圆角使用 Dimens.cardCorner；标题占满剩余宽度，动作槽靠右对齐。
 *
 * 存在玻璃背景宿主时使用内容玻璃表面（采样 L0 背景基底）；
 * 无宿主（Preview、独立窗口）保持原 Card 呈现，功能与内容不受影响。
 *
 * @param title 卡片标题。
 * @param action 标题右侧的动作槽（通常是文字按钮/图标按钮）；为 null 时不展示。
 * @param modifier 外部布局修饰符。
 * @param content 卡片正文内容，作用域为 ColumnScope，按列排布。
 */
@Composable
fun SectionCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(Dimens.spacingL)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                action?.invoke()
            }
            Spacer(modifier = Modifier.height(Dimens.spacingM))
            content()
        }
    }
    if (LocalGlassBackdrop.current == null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.cardCorner),
        ) {
            body()
        }
    } else {
        GlassSurface(
            modifier = modifier.fillMaxWidth(),
            material = GlassMaterial.CONTENT,
        ) {
            body()
        }
    }
}

/** 亮色预览：带动作槽。 */
@Preview(name = "SectionCard - 亮色", showBackground = true)
@Composable
private fun SectionCardLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
                SectionCard(
                    title = "今日课程",
                    action = {
                        TextButton(onClick = {}) { Text(text = "全部") }
                    },
                ) {
                    Text(
                        text = "内容区域：这里放置卡片正文，例如课程列表。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 暗色预览：无动作槽。 */
@Preview(name = "SectionCard - 暗色", showBackground = true)
@Composable
private fun SectionCardDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            SectionCard(title = "本周概览") {
                Text(
                    text = "共 18 节课，早八 3 次。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
