package com.gfs.iyango.feature.main.settings

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 根级 / 课表级壁纸绘制层（F10）。
 *
 * 只持有已复制到私有目录的绝对路径；解码失败时静默回退到主题背景色，
 * 不阻塞页面也不影响旧设置。支持壁纸切换时的平滑淡入淡出过渡。
 *
 * @param path 私有目录图片的绝对路径；null 表示不使用壁纸。
 * @param scrim 可读性遮罩不透明度 0..0.8（与主题背景色混合）。
 * @param blur 模糊强度 0..1；0 关闭。映射为高斯半径，Android 12 以下自动忽略。
 */
@Composable
fun WallpaperLayer(
    path: String?,
    scrim: Float,
    blur: Float = 0f,
    modifier: Modifier = Modifier,
) {
    if (path == null) return
    val motionEnabled = LocalMotionEnabled.current
    Box(modifier = modifier.fillMaxSize()) {
        Crossfade(
            targetState = path,
            animationSpec = tween(durationMillis = if (motionEnabled) 450 else 0),
            label = "wallpaperCrossfade",
            modifier = Modifier.fillMaxSize(),
        ) { targetPath ->
            val bitmap by rememberWallpaperBitmap(targetPath)
            bitmap?.let {
                val imageModifier = if (blur > 0f) {
                    Modifier.fillMaxSize().blur((blur * MAX_BLUR_RADIUS_DP).dp)
                } else {
                    Modifier.fillMaxSize()
                }
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = imageModifier,
                )
            }
        }
        if (scrim > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = scrim.coerceIn(0f, 0.8f))),
            )
        }
    }
}

/** 模糊强度 1.0 对应的高斯半径（dp 数值，使用处再转 Dp）。 */
private const val MAX_BLUR_RADIUS_DP = 24f

private object WallpaperBitmapCache {
    private val cache = LruCache<String, ImageBitmap>(8)

    private fun keyOf(path: String, maxDimension: Int): String = "$path#$maxDimension"

    fun get(path: String?, maxDimension: Int): ImageBitmap? {
        if (path.isNullOrBlank()) return null
        return synchronized(cache) { cache.get(keyOf(path, maxDimension)) }
    }

    fun put(path: String, maxDimension: Int, bitmap: ImageBitmap) {
        synchronized(cache) { cache.put(keyOf(path, maxDimension), bitmap) }
    }
}

private fun decodeSampledWallpaper(path: String, maxDimension: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    var longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest > 0 && longest / 2 >= maxDimension) {
        sample *= 2
        longest /= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}.getOrNull()

/**
 * 按目标长边采样解码图片，避免把整张原图读进内存；命中进程内 [WallpaperBitmapCache] 时
 * 同步返回已解码位图，避免二级页面首帧等待 IO 露白。
 *
 * @param maxDimension 解码后允许的最长边（px）。
 */
@Composable
fun rememberWallpaperBitmap(
    path: String?,
    maxDimension: Int = 1600,
): State<ImageBitmap?> {
    val cached = remember(path, maxDimension) { WallpaperBitmapCache.get(path, maxDimension) }
    return produceState<ImageBitmap?>(initialValue = cached, path, maxDimension) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            WallpaperBitmapCache.get(path, maxDimension) ?: withContext(Dispatchers.IO) {
                decodeSampledWallpaper(path, maxDimension)
            }?.also { decoded ->
                WallpaperBitmapCache.put(path, maxDimension, decoded)
            }
        }
    }
}
