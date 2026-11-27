package com.gfs.iyango.feature.main.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.view.TextureView
import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.domain.WallpaperMediaKind
import java.io.File
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
        if (WallpaperMediaKind.isVideoPath(path)) {
            key(path) {
                VideoWallpaperSurface(
                    path = path,
                    blur = blur,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
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

@Composable
private fun VideoWallpaperSurface(
    path: String,
    blur: Float,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val playerHolder = remember(path) { VideoPlayerHolder() }
    val posterBitmap by rememberWallpaperBitmap(path)
    var firstFrameRendered by remember(path) { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, path) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> playerHolder.resume()
                Lifecycle.Event.ON_PAUSE -> playerHolder.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playerHolder.release()
        }
    }

    val blurredModifier = if (blur > 0f) {
        Modifier.fillMaxSize().blur((blur * MAX_BLUR_RADIUS_DP).dp)
    } else {
        Modifier.fillMaxSize()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!firstFrameRendered) {
            posterBitmap?.let { poster ->
                Image(
                    bitmap = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = blurredModifier,
                )
            }
        }
        AndroidView(
            modifier = blurredModifier.graphicsLayer {
                // Kyant0/AndroidLiquidGlass 视频采样防闪烁稳定层；首帧渲染前保持透明露出缩略图垫底。
                alpha = if (firstFrameRendered) 0.999f else 0f
            },
            factory = { context ->
                TextureView(context).apply {
                    playerHolder.attach(
                        context = context,
                        textureView = this,
                        path = path,
                        onFirstFrameRendered = { firstFrameRendered = true },
                    )
                }
            },
        )
    }
}

private class VideoPlayerHolder {
    private var player: ExoPlayer? = null
    private var attachedTextureView: TextureView? = null
    private var layoutListener: View.OnLayoutChangeListener? = null
    private var displayVideoWidth: Float = 0f
    private var displayVideoHeight: Float = 0f

    fun attach(
        context: Context,
        textureView: TextureView,
        path: String,
        onFirstFrameRendered: () -> Unit,
    ) {
        release()
        attachedTextureView = textureView
        val onLayout = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (w != oldW || h != oldH) {
                updateViewport(textureView, w, h)
            }
        }
        layoutListener = onLayout
        textureView.addOnLayoutChangeListener(onLayout)

        runCatching {
            val exo = ExoPlayer.Builder(context)
                .setAudioAttributes(AudioAttributes.DEFAULT, false)
                .build()
            player = exo
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.volume = 0f
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            exo.setVideoTextureView(textureView)
            exo.addListener(
                object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        val ratio = if (videoSize.pixelWidthHeightRatio > 0f) {
                            videoSize.pixelWidthHeightRatio
                        } else {
                            1f
                        }
                        displayVideoWidth = videoSize.width * ratio
                        displayVideoHeight = videoSize.height.toFloat()
                        updateViewport(textureView, textureView.width, textureView.height)
                    }

                    override fun onRenderedFirstFrame() {
                        onFirstFrameRendered()
                    }
                },
            )
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            exo.playWhenReady = true
            exo.prepare()
        }.onFailure {
            release()
        }
    }

    fun updateViewport(textureView: TextureView, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || displayVideoWidth <= 0f || displayVideoHeight <= 0f) return
        val matrix = Matrix()
        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val videoRatio = displayVideoWidth / displayVideoHeight
        val scaleX: Float
        val scaleY: Float
        if (videoRatio > viewRatio) {
            scaleX = videoRatio / viewRatio
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewRatio / videoRatio
        }
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        textureView.setTransform(matrix)
    }

    fun pause() {
        runCatching { player?.pause() }
    }

    fun resume() {
        runCatching {
            player?.let { if (it.playbackState != Player.STATE_IDLE) it.play() }
        }
    }

    fun release() {
        val view = attachedTextureView
        val listener = layoutListener
        if (view != null && listener != null) {
            view.removeOnLayoutChangeListener(listener)
        }
        layoutListener = null
        player?.let { exo ->
            if (view != null) {
                runCatching { exo.clearVideoTextureView(view) }
            }
            runCatching { exo.release() }
        }
        player = null
        attachedTextureView = null
        displayVideoWidth = 0f
        displayVideoHeight = 0f
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
    if (WallpaperMediaKind.isVideoPath(path)) {
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(0L)
                ?: retriever.getFrameAtTime(-1L)
                ?: retriever.getFrameAtTime(1_000_000L)
        } finally {
            runCatching { retriever.release() }
        } ?: return@runCatching null
        val longest = maxOf(frame.width, frame.height)
        val finalBitmap = if (longest > maxDimension && maxDimension > 0) {
            val scale = maxDimension.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            frame
        }
        return@runCatching finalBitmap.asImageBitmap()
    }
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
