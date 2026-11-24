// 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0）：
// app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt (kmp 分支)
// 本项目适配：选中语义与统一用户选中入口；按 LocalLiquidBottomTabScale 缩放内容。
package com.gfs.iyango.feature.main.liquidglass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }
internal val LocalLiquidBottomTabSelect = staticCompositionLocalOf<(Int) -> Unit> { {} }
internal val LocalLiquidBottomTabEnabled = staticCompositionLocalOf { true }

@Composable
fun RowScope.LiquidBottomTab(
    index: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    val select = LocalLiquidBottomTabSelect.current
    val enabled = LocalLiquidBottomTabEnabled.current
    Column(
        modifier
            .clip(Capsule())
            .semantics { this.selected = selected }
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = { select(index) }
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
