// 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0）：
// app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt (kmp 分支)
// 本项目适配：主题与蒙层配置、单向选中同步、点击即时反馈，以及共同祖先上的长按拖拽。
// 保留基础玻璃条 / 隐藏强调色内容层 / 可拖拽指示器三层材质结构。
package com.gfs.iyango.feature.main.liquidglass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.DockGlassSettings
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign

/** [自定义长按] 底栏"长按跳转"的判定阈值（毫秒），不再用系统长按超时。 */
private const val LONG_PRESS_JUMP_MS = 0L

/**
 * Dock 几何常量。
 *
 * 参考 com.coolapk.market 16.6.5 的 `liquid_bottom_navigation`：
 * 容器 182px@density3.25 = 56dp（资源 `liquid_bottom_navigation_height`），
 * 内容行 156px = 48dp，content padding 4dp（资源 `liquid_bottom_navigation_content_padding`）。
 */
internal object DockGeometry {
    /** 底栏总高 dp（原 64 → 56）。 */
    const val BAR_HEIGHT_DP = 56f

    /** 内容行高 dp（原 56 → 48 = 56 - 4*2）。 */
    const val CONTENT_HEIGHT_DP = 48f
}

/**
 * Dock 玻璃材质参数（对齐 Kyant0 标准 Apple 液态玻璃 + 暗色黑玻璃基线）。
 */
internal object DockGlass {
    /** 基础条与隐藏层背景模糊半径 dp（8dp 虚化，与 Kyant0 LiquidBottomTabs 对齐）。 */
    const val BLUR_DP = 8f

    /** 基础条透镜宽 dp。 */
    const val LENS_WIDTH_DP = 24f

    /** 基础条透镜高 dp。 */
    const val LENS_HEIGHT_DP = 24f

    /** 细亮边宽度 dp。 */
    const val EDGE_WIDTH_DP = 0.5f

    /** 细亮边模糊半径 dp（0.25dp 锐利反光，对齐 Highlight.Default）。 */
    const val EDGE_BLUR_DP = 0.25f

    /** 基础条细亮边不透明度。 */
    const val EDGE_ALPHA_BASE = 0.85f

    /** 隐藏层细亮边峰值（按压时 progress * 0.90）。 */
    const val EDGE_ALPHA_HIDDEN = 0.90f

    /** 指示器细亮边峰值（按压时 progress * 1.0）。 */
    const val EDGE_ALPHA_INDICATOR = 1f

    /** 指示器常态底色不透明度。 */
    const val INDICATOR_SURFACE_ALPHA = 0.10f

    /** 指示器旧系统 tier 0 底色（无折射时保证可见）。 */
    const val INDICATOR_SURFACE_ALPHA_LEGACY = 0.12f

    /** 指示器按压加深层（乘 progress）。 */
    const val INDICATOR_SURFACE_PRESSED_ALPHA = 0.03f

    /** 指示器投影半径 dp。 */
    const val INDICATOR_SHADOW_RADIUS_DP = 16f

    /** 指示器投影峰值（按压时 progress * 0.35）。 */
    const val INDICATOR_SHADOW_ALPHA = 0.35f

    /** 指示器内阴影峰值半径 dp（乘 progress）。 */
    const val INDICATOR_INNER_RADIUS_DP = 8f

    /** 指示器内阴影峰值（progress * 0.55）。 */
    const val INDICATOR_INNER_ALPHA = 0.55f

    /** 蒙层默认值——亮（标准 Apple 液态玻璃 0.40）。 */
    const val SURFACE_ALPHA_LIGHT_DEFAULT = 0.40f

    /** 蒙层默认值——暗（标准 Apple 液态玻璃 0.36）。 */
    const val SURFACE_ALPHA_DARK_DEFAULT = 0.36f

    /** tier 1（Android 12：有模糊无透镜）蒙层增益。 */
    const val LEGACY_TIER1_ALPHA_GAIN = 1.4f

    /** tier 1 蒙层增益上限。 */
    const val LEGACY_TIER1_ALPHA_MAX = 0.8f

    /** tier 0（Android 8-11：无模糊/折射）半透明实体底色——亮。 */
    const val LEGACY_TIER0_ALPHA_LIGHT = 0.55f

    /** tier 0 半透明实体底色——暗。 */
    const val LEGACY_TIER0_ALPHA_DARK = 0.35f
}

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    /** 可读性蒙层不透明度（亮色模式），0..0.8；0 = 无遮罩（纯折射）。默认 0.40。 */
    surfaceAlphaLight: Float = DockGlass.SURFACE_ALPHA_LIGHT_DEFAULT,
    /** 可读性蒙层不透明度（暗色模式），0..0.8；0 = 无遮罩。默认 0.36。 */
    surfaceAlphaDark: Float = DockGlass.SURFACE_ALPHA_DARK_DEFAULT,
    /** 液态玻璃高级视觉参数（模糊、透镜、细亮边与指示器阴影等）。 */
    glassSettings: DockGlassSettings = DockGlassSettings(),
    content: @Composable RowScope.() -> Unit
) {
    val glass = remember(glassSettings) { glassSettings.sanitized() }
    // [本项目适配] 明暗与强调色来自 IYangoTheme（LocalBrand），
    // 官方为硬编码 #0088FF / isSystemInDarkTheme。
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    val accentColor = LocalBrand.current.accent
    // [本项目适配] 蒙层不透明度按明暗取参（用户可调，经 ThemeSettings/DataStore 下发）；
    // 0 时跳过蒙层绘制（纯折射玻璃）。
    // [旧系统降级] 按库的平台能力探测分层（不读 Build.VERSION）：
    // tier 0（Android 8-11，无 RenderEffect）：模糊/折射全部不可用，改半透明实体底色，
    //   刻意忽略用户蒙层配置，避免旧机底栏不可见；
    // tier 1（Android 12，有模糊无 RuntimeShader 透镜）：蒙层按 1.4 倍增益；
    // tier 2（Android 13+）：完整玻璃，按用户配置。
    val glassTier = when {
        !isRenderEffectSupported() -> 0
        !isRuntimeShaderSupported() -> 1
        else -> 2
    }
    val configuredAlpha = if (isLightTheme) surfaceAlphaLight else surfaceAlphaDark
    val containerAlpha = when (glassTier) {
        0 -> if (isLightTheme) {
            glass.legacyTier0AlphaLight
        } else {
            glass.legacyTier0AlphaDark
        }
        1 -> (configuredAlpha * DockGlass.LEGACY_TIER1_ALPHA_GAIN)
            .coerceAtMost(DockGlass.LEGACY_TIER1_ALPHA_MAX)
        else -> configuredAlpha
    }
    // [酷安对齐] 暗色玻璃必须是“比背景更暗”的黑玻璃，而不是比背景更亮的 #121212 灰板。
    val containerColor =
        if (isLightTheme) Color(0xFFFDFDFD).copy(containerAlpha)
        else Color(0xFF141416).copy(containerAlpha)
    /** 蒙层是否完全关闭（0）。 */
    val surfaceDisabled = containerAlpha <= 0.004f

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val motionEnabled = LocalMotionEnabled.current
        val currentMotionEnabled by rememberUpdatedState(motionEnabled)
        var directDragging by remember { mutableStateOf(false) }
        val offsetAnimation = remember { Animatable(0f) }
        val animationScope = rememberCoroutineScope()
        val releasePanelOffset = remember(animationScope, offsetAnimation) {
            {
                animationScope.launch {
                    if (currentMotionEnabled) offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    else offsetAnimation.snapTo(0f)
                }
            }
        }
        LaunchedEffect(motionEnabled) {
            if (!motionEnabled && !directDragging) offsetAnimation.snapTo(0f)
        }
        val panelOffset by remember(density, constraints.maxWidth) {
            derivedStateOf {
                val offset = if (currentMotionEnabled || directDragging) offsetAnimation.value else 0f
                val fraction = (offset / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val currentSelectedIndex by rememberUpdatedState(selectedTabIndex)
        val currentOnTabSelected by rememberUpdatedState(onTabSelected)
        var longPressDragging by remember { mutableStateOf(false) }
        var directPressIndex by remember { mutableIntStateOf(selectedTabIndex()) }
        val dampedDragAnimation = remember(animationScope, tabWidth, tabsCount, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = { position ->
                    directDragging = true
                    // A moving indicator can cover a different tab than its target.
                    // A tap belongs to the finger's actual location, not that old target.
                    val fraction = if (isLtr) position.x / tabWidth else 1f - position.x / tabWidth
                    directPressIndex = (value + fraction).toInt().fastCoerceIn(0, tabsCount - 1)
                },
                onDragStopped = { wasDragged ->
                    directDragging = false
                    val targetIndex = if (wasDragged) {
                        targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    } else directPressIndex
                    if (!longPressDragging) {
                        animateToValue(targetIndex.toFloat())
                        if (targetIndex != currentSelectedIndex()) currentOnTabSelected(targetIndex)
                    }
                    releasePanelOffset()
                },
                onDragCancelled = {
                    directDragging = false
                    if (!longPressDragging) animateToValue(currentSelectedIndex().toFloat())
                    releasePanelOffset()
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { Triple(currentSelectedIndex(), directDragging, longPressDragging) }
                .collectLatest { (index, dragging, longPress) ->
                    // External synchronization never emits a navigation event.
                    if (!dragging && !longPress && dampedDragAnimation.targetValue != index.toFloat()) {
                        dampedDragAnimation.animateToValue(index.toFloat())
                    }
                }
        }
        val selectTab: (Int) -> Unit = { index ->
            // Start feedback in the input callback, before composing the destination.
            dampedDragAnimation.animateToValue(index.toFloat())
            if (index != currentSelectedIndex()) currentOnTabSelected(index)
        }

        val interactiveHighlight = remember(animationScope, dampedDragAnimation, tabWidth, isLtr) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }
        SideEffect {
            dampedDragAnimation.updateMotionPolicy(motionEnabled)
            interactiveHighlight.updateMotionPolicy(motionEnabled)
        }

        // [长按跳转] 长按底栏任意位置：液态指示器先滑到手指所在位置，按住可继续拖动，
        // 松手吸附到最近的 tab 并切换页面（不影响正常点击/直接拖动指示器）。
        // 长按判定用自定义 withTimeoutOrNull（阈值 LONG_PRESS_JUMP_MS），不用系统长按超时。
        val longPressDragModifier = Modifier.pointerInput(tabWidth, tabsCount, isLtr) {
            val contentPadding = 4.dp.toPx()
            fun indexAt(x: Float): Float =
                (((if (isLtr) x else size.width - x) - contentPadding - tabWidth * 0.5f) / tabWidth)
                    .fastCoerceIn(0f, (tabsCount - 1).toFloat())

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)

                // 阈值内松手、或移动超过 touchSlop → 都不算长按，交还给点击/拖拽逻辑。
                val endedBeforeThreshold = withTimeoutOrNull(LONG_PRESS_JUMP_MS) {
                    var change: PointerInputChange? = down
                    while (change != null && change.pressed) {
                        change = awaitPointerEvent()
                            .changes
                            .firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            return@withTimeoutOrNull true
                        }
                        if ((change.position - down.position).getDistance() >
                            viewConfiguration.touchSlop
                        ) {
                            return@withTimeoutOrNull true
                        }
                    }
                    true
                }
                if (endedBeforeThreshold != null) return@awaitEachGesture
                // The indicator already owns a direct press/drag. Do not create a
                // second owner that would cancel and release it on the next MOVE.
                if (directDragging) return@awaitEachGesture

                val pressed = currentEvent.changes.firstOrNull { it.id == down.id }
                    ?: return@awaitEachGesture
                pressed.consume()

                longPressDragging = true
                dampedDragAnimation.press()
                dampedDragAnimation.updateValue(indexAt(pressed.position.x))

                var released = false
                try {
                    while (true) {
                        // Consume in Initial so the clickable copy above the base row sees
                        // cancellation before it can handle UP as an ordinary click.
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            ?: break
                        if (change.isConsumed) break
                        change.consume()
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        dampedDragAnimation.updateValue(indexAt(change.position.x))
                    }
                } finally {
                    if (released) {
                        selectTab(dampedDragAnimation.targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1))
                    } else {
                        dampedDragAnimation.animateToValue(currentSelectedIndex().toFloat())
                    }
                    longPressDragging = false
                }
            }
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabSelect provides selectTab,
            LocalLiquidBottomTabEnabled provides interactive,
        ) {
            Box((if (interactive) longPressDragModifier else Modifier).testTag("liquid-bottom-tabs"), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier
                        .graphicsLayer {
                            translationX = panelOffset
                        }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                applyDockEffects(
                                    blurDp = glass.blurDp,
                                    lensWidthDp = glass.lensWidthDp,
                                    lensHeightDp = glass.lensHeightDp,
                                )
                            },
                            highlight = {
                                // [酷安对齐] 方向性细亮边：只留顶侧一条，不再是整圈描边。
                                Highlight(
                                    width = glass.edgeWidthDp.dp,
                                    blurRadius = glass.edgeBlurDp.dp,
                                    alpha = glass.edgeAlphaBase,
                                    style = HighlightStyle.Default
                                )
                            },
                            layerBlock = {
                                val progress = dampedDragAnimation.pressProgress
                                val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                                scaleX = scale
                                scaleY = scale
                            },
                            onDrawSurface = {
                                if (!surfaceDisabled) drawRect(containerColor)
                            },
                        )
                        .then(interactiveHighlight.modifier)
                        .height(DockGeometry.BAR_HEIGHT_DP.dp)
                        .fillMaxWidth()
                        .padding(4f.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val restingAlpha = if (glassTier == 0) {
                        DockGlass.INDICATOR_SURFACE_ALPHA_LEGACY
                    } else {
                        glass.indicatorSurfaceAlpha
                    }
                    val restingPillColor = if (isLightTheme) {
                        Color.Black.copy(restingAlpha)
                    } else {
                        Color.White.copy(restingAlpha)
                    }
                    Box(
                        Modifier
                            .graphicsLayer {
                                translationX =
                                    if (isLtr) dampedDragAnimation.value * tabWidth
                                    else size.width - (dampedDragAnimation.value + 1f) * tabWidth
                                alpha = (1f - dampedDragAnimation.pressProgress).coerceIn(0f, 1f)
                            }
                            .height(DockGeometry.CONTENT_HEIGHT_DP.dp)
                            .fillMaxWidth(1f / tabsCount)
                            .background(restingPillColor, Capsule())
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(DockGeometry.CONTENT_HEIGHT_DP.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = content,
                    )
                }

                CompositionLocalProvider(
                    LocalLiquidBottomTabScale provides {
                        lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                    }
                ) {
                    Row(
                        Modifier
                            .clearAndSetSemantics {}
                            .alpha(0f)
                            .layerBackdrop(tabsBackdrop)
                            .graphicsLayer {
                                translationX = panelOffset
                            }
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { Capsule() },
                                effects = {
                                    val progress = dampedDragAnimation.pressProgress
                                    applyDockEffects(
                                        blurDp = glass.blurDp,
                                        lensWidthDp = if (progress > 0.001f) glass.lensWidthDp * progress else 0f,
                                        lensHeightDp = if (progress > 0.001f) glass.lensHeightDp * progress else 0f,
                                    )
                                },
                                highlight = {
                                    val progress = dampedDragAnimation.pressProgress
                                    if (progress > 0.001f) {
                                        Highlight(
                                            width = glass.edgeWidthDp.dp,
                                            blurRadius = glass.edgeBlurDp.dp,
                                            alpha = glass.edgeAlphaHidden * progress,
                                            style = HighlightStyle.Default
                                        )
                                    } else {
                                        null
                                    }
                                },
                                shadow = null,
                                onDrawSurface = {
                                    if (!surfaceDisabled) drawRect(containerColor)
                                },
                            )
                            .then(interactiveHighlight.modifier)
                            .height(DockGeometry.CONTENT_HEIGHT_DP.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 4f.dp)
                            .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                        verticalAlignment = Alignment.CenterVertically,
                        content = content
                    )
                }

                Box(
                    Modifier
                        .padding(horizontal = 4f.dp)
                        .graphicsLayer {
                            translationX =
                                if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                                else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                        }
                        .then(if (interactive) interactiveHighlight.gestureModifier else Modifier)
                        .then(if (interactive) dampedDragAnimation.modifier else Modifier)
                        .drawBackdrop(
                            backdrop = tabsBackdrop,
                            shape = { Capsule() },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                if (progress > 0.001f) {
                                    lens(
                                        glass.indicatorLensWidthDp.dp.toPx() * progress,
                                        glass.indicatorLensHeightDp.dp.toPx() * progress,
                                        chromaticAberration = glass.indicatorChromaticAberration
                                    )
                                }
                            },
                            highlight = {
                                val progress = dampedDragAnimation.pressProgress
                                if (progress > 0.001f) {
                                    Highlight(
                                        width = glass.edgeWidthDp.dp,
                                        blurRadius = glass.edgeBlurDp.dp,
                                        alpha = glass.edgeAlphaIndicator * progress,
                                        style = HighlightStyle.Default
                                    )
                                } else {
                                    null
                                }
                            },
                            shadow = {
                                val progress = dampedDragAnimation.pressProgress
                                if (progress > 0.001f) {
                                    Shadow(
                                        radius = glass.indicatorShadowRadiusDp.dp,
                                        alpha = glass.indicatorShadowAlpha * progress
                                    )
                                } else {
                                    null
                                }
                            },
                            innerShadow = {
                                val progress = dampedDragAnimation.pressProgress
                                if (progress > 0.001f) {
                                    InnerShadow(
                                        radius = glass.indicatorInnerRadiusDp.dp * progress,
                                        alpha = glass.indicatorInnerAlpha * progress
                                    )
                                } else {
                                    null
                                }
                            },
                            layerBlock = {
                                alpha = dampedDragAnimation.pressProgress.coerceIn(0f, 1f)
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                if (progress > 0.001f) {
                                    drawRect(
                                        Color.Black.copy(
                                            alpha = glass.indicatorSurfacePressedAlpha * progress
                                        )
                                    )
                                }
                            }
                        )
                        .height(DockGeometry.CONTENT_HEIGHT_DP.dp)
                        .fillMaxWidth(1f / tabsCount)
                        .testTag("dock-indicator")
                )
            }
        }
    }
}

private fun BackdropEffectScope.applyDockEffects(
    blurDp: Float,
    lensWidthDp: Float,
    lensHeightDp: Float,
) {
    val hasBlur = blurDp > 0.01f
    val hasLens = lensWidthDp > 0.01f && lensHeightDp > 0.01f
    if (!hasBlur && !hasLens) return

    vibrancy()
    if (hasBlur) {
        blur(blurDp.dp.toPx())
    }
    if (hasLens) {
        val rawHeight = lensWidthDp.dp.toPx()
        val rawAmount = lensHeightDp.dp.toPx()
        val maxLensHeight = (size.minDimension * 0.25f).coerceAtLeast(0f)
        val scale = if (rawHeight > maxLensHeight && rawHeight > 0f) {
            maxLensHeight / rawHeight
        } else {
            1f
        }
        lens(rawHeight * scale, rawAmount * scale)
    }
}
