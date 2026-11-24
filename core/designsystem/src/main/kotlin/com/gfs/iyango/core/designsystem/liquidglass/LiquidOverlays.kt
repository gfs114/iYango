package com.gfs.iyango.core.designsystem.liquidglass

import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangularShape

private val DialogDefaultShape = RoundedCornerShape(28.dp)
private val SheetTopShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val DropdownShape = RoundedCornerShape(18.dp)
private val FloatingBarShape: Shape = Capsule()

private fun findDialogWindow(view: View): Window? {
    var current: View? = view
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        val parent: ViewParent? = current.parent
        current = parent as? View
    }
    return null
}

/**
 * 在 Android 12+（API 31+）允许跨窗口模糊时，为独立 Dialog/BottomSheet 窗口启用原生窗口后模糊
 * （统一规范 §4.4：独立窗口使用平台级跨窗口模糊，与窗口内局域玻璃协同而不跨窗错误采样 Activity 图层）。
 * 在 Robolectric 或禁用跨窗口模糊设备上安全静默回退。
 */
fun applyWindowBlur(view: View, blurRadiusPx: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val window = findDialogWindow(view) ?: return
    runCatching {
        if (blurRadiusPx <= 0) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = 0
            }
            return
        }
        val wm = view.context.getSystemService(WindowManager::class.java) ?: return
        if (wm.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = blurRadiusPx
            }
        }
    }
}

/**
 * 独立窗口（Dialog / ModalBottomSheet / DropdownMenu）的局域液态玻璃表面：
 * - 创建窗口自身作用域的 [LocalGlassBackdrop]，绝不越窗错误采样主 Activity 的 [Backdrop]；
 * - 内部子控件（`LiquidButton`、`LiquidToggle`、`LiquidSlider` 等）自动采样当前浮层表面；
 * - 自带浮层玻璃模糊、高光亮边、内外阴影与明暗强可读性蒙层。
 */
@Composable
fun LiquidDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape = DialogDefaultShape,
    isCompact: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tier = rememberGlassTier()
    val isLight = isGlassLightTheme()
    val spec = LocalGlassPalette.current.overlay
    val hasWallpaper = LocalGlassHasWallpaper.current
    val baseOverlayColor = (if (isLight) GlassTokens.tintLight else GlassTokens.tintDark).copy(
        alpha = when (tier) {
            GlassTier.BASIC -> if (isLight) spec.tier0AlphaLight else spec.tier0AlphaDark
            GlassTier.BLUR -> ((if (isLight) spec.tintAlphaLight else spec.tintAlphaDark) * 1.1f).coerceAtMost(0.92f)
            GlassTier.FULL -> if (isLight) spec.tintAlphaLight else spec.tintAlphaDark
        },
    )
    val latestComposite by rememberUpdatedState(baseOverlayColor)
    val windowLocalBackdrop = rememberCanvasBackdrop {
        if (latestComposite.alpha > 0.004f) {
            drawRect(latestComposite)
        }
    }

    val hasEdge = spec.edgeAlpha > 0.004f && spec.edgeWidthDp > 0.01f
    val borderStroke = remember(hasEdge, spec.edgeWidthDp, spec.edgeAlpha, isLight) {
        if (hasEdge) {
            BorderStroke(
                width = spec.edgeWidthDp.dp,
                color = if (isLight) {
                    Color.Black.copy(alpha = (spec.edgeAlpha * 0.22f).coerceAtMost(0.12f))
                } else {
                    Color.White.copy(alpha = spec.edgeAlpha.coerceAtMost(0.28f))
                },
            )
        } else {
            null
        }
    }

    CompositionLocalProvider(
        LocalGlassBackdrop provides windowLocalBackdrop,
        LocalGlassHasWallpaper provides false,
        LocalGlassWallpaperPath provides null,
    ) {
        if (tier == GlassTier.BASIC) {
            val isVisibleSurface = baseOverlayColor.alpha > 0.004f
            Surface(
                modifier = modifier,
                shape = shape,
                color = if (isVisibleSurface) baseOverlayColor else Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = borderStroke,
                tonalElevation = 0.dp,
                shadowElevation = if (isVisibleSurface) (if (isCompact) 8.dp else 12.dp) else 0.dp,
                content = content,
            )
            return@CompositionLocalProvider
        }

        val canRefract = tier == GlassTier.FULL &&
            (shape is RoundedRectangularShape || shape is CornerBasedShape)
        val highlightSpec = remember(hasEdge, spec.edgeWidthDp, spec.edgeBlurDp, spec.edgeAlpha) {
            if (hasEdge) {
                Highlight(
                    width = spec.edgeWidthDp.dp,
                    blurRadius = spec.edgeBlurDp.dp,
                    alpha = spec.edgeAlpha,
                    style = HighlightStyle.Default,
                )
            } else {
                null
            }
        }
        val blurDp = if (isCompact && spec.blurDp > 0.01f) (spec.blurDp * 0.75f).dp else spec.blurDp.dp
        val lensW = if (isCompact) (spec.lensWidthDp * 0.75f).dp else spec.lensWidthDp.dp
        val lensH = if (isCompact) (spec.lensHeightDp * 0.75f).dp else spec.lensHeightDp.dp
        val wallpaperWash = MaterialTheme.colorScheme.primary.copy(alpha = 0.03f)
        val hasShadow = baseOverlayColor.alpha > 0.004f || spec.blurDp > 0.01f || hasEdge

        Box(
            modifier = modifier
                .drawBackdrop(
                    backdrop = windowLocalBackdrop,
                    shape = { shape },
                    effects = {
                        if (blurDp > 0.01.dp) {
                            blur(blurDp.toPx())
                        }
                        if (canRefract && lensW > 0.01.dp && lensH > 0.01.dp) {
                            lens(lensW.toPx(), lensH.toPx())
                        }
                    },
                    highlight = { highlightSpec },
                    shadow = {
                        if (hasShadow) {
                            Shadow(
                                radius = if (isCompact) 12.dp else 20.dp,
                                alpha = if (isLight) 0.14f else 0.32f,
                            )
                        } else {
                            null
                        }
                    },
                    innerShadow = {
                        if (hasEdge) {
                            InnerShadow(
                                radius = 1.5.dp,
                                alpha = (if (isLight) 0.25f else 0.18f) * (spec.edgeAlpha / 0.42f).coerceIn(0f, 1.5f),
                            )
                        } else {
                            null
                        }
                    },
                    onDrawSurface = {
                        if (baseOverlayColor.alpha > 0.004f) {
                            drawRect(baseOverlayColor)
                            if (hasWallpaper) {
                                drawRect(wallpaperWash)
                            }
                        }
                    },
                )
                .clip(shape)
                .then(if (borderStroke != null) Modifier.border(borderStroke, shape) else Modifier),
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                content()
            }
        }
    }
}

/**
 * 液态玻璃警告/确认弹窗（替代 Material 3 AlertDialog）：
 * - 在独立窗口应用跨窗口模糊（Android 12+），面板内采用 [LiquidDialogSurface] 并向下传递窗口局域背景；
 * - 完整保留 title、scrollable text/body、dismiss/confirm 动作布局、无障碍角色与返回键关闭行为。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiquidAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = DialogDefaultShape,
    properties: DialogProperties = DialogProperties(),
) {
    val overlayBlurDp = LocalGlassPalette.current.overlay.blurDp
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        val view = LocalView.current
        val blurPx = with(LocalDensity.current) { overlayBlurDp.dp.toPx().toInt() }
        SideEffect { applyWindowBlur(view, blurPx) }

        LiquidDialogSurface(
            modifier = modifier.widthIn(min = 280.dp, max = 560.dp),
            shape = shape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.secondary,
                        ) {
                            icon()
                        }
                    }
                }
                if (title != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (text != null) 16.dp else 20.dp),
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                            LocalTextStyle provides MaterialTheme.typography.headlineSmall,
                        ) {
                            title()
                        }
                    }
                }
                if (text != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                            LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                        ) {
                            text()
                        }
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * 液态玻璃底部半模态抽屉（替代 Material 3 ModalBottomSheet）：
 * - 将 Material 3 容器设为全透、保留手势与半/全展开状态契约；
 * - 内嵌顶部圆角 [LiquidDialogSurface] 延展至底部安全区，并在 Android 12+ 开启窗口后模糊。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = SheetTopShape,
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    dragHandle: (@Composable () -> Unit)? = { LiquidSheetDragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = BottomSheetDefaults.ScrimColor,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = properties,
    ) {
        val view = LocalView.current
        val overlayBlurDp = LocalGlassPalette.current.overlay.blurDp
        val blurPx = with(LocalDensity.current) { overlayBlurDp.dp.toPx().toInt() }
        SideEffect { applyWindowBlur(view, blurPx) }

        LiquidDialogSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                    ),
            ) {
                if (dragHandle != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        dragHandle()
                    }
                }
                content()
            }
        }
    }
}

@Composable
private fun LiquidSheetDragHandle() {
    val isLight = isGlassLightTheme()
    Box(
        modifier = Modifier
            .padding(vertical = 12.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(Capsule())
            .background(
                if (isLight) {
                    Color.Black.copy(alpha = 0.22f)
                } else {
                    Color.White.copy(alpha = 0.34f)
                },
            ),
    )
}

/**
 * 液态玻璃下拉菜单（替代 Material 3 DropdownMenu）：
 * 在透明 Popup 内挂载紧凑型 [LiquidDialogSurface]，菜单项内嵌 `LocalGlassBackdrop`。
 */
@Composable
fun LiquidDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = DropdownShape,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
    ) {
        LiquidDialogSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            isCompact = true,
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

/**
 * 同窗口悬浮液态玻璃操作栏（用于多选批量栏、暂存调课条等浮动胶囊工具条）：
 * 直接采样当前窗口 [LocalGlassBackdrop]（L0/L1），带触摸拦截与外投影。
 */
@Composable
fun LiquidFloatingBar(
    modifier: Modifier = Modifier,
    shape: Shape = FloatingBarShape,
    tintColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onTap = {})
        },
        shape = shape,
        material = GlassMaterial.OVERLAY,
        allowRefraction = true,
        includeShadow = true,
        tintColor = tintColor,
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            content()
        }
    }
}
