package com.gfs.iyango.core.designsystem.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 当前作用域可供玻璃采样的 L0 背景（主题基底 + 壁纸）。
 *
 * 无宿主（Preview、独立窗口、未接线的页面）时为 null，
 * 玻璃组件按主题表面回退，不得因此崩溃。
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 当前作用域的 L0 背景是否包含有效壁纸（false = 仅纯色主题基底）。
 *
 * 无壁纸时，内容玻璃（[GlassMaterial.CONTENT]）对纯色基底做高斯模糊与透镜折射在数学上
 * 等价于直接叠加 tint 蒙层，可安全走轻量 [androidx.compose.material3.Surface] 分支，
 * 避免列表逐项离屏渲染开销。
 */
val LocalGlassHasWallpaper = staticCompositionLocalOf { false }

/**
 * 当前作用域的 L0 背景壁纸路径（null = 无壁纸）。
 *
 * 供子页面（如课表页）判断自身壁纸是否与根宿主壁纸完全相同：相同时直接复用根宿主 L0，
 * 避免重复创建全屏 [GlassBackdropHost] 与重复解码/模糊同一张壁纸（P3 优化）。
 */
val LocalGlassWallpaperPath = staticCompositionLocalOf<String?> { null }

/**
 * L0 背景宿主：创建页面/窗口作用域的玻璃背景来源，经 [LocalGlassBackdrop] 下发。
 *
 * [background] 槽（壁纸层等基底内容）被记录进 L0，绘制在 [content] 之下；
 * content 中的玻璃表面只采样 L0，而它自身不录入 L0，从结构上杜绝
 * “子内容采样包含自己的记录”的自采样闭环（统一规范 §4.1）。
 *
 * L0 绘制前先铺主题基底：无壁纸、壁纸未加载或解码失败时玻璃不出现透明洞（§4.2）。
 */
@Composable
fun GlassBackdropHost(
    modifier: Modifier = Modifier,
    hasWallpaper: Boolean = false,
    wallpaperPath: String? = null,
    background: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    // 绘制 lambda 非 Composable 上下文：经 State 读取主题基底，明暗切换时自动跟随。
    val currentBackground by rememberUpdatedState(MaterialTheme.colorScheme.background)
    val backdrop = rememberLayerBackdrop {
        drawRect(currentBackground)
        // layerBackdrop's recording callback replaces its normal content recording.
        // Include only this background slot; the sibling foreground stays outside L0.
        drawContent()
    }
    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        LocalGlassHasWallpaper provides (hasWallpaper || wallpaperPath != null),
        LocalGlassWallpaperPath provides wallpaperPath,
    ) {
        Box(modifier = modifier.background(currentBackground)) {
            Box(modifier = Modifier.matchParentSize().layerBackdrop(backdrop)) {
                background()
            }
            content()
        }
    }
}

/**
 * 为交互控件提供可用的玻璃采样背景：优先使用当前作用域 [LocalGlassBackdrop]，
 * 若无宿主（例如 Preview、独立弹窗或无壁纸兜底场景）则回退为纯色 [rememberCanvasBackdrop]，
 * 保证控件的折射与高光结构完整而不空指针或穿透透明洞。
 */
@Composable
fun rememberGlassFallbackBackdrop(
    fallbackColor: Color = MaterialTheme.colorScheme.surface,
): Backdrop {
    val scoped = LocalGlassBackdrop.current
    if (scoped != null) return scoped
    val currentColor by rememberUpdatedState(fallbackColor)
    return rememberCanvasBackdrop { drawRect(currentColor) }
}
