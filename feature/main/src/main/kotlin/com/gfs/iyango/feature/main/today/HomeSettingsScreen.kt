package com.gfs.iyango.feature.main.today

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.*
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidCheckbox
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.model.*
import com.gfs.iyango.feature.main.EditorDiscardBackHandler
import com.gfs.iyango.feature.main.backPreviewLayer
import com.gfs.iyango.feature.main.rememberBackPreviewState
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HomeSettingsScreen(
    onBack: () -> Unit,
    viewModel: HomeSettingsViewModel = hiltViewModel(),
    todayViewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today by todayViewModel.state.collectAsStateWithLifecycle()
    val display by todayViewModel.displaySettings.collectAsStateWithLifecycle()
    var leaveDialog by remember { mutableStateOf(false) }
    val backPreview = rememberBackPreviewState()
    fun requestBack() { if (!state.saving) { if (state.dirty) leaveDialog = true else onBack() } }
    EditorDiscardBackHandler(backPreview, enabled = state.dirty || state.saving) { requestBack() }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    HomeSettingsContent(
        state = state, modifier = Modifier.backPreviewLayer(backPreview, cardStyle = true),
        onBack = ::requestBack, onSave = viewModel::save, onRestoreDefaults = viewModel::restoreDefaults,
        onToggleModule = viewModel::toggleModule, onMoveModule = viewModel::moveModule,
        onToggleShortcut = viewModel::toggleShortcut, onMoveShortcut = viewModel::moveShortcut,
    ) {
        val ready = today as? TodayUiState.Ready
        val preview = ready ?: remember {
            val date = LocalDate.now()
            TodayUiState.Ready(Timetable(name = "示例课表"), null, 0, date, emptyList(), date.plusDays(1),
                emptyList(), emptySet(), YearMonth.from(date), emptyList(), emptyList())
        }
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            TodayReadyContent(preview, 16.dp, 0.dp, display.paletteLight, display.paletteDark,
                {}, {}, {}, {}, {}, home = state.draft, animateLayoutChanges = true)
        }
    }
    if (leaveDialog) LiquidAlertDialog(
        onDismissRequest = { if (!state.saving) leaveDialog = false },
        title = { Text("保存首页设置？") }, text = { Text("当前修改尚未保存。") },
        confirmButton = { TextButton(onClick = { leaveDialog = false; viewModel.save() }, enabled = !state.saving) { Text("保存") } },
        dismissButton = { Row {
            TextButton(onClick = { leaveDialog = false }, enabled = !state.saving) { Text("继续编辑") }
            TextButton(onClick = onBack, enabled = !state.saving) { Text("放弃修改") }
        } },
    )
}

@Composable
internal fun HomeSettingsContent(
    state: HomeEditorState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onRestoreDefaults: () -> Unit,
    onToggleModule: (String) -> Unit,
    onMoveModule: (String, Int) -> Unit,
    onToggleShortcut: (String) -> Unit,
    onMoveShortcut: (String, Int) -> Unit,
    preview: @Composable () -> Unit,
) {
    val list = rememberLazyListState()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val interactive = state.loaded && !state.saving && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val drag = rememberHomeOrderDrag(list, interactive)
    val visibleModules = remember(state.draft.moduleOrder) {
        state.draft.moduleOrder.filter { it != HomeModule.EXAMS.id }
    }
    val visibleQuickActions = remember(state.draft.quickActions) {
        state.draft.quickActions.filter { it != HomeShortcut.EXAMS.id }
    }
    GlassScaffold(modifier = modifier.fillMaxSize(), topBar = {
        GlassTopBar(title = "首页设置", navigationIcon = {
            GlassTopBarIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "取消") }
        }, actions = { TextButton(onClick = onSave, enabled = state.loaded && !state.saving) { Text(if (state.saving) "保存中" else "保存") } })
    }) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (!state.loaded) item { LoadingState() }
                else {
                    item { SectionHeader(title = "模块与顺序") }
                    itemsIndexed(visibleModules, key = { _, id -> "module:$id" }) { index, id ->
                        HomeOrderRow(drag, "module:$id", homeModuleTitle(id), index, visibleModules.size,
                            selected = id !in state.draft.hiddenModules, enabled = interactive,
                            onToggle = { onToggleModule(id) }, onMove = { onMoveModule(id, it) })
                    }
                    item { SectionHeader(title = "快捷入口（${visibleQuickActions.size}/8）") }
                    itemsIndexed(visibleQuickActions, key = { _, id -> "shortcut:$id" }) { index, id ->
                        HomeOrderRow(drag, "shortcut:$id", homeShortcutTitle(id), index, visibleQuickActions.size, true, interactive,
                            onToggle = { onToggleShortcut(id) }, onMove = { onMoveShortcut(id, it) })
                    }
                    item {
                        Column {
                            Text("可添加的入口", style = MaterialTheme.typography.titleSmall)
                            HomeShortcut.entries.filter { it != HomeShortcut.EXAMS && it.id !in state.draft.quickActions }.forEach { shortcut ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(homeShortcutTitle(shortcut.id), Modifier.weight(1f))
                                    TextButton(onClick = { onToggleShortcut(shortcut.id) }, enabled = !state.saving && visibleQuickActions.size < 8) { Text("添加") }
                                }
                            }
                        }
                    }
                    item { LiquidOutlinedButton(onClick = onRestoreDefaults, enabled = !state.saving) { Text("恢复默认布局") } }
                    item { SectionHeader(title = "实时预览") }
                    item { GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) { preview() }
                    } }
                }
            }
        }
    }
}

/** Gesture detector exists exclusively on the handle; switches and content never initiate a drag. */
@Composable
private fun LazyItemScope.HomeOrderRow(drag: HomeOrderDrag, key: String, title: String, index: Int, count: Int, selected: Boolean, enabled: Boolean,
    onToggle: () -> Unit, onMove: (Int) -> Unit) {
    val motion = LocalMotionEnabled.current
    val row = remember(drag, key) { derivedStateOf { drag.row?.takeIf { it.key == key } } }.value
    val currentIndex by rememberUpdatedState(index)
    val currentMove by rememberUpdatedState(onMove)
    val currentMotion by rememberUpdatedState(motion)
    val position = when {
        row != null -> Modifier.zIndex(1f).graphicsLayer {
            translationY = if (enabled && (row.dragging || motion)) row.translation() else 0f
            scaleX = if (motion) row.scale else 1f
            scaleY = scaleX
        }
        motion -> Modifier.animateItem(fadeInSpec = null, placementSpec = tween(AppMotion.Reorder), fadeOutSpec = null)
        else -> Modifier
    }
    GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().then(position)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).semantics { contentDescription = "拖动$title" }
                .pointerInput(key, count, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { drag.start(key, currentIndex, count, it.y, size.height) { delta -> currentMove(delta) } },
                        onDragEnd = { drag.finish(key) }, onDragCancel = { drag.finish(key) },
                    ) { change, amount ->
                        change.consume()
                        drag.row?.takeIf { it.key == key }?.let {
                            it.dragBy(amount.y * if (currentMotion) it.scale else 1f)
                        }
                    }
                }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Menu, null) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { onMove(-1) }, enabled = enabled && index > 0) { Icon(Icons.Filled.KeyboardArrowUp, "上移$title") }
            IconButton(onClick = { onMove(1) }, enabled = enabled && index < count - 1) { Icon(Icons.Filled.KeyboardArrowDown, "下移$title") }
            LiquidCheckbox(selected, onCheckedChange = { onToggle() }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "显示$title" })
        }
    }
}
