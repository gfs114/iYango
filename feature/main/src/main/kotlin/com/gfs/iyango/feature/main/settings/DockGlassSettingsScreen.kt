@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.liquidglass.LiquidSlider
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.DockGlassSettings
import com.gfs.iyango.core.domain.ThemeSettings
import com.gfs.iyango.feature.main.DockTabContent
import com.gfs.iyango.feature.main.liquidglass.LiquidBottomTab
import com.gfs.iyango.feature.main.liquidglass.LiquidBottomTabs
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.util.Locale
import kotlin.math.roundToInt

/** 底栏液态玻璃调节模式：拖动滑杆或直接输入精确数值。 */
enum class DockGlassInputMode(val label: String) {
    SLIDER("拖动调节"),
    NUMERIC("数值输入"),
}

private data class GlassFloatParamSpec(
    val key: String,
    val title: String,
    val unit: String,
    val range: ClosedFloatingPointRange<Float>,
    val effectDescription: String,
    val get: (ThemeSettings) -> Float,
    val set: (ThemeSettings, Float) -> ThemeSettings,
)

private data class GlassParamGroup(
    val title: String,
    val params: List<GlassFloatParamSpec>,
    val includeChromaticSwitch: Boolean = false,
)

private val DefaultThemeSettings = ThemeSettings()
private val DefaultDockGlass = DockGlassSettings()

private val BlurDpParam = GlassFloatParamSpec(
    key = "blurDp",
    title = "背景模糊半径",
    unit = "dp",
    range = 0f..24f,
    effectDescription = "调小至 0 = 去掉背景模糊，背后内容完全清晰；调大后底栏后方的课程卡片与文字会更柔和虚化。",
    get = { it.dockGlass.blurDp },
    set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(blurDp = v)) },
)

private val LensWidthDpParam = GlassFloatParamSpec(
    key = "lensWidthDp",
    title = "透镜折射宽",
    unit = "dp",
    range = 0f..40f,
    effectDescription = "调小至 0 = 去掉左右边缘折射扭曲；调大后底栏横向边缘的“厚玻璃”折射弯曲带更宽。",
    get = { it.dockGlass.lensWidthDp },
    set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(lensWidthDp = v)) },
)

private val LensHeightDpParam = GlassFloatParamSpec(
    key = "lensHeightDp",
    title = "透镜折射高",
    unit = "dp",
    range = 0f..40f,
    effectDescription = "调小至 0 = 去掉上下边缘折射扭曲；调大后底栏上下边界的折射畸变与放大感更明显。",
    get = { it.dockGlass.lensHeightDp },
    set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(lensHeightDp = v)) },
)

private val EdgeAlphaBaseParam = GlassFloatParamSpec(
    key = "edgeAlphaBase",
    title = "基础条高光",
    unit = "",
    range = 0f..1f,
    effectDescription = "调小至 0 = 去掉细高光边；调大后底栏外框顶侧和边缘的方向性亮边反光更醒目。",
    get = { it.dockGlass.edgeAlphaBase },
    set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeAlphaBase = v)) },
)

private val SurfaceAlphaLightParam = GlassFloatParamSpec(
    key = "dockSurfaceAlphaLight",
    title = "亮色主题底栏蒙层不透明度",
    unit = "",
    range = 0f..0.8f,
    effectDescription = "0 = 纯折射玻璃（最通透），调大后底栏会逐渐变为“白雾玻璃”，提升亮色背景下文字可读性。",
    get = { it.dockSurfaceAlphaLight },
    set = { t, v -> t.copy(dockSurfaceAlphaLight = v) },
)

private val SurfaceAlphaDarkParam = GlassFloatParamSpec(
    key = "dockSurfaceAlphaDark",
    title = "暗色主题底栏蒙层不透明度",
    unit = "",
    range = 0f..0.8f,
    effectDescription = "0 = 黑玻璃纯折射（最通透），调大后底栏会加重半透黑雾遮罩，压暗下方复杂背景。",
    get = { it.dockSurfaceAlphaDark },
    set = { t, v -> t.copy(dockSurfaceAlphaDark = v) },
)

private val LegacyTier0AlphaLightParam = GlassFloatParamSpec(
    key = "legacyTier0AlphaLight",
    title = "Android 8-11 浅色实体底色透明度",
    unit = "",
    range = 0f..1f,
    effectDescription = "仅影响不支持实时模糊/折射的 Android 8-11 设备，控制浅色模式下实体半透胶囊底色浓淡（调小更透，调大更实）。",
    get = { it.dockGlass.legacyTier0AlphaLight },
    set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(legacyTier0AlphaLight = v)) },
)

private val LegacyTier0AlphaDarkParam = GlassFloatParamSpec(
    key = "legacyTier0AlphaDark",
    title = "Android 8-11 暗色实体底色透明度",
    unit = "",
    range = 0f..1f,
    effectDescription = "仅影响不支持实时模糊/折射的 Android 8-11 设备，控制暗色模式下实体半透黑底浓淡（调小更透，调大更接近纯黑）。",
    get = { it.dockGlass.legacyTier0AlphaDark },
    set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(legacyTier0AlphaDark = v)) },
)

private val IndicatorSurfaceAlphaParam = GlassFloatParamSpec(
    key = "indicatorSurfaceAlpha",
    title = "选中指示器胶囊底色透明度",
    unit = "",
    range = 0f..0.5f,
    effectDescription = "控制当前选中指示器胶囊自身的常态底色浓淡；0 为完全透明折射，调大后胶囊轮廓会更明显。",
    get = { it.dockGlass.indicatorSurfaceAlpha },
    set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorSurfaceAlpha = v)) },
)

private val SimpleDockGlassParamGroups = listOf(
    GlassParamGroup(
        title = "底栏玻璃效果",
        params = listOf(
            BlurDpParam,
            LensWidthDpParam,
            LensHeightDpParam,
            EdgeAlphaBaseParam,
        ),
    ),
)

private val FullDockGlassParamGroups = listOf(
    GlassParamGroup(
        title = "基础玻璃",
        params = listOf(
            BlurDpParam,
            LensWidthDpParam,
            LensHeightDpParam,
            SurfaceAlphaLightParam,
            SurfaceAlphaDarkParam,
            LegacyTier0AlphaLightParam,
            LegacyTier0AlphaDarkParam,
        ),
    ),
    GlassParamGroup(
        title = "边缘高光",
        params = listOf(
            GlassFloatParamSpec(
                key = "edgeWidthDp",
                title = "细亮边宽度",
                unit = "dp",
                range = 0f..3f,
                effectDescription = "控制底栏、隐藏层与指示器边缘高光描边的粗细；0 为无高光边，调大后玻璃轮廓感更强。",
                get = { it.dockGlass.edgeWidthDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeWidthDp = v)) },
            ),
            GlassFloatParamSpec(
                key = "edgeBlurDp",
                title = "细亮边模糊半径",
                unit = "dp",
                range = 0f..4f,
                effectDescription = "控制边缘高光的羽化柔和度；0 为锐利硬边，调大后会呈现更扩散的柔光边缘。",
                get = { it.dockGlass.edgeBlurDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeBlurDp = v)) },
            ),
            EdgeAlphaBaseParam,
            GlassFloatParamSpec(
                key = "edgeAlphaHidden",
                title = "隐藏层高光峰值",
                unit = "",
                range = 0f..1f,
                effectDescription = "控制按下/拖拽时内部强调色渲染层的亮边峰值，影响被指示器折射出的高光层次。",
                get = { it.dockGlass.edgeAlphaHidden },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeAlphaHidden = v)) },
            ),
            GlassFloatParamSpec(
                key = "edgeAlphaIndicator",
                title = "指示器高光峰值",
                unit = "",
                range = 0f..1f,
                effectDescription = "控制按下或拖动选中指示器胶囊时的边缘反光峰值亮度，调大时悬浮水滴轮廓更立体。",
                get = { it.dockGlass.edgeAlphaIndicator },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(edgeAlphaIndicator = v)) },
            ),
        ),
    ),
    GlassParamGroup(
        title = "指示器与阴影",
        includeChromaticSwitch = true,
        params = listOf(
            GlassFloatParamSpec(
                key = "indicatorLensWidthDp",
                title = "指示器透镜宽",
                unit = "dp",
                range = 0f..32f,
                effectDescription = "控制按下或拖动选中胶囊时，指示器左右边缘放大/折射图标的带宽（需 Android 13+）。",
                get = { it.dockGlass.indicatorLensWidthDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorLensWidthDp = v)) },
            ),
            GlassFloatParamSpec(
                key = "indicatorLensHeightDp",
                title = "指示器透镜高",
                unit = "dp",
                range = 0f..32f,
                effectDescription = "控制按下或拖动选中胶囊时，指示器上下边缘凸透镜折射拉伸幅度（需 Android 13+）。",
                get = { it.dockGlass.indicatorLensHeightDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorLensHeightDp = v)) },
            ),
            IndicatorSurfaceAlphaParam,
            GlassFloatParamSpec(
                key = "indicatorSurfacePressedAlpha",
                title = "指示器按压加深",
                unit = "",
                range = 0f..0.5f,
                effectDescription = "控制手指按住或拖动指示器时叠加的暗色加深层；调大后按压水滴会更明显地偏暗。",
                get = { it.dockGlass.indicatorSurfacePressedAlpha },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorSurfacePressedAlpha = v)) },
            ),
            GlassFloatParamSpec(
                key = "indicatorShadowRadiusDp",
                title = "投影半径",
                unit = "dp",
                range = 0f..32f,
                effectDescription = "控制按压/拖拽指示器胶囊向外扩散的外投影范围，调大后漂浮感更强。",
                get = { it.dockGlass.indicatorShadowRadiusDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorShadowRadiusDp = v)) },
            ),
            GlassFloatParamSpec(
                key = "indicatorShadowAlpha",
                title = "投影不透明度",
                unit = "",
                range = 0f..1f,
                effectDescription = "控制按压/拖拽指示器胶囊时外投影的深浅；0 无外投影，调大后悬浮立体感更明显。",
                get = { it.dockGlass.indicatorShadowAlpha },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorShadowAlpha = v)) },
            ),
            GlassFloatParamSpec(
                key = "indicatorInnerRadiusDp",
                title = "内阴影半径",
                unit = "dp",
                range = 0f..20f,
                effectDescription = "控制按压时指示器胶囊内侧边缘的内阴影扩散范围，营造内部凹陷/厚度光影。",
                get = { it.dockGlass.indicatorInnerRadiusDp },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorInnerRadiusDp = v)) },
            ),
            GlassFloatParamSpec(
                key = "indicatorInnerAlpha",
                title = "内阴影不透明度",
                unit = "",
                range = 0f..1f,
                effectDescription = "控制按压时指示器内阴影的深浅；调大后液态水滴内缘阴影边界更清晰。",
                get = { it.dockGlass.indicatorInnerAlpha },
                set = { t, v -> t.copy(dockGlass = t.dockGlass.copy(indicatorInnerAlpha = v)) },
            ),
        ),
    ),
)

private data class PreviewDockTab(val label: String, val icon: ImageVector)

private val PreviewTabs = listOf(
    PreviewDockTab("今日", Icons.Filled.Home),
    PreviewDockTab("课表", Icons.Filled.DateRange),
    PreviewDockTab("成绩", Icons.Filled.Star),
    PreviewDockTab("我的", Icons.Filled.Person),
)

/**
 * 底栏液态玻璃调节页：
 * - 简化版（`fullMode = false`）：面向常规外观设置，提供背景模糊（`blurDp`）、透镜折射宽（`lensWidthDp`）、
 *   透镜折射高（`lensHeightDp`）与基础条高光（`edgeAlphaBase`）4 项核心调节；
 * - 完整版（`fullMode = true`）：位于开发者模式，开放全部 21 项模糊、透镜、色散、高光与阴影调参。
 *
 * 两种版本均支持「拖动调节」（滑杆）与「数值输入」（预填当前项目生效值），并附顶部实时效果图与逐项效果说明。
 */
@Composable
fun DockGlassSettingsScreen(
    themeSettings: ThemeSettings,
    onThemeSettingsChange: (ThemeSettings) -> Unit,
    wallpaperPath: String? = null,
    wallpaperScrim: Float = 0f,
    wallpaperBlur: Float = 0f,
    initialMode: DockGlassInputMode = DockGlassInputMode.SLIDER,
    fullMode: Boolean = false,
    onBack: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var draft by remember { mutableStateOf(themeSettings) }
    var lastCommitted by remember { mutableStateOf(themeSettings) }
    var resetVersion by remember { mutableIntStateOf(0) }
    if (themeSettings != lastCommitted) {
        lastCommitted = themeSettings
        draft = themeSettings
        resetVersion++
    }

    fun normalize(settings: ThemeSettings): ThemeSettings = settings.copy(
        dockSurfaceAlphaLight = settings.dockSurfaceAlphaLight.coerceIn(0f, 0.8f),
        dockSurfaceAlphaDark = settings.dockSurfaceAlphaDark.coerceIn(0f, 0.8f),
        dockGlass = settings.dockGlass.sanitized(),
    )

    fun commit(updated: ThemeSettings) {
        val normalized = normalize(updated)
        draft = normalized
        lastCommitted = normalized
        onThemeSettingsChange(normalized)
    }

    val isDefault = if (fullMode) {
        draft.dockGlass == DefaultDockGlass &&
            draft.dockSurfaceAlphaLight == DefaultThemeSettings.dockSurfaceAlphaLight &&
            draft.dockSurfaceAlphaDark == DefaultThemeSettings.dockSurfaceAlphaDark
    } else {
        draft.dockGlass.blurDp == DefaultDockGlass.blurDp &&
            draft.dockGlass.lensWidthDp == DefaultDockGlass.lensWidthDp &&
            draft.dockGlass.lensHeightDp == DefaultDockGlass.lensHeightDp &&
            draft.dockGlass.edgeAlphaBase == DefaultDockGlass.edgeAlphaBase
    }

    val activeGroups = if (fullMode) FullDockGlassParamGroups else SimpleDockGlassParamGroups

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = if (fullMode) "底栏液态玻璃（完整调参）" else "底栏液态玻璃",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    GlassTopBarTextButton(
                        text = "恢复默认",
                        enabled = !isDefault,
                        onClick = {
                            val reset = if (fullMode) {
                                draft.copy(
                                    dockSurfaceAlphaLight = DefaultThemeSettings.dockSurfaceAlphaLight,
                                    dockSurfaceAlphaDark = DefaultThemeSettings.dockSurfaceAlphaDark,
                                    dockGlass = DockGlassSettings(),
                                )
                            } else {
                                draft.copy(
                                    dockGlass = draft.dockGlass.copy(
                                        blurDp = DefaultDockGlass.blurDp,
                                        lensWidthDp = DefaultDockGlass.lensWidthDp,
                                        lensHeightDp = DefaultDockGlass.lensHeightDp,
                                        edgeAlphaBase = DefaultDockGlass.edgeAlphaBase,
                                    ),
                                )
                            }
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
            val narrowPreviewHeight = (availableHeight * 0.34f).coerceIn(160.dp, 220.dp)

            val controlsContent: @Composable (Modifier) -> Unit = { contentModifier ->
                Column(
                    modifier = contentModifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ModeSelectorCard(
                        mode = mode,
                        onModeChange = { next ->
                            if (mode != next) {
                                mode = next
                                if (next == DockGlassInputMode.NUMERIC) {
                                    resetVersion++
                                }
                            }
                        },
                    )
                    activeGroups.forEach { group ->
                        GlassParamSection(
                            group = group,
                            mode = mode,
                            draft = draft,
                            resetVersion = resetVersion,
                            onDraftChange = { draft = normalize(it) },
                            onCommit = ::commit,
                        )
                    }
                    Text(
                        text = if (fullMode) {
                            "拖动滑杆可实时预览效果，数值输入支持精确填写；" +
                                "Android 13+ 完整支持模糊、透镜与色散，Android 12 支持模糊与蒙层。"
                        } else {
                            "此页面仅调整背景模糊、边缘透镜折射与基础细高光边，拉到 0 可完全清晰无扭曲无亮边；如需调整蒙层透明度、老设备底色、指示器与阴影等全部参数，请前往「关于与反馈」开启开发者模式。"
                        },
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
                        DockGlassPreview(
                            settings = draft,
                            wallpaperPath = wallpaperPath,
                            wallpaperScrim = wallpaperScrim,
                            wallpaperBlur = wallpaperBlur,
                            previewHeight = 260.dp,
                        )
                        Text(
                            text = "可直接点击或长按拖动预览底部栏的模糊、折射、高光与阴影效果。",
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
                        DockGlassPreview(
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
private fun DockGlassPreview(
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
    var selectedTab by remember { mutableIntStateOf(0) }
    val brand = LocalBrand.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(previewHeight)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
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
                                brand.accent.copy(alpha = 0.28f),
                                Color(0xFF4C8DF6).copy(alpha = 0.22f),
                                Color(0xFF34C759).copy(alpha = 0.22f),
                            ),
                        ),
                    )
                    drawCircle(
                        color = brand.accent.copy(alpha = 0.45f),
                        radius = size.minDimension * 0.32f,
                        center = Offset(size.width * 0.22f, size.height * 0.45f),
                    )
                    drawCircle(
                        color = Color(0xFFFF9F0A).copy(alpha = 0.42f),
                        radius = size.minDimension * 0.28f,
                        center = Offset(size.width * 0.56f, size.height * 0.72f),
                    )
                    drawCircle(
                        color = Color(0xFF5E5CE6).copy(alpha = 0.40f),
                        radius = size.minDimension * 0.30f,
                        center = Offset(size.width * 0.84f, size.height * 0.50f),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PreviewSampleTile("高等数学 · A302", Color(0xFF3B82F6).copy(alpha = 0.72f), Modifier.weight(1f))
                    PreviewSampleTile("程序设计 · 实验楼", Color(0xFF10B981).copy(alpha = 0.72f), Modifier.weight(1f))
                }
                Text(
                    text = "实时预览：调节下方参数可即时观察模糊、边缘折射与高光变化",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }
        }

        LiquidBottomTabs(
            selectedTabIndex = { selectedTab },
            onTabSelected = { selectedTab = it },
            backdrop = previewBackdrop,
            tabsCount = PreviewTabs.size,
            surfaceAlphaLight = settings.dockSurfaceAlphaLight,
            surfaceAlphaDark = settings.dockSurfaceAlphaDark,
            glassSettings = settings.dockGlass,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .testTag("dock-glass-preview"),
        ) {
            PreviewTabs.forEachIndexed { index, tab ->
                LiquidBottomTab(
                    index = index,
                    selected = index == selectedTab,
                ) {
                    DockTabContent(
                        label = tab.label,
                        icon = tab.icon,
                        selected = index == selectedTab,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewSampleTile(
    text: String,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ModeSelectorCard(
    mode: DockGlassInputMode,
    onModeChange: (DockGlassInputMode) -> Unit,
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
            Text("调节方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DockGlassInputMode.entries.forEach { item ->
                    val selected = mode == item
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) brand.accent else MaterialTheme.colorScheme.surfaceVariant)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onModeChange(item) },
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) brand.onBrand else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = if (mode == DockGlassInputMode.SLIDER) {
                    "拖动滑杆实时查看效果，松手自动保存。"
                } else {
                    "已填入当前生效数值，可直接修改精确参数。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GlassParamSection(
    group: GlassParamGroup,
    mode: DockGlassInputMode,
    draft: ThemeSettings,
    resetVersion: Int,
    onDraftChange: (ThemeSettings) -> Unit,
    onCommit: (ThemeSettings) -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            group.params.forEach { spec ->
                when (mode) {
                    DockGlassInputMode.SLIDER -> GlassSliderRow(
                        spec = spec,
                        value = spec.get(draft),
                        onDraftValue = { onDraftChange(spec.set(draft, it)) },
                        onCommitValue = { onCommit(spec.set(draft, it)) },
                    )
                    DockGlassInputMode.NUMERIC -> GlassNumericField(
                        spec = spec,
                        value = spec.get(draft),
                        resetVersion = resetVersion,
                        onValidValue = { onCommit(spec.set(draft, it)) },
                    )
                }
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
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassSliderRow(
    spec: GlassFloatParamSpec,
    value: Float,
    onDraftValue: (Float) -> Unit,
    onCommitValue: (Float) -> Unit,
) {
    val current = value.coerceIn(spec.range)
    var latestRounded by remember(current) { mutableFloatStateOf(current) }
    val unitSuffix = if (spec.unit.isEmpty()) "" else " ${spec.unit}"
    val scaleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = spec.title,
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
                val rounded = ((raw * 100f).roundToInt() / 100f).coerceIn(spec.range)
                latestRounded = rounded
                onDraftValue(rounded)
            },
            valueRange = spec.range,
            onValueChangeFinished = { onCommitValue(latestRounded) },
            modifier = Modifier.testTag("dock-glass-slider-${spec.key}"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatGlassFloat(spec.range.start),
                style = MaterialTheme.typography.labelSmall,
                color = scaleColor,
            )
            Text(
                text = "${formatGlassFloat(spec.range.endInclusive)}$unitSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = scaleColor,
            )
        }
        Text(
            text = spec.effectDescription,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GlassNumericField(
    spec: GlassFloatParamSpec,
    value: Float,
    resetVersion: Int,
    onValidValue: (Float) -> Unit,
) {
    var text by remember(spec.key, resetVersion) {
        mutableStateOf(formatGlassFloat(value.coerceIn(spec.range)))
    }
    var isError by remember(spec.key, resetVersion) { mutableStateOf(false) }

    val unitSuffix = if (spec.unit.isEmpty()) "" else " ${spec.unit}"
    val labelText = if (spec.unit.isEmpty()) spec.title else "${spec.title} (${spec.unit})"
    val minText = formatGlassFloat(spec.range.start)
    val maxText = formatGlassFloat(spec.range.endInclusive)
    val defaultText = formatGlassFloat(spec.get(DefaultThemeSettings))

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
                if (parsed != null && parsed.isFinite() && parsed in spec.range) {
                    isError = false
                    onValidValue(parsed)
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
                .testTag("dock-glass-input-${spec.key}"),
        )
        Text(
            text = spec.effectDescription,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun formatGlassFloat(value: Float): String {
    if (!value.isFinite()) return "0"
    val formatted = String.format(Locale.US, "%.3f", value)
        .trimEnd('0')
        .trimEnd('.')
    return if (formatted == "-0" || formatted.isEmpty()) "0" else formatted
}
