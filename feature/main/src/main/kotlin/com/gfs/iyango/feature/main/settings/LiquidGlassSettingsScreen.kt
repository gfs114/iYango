@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterialSpec
import com.gfs.iyango.core.designsystem.liquidglass.GlassPalette
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidCheckbox
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.liquidglass.LiquidSlider
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassBackdrop
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassHasWallpaper
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassPalette
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.DockGlassSettings
import com.gfs.iyango.core.domain.GlassLayerSettings
import com.gfs.iyango.core.domain.GlobalGlassSettings
import com.gfs.iyango.core.domain.LiquidGlassPreset
import com.gfs.iyango.core.domain.LiquidGlassShareCodec
import com.gfs.iyango.core.domain.ThemeSettings
import kotlinx.coroutines.launch
import com.gfs.iyango.feature.main.DockTabContent
import com.gfs.iyango.feature.main.liquidglass.LiquidBottomTab
import com.gfs.iyango.feature.main.liquidglass.LiquidBottomTabs
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.roundToInt

/** 将领域层的四类全局液态玻璃配置转换为设计系统材质面板。 */
fun GlobalGlassSettings.toGlassPalette(): GlassPalette {
    val safe = sanitized()
    return GlassPalette(
        navigation = safe.navigation.toMaterialSpec(),
        content = safe.content.toMaterialSpec(),
        control = safe.control.toMaterialSpec(),
        overlay = safe.overlay.toMaterialSpec(),
    )
}

private fun GlassLayerSettings.toMaterialSpec(): GlassMaterialSpec = GlassMaterialSpec(
    blurDp = blurDp,
    lensWidthDp = lensWidthDp,
    lensHeightDp = lensHeightDp,
    edgeWidthDp = edgeWidthDp,
    edgeBlurDp = edgeBlurDp,
    edgeAlpha = edgeAlpha,
    tintAlphaLight = tintAlphaLight,
    tintAlphaDark = tintAlphaDark,
    tier0AlphaLight = tier0AlphaLight,
    tier0AlphaDark = tier0AlphaDark,
    depthEffect = depthEffect,
    chromaticAberration = chromaticAberration,
)

/** 液态玻璃分类调节页的五个材质分类。 */
enum class LiquidGlassCategory(val title: String, val subtitle: String) {
    NAVIGATION("导航栏", "顶栏、侧栏、浮动栏"),
    CONTENT("卡片内容", "课程卡、统计卡、列表卡等"),
    CONTROL("交互控件", "按钮、选择胶囊、开关、滑块、输入框"),
    OVERLAY("弹窗浮层", "Dialog、Sheet、菜单、提示条"),
    DOCK("底部栏", "底部导航栏玻璃与蒙层"),
}

private data class LayerParamSpec(
    val key: String,
    val title: String,
    val unit: String,
    val range: ClosedFloatingPointRange<Float>,
    val description: String,
    val defaultProvider: (GlobalGlassSettings) -> Float = { 0f },
    val getLayer: (GlassLayerSettings) -> Float,
    val setLayer: (GlassLayerSettings, Float) -> GlassLayerSettings,
)

private data class DockLayerParamSpec(
    val key: String,
    val title: String,
    val unit: String,
    val range: ClosedFloatingPointRange<Float>,
    val description: String,
    val get: (ThemeSettings) -> Float,
    val set: (ThemeSettings, Float) -> ThemeSettings,
)

private data class DockParamGroup(
    val title: String,
    val includeChromaticSwitch: Boolean = false,
    val params: List<DockLayerParamSpec>,
)

private val DefaultGlobal = GlobalGlassSettings()
private val DefaultTheme = ThemeSettings()

private val LayerParamSpecs = listOf(
    LayerParamSpec(
        key = "blurDp",
        title = "背景模糊半径",
        unit = "dp",
        range = 0f..32f,
        description = "0 = 去掉背景模糊（背景完全清晰）；调大后下层内容更柔和虚化。",
        getLayer = { it.blurDp },
        setLayer = { s, v -> s.copy(blurDp = v) },
    ),
    LayerParamSpec(
        key = "lensWidthDp",
        title = "透镜折射宽",
        unit = "dp",
        range = 0f..40f,
        description = "0 = 去掉横向折射扭曲；调大后边缘弧形“厚玻璃”折射带更明显。",
        getLayer = { it.lensWidthDp },
        setLayer = { s, v -> s.copy(lensWidthDp = v) },
    ),
    LayerParamSpec(
        key = "lensHeightDp",
        title = "透镜折射高",
        unit = "dp",
        range = 0f..40f,
        description = "0 = 去掉纵向折射扭曲；调大后上下边界折射与放大感更强。",
        getLayer = { it.lensHeightDp },
        setLayer = { s, v -> s.copy(lensHeightDp = v) },
    ),
    LayerParamSpec(
        key = "edgeWidthDp",
        title = "细亮边宽度",
        unit = "dp",
        range = 0f..3f,
        description = "0 = 去掉高光边；调大后玻璃轮廓描边更粗、立体感更强。",
        getLayer = { it.edgeWidthDp },
        setLayer = { s, v -> s.copy(edgeWidthDp = v) },
    ),
    LayerParamSpec(
        key = "edgeBlurDp",
        title = "细亮边模糊半径",
        unit = "dp",
        range = 0f..4f,
        description = "0 = 锐利硬边反光；调大后呈现柔和弥散的羽化边缘。",
        getLayer = { it.edgeBlurDp },
        setLayer = { s, v -> s.copy(edgeBlurDp = v) },
    ),
    LayerParamSpec(
        key = "edgeAlpha",
        title = "高光边不透明度",
        unit = "",
        range = 0f..1f,
        description = "0 = 去掉细亮高光描边；调大后玻璃轮廓反光更醒目。",
        getLayer = { it.edgeAlpha },
        setLayer = { s, v -> s.copy(edgeAlpha = v) },
    ),
    LayerParamSpec(
        key = "tintAlphaLight",
        title = "亮色主题蒙层不透明度",
        unit = "",
        range = 0f..1f,
        description = "0 = 全透明纯折射；调大后表面叠加浅白雾化层，提高亮色背景文字可读性。",
        getLayer = { it.tintAlphaLight },
        setLayer = { s, v -> s.copy(tintAlphaLight = v) },
    ),
    LayerParamSpec(
        key = "tintAlphaDark",
        title = "暗色主题蒙层不透明度",
        unit = "",
        range = 0f..1f,
        description = "0 = 全透明黑玻璃纯折射；调大后增强半透黑雾遮罩。",
        getLayer = { it.tintAlphaDark },
        setLayer = { s, v -> s.copy(tintAlphaDark = v) },
    ),
    LayerParamSpec(
        key = "tier0AlphaLight",
        title = "Android 8-11 浅色实体底色",
        unit = "",
        range = 0f..1f,
        description = "仅影响不支持实时模糊/折射的 Android 8-11 设备，0 为全透明，调大更实。",
        getLayer = { it.tier0AlphaLight },
        setLayer = { s, v -> s.copy(tier0AlphaLight = v) },
    ),
    LayerParamSpec(
        key = "tier0AlphaDark",
        title = "Android 8-11 暗色实体底色",
        unit = "",
        range = 0f..1f,
        description = "仅影响不支持实时模糊/折射的 Android 8-11 设备，0 为全透明，调大更暗实。",
        getLayer = { it.tier0AlphaDark },
        setLayer = { s, v -> s.copy(tier0AlphaDark = v) },
    ),
)

private val DockCategoryGroups = listOf(
    DockParamGroup(
        title = "基础玻璃",
        params = listOf(
            DockLayerParamSpec(
                key = "blurDp",
                title = "背景模糊半径",
                unit = "dp",
                range = 0f..24f,
                description = "0 = 完全无模糊；调大后底栏背后内容会更虚化。",
                get = { it.dockGlass.blurDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(blurDp = v)) },
            ),
            DockLayerParamSpec(
                key = "lensWidthDp",
                title = "透镜折射宽",
                unit = "dp",
                range = 0f..40f,
                description = "0 = 去掉左右边缘折射扭曲；调大后底栏横向边缘的“厚玻璃”折射弯曲带更宽。",
                get = { it.dockGlass.lensWidthDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(lensWidthDp = v)) },
            ),
            DockLayerParamSpec(
                key = "lensHeightDp",
                title = "透镜折射高",
                unit = "dp",
                range = 0f..40f,
                description = "0 = 去掉上下边缘折射扭曲；调大后底栏上下边界的折射畸变与放大感更明显。",
                get = { it.dockGlass.lensHeightDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(lensHeightDp = v)) },
            ),
            DockLayerParamSpec(
                key = "dockSurfaceAlphaLight",
                title = "亮色主题底栏蒙层不透明度",
                unit = "",
                range = 0f..0.8f,
                description = "0 = 纯折射玻璃（最通透），调大后底栏会逐渐变为“白雾玻璃”，提升亮色背景下文字可读性。",
                get = { it.dockSurfaceAlphaLight },
                set = { t, v -> t.copy(dockSurfaceAlphaLight = v) },
            ),
            DockLayerParamSpec(
                key = "dockSurfaceAlphaDark",
                title = "暗色主题底栏蒙层不透明度",
                unit = "",
                range = 0f..0.8f,
                description = "0 = 黑玻璃纯折射（最通透），调大后底栏会加重半透黑雾遮罩，压暗下方复杂背景。",
                get = { it.dockSurfaceAlphaDark },
                set = { t, v -> t.copy(dockSurfaceAlphaDark = v) },
            ),
            DockLayerParamSpec(
                key = "legacyTier0AlphaLight",
                title = "Android 8-11 浅色实体底色透明度",
                unit = "",
                range = 0f..1f,
                description = "仅影响不支持实时模糊/折射的 Android 8-11 设备，控制浅色模式下实体半透胶囊底色浓淡（调小更透，调大更实）。",
                get = { it.dockGlass.legacyTier0AlphaLight },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(legacyTier0AlphaLight = v)) },
            ),
            DockLayerParamSpec(
                key = "legacyTier0AlphaDark",
                title = "Android 8-11 暗色实体底色透明度",
                unit = "",
                range = 0f..1f,
                description = "仅影响不支持实时模糊/折射的 Android 8-11 设备，控制暗色模式下实体半透黑底浓淡（调小更透，调大更接近纯黑）。",
                get = { it.dockGlass.legacyTier0AlphaDark },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(legacyTier0AlphaDark = v)) },
            ),
        ),
    ),
    DockParamGroup(
        title = "边缘高光",
        params = listOf(
            DockLayerParamSpec(
                key = "edgeWidthDp",
                title = "细亮边宽度",
                unit = "dp",
                range = 0f..3f,
                description = "控制底栏、隐藏层与指示器边缘高光描边的粗细；0 为无高光边，调大后玻璃轮廓感更强。",
                get = { it.dockGlass.edgeWidthDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeWidthDp = v)) },
            ),
            DockLayerParamSpec(
                key = "edgeBlurDp",
                title = "细亮边模糊半径",
                unit = "dp",
                range = 0f..4f,
                description = "控制边缘高光的羽化柔和度；0 为锐利硬边，调大后会呈现更扩散的柔光边缘。",
                get = { it.dockGlass.edgeBlurDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeBlurDp = v)) },
            ),
            DockLayerParamSpec(
                key = "edgeAlphaBase",
                title = "基础条高光",
                unit = "",
                range = 0f..1f,
                description = "调小至 0 = 去掉细高光边；调大后底栏外框顶侧和边缘的方向性亮边反光更醒目。",
                get = { it.dockGlass.edgeAlphaBase },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeAlphaBase = v)) },
            ),
            DockLayerParamSpec(
                key = "edgeAlphaHidden",
                title = "隐藏层高光峰值",
                unit = "",
                range = 0f..1f,
                description = "控制按下/拖拽时内部强调色渲染层的亮边峰值，影响被指示器折射出的高光层次。",
                get = { it.dockGlass.edgeAlphaHidden },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeAlphaHidden = v)) },
            ),
            DockLayerParamSpec(
                key = "edgeAlphaIndicator",
                title = "指示器高光峰值",
                unit = "",
                range = 0f..1f,
                description = "控制按下或拖动选中指示器胶囊时的边缘反光峰值亮度，调大时悬浮水滴轮廓更立体。",
                get = { it.dockGlass.edgeAlphaIndicator },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeAlphaIndicator = v)) },
            ),
        ),
    ),
    DockParamGroup(
        title = "指示器与阴影",
        includeChromaticSwitch = true,
        params = listOf(
            DockLayerParamSpec(
                key = "indicatorLensWidthDp",
                title = "指示器透镜宽",
                unit = "dp",
                range = 0f..32f,
                description = "控制按下或拖动选中胶囊时，指示器左右边缘放大/折射图标的带宽（需 Android 13+）。",
                get = { it.dockGlass.indicatorLensWidthDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorLensWidthDp = v)) },
            ),
            DockLayerParamSpec(
                key = "indicatorLensHeightDp",
                title = "指示器透镜高",
                unit = "dp",
                range = 0f..32f,
                description = "控制按下或拖动选中胶囊时，指示器上下边缘凸透镜折射拉伸幅度（需 Android 13+）。",
                get = { it.dockGlass.indicatorLensHeightDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorLensHeightDp = v)) },
            ),
            DockLayerParamSpec(
                key = "indicatorSurfaceAlpha",
                title = "选中指示器胶囊底色透明度",
                unit = "",
                range = 0f..0.5f,
                description = "控制当前选中指示器胶囊自身的常态底色浓淡；0 为完全透明折射，调大后胶囊轮廓会更明显。",
                get = { it.dockGlass.indicatorSurfaceAlpha },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorSurfaceAlpha = v)) },
            ),
            DockLayerParamSpec(
                key = "indicatorSurfacePressedAlpha",
                title = "指示器按压加深",
                unit = "",
                range = 0f..0.5f,
                description = "控制手指按住或拖动指示器时叠加的暗色加深层；调大后按压水滴会更明显地偏暗。",
                get = { it.dockGlass.indicatorSurfacePressedAlpha },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorSurfacePressedAlpha = v)) },
            ),
            DockLayerParamSpec(
                key = "indicatorShadowRadiusDp",
                title = "投影半径",
                unit = "dp",
                range = 0f..32f,
                description = "控制按压/拖拽指示器胶囊向外扩散的外投影范围，调大后漂浮感更强。",
                get = { it.dockGlass.indicatorShadowRadiusDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorShadowRadiusDp = v)) },
            ),
            DockLayerParamSpec(
                key = "indicatorShadowAlpha",
                title = "投影不透明度",
                unit = "",
                range = 0f..1f,
                description = "控制按压/拖拽指示器胶囊时外投影的深浅；0 无外投影，调大后悬浮立体感更明显。",
                get = { it.dockGlass.indicatorShadowAlpha },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorShadowAlpha = v)) },
            ),
            DockLayerParamSpec(
                key = "indicatorInnerRadiusDp",
                title = "内阴影半径",
                unit = "dp",
                range = 0f..20f,
                description = "控制按压时指示器胶囊内侧边缘的内阴影扩散范围，营造内部凹陷/厚度光影。",
                get = { it.dockGlass.indicatorInnerRadiusDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorInnerRadiusDp = v)) },
            ),
            DockLayerParamSpec(
                key = "indicatorInnerAlpha",
                title = "内阴影不透明度",
                unit = "",
                range = 0f..1f,
                description = "控制按压时指示器内阴影的深浅；调大后液态水滴内缘阴影边界更清晰。",
                get = { it.dockGlass.indicatorInnerAlpha },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorInnerAlpha = v)) },
            ),
        ),
    ),
)

private fun GlobalGlassSettings.layerFor(category: LiquidGlassCategory): GlassLayerSettings = when (category) {
    LiquidGlassCategory.NAVIGATION -> navigation
    LiquidGlassCategory.CONTENT -> content
    LiquidGlassCategory.CONTROL -> control
    LiquidGlassCategory.OVERLAY -> overlay
    LiquidGlassCategory.DOCK -> navigation
}

private fun GlobalGlassSettings.withLayer(
    category: LiquidGlassCategory,
    layer: GlassLayerSettings,
): GlobalGlassSettings = when (category) {
    LiquidGlassCategory.NAVIGATION -> copy(navigation = layer)
    LiquidGlassCategory.CONTENT -> copy(content = layer)
    LiquidGlassCategory.CONTROL -> copy(control = layer)
    LiquidGlassCategory.OVERLAY -> copy(overlay = layer)
    LiquidGlassCategory.DOCK -> this
}

private fun defaultLayerFor(category: LiquidGlassCategory): GlassLayerSettings = when (category) {
    LiquidGlassCategory.NAVIGATION -> GlobalGlassSettings.DefaultNavigation
    LiquidGlassCategory.CONTENT -> GlobalGlassSettings.DefaultContent
    LiquidGlassCategory.CONTROL -> GlobalGlassSettings.DefaultControl
    LiquidGlassCategory.OVERLAY -> GlobalGlassSettings.DefaultOverlay
    LiquidGlassCategory.DOCK -> GlobalGlassSettings.DefaultNavigation
}

/** 液态玻璃分类调节页：按「导航栏 / 卡片内容 / 交互控件 / 弹窗浮层 / 底部栏」分类调节模糊、折射、高光与透明度。 */
@Composable
fun LiquidGlassSettingsScreen(
    themeSettings: ThemeSettings,
    onThemeSettingsChange: (ThemeSettings) -> Unit,
    wallpaperPath: String? = null,
    wallpaperScrim: Float = 0.18f,
    wallpaperBlur: Float = 0f,
    initialCategory: LiquidGlassCategory = LiquidGlassCategory.NAVIGATION,
    onBack: () -> Unit,
) {
    fun normalize(settings: ThemeSettings): ThemeSettings = settings.copy(
        dockSurfaceAlphaLight = settings.dockSurfaceAlphaLight.coerceIn(0f, 0.8f),
        dockSurfaceAlphaDark = settings.dockSurfaceAlphaDark.coerceIn(0f, 0.8f),
        dockGlass = settings.dockGlass.sanitized(),
        globalGlass = settings.globalGlass.sanitized(),
    )

    var selectedCategory by rememberSaveable { mutableStateOf(initialCategory) }
    var inputMode by rememberSaveable { mutableStateOf(DockGlassInputMode.SLIDER) }
    var resetVersion by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf(normalize(themeSettings)) }
    var lastCommitted by remember { mutableStateOf(draft) }

    LaunchedEffect(themeSettings) {
        val incoming = normalize(themeSettings)
        if (incoming != lastCommitted) {
            draft = incoming
            lastCommitted = incoming
            resetVersion++
        }
    }

    fun commit(next: ThemeSettings) {
        val normalized = normalize(next)
        draft = normalized
        if (normalized != lastCommitted) {
            lastCommitted = normalized
            onThemeSettingsChange(normalized)
        }
    }

    val isAllDefault = draft.globalGlass == DefaultGlobal &&
        draft.dockSurfaceAlphaLight == 0f &&
        draft.dockSurfaceAlphaDark == 0f &&
        draft.dockGlass == DockGlassSettings()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun showNotice(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    GlassScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = "液态玻璃",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    GlassTopBarTextButton(
                        text = "恢复默认",
                        enabled = !isAllDefault,
                        onClick = {
                            val reset = draft.copy(
                                dockSurfaceAlphaLight = 0f,
                                dockSurfaceAlphaDark = 0f,
                                dockGlass = DockGlassSettings(),
                                globalGlass = DefaultGlobal,
                            )
                            commit(reset)
                            resetVersion++
                        },
                    )
                },
            )
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val topPadding = innerPadding.calculateTopPadding()
        val bottomPadding = innerPadding.calculateBottomPadding()
        val horizontalModifier = Modifier.padding(
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
        )

        BoxWithConstraints(Modifier.fillMaxSize().then(horizontalModifier)) {
            val availableHeight = (maxHeight - topPadding - bottomPadding).coerceAtLeast(0.dp)
            val narrowPreviewHeight = (availableHeight * 0.30f).coerceIn(152.dp, 210.dp)

            val controlsContent: @Composable (Modifier) -> Unit = { contentModifier ->
                Column(
                    modifier = contentModifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PresetSchemesCard(
                        draft = draft,
                        onApplyPreset = { preset ->
                            commit(
                                draft.copy(
                                    globalGlass = preset.globalGlass,
                                    dockGlass = preset.dockGlass,
                                    dockSurfaceAlphaLight = preset.dockSurfaceAlphaLight,
                                    dockSurfaceAlphaDark = preset.dockSurfaceAlphaDark,
                                ),
                            )
                            resetVersion++
                        },
                        onSavePreset = { presetName ->
                            val newPreset = LiquidGlassPreset(
                                id = "custom_${System.currentTimeMillis()}_${draft.savedGlassPresets.size}",
                                name = presetName,
                                globalGlass = draft.globalGlass,
                                dockGlass = draft.dockGlass,
                                dockSurfaceAlphaLight = draft.dockSurfaceAlphaLight,
                                dockSurfaceAlphaDark = draft.dockSurfaceAlphaDark,
                            ).sanitized()
                            commit(
                                draft.copy(
                                    savedGlassPresets = (draft.savedGlassPresets + newPreset)
                                        .distinctBy { it.id }
                                        .take(10),
                                ),
                            )
                        },
                        onImportPreset = { imported, saveToList ->
                            val nextPresets = if (saveToList && draft.savedGlassPresets.size < 10) {
                                (draft.savedGlassPresets + imported.sanitized())
                                    .distinctBy { it.id }
                                    .take(10)
                            } else {
                                draft.savedGlassPresets
                            }
                            commit(
                                draft.copy(
                                    globalGlass = imported.globalGlass,
                                    dockGlass = imported.dockGlass,
                                    dockSurfaceAlphaLight = imported.dockSurfaceAlphaLight,
                                    dockSurfaceAlphaDark = imported.dockSurfaceAlphaDark,
                                    savedGlassPresets = nextPresets,
                                ),
                            )
                            resetVersion++
                            showNotice("已导入方案「${imported.name}」")
                        },
                        onShowMessage = ::showNotice,
                        onDeletePreset = { presetId ->
                            commit(
                                draft.copy(
                                    savedGlassPresets = draft.savedGlassPresets.filterNot { it.id == presetId },
                                ),
                            )
                        },
                    )

                    CategorySelectorCard(
                        selected = selectedCategory,
                        onSelect = { selectedCategory = it },
                    )

                    QuickPresetsCard(
                        inputMode = inputMode,
                        canSyncToAllFour = selectedCategory != LiquidGlassCategory.DOCK,
                        onModeChange = { next ->
                            if (inputMode != next) {
                                inputMode = next
                                if (next == DockGlassInputMode.NUMERIC) {
                                    resetVersion++
                                }
                            }
                        },
                        onSyncCurrentToAllFour = {
                            if (selectedCategory != LiquidGlassCategory.DOCK) {
                                val sourceLayer = draft.globalGlass.layerFor(selectedCategory)
                                commit(
                                    draft.copy(
                                        globalGlass = GlobalGlassSettings(
                                            navigation = sourceLayer,
                                            content = sourceLayer,
                                            control = sourceLayer,
                                            overlay = sourceLayer,
                                        ),
                                    ),
                                )
                                resetVersion++
                            }
                        },
                        onClearCurrentCategory = {
                            val cleared = if (selectedCategory == LiquidGlassCategory.DOCK) {
                                draft.copy(
                                    dockSurfaceAlphaLight = 0f,
                                    dockSurfaceAlphaDark = 0f,
                                    dockGlass = draft.dockGlass.copy(
                                        blurDp = 0f,
                                        lensWidthDp = 0f,
                                        lensHeightDp = 0f,
                                        edgeAlphaBase = 0f,
                                        edgeAlphaHidden = 0f,
                                        edgeAlphaIndicator = 0f,
                                        indicatorLensWidthDp = 0f,
                                        indicatorLensHeightDp = 0f,
                                        indicatorSurfaceAlpha = 0f,
                                        indicatorSurfacePressedAlpha = 0f,
                                        indicatorShadowAlpha = 0f,
                                        indicatorInnerAlpha = 0f,
                                        legacyTier0AlphaLight = 0f,
                                        legacyTier0AlphaDark = 0f,
                                    ),
                                )
                            } else {
                                val nextLayer = draft.globalGlass.layerFor(selectedCategory).cleared()
                                draft.copy(globalGlass = draft.globalGlass.withLayer(selectedCategory, nextLayer))
                            }
                            commit(cleared)
                            resetVersion++
                        },
                        onClearAllFour = {
                            commit(draft.copy(globalGlass = GlobalGlassSettings.ClearPreset))
                            resetVersion++
                        },
                        onResetCurrentCategory = {
                            val restored = if (selectedCategory == LiquidGlassCategory.DOCK) {
                                draft.copy(
                                    dockSurfaceAlphaLight = DefaultTheme.dockSurfaceAlphaLight,
                                    dockSurfaceAlphaDark = DefaultTheme.dockSurfaceAlphaDark,
                                    dockGlass = DockGlassSettings(),
                                )
                            } else {
                                val defLayer = defaultLayerFor(selectedCategory)
                                draft.copy(globalGlass = draft.globalGlass.withLayer(selectedCategory, defLayer))
                            }
                            commit(restored)
                            resetVersion++
                        },
                    )

                    CategoryParamsCard(
                        category = selectedCategory,
                        mode = inputMode,
                        draft = draft,
                        resetVersion = resetVersion,
                        onDraftChange = { draft = normalize(it) },
                        onCommit = ::commit,
                    )

                    Text(
                        text = "四类参数支持全部调至 0（无模糊、无折射、无蒙层、无高光边），也可按页面区域精细微调。Android 13+ 完整支持模糊与透镜折射，Android 12 支持模糊与蒙层，Android 8-11 对应实体底色透明度。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (maxWidth >= 840.dp) {
                val verticalPadding = PaddingValues(top = topPadding, bottom = bottomPadding)
                Row(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 360.dp)
                            .padding(verticalPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CategoryLivePreview(
                            category = selectedCategory,
                            settings = draft,
                            wallpaperPath = wallpaperPath,
                            wallpaperScrim = wallpaperScrim,
                            wallpaperBlur = wallpaperBlur,
                            previewHeight = 250.dp,
                        )
                        Text(
                            text = "当前预览：${selectedCategory.title}（${selectedCategory.subtitle}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        controlsContent(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(verticalPadding),
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = topPadding, start = 16.dp, end = 16.dp, bottom = 8.dp),
                    ) {
                        CategoryLivePreview(
                            category = selectedCategory,
                            settings = draft,
                            wallpaperPath = wallpaperPath,
                            wallpaperScrim = wallpaperScrim,
                            wallpaperBlur = wallpaperBlur,
                            previewHeight = narrowPreviewHeight,
                        )
                    }
                    controlsContent(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = bottomPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryLivePreview(
    category: LiquidGlassCategory,
    settings: ThemeSettings,
    wallpaperPath: String?,
    wallpaperScrim: Float,
    wallpaperBlur: Float,
    previewHeight: Dp,
) {
    val themeBackground = MaterialTheme.colorScheme.background
    val currentBackground by rememberUpdatedState(themeBackground)
    val previewBackdrop = rememberLayerBackdrop {
        drawRect(currentBackground)
        drawContent()
    }
    val brand = LocalBrand.current
    val palette = remember(settings.globalGlass) { settings.globalGlass.toGlassPalette() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(previewHeight)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .testTag("liquid-glass-preview"),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .layerBackdrop(previewBackdrop),
        ) {
            if (wallpaperPath != null) {
                WallpaperLayer(
                    path = wallpaperPath,
                    scrim = wallpaperScrim,
                    blur = wallpaperBlur,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(
                                brand.accent.copy(alpha = 0.30f),
                                Color(0xFF4C8DF6).copy(alpha = 0.24f),
                                Color(0xFF34C759).copy(alpha = 0.24f),
                            ),
                        ),
                    )
                    drawCircle(
                        color = brand.accent.copy(alpha = 0.45f),
                        radius = size.minDimension * 0.32f,
                        center = Offset(size.width * 0.20f, size.height * 0.42f),
                    )
                    drawCircle(
                        color = Color(0xFFFF9F0A).copy(alpha = 0.42f),
                        radius = size.minDimension * 0.28f,
                        center = Offset(size.width * 0.56f, size.height * 0.72f),
                    )
                    drawCircle(
                        color = Color(0xFF5E5CE6).copy(alpha = 0.40f),
                        radius = size.minDimension * 0.30f,
                        center = Offset(size.width * 0.85f, size.height * 0.48f),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF3B82F6).copy(alpha = 0.72f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("高等数学 · A302", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.72f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("软件工程 · 实验楼", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }

        CompositionLocalProvider(
            LocalGlassBackdrop provides previewBackdrop,
            LocalGlassHasWallpaper provides true,
            LocalGlassPalette provides palette,
        ) {
            when (category) {
                LiquidGlassCategory.NAVIGATION -> {
                    GlassSurface(
                        material = GlassMaterial.NAVIGATION,
                        shape = RoundedRectangle(14.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("第11周 周六", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("顶栏 / 侧栏 / 浮动栏", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                LiquidGlassCategory.CONTENT -> {
                    GlassSurface(
                        material = GlassMaterial.CONTENT,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("今日课程概览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("08:00 高等数学 · @明理楼 A101", style = MaterialTheme.typography.bodySmall)
                            Text("14:00 操作系统 · @信息楼 B301", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                LiquidGlassCategory.CONTROL -> {
                    var toggleState by remember { mutableStateOf(true) }
                    var sliderState by remember { mutableFloatStateOf(0.55f) }
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiquidButton(onClick = {}) { Text("主按钮") }
                            LiquidOutlinedButton(onClick = {}) { Text("次按钮") }
                            LiquidFilterChip(selected = false, onClick = {}, label = { Text("筛选") })
                            Spacer(Modifier.weight(1f))
                            LiquidToggle(checked = toggleState, onCheckedChange = { toggleState = it })
                        }
                        LiquidSlider(
                            value = sliderState,
                            onValueChange = { sliderState = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                LiquidGlassCategory.OVERLAY -> {
                    GlassSurface(
                        material = GlassMaterial.OVERLAY,
                        shape = RoundedCornerShape(20.dp),
                        includeShadow = true,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("确认临时调课？", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("弹窗 / 底部抽屉 / 菜单均使用此材质参数", style = MaterialTheme.typography.bodySmall)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                LiquidOutlinedButton(onClick = {}) { Text("取消") }
                                LiquidButton(onClick = {}) { Text("确定") }
                            }
                        }
                    }
                }
                LiquidGlassCategory.DOCK -> {
                    var selectedTab by remember { mutableIntStateOf(0) }
                    val previewTabs = listOf(
                        "今日" to Icons.Filled.Home,
                        "课表" to Icons.Filled.DateRange,
                        "成绩" to Icons.Filled.Star,
                        "我的" to Icons.Filled.Person,
                    )
                    LiquidBottomTabs(
                        selectedTabIndex = { selectedTab },
                        onTabSelected = { selectedTab = it },
                        backdrop = previewBackdrop,
                        tabsCount = previewTabs.size,
                        surfaceAlphaLight = settings.dockSurfaceAlphaLight,
                        surfaceAlphaDark = settings.dockSurfaceAlphaDark,
                        glassSettings = settings.dockGlass,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        previewTabs.forEachIndexed { index, (label, icon) ->
                            LiquidBottomTab(
                                index = index,
                                selected = index == selectedTab,
                            ) {
                                DockTabContent(
                                    label = label,
                                    icon = icon,
                                    selected = index == selectedTab,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySelectorCard(
    selected: LiquidGlassCategory,
    onSelect: (LiquidGlassCategory) -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("选择调整分类", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiquidGlassCategory.entries.forEach { category ->
                    LiquidFilterChip(
                        selected = selected == category,
                        onClick = { onSelect(category) },
                        label = { Text(category.title) },
                        modifier = Modifier.testTag("liquid-glass-cat-${category.name}"),
                    )
                }
            }
            Text(
                text = "当前：${selected.title} · ${selected.subtitle}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickPresetsCard(
    inputMode: DockGlassInputMode,
    canSyncToAllFour: Boolean = false,
    onModeChange: (DockGlassInputMode) -> Unit,
    onSyncCurrentToAllFour: () -> Unit = {},
    onClearCurrentCategory: () -> Unit,
    onClearAllFour: () -> Unit,
    onResetCurrentCategory: () -> Unit,
) {
    val brand = LocalBrand.current
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("调节方式与快捷预设", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DockGlassInputMode.entries.forEach { item ->
                    val isSelected = inputMode == item
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (isSelected) brand.accent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            )
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onModeChange(item) },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) brand.onBrand else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canSyncToAllFour) {
                    LiquidOutlinedButton(
                        onClick = onSyncCurrentToAllFour,
                        modifier = Modifier.testTag("liquid-glass-sync-all-four-btn"),
                    ) {
                        Text("同步当前分类到全部四类")
                    }
                }
                LiquidOutlinedButton(onClick = onClearCurrentCategory) {
                    Text("当前分类全透明")
                }
                LiquidOutlinedButton(onClick = onClearAllFour) {
                    Text("全部四类全透明")
                }
                LiquidOutlinedButton(onClick = onResetCurrentCategory) {
                    Text("恢复本类默认")
                }
            }
        }
    }
}

@Composable
private fun CategoryParamsCard(
    category: LiquidGlassCategory,
    mode: DockGlassInputMode,
    draft: ThemeSettings,
    resetVersion: Int,
    onDraftChange: (ThemeSettings) -> Unit,
    onCommit: (ThemeSettings) -> Unit,
) {
    if (category == LiquidGlassCategory.DOCK) {
        val defaultDock = DefaultTheme
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DockCategoryGroups.forEach { group ->
                GlassSurface(
                    shape = RoundedCornerShape(16.dp),
                    material = GlassMaterial.CONTENT,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        group.params.forEach { spec ->
                            val currentValue = spec.get(draft)
                            val defaultValue = spec.get(defaultDock)
                            ParamRowByMode(
                                category = category,
                                key = spec.key,
                                title = spec.title,
                                unit = spec.unit,
                                range = spec.range,
                                description = spec.description,
                                value = currentValue,
                                defaultValue = defaultValue,
                                mode = mode,
                                resetVersion = resetVersion,
                                onDraftValue = { v -> onDraftChange(spec.set(draft, v)) },
                                onCommitValue = { v -> onCommit(spec.set(draft, v)) },
                            )
                        }
                        if (group.includeChromaticSwitch) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("指示器色散效果", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "开启后按压或拖动指示器时，边缘会呈现微弱的 RGB 棱镜色散；关闭则只做纯净几何折射。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                LiquidToggle(
                                    checked = draft.dockGlass.indicatorChromaticAberration,
                                    onCheckedChange = { enabled ->
                                        onCommit(
                                            draft.copy(
                                                dockGlass = draft.dockGlass.copy(indicatorChromaticAberration = enabled),
                                            ),
                                        )
                                    },
                                    modifier = Modifier.testTag("liquid-glass-chromatic-toggle"),
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        GlassSurface(
            shape = RoundedCornerShape(16.dp),
            material = GlassMaterial.CONTENT,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "${category.title}参数",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                val currentLayer = draft.globalGlass.layerFor(category)
                val defaultLayer = defaultLayerFor(category)
                LayerParamSpecs.forEach { spec ->
                    val currentValue = spec.getLayer(currentLayer)
                    val defaultValue = spec.getLayer(defaultLayer)
                    ParamRowByMode(
                        category = category,
                        key = spec.key,
                        title = spec.title,
                        unit = spec.unit,
                        range = spec.range,
                        description = spec.description,
                        value = currentValue,
                        defaultValue = defaultValue,
                        mode = mode,
                        resetVersion = resetVersion,
                        onDraftValue = { v ->
                            val updatedLayer = spec.setLayer(currentLayer, v)
                            onDraftChange(
                                draft.copy(globalGlass = draft.globalGlass.withLayer(category, updatedLayer)),
                            )
                        },
                        onCommitValue = { v ->
                            val updatedLayer = spec.setLayer(currentLayer, v)
                            onCommit(
                                draft.copy(globalGlass = draft.globalGlass.withLayer(category, updatedLayer)),
                            )
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("立体深度折射", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "开启后透镜边缘呈现球面凸透镜立体弯曲景深（Kyant0 Backdrop depthEffect，需 Android 13+）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LiquidToggle(
                        checked = currentLayer.depthEffect,
                        onCheckedChange = { enabled ->
                            val updatedLayer = currentLayer.copy(depthEffect = enabled)
                            onCommit(
                                draft.copy(globalGlass = draft.globalGlass.withLayer(category, updatedLayer)),
                            )
                        },
                        modifier = Modifier.testTag("liquid-glass-depth-toggle-${category.name}"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("边缘棱镜色散", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "开启后折射边缘呈现微弱的 RGB 棱镜色散彩边（Kyant0 Backdrop chromaticAberration，需 Android 13+）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LiquidToggle(
                        checked = currentLayer.chromaticAberration,
                        onCheckedChange = { enabled ->
                            val updatedLayer = currentLayer.copy(chromaticAberration = enabled)
                            onCommit(
                                draft.copy(globalGlass = draft.globalGlass.withLayer(category, updatedLayer)),
                            )
                        },
                        modifier = Modifier.testTag("liquid-glass-chromatic-toggle-${category.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ParamRowByMode(
    category: LiquidGlassCategory,
    key: String,
    title: String,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    description: String,
    value: Float,
    defaultValue: Float,
    mode: DockGlassInputMode,
    resetVersion: Int,
    onDraftValue: (Float) -> Unit,
    onCommitValue: (Float) -> Unit,
) {
    val current = value.coerceIn(range)
    val unitSuffix = if (unit.isEmpty()) "" else " $unit"
    val scaleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

    when (mode) {
        DockGlassInputMode.SLIDER -> {
            var latestRounded by remember(category.name, key, current) { mutableFloatStateOf(current) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${formatGlassFloat(current)}$unitSuffix",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LiquidSlider(
                    value = current,
                    onValueChange = { raw ->
                        val rounded = ((raw * 100f).roundToInt() / 100f).coerceIn(range)
                        latestRounded = rounded
                        onDraftValue(rounded)
                    },
                    valueRange = range,
                    onValueChangeFinished = { onCommitValue(latestRounded) },
                    modifier = Modifier.testTag("liquid-glass-slider-${category.name}-$key"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatGlassFloat(range.start),
                        style = MaterialTheme.typography.labelSmall,
                        color = scaleColor,
                    )
                    Text(
                        text = "${formatGlassFloat(range.endInclusive)}$unitSuffix",
                        style = MaterialTheme.typography.labelSmall,
                        color = scaleColor,
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DockGlassInputMode.NUMERIC -> {
            var text by remember(category.name, key, resetVersion) {
                mutableStateOf(formatGlassFloat(current))
            }
            var isError by remember(category.name, key, resetVersion) { mutableStateOf(false) }
            val labelText = if (unit.isEmpty()) title else "$title ($unit)"
            val minText = formatGlassFloat(range.start)
            val maxText = formatGlassFloat(range.endInclusive)
            val defaultText = formatGlassFloat(defaultValue)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LiquidOutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        text = raw
                        val trimmed = raw.trim()
                        val parsed = if (trimmed.isNotEmpty() && trimmed.none { it.isLetter() }) {
                            trimmed.toFloatOrNull()
                        } else {
                            null
                        }
                        if (parsed != null && parsed.isFinite() && parsed in range) {
                            isError = false
                            onCommitValue(parsed)
                        } else {
                            isError = true
                        }
                    },
                    label = { Text(labelText) },
                    isError = isError,
                    supportingText = {
                        Text(
                            if (isError) {
                                "请输入 $minText ~ $maxText$unitSuffix 之间的有效数值"
                            } else {
                                "当前项目默认 $defaultText$unitSuffix · 范围 $minText ~ $maxText"
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("liquid-glass-input-${category.name}-$key"),
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSchemesCard(
    draft: ThemeSettings,
    onApplyPreset: (LiquidGlassPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onImportPreset: (LiquidGlassPreset, Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
) {
    val context = LocalContext.current
    var showSaveInput by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var showImportInput by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var saveImportedPreset by remember { mutableStateOf(true) }
    val savedPresets = draft.savedGlassPresets

    fun matchesPreset(preset: LiquidGlassPreset): Boolean =
        draft.globalGlass == preset.globalGlass &&
            draft.dockGlass == preset.dockGlass &&
            draft.dockSurfaceAlphaLight == preset.dockSurfaceAlphaLight &&
            draft.dockSurfaceAlphaDark == preset.dockSurfaceAlphaDark

    fun activeSchemeName(): String =
        (LiquidGlassPreset.BuiltIns + savedPresets).firstOrNull(::matchesPreset)?.name ?: "我的液态玻璃"

    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "样式方案预设",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "一键切换内置或自定义液态玻璃风格（含四类分层与底栏参数），切换后仍可继续微调。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LiquidGlassPreset.BuiltIns.forEach { preset ->
                    LiquidFilterChip(
                        selected = matchesPreset(preset),
                        onClick = { onApplyPreset(preset) },
                        label = { Text(preset.name) },
                        modifier = Modifier.testTag("liquid-glass-preset-${preset.id}"),
                    )
                }
            }
            if (savedPresets.isNotEmpty()) {
                Text(
                    text = "我的自定义方案（${savedPresets.size}/10）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    savedPresets.forEach { preset ->
                        LiquidFilterChip(
                            selected = matchesPreset(preset),
                            onClick = { onApplyPreset(preset) },
                            label = { Text(preset.name) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "删除预设 ${preset.name}",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onDeletePreset(preset.id) },
                                )
                            },
                            modifier = Modifier.testTag("liquid-glass-preset-${preset.id}"),
                        )
                    }
                }
            }
            if (showSaveInput) {
                val trimmed = nameInput.trim()
                LiquidOutlinedTextField(
                    value = nameInput,
                    onValueChange = { if (it.length <= 24) nameInput = it },
                    label = { Text("方案名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("liquid-glass-preset-name-input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiquidOutlinedButton(
                        onClick = {
                            showSaveInput = false
                            nameInput = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }
                    LiquidButton(
                        onClick = {
                            if (trimmed.isNotEmpty()) {
                                onSavePreset(trimmed)
                                showSaveInput = false
                                nameInput = ""
                            }
                        },
                        enabled = trimmed.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存")
                    }
                }
            } else if (showImportInput) {
                Text(
                    text = "粘贴好友分享的 IYANGO-GLASS-V1 口令（整段聊天内容也可自动识别）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LiquidOutlinedTextField(
                    value = importInput,
                    onValueChange = {
                        importInput = it
                        importError = null
                    },
                    label = { Text("分享口令") },
                    placeholder = { Text("IYANGO-GLASS-V1:...") },
                    isError = importError != null,
                    supportingText = importError?.let { msg -> { Text(msg) } },
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("liquid-glass-import-input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiquidOutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val clipText = clipboard?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                .orEmpty()
                            if (clipText.isBlank()) {
                                importError = "剪贴板为空"
                            } else {
                                importInput = clipText
                                importError = null
                            }
                        },
                    ) {
                        Text("粘贴剪贴板")
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = savedPresets.size < 10) {
                            saveImportedPreset = !saveImportedPreset
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiquidCheckbox(
                        checked = saveImportedPreset && savedPresets.size < 10,
                        onCheckedChange = null,
                        enabled = savedPresets.size < 10,
                    )
                    Text(
                        text = if (savedPresets.size < 10) {
                            "同时保存为我的自定义方案"
                        } else {
                            "自定义方案已满（仅应用当前效果）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiquidOutlinedButton(
                        onClick = {
                            showImportInput = false
                            importError = null
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }
                    LiquidButton(
                        onClick = {
                            LiquidGlassShareCodec.decode(importInput)
                                .onSuccess { decoded ->
                                    showImportInput = false
                                    importError = null
                                    onImportPreset(decoded, saveImportedPreset)
                                }
                                .onFailure { err ->
                                    importError = err.message ?: "口令无效或已损坏"
                                }
                        },
                        enabled = importInput.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("liquid-glass-import-confirm-btn"),
                    ) {
                        Text("立即应用")
                    }
                }
            } else {
                LiquidOutlinedButton(
                    onClick = {
                        nameInput = ""
                        showSaveInput = true
                    },
                    enabled = savedPresets.size < 10,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("liquid-glass-save-preset-btn"),
                ) {
                    Text(if (savedPresets.size >= 10) "自定义方案已满（10/10）" else "保存当前为方案")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LiquidOutlinedButton(
                        onClick = {
                            val shareCode = LiquidGlassShareCodec.encode(
                                name = activeSchemeName(),
                                globalGlass = draft.globalGlass,
                                dockGlass = draft.dockGlass,
                                dockSurfaceAlphaLight = draft.dockSurfaceAlphaLight,
                                dockSurfaceAlphaDark = draft.dockSurfaceAlphaDark,
                            )
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("iYango液态玻璃口令", shareCode),
                            )
                            onShowMessage("已复制液态玻璃分享口令")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("liquid-glass-copy-code-btn"),
                    ) {
                        Text("复制分享口令")
                    }
                    LiquidOutlinedButton(
                        onClick = {
                            importInput = ""
                            importError = null
                            saveImportedPreset = savedPresets.size < 10
                            showImportInput = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("liquid-glass-import-code-btn"),
                    ) {
                        Text("导入分享口令")
                    }
                }
            }
        }
    }
}
