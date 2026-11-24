package com.gfs.iyango.feature.main.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 校园 AI 助理机器人图标（24x24 矢量，适配浅/深色主题着色）。
 */
internal val AiRobotIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AiRobot",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            // 天线圆球
            moveTo(10.5f, 3.6f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13.5f, 3.6f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.5f, 3.6f)
            close()

            // 天线连接柱
            moveTo(11.2f, 4.8f)
            lineTo(12.8f, 4.8f)
            lineTo(12.8f, 7.2f)
            lineTo(11.2f, 7.2f)
            close()

            // 头部外轮廓
            moveTo(8f, 7f)
            lineTo(16f, 7f)
            quadTo(19f, 7f, 19f, 10f)
            lineTo(19f, 16.5f)
            quadTo(19f, 19.5f, 16f, 19.5f)
            lineTo(8f, 19.5f)
            quadTo(5f, 19.5f, 5f, 16.5f)
            lineTo(5f, 10f)
            quadTo(5f, 7f, 8f, 7f)
            close()

            // 左耳
            moveTo(5f, 11.2f)
            lineTo(3.5f, 11.2f)
            quadTo(2.5f, 11.2f, 2.5f, 12.2f)
            lineTo(2.5f, 14.3f)
            quadTo(2.5f, 15.3f, 3.5f, 15.3f)
            lineTo(5f, 15.3f)
            close()

            // 右耳
            moveTo(19f, 11.2f)
            lineTo(20.5f, 11.2f)
            quadTo(21.5f, 11.2f, 21.5f, 12.2f)
            lineTo(21.5f, 14.3f)
            quadTo(21.5f, 15.3f, 20.5f, 15.3f)
            lineTo(19f, 15.3f)
            close()

            // 左眼镂空
            moveTo(7.8f, 12f)
            arcTo(1.4f, 1.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.6f, 12f)
            arcTo(1.4f, 1.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7.8f, 12f)
            close()

            // 右眼镂空
            moveTo(13.4f, 12f)
            arcTo(1.4f, 1.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.2f, 12f)
            arcTo(1.4f, 1.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13.4f, 12f)
            close()

            // 嘴部镂空
            moveTo(9.8f, 15.4f)
            lineTo(14.2f, 15.4f)
            arcTo(0.8f, 0.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 14.2f, 17f)
            lineTo(9.8f, 17f)
            arcTo(0.8f, 0.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9.8f, 15.4f)
            close()
        }
    }.build()
}
