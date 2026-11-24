package com.gfs.iyango

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.liquidglass.GlassBackdropHost
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LocalGlassPalette
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidRadioButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidSlider
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import com.gfs.iyango.core.designsystem.theme.IYangoTheme
import com.gfs.iyango.feature.main.settings.toGlassPalette
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.domain.WidgetConfigPort
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Timetable
import com.gfs.iyango.core.model.WidgetConfig
import com.gfs.iyango.core.model.WidgetLayout
import com.gfs.iyango.platform.android.ScheduleWidgets
import com.gfs.iyango.platform.android.WakeUpCanvasRenderer
import com.gfs.iyango.platform.android.WidgetIntents
import com.gfs.iyango.platform.android.WidgetPresentation
import com.gfs.iyango.platform.android.WidgetRefreshAlarm
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 组件配置（F14，两阶段，对齐 WakeUp 改造方案第七章）：
 *
 * - 阶段一（首次放置，数据库尚无该 widgetId 记录）：课表快速绑定 + "直接进入详细样式设置"；
 * - 阶段二（从卡片"调整"图标进入）：顶部实时 Canvas 预览 + 底部全量样式分组设置。
 *
 * 取消或返回时保持 RESULT_CANCELED，宿主不会添加未配置组件；确认后持久化配置并立即重绘。
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject
    lateinit var timetableRepository: TimetableRepositoryPort

    @Inject
    lateinit var widgetConfig: WidgetConfigPort

    @Inject
    lateinit var effectiveSchedule: EffectiveSchedulePort

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var layout: WidgetLayout = WidgetLayout.PREVIEW_2X2

    /** 重新配置时带回既有选择；读取完成前不渲染表单，避免默认值覆盖。 */
    private val initialConfig = mutableStateOf<WidgetConfig?>(null)
    private val configLoaded = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val saving = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = WidgetIntents.widgetId(intent)
        val ownedLayout = WidgetIntents.layout(this, widgetId)
        if (ownedLayout == null) {
            finish()
            return
        }
        layout = ownedLayout
        lifecycleScope.launch {
            try {
                initialConfig.value = widgetConfig.get(widgetId)?.normalized()
                configLoaded.value = true
            } catch (_: Exception) {
                error.value = "读取组件设置失败，请返回后重试。"
            }
        }
        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val settings by themeViewModel.settings.collectAsStateWithLifecycle()
            IYangoTheme(
                darkTheme = settings.themeMode == com.gfs.iyango.core.domain.ThemeMode.DARK ||
                    (settings.themeMode == com.gfs.iyango.core.domain.ThemeMode.SYSTEM &&
                        androidx.compose.foundation.isSystemInDarkTheme()),
                accentHex = settings.accentHex,
                tintBackground = settings.tintBackground,
            ) {
                val widgetGlassPalette = remember(settings.globalGlass) { settings.globalGlass.toGlassPalette() }
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalGlassPalette provides widgetGlassPalette,
                ) {
                    GlassBackdropHost(modifier = Modifier.fillMaxSize()) {
                    if (configLoaded.value) {
                        // 阶段一：数据库尚无该 widgetId 记录 → 课表快速绑定。
                        if (initialConfig.value == null) {
                            QuickBindScreen(
                                timetableRepository = timetableRepository,
                                error = error.value,
                                saving = saving.value,
                                onCancel = ::cancel,
                                onBind = ::quickBind,
                                onAdvanced = { initialConfig.value = WidgetConfig(widgetId = widgetId, layout = layout) },
                            )
                        } else {
                            WidgetStyleScreen(
                                initial = initialConfig.value!!,
                                timetables = timetableRepository,
                                effectiveSchedule = effectiveSchedule,
                                error = error.value,
                                saving = saving.value,
                                onCancel = ::cancel,
                                onSave = ::save,
                            )
                        }
                    } else {
                        Text(error.value ?: "正在读取组件设置…", modifier = Modifier.padding(32.dp))
                    }
                    }
                }
            }
        }
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /** 阶段一：点击某张课表即完成绑定并立即返回刷新桌面卡片（文档 7.1）。 */
    private fun quickBind(timetableId: Long) {
        val base = initialConfig.value ?: WidgetConfig(widgetId = widgetId, layout = layout)
        save(base.copy(timetableId = timetableId))
    }

    private fun save(config: WidgetConfig) {
        if (saving.value) return
        if (WidgetIntents.layout(this, widgetId) != layout) { cancel(); return }
        saving.value = true
        error.value = null
        lifecycleScope.launch {
            try {
                val published = ScheduleWidgets.updateOrThrow(applicationContext, widgetId, layout) {
                    val normalized = config.copy(widgetId = widgetId, layout = layout, updatedAt = System.currentTimeMillis()).normalized()
                    widgetConfig.save(normalized)
                    if (normalized.backgroundUri.isBlank()) {
                        runCatching { File(applicationContext.filesDir, "widget_${widgetId}_bg.jpg").delete() }
                    }
                }
                check(published) { "Widget was deleted while saving" }
                WidgetRefreshAlarm.schedule(applicationContext)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            } catch (_: Exception) {
                error.value = "保存失败，请重试。"
                setResult(RESULT_CANCELED)
            } finally {
                saving.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickBindScreen(
    timetableRepository: TimetableRepositoryPort,
    error: String?,
    saving: Boolean,
    onCancel: () -> Unit,
    onBind: (Long) -> Unit,
    onAdvanced: () -> Unit,
) {
    val timetables by timetableRepository.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList<Timetable>())
    var selectedId by remember { mutableStateOf(0L) }
    GlassScaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            GlassTopBar(
                title = "选择课表",
                actions = {
                    GlassTopBarTextButton(
                        text = if (saving) "正在保存…" else "保存",
                        onClick = { if (!saving) onBind(selectedId) },
                    )
                },
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            GlassSurface(
                shape = RoundedCornerShape(16.dp),
                material = GlassMaterial.CONTENT,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("绑定课表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "选择要在桌面卡片显示的课表；之后可从卡片右上角进入详细样式设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TimetableOption(
                        title = "跟随当前课表",
                        hint = "自动选表规则生效时按当天有效课表显示",
                        selected = selectedId == 0L,
                        onClick = { selectedId = 0L },
                    )
                    timetables.forEach { table ->
                        TimetableOption(
                            title = table.name,
                            hint = if (table.autoEnabled) "自动切换已开启" else "固定课表",
                            selected = selectedId == table.id,
                            onClick = { selectedId = table.id },
                        )
                    }
                    if (timetables.isEmpty()) {
                        Text(
                            "还没有课表；组件会显示空状态，创建课表后自动更新。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onAdvanced) {
                        Text("直接进入详细样式设置 →")
                    }
                }
            }
        }
    }
}

/** 预览样本课程（无真实排课时兜底，保证任意开关变化都有可见反馈）。 */
private fun sampleOccurrences(): List<CourseOccurrence> = listOf(
    CourseOccurrence(
        "preview-1", Course(name = "高等数学", teacher = "张老师", classroom = "A101", color = "#3E7BFA", startSection = 1, endSection = 2),
        LocalDate.now(), 480, 570, false,
    ),
    CourseOccurrence(
        "preview-2", Course(name = "大学英语", teacher = "李老师", classroom = "B203", color = "#2E9E5B", startSection = 3, endSection = 4),
        LocalDate.now(), 590, 680, false,
    ),
    CourseOccurrence(
        "preview-3", Course(name = "数据结构", teacher = "王老师", classroom = "C305", color = "#D9534F", startSection = 6, endSection = 7),
        LocalDate.now(), 840, 930, false,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetStyleScreen(
    initial: WidgetConfig,
    timetables: TimetableRepositoryPort,
    effectiveSchedule: EffectiveSchedulePort,
    error: String?,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: (WidgetConfig) -> Unit,
) {
    var config by remember { mutableStateOf(initial) }
    fun update(transform: (WidgetConfig) -> WidgetConfig) { config = transform(config) }
    val activityContext = androidx.compose.ui.platform.LocalContext.current

    val timetablesList by timetables.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList<Timetable>())
    // 顶部实时预览数据：绑定课表当日真实排课，空时回退样本（文档 7.2）。
    val occurrences by produceState(initialValue = sampleOccurrences(), config.timetableId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val tableId = config.timetableId
                val list = if (tableId > 0) {
                    effectiveSchedule.resolveRange(tableId, LocalDate.now()..LocalDate.now())
                } else {
                    effectiveSchedule.resolveDay(LocalDate.now()).occurrences
                }
                list.ifEmpty { sampleOccurrences() }
            }.getOrDefault(sampleOccurrences())
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 文档 5.3：拷贝至应用私有目录，渲染时按目标尺寸降采样。
        coroutineScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(activityContext.filesDir, "widget_${initial.widgetId}_bg.jpg")
                    activityContext.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                        target.absolutePath
                    }
                }.getOrNull()
            }
            if (!savedPath.isNullOrBlank()) {
                update { it.copy(backgroundUri = savedPath) }
            }
        }
    }

    GlassScaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            GlassTopBar(
                title = "组件设置",
                actions = {
                    GlassTopBarTextButton(
                        text = if (saving) "正在保存…" else "保存",
                        onClick = { if (!saving) onSave(config) },
                    )
                },
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            StylePreviewCard(config, occurrences)
            SectionCard("绑定课表") {
                TimetableOption(
                    title = "跟随当前课表",
                    hint = "自动选表规则生效时按当天有效课表显示",
                    selected = config.timetableId == 0L,
                    onClick = { update { it.copy(timetableId = 0L) } },
                )
                timetablesList.forEach { table ->
                    TimetableOption(
                        title = table.name,
                        hint = if (table.autoEnabled) "自动切换已开启" else "固定课表",
                        selected = config.timetableId == table.id,
                        onClick = { update { it.copy(timetableId = table.id) } },
                    )
                }
            }
            SectionCard("背景设置") {
                SwitchRow("显示背景", config.showBg) { value -> update { it.copy(showBg = value) } }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        0xD8FFFFFF.toInt() to "85% 白",
                        0xB3FFFFFF.toInt() to "轻玻璃",
                        0x80FFFFFF.toInt() to "柔雾",
                        0xB31C1C1E.toInt() to "深色玻璃",
                        0x00000000 to "全透明",
                    ).forEach { (color, label) ->
                        LiquidFilterChip(
                            selected = config.bgColor == color,
                            onClick = { update { it.copy(bgColor = color) } },
                            label = { Text(label) },
                        )
                    }
                }
                SwitchRow("使用自定义背景图", config.backgroundUri.isNotBlank(), onChange = { use ->
                    if (use) {
                        pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        update { it.copy(backgroundUri = "") }
                    }
                })
                if (config.backgroundUri.isNotBlank()) {
                    Text("已选择背景图：${File(config.backgroundUri).name}", style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionCard("顶栏与按钮") {
                SwitchRow("显示顶栏", config.showHeaderArea) { value -> update { it.copy(showHeaderArea = value) } }
                SwitchRow("显示日期", config.showDate) { value -> update { it.copy(showDate = value) } }
                SwitchRow("显示按钮", config.showButton) { value -> update { it.copy(showButton = value) } }
                if (!config.showButton) {
                    Text(
                        "按钮已隐藏；点击卡片顶栏或列表将直接进入设置页。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val isClassicOrWeek = config.layout == WidgetLayout.TODAY_2X4 || config.layout == WidgetLayout.WEEK_4X4
            SectionCard("尺寸与圆角") {
                SliderRow("顶栏字号", config.headerTextSizeSp.toFloat(), 10f..20f) { value -> update { it.copy(headerTextSizeSp = value.roundToInt()) } }
                SliderRow("内容字号", config.textSizeSp.toFloat(), 10f..18f) { value -> update { it.copy(textSizeSp = value.roundToInt()) } }
                if (isClassicOrWeek) {
                    SliderRow("课程行高", config.itemHeightDp.toFloat(), 36f..96f) { value -> update { it.copy(itemHeightDp = value.roundToInt()) } }
                    SliderRow("圆角半径", config.radiusDp.toFloat(), 0f..24f) { value -> update { it.copy(radiusDp = value.roundToInt()) } }
                    SliderRow("课程块不透明度", config.itemAlpha.toFloat(), 0f..100f) { value -> update { it.copy(itemAlpha = value.roundToInt()) } }
                }
                if (config.layout == WidgetLayout.WEEK_4X4) {
                    SliderRow("非本周课程透明度", config.otherWeekCourseAlpha.toFloat(), 0f..100f) { value -> update { it.copy(otherWeekCourseAlpha = value.roundToInt()) } }
                }
            }
            SectionCard("颜色与显示项") {
                ColorRow("正文字色", config.textColor) { value -> update { it.copy(textColor = value) } }
                SwitchRow("正文字色随深色模式反色", config.textColorCompose) { value -> update { it.copy(textColorCompose = value) } }
                SwitchRow("显示课程色标", config.showColor) { value -> update { it.copy(showColor = value) } }
                SwitchRow("显示地点", config.showLocation) { value -> update { it.copy(showLocation = value) } }
                SwitchRow("地点 @ 前缀", config.showRoomPrefix) { value -> update { it.copy(showRoomPrefix = value) } }
                SwitchRow("显示教师", config.showTeacher) { value -> update { it.copy(showTeacher = value) } }
                if (isClassicOrWeek) {
                    ColorRow("课程文字色", config.courseTextColor) { value -> update { it.copy(courseTextColor = value) } }
                    ColorRow("边框色", config.strokeColor) { value -> update { it.copy(strokeColor = value) } }
                    SwitchRow("边框色随深色模式反色", config.strokeColorCompose) { value -> update { it.copy(strokeColorCompose = value) } }
                    SwitchRow("显示时间", config.showTime) { value -> update { it.copy(showTime = value) } }
                    SwitchRow("课程块水平居中", config.itemCenterHorizontal) { value -> update { it.copy(itemCenterHorizontal = value) } }
                    SwitchRow("课程块垂直居中", config.itemCenterVertical) { value -> update { it.copy(itemCenterVertical = value) } }
                }
            }
            if (config.layout == WidgetLayout.WEEK_4X4) {
                SectionCard("周视图") {
                    SwitchRow("显示周六", config.showSat) { value -> update { it.copy(showSat = value) } }
                    SwitchRow("显示周日", config.showSun) { value -> update { it.copy(showSun = value) } }
                    SwitchRow("显示非本周课程", config.showOtherWeekCourse) { value -> update { it.copy(showOtherWeekCourse = value) } }
                    SwitchRow("显示节次时间条", config.showTimeBar) { value -> update { it.copy(showTimeBar = value) } }
                    SwitchRow("显示网格线", config.showGrid) { value -> update { it.copy(showGrid = value) } }
                    SwitchRow("网格线用虚线", config.useDottedLine) { value -> update { it.copy(useDottedLine = value) } }
                }
            }
            Text(
                "背景透明度只影响底色，文字保持清晰；玻璃风格为静态半透明效果。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 兼容旧调用路径的文件目录桥（预览位图背景复制目标）。 */

@Composable
private fun StylePreviewCard(config: WidgetConfig, occurrences: List<CourseOccurrence>) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 320.dp.toPx() }.roundToInt()
    val isClassicOrWeek = config.layout == WidgetLayout.TODAY_2X4 || config.layout == WidgetLayout.WEEK_4X4
    val bitmaps by produceState<List<Bitmap>>(emptyList(), config, occurrences) {
        value = if (!isClassicOrWeek) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    if (config.layout == WidgetLayout.WEEK_4X4) {
                        listOf(
                            WakeUpCanvasRenderer.renderWeekGridBitmap(
                                widthPx = widthPx,
                                occurrences = occurrences,
                                density = density,
                                itemHeightDp = config.itemHeightDp,
                                radiusDp = config.radiusDp,
                                itemAlpha = config.itemAlpha,
                                otherWeekCourseAlpha = config.otherWeekCourseAlpha,
                                textSizeSp = config.textSizeSp,
                                courseTextColor = config.courseTextColor,
                                strokeColor = config.strokeColor,
                                showSat = config.showSat,
                                showSun = config.showSun,
                                showLocation = config.showLocation,
                                showRoomPrefix = config.showRoomPrefix,
                                showTeacher = config.showTeacher,
                                showTime = config.showTime,
                                showGrid = config.showGrid,
                                useDottedLine = config.useDottedLine,
                                showOtherWeekCourse = config.showOtherWeekCourse,
                            ),
                        )
                    } else {
                        occurrences.take(3).map { occurrence ->
                            WakeUpCanvasRenderer.renderClassicCourseBitmap(
                                widthPx = widthPx,
                                heightPx = (config.itemHeightDp * density).roundToInt(),
                                density = density,
                                courseHex = occurrence.course.color,
                                name = occurrence.course.name,
                                teacher = occurrence.course.teacher,
                                classroom = occurrence.course.classroom,
                                sectionLabel = "第${occurrence.course.startSection}-${occurrence.course.endSection}节",
                                timeText = ScheduleWidgets.timeRange(occurrence),
                                radiusDp = config.radiusDp,
                                itemAlpha = config.itemAlpha,
                                strokeColor = config.strokeColor,
                                showColor = config.showColor,
                                courseTextColor = config.courseTextColor,
                                showTime = config.showTime,
                                showLocation = config.showLocation,
                                showRoomPrefix = config.showRoomPrefix,
                                showTeacher = config.showTeacher,
                                itemCenterHorizontal = config.itemCenterHorizontal,
                                itemCenterVertical = config.itemCenterVertical,
                                textSizeSp = config.textSizeSp,
                            )
                        }
                    }
                }.getOrDefault(emptyList())
            }
        }
    }
    val today = remember { LocalDate.now() }
    val weekdayLabels = remember { listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日") }
    val todayLabel = "${today.monthValue}月${today.dayOfMonth}日 ${weekdayLabels[(today.dayOfWeek.value - 1).coerceIn(0, 6)]}"
    val isDark = isSystemInDarkTheme()
    val cardBgColor = if (config.showBg) Color(config.bgColor) else Color(0x00000000)
    val isLightSurface = config.showBg && cardBgColor.alpha >= 0.35f &&
        (cardBgColor.red * 0.299f + cardBgColor.green * 0.587f + cardBgColor.blue * 0.114f) > 0.5f
    val defaultPrimaryText = if (isLightSurface || !isDark) Color(0xFF1B1B1F) else Color(0xFFF2F2F5)
    val defaultSecondaryText = if (isLightSurface || !isDark) Color(0xFF4D525B) else Color(0xFFB8BAC0)
    val accentColor = if (isLightSurface || !isDark) Color(0xFF174BB5) else Color(0xFFA8C8FF)
    val primaryTextColor = if (config.textColor != 0xFF000000.toInt()) Color(config.textColor) else defaultPrimaryText
    val secondaryTextColor = if (config.textColor != 0xFF000000.toInt()) Color(config.textColor).copy(alpha = 0.78f) else defaultSecondaryText
    val previewCount = occurrences.size.coerceAtMost(4)

    Surface(Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("实时预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // 模拟桌面壁纸底纹，便于直观观察透明度与玻璃底色变化（借鉴拾光课程表预览区）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF1F2A44), Color(0xFF2D436B), Color(0xFF1E3A5F)),
                        ),
                    )
                    .padding(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBgColor)
                        .border(0.75.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (config.showHeaderArea &&
                        config.layout != WidgetLayout.NEXT_PILL_2X1 &&
                        config.layout != WidgetLayout.EXAM_COUNTDOWN_2X2
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (config.showDate) {
                                    Text(
                                        text = todayLabel,
                                        fontSize = config.headerTextSizeSp.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryTextColor,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    text = "当前课表 · 今日${previewCount}节",
                                    fontSize = 10.sp,
                                    color = secondaryTextColor,
                                    maxLines = 1,
                                )
                            }
                            if (config.showButton) {
                                Text(
                                    text = "⚙  ‹  今  ›",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryTextColor,
                                )
                            }
                        }
                    }
                    when (config.layout) {
                        WidgetLayout.TODAY_4X4 -> {
                            val leftList = occurrences.take(2)
                            val rightFallback = remember(today) {
                                listOf(
                                    CourseOccurrence(
                                        "preview-next-1",
                                        Course(id = 999L, name = "计算机网络", teacher = "陈老师", classroom = "D402", color = "#8E6CEF", startSection = 3, endSection = 4),
                                        today.plusDays(1),
                                        600,
                                        690,
                                        false,
                                    ),
                                )
                            }
                            val rightList = occurrences.drop(2).take(2).ifEmpty { rightFallback }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            WidgetPresentation.columnHeaderTitle(today, today),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryTextColor,
                                        )
                                        Text("共 ${leftList.size} 节", fontSize = 9.5.sp, color = secondaryTextColor)
                                    }
                                    leftList.forEach { item ->
                                        ModernPreviewRow(
                                            occurrence = item,
                                            config = config,
                                            primaryTextColor = primaryTextColor,
                                            secondaryTextColor = secondaryTextColor,
                                            accentColor = accentColor,
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            WidgetPresentation.columnHeaderTitle(today.plusDays(1), today),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryTextColor,
                                        )
                                        Text("预告", fontSize = 9.5.sp, color = secondaryTextColor)
                                    }
                                    rightList.forEach { item ->
                                        ModernPreviewRow(
                                            occurrence = item,
                                            config = config,
                                            primaryTextColor = primaryTextColor,
                                            secondaryTextColor = secondaryTextColor,
                                            accentColor = accentColor,
                                        )
                                    }
                                }
                            }
                        }
                        WidgetLayout.PREVIEW_2X2, WidgetLayout.RULER_4X4 -> {
                            val sep = if (config.layout == WidgetLayout.RULER_4X4) "–" else " - "
                            occurrences.take(3).forEach { item ->
                                ModernPreviewRow(
                                    occurrence = item,
                                    config = config,
                                    separator = sep,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    accentColor = accentColor,
                                )
                            }
                        }
                        WidgetLayout.NEXT_PILL_2X1 -> {
                            val first = occurrences.firstOrNull()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (config.showColor) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(first?.let { Color(WakeUpCanvasRenderer.courseColor(it.course.color)) } ?: accentColor),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = first?.course?.name ?: "高等数学",
                                        fontSize = config.textSizeSp.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryTextColor,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = first?.let { "${it.course.classroom.ifBlank { "A302" }} · ${ScheduleWidgets.timeRange(it, "-")}" }
                                            ?: "明理楼 A302 · 08:00-09:40",
                                        fontSize = 11.sp,
                                        color = secondaryTextColor,
                                        maxLines = 1,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(accentColor.copy(alpha = 0.16f))
                                        .padding(horizontal = 9.dp, vertical = 4.dp),
                                ) {
                                    Text("15分后", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accentColor)
                                }
                            }
                        }
                        WidgetLayout.EXAM_COUNTDOWN_2X2 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("考试倒计时", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryTextColor)
                                Text("共 2 场", fontSize = 10.sp, color = secondaryTextColor)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x14174BB5))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("高等数学（上）", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = primaryTextColor)
                                    Text("3天", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                                }
                                Text("11/24 09:00-11:00 · A302 · 12号座", fontSize = 10.sp, color = secondaryTextColor)
                            }
                        }
                        else -> {
                            bitmaps.forEach { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(androidx.compose.ui.platform.LocalDensity.current) {
                                            (bitmap.height / density).dp
                                        }),
                                    contentScale = ContentScale.FillWidth,
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
private fun ModernPreviewRow(
    occurrence: CourseOccurrence,
    config: WidgetConfig,
    separator: String = " - ",
    primaryTextColor: Color = Color(0xFF1B1B1F),
    secondaryTextColor: Color = Color(0xFF4D525B),
    accentColor: Color = Color(0xFF174BB5),
) {
    val barColor = Color(WakeUpCanvasRenderer.courseColor(occurrence.course.color))
    val detailSize = (config.textSizeSp - 2).coerceAtLeast(9).sp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x14174BB5))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (config.showColor) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor),
            )
            Spacer(Modifier.width(7.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = occurrence.course.name,
                    modifier = Modifier.weight(1f),
                    fontSize = config.textSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${occurrence.course.startSection}-${occurrence.course.endSection}节",
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x141B1B1F))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    fontSize = 9.sp,
                    color = secondaryTextColor,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ScheduleWidgets.timeRange(occurrence, separator),
                    fontSize = detailSize,
                    color = accentColor,
                    maxLines = 1,
                )
                if (config.showLocation && occurrence.course.classroom.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (config.showRoomPrefix) "@${occurrence.course.classroom}" else occurrence.course.classroom,
                        fontSize = detailSize,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (config.showTeacher && occurrence.course.teacher.isNotBlank()) {
                Text(
                    text = WidgetPresentation.compactTeachers(occurrence.course.teacher),
                    fontSize = (config.textSizeSp - 3).coerceAtLeast(8).sp,
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        LiquidToggle(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Text("$label：${local.roundToInt()}")
        LiquidSlider(value = local, valueRange = range, onValueChange = { local = it }, onValueChangeFinished = { onCommit(local) })
    }
}

@Composable
private fun ColorRow(label: String, current: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.weight(1f))
        listOf(
            0xFF000000.toInt() to "黑",
            0xFFFFFFFF.toInt() to "白",
            0xFF3E7BFA.toInt() to "蓝",
            0xFF2E9E5B.toInt() to "绿",
            0xFFD9534F.toInt() to "红",
        ).forEach { (color, name) ->
            LiquidFilterChip(
                selected = current == color,
                onClick = { onChange(color) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun TimetableOption(
    title: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidRadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
