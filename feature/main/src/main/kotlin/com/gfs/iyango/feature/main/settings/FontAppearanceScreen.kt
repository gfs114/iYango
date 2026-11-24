@file:OptIn(ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.component.SettingSliderRow
import com.gfs.iyango.core.domain.AppSettingsPort
import com.gfs.iyango.core.domain.CustomFontStore
import com.gfs.iyango.core.model.AppPreferences
import com.gfs.iyango.core.model.GrayTextMode
import com.gfs.iyango.feature.main.isDarkTheme
import com.gfs.iyango.feature.main.parseCustomHexColor
import com.gfs.iyango.feature.main.resolveGrayTextColors
import com.gfs.iyango.feature.main.textTertiaryColor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class FontAppearanceViewModel @Inject constructor(
    private val appSettings: AppSettingsPort,
    private val customFontStore: CustomFontStore,
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = appSettings.appPreferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun importFont(sourceUri: String) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                val imported = customFontStore.importFromUri(sourceUri)
                if (imported == null) {
                    _message.value = "字体导入失败，请确认文件为有效的 TTF/OTF 字体（30 MB 以内）"
                    return@launch
                }
                val oldPath = preferences.value.customFontPath
                appSettings.transformAppPreferences {
                    it.copy(
                        customFontPath = imported.relativePath,
                        customFontName = imported.displayName,
                    ).sanitized()
                }
                if (oldPath.isNotBlank() && oldPath != imported.relativePath) {
                    customFontStore.delete(oldPath)
                }
                _message.value = "已应用字体：${imported.displayName}"
            } finally {
                _importing.value = false
            }
        }
    }

    fun resetFont() {
        viewModelScope.launch {
            val oldPath = preferences.value.customFontPath
            appSettings.transformAppPreferences {
                it.copy(customFontPath = "", customFontName = "").sanitized()
            }
            if (oldPath.isNotBlank()) {
                customFontStore.delete(oldPath)
            }
            _message.value = "已恢复系统默认字体"
        }
    }

    fun setFontScaleFactor(scale: Float) {
        viewModelScope.launch {
            appSettings.transformAppPreferences { it.copy(fontScaleFactor = scale).sanitized() }
        }
    }

    fun setGrayTextMode(mode: GrayTextMode) {
        viewModelScope.launch {
            appSettings.transformAppPreferences { it.copy(grayTextMode = mode).sanitized() }
        }
    }

    fun setGrayTextCustomHex(hex: String) {
        viewModelScope.launch {
            val clean = hex.trim().uppercase().let { if (it.startsWith("#")) it else "#$it" }
            val parsed = parseCustomHexColor(clean)
            if (parsed == null) {
                _message.value = "请输入合法的 6 位十六进制色值（例如 #3A434D）"
                return@launch
            }
            appSettings.transformAppPreferences {
                it.copy(
                    grayTextMode = GrayTextMode.CUSTOM,
                    grayTextCustomHex = clean,
                ).sanitized()
            }
            _message.value = "已应用自定义灰字颜色 $clean"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

@Composable
fun FontAppearanceScreen(
    onBack: () -> Unit,
    viewModel: FontAppearanceViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFont(uri.toString())
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "字体与可读性",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FontAndGrayPreviewCard(prefs = prefs)

            SectionCard(title = "全局自定义字体") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "当前字体：${prefs.customFontName.ifBlank { "系统默认" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LiquidButton(
                            enabled = !importing,
                            onClick = {
                                fontPicker.launch(
                                    arrayOf(
                                        "font/*",
                                        "application/x-font-ttf",
                                        "application/x-font-opentype",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                        ) {
                            Text(if (importing) "导入中..." else "导入字体 (.ttf / .otf)")
                        }
                        if (prefs.customFontPath.isNotBlank()) {
                            LiquidOutlinedButton(
                                enabled = !importing,
                                onClick = viewModel::resetFont,
                            ) {
                                Text("恢复系统默认")
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    var scaleDraft by remember(prefs.fontScaleFactor) {
                        mutableFloatStateOf(prefs.fontScaleFactor.coerceIn(0.85f, 1.30f))
                    }
                    SettingSliderRow(
                        title = "全局字号缩放",
                        value = scaleDraft,
                        range = 0.85f..1.30f,
                        valueLabel = "${(scaleDraft * 100f).roundToInt()}%",
                        onValueChange = { scaleDraft = it },
                        onValueChangeFinished = { viewModel.setFontScaleFactor(scaleDraft) },
                    )
                }
            }

            GrayTextModeSection(
                mode = prefs.grayTextMode,
                customHex = prefs.grayTextCustomHex,
                onSelectMode = viewModel::setGrayTextMode,
                onApplyCustomHex = viewModel::setGrayTextCustomHex,
            )
        }
    }
}

@Composable
private fun FontAndGrayPreviewCard(prefs: AppPreferences) {
    val dark = isDarkTheme()
    val (secOverride, terOverride) = remember(prefs.grayTextMode, prefs.grayTextCustomHex, dark) {
        resolveGrayTextColors(prefs.grayTextMode, prefs.grayTextCustomHex, dark)
    }
    val secondaryColor = secOverride ?: MaterialTheme.colorScheme.onSurfaceVariant
    val tertiaryColor = terOverride ?: textTertiaryColor()

    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("font_gray_preview_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "实时排版预览",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "高等数学 · Linear Algebra · 08:00–09:40",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "二级说明文字：@明理楼 A101 · 陈老师 · 第 1–16 周",
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryColor,
            )
            Text(
                text = "三级辅助文字：字号倍率 ${(prefs.fontScaleFactor * 100f).roundToInt()}% · 可在壁纸上增强对比",
                style = MaterialTheme.typography.bodySmall,
                color = tertiaryColor,
            )
        }
    }
}

@Composable
private fun GrayTextModeSection(
    mode: GrayTextMode,
    customHex: String,
    onSelectMode: (GrayTextMode) -> Unit,
    onApplyCustomHex: (String) -> Unit,
) {
    val modeLabels = listOf(
        GrayTextMode.DEFAULT to "默认",
        GrayTextMode.AUTO_ENHANCED to "自动增强",
        GrayTextMode.HIGH_CONTRAST_DARK to "纯黑",
        GrayTextMode.HIGH_CONTRAST_LIGHT to "纯白",
        GrayTextMode.CUSTOM to "自定义",
    )
    var hexInput by remember(customHex) { mutableStateOf(customHex.ifBlank { "#3A434D" }) }
    val presetHexes = listOf("#1A1D1C", "#3A434D", "#6D7781", "#D0D7DE", "#FFFFFF")

    SectionCard(title = "灰字可读性增强") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                modeLabels.forEach { (itemMode, label) ->
                    LiquidFilterChip(
                        selected = mode == itemMode,
                        onClick = { onSelectMode(itemMode) },
                        label = { Text(label) },
                    )
                }
            }

            if (mode == GrayTextMode.CUSTOM) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    presetHexes.forEach { hex ->
                        val color = parseCustomHexColor(hex) ?: MaterialTheme.colorScheme.onSurfaceVariant
                        val selected = customHex.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selected) 2.dp else 0.8.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    hexInput = hex
                                    onApplyCustomHex(hex)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "已选色值",
                                    tint = if (color.luminance() > 0.55f) Color.Black else Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiquidOutlinedTextField(
                        value = hexInput,
                        onValueChange = { hexInput = it },
                        singleLine = true,
                        label = { Text("自定义 Hex (#RRGGBB)") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onApplyCustomHex(hexInput) }) {
                        Text("应用")
                    }
                }
            }
        }
    }
}
