package com.gfs.iyango.feature.main.importexport

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.domain.QrMatrix

/** 二维码静区模块数（ISO 规范要求至少 4 格）。 */
internal const val QR_QUIET_ZONE_MODULES = 4

/**
 * 离屏绘制高清二维码位图，强制使用纯白不透明底色与纯黑前景模块。
 */
internal fun renderQrBitmap(
    matrix: QrMatrix,
    targetSizePx: Int = 1080,
    quietZoneModules: Int = QR_QUIET_ZONE_MODULES,
): Bitmap {
    val totalModules = matrix.size + quietZoneModules * 2
    val modulePx = (targetSizePx / totalModules).coerceAtLeast(2)
    val bitmapSize = totalModules * modulePx
    val pixels = IntArray(bitmapSize * bitmapSize) { AndroidColor.WHITE }
    for (y in 0 until matrix.size) {
        val pyStart = (y + quietZoneModules) * modulePx
        for (x in 0 until matrix.size) {
            if (!matrix.isDark(x, y)) continue
            val pxStart = (x + quietZoneModules) * modulePx
            for (dy in 0 until modulePx) {
                val rowOffset = (pyStart + dy) * bitmapSize + pxStart
                for (dx in 0 until modulePx) {
                    pixels[rowOffset + dx] = AndroidColor.BLACK
                }
            }
        }
    }
    return Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, bitmapSize, 0, 0, bitmapSize, bitmapSize)
    }
}

/**
 * 屏幕展示用二维码：独立强制白底黑码，不跟随深色主题反色，也不叠加透明玻璃材质。
 */
@Composable
internal fun QrCodeCanvas(
    matrix: QrMatrix,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
    ) {
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            val totalModules = matrix.size + QR_QUIET_ZONE_MODULES * 2
            val moduleSize = minOf(size.width, size.height) / totalModules.toFloat()
            val offsetX = (size.width - moduleSize * totalModules) / 2f
            val offsetY = (size.height - moduleSize * totalModules) / 2f
            drawRect(color = Color.White)
            for (y in 0 until matrix.size) {
                val top = offsetY + (y + QR_QUIET_ZONE_MODULES) * moduleSize
                for (x in 0 until matrix.size) {
                    if (matrix.isDark(x, y)) {
                        val left = offsetX + (x + QR_QUIET_ZONE_MODULES) * moduleSize
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(left, top),
                            size = Size(moduleSize + 0.5f, moduleSize + 0.5f),
                        )
                    }
                }
            }
        }
    }
}
