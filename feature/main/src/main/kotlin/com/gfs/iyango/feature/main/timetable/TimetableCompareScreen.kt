@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.CommonFreeBlock
import com.gfs.iyango.core.domain.ComparisonSlotCell
import com.gfs.iyango.core.domain.TimetableComparisonResult
import java.time.format.DateTimeFormatter

private enum class CompareViewMode(val label: String) {
    GRID("周网格"),
    FREE_LIST("空闲时段"),
}

private val DATE_RANGE_FMT = DateTimeFormatter.ofPattern("MM.dd")
private val WEEKDAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 多课表「合表找空」页面。
 */
@Composable
fun TimetableCompareScreen(
    onBack: () -> Unit,
    onOpenImport: () -> Unit = {},
    onOpenClassroom: (week: Int, dayOfWeek: Int, startSection: Int, endSection: Int) -> Unit = { _, _, _, _ -> },
    viewModel: TimetableCompareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val partnerTimetableId by viewModel.partnerTimetableId.collectAsStateWithLifecycle()
    var viewMode by remember { mutableStateOf(CompareViewMode.GRID) }
    var inspectedCell by remember { mutableStateOf<ComparisonSlotCell?>(null) }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "合表找空",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (state.timetables.size < 2) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SectionCard(title = "至少需要两张课表") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "请先导入同学课表或新建第二张课表",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LiquidButton(
                                onClick = onOpenImport,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                            ) {
                                Text("导入课表或分享口令")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = innerPadding.calculateTopPadding() + 12.dp,
                        bottom = innerPadding.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        WeekNavigatorCard(
                            state = state,
                            onPrevious = viewModel::previousWeek,
                            onNext = viewModel::nextWeek,
                            onCurrent = viewModel::jumpToCurrentWeek,
                        )
                    }

                    item {
                        SectionCard(title = "参与对比课表 (${state.selectedIds.size})") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    state.timetables.forEach { table ->
                                        LiquidFilterChip(
                                            selected = table.id in state.selectedIds,
                                            onClick = { viewModel.toggleTimetable(table.id) },
                                            label = {
                                                Text(
                                                    table.name.ifBlank { "课表 ${table.id}" },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                        )
                                    }
                                }
                                val currentTable = state.timetables.firstOrNull { it.isCurrent }
                                val partnerCandidate = if (state.selectedIds.size == 2 && currentTable != null && currentTable.id in state.selectedIds) {
                                    state.timetables.firstOrNull { it.id in state.selectedIds && it.id != currentTable.id }
                                } else {
                                    null
                                }
                                if (partnerCandidate != null) {
                                    val active = partnerCandidate.id == partnerTimetableId
                                    LiquidOutlinedButton(
                                        onClick = {
                                            if (active) {
                                                viewModel.clearPartnerTimetable()
                                            } else {
                                                viewModel.setAsPartnerTimetable(
                                                    partnerCandidate.id,
                                                    partnerCandidate.name.ifBlank { "TA" },
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(if (active) "取消双人对照" else "设为双人对照")
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CompareViewMode.entries.forEach { mode ->
                                LiquidFilterChip(
                                    selected = viewMode == mode,
                                    onClick = { viewMode = mode },
                                    label = { Text(mode.label) },
                                )
                            }
                            if (viewMode == CompareViewMode.FREE_LIST) {
                                Spacer(Modifier.weight(1f))
                                LiquidFilterChip(
                                    selected = state.minConsecutiveTwo,
                                    onClick = { viewModel.setMinConsecutiveTwo(!state.minConsecutiveTwo) },
                                    label = { Text("仅看 ≥2 节") },
                                )
                            }
                        }
                    }

                    val result = state.result
                    if (state.selectedIds.size < 2) {
                        item {
                            GlassSurface(
                                shape = RoundedCornerShape(16.dp),
                                material = GlassMaterial.CONTENT,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "请至少选择 2 张课表进行对比",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    } else if (result != null) {
                        if (viewMode == CompareViewMode.GRID) {
                            item {
                                ComparisonGridCard(
                                    result = result,
                                    onCellClick = { inspectedCell = it },
                                )
                            }
                        } else {
                            val blocks = if (state.minConsecutiveTwo) {
                                result.commonFreeBlocks.filter { it.sectionCount >= 2 }
                            } else {
                                result.commonFreeBlocks
                            }
                            if (blocks.isEmpty()) {
                                item {
                                    GlassSurface(
                                        shape = RoundedCornerShape(16.dp),
                                        material = GlassMaterial.CONTENT,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = "本周暂无符合条件的共同空课",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                            } else {
                                items(
                                    items = blocks,
                                    key = { "${it.dayOfWeek}-${it.startSection}-${it.endSection}" },
                                ) { block ->
                                    CommonFreeBlockRow(
                                        block = block,
                                        teachingWeek = state.anchorTeachingWeek,
                                        onOpenClassroom = {
                                            onOpenClassroom(
                                                state.anchorTeachingWeek,
                                                block.dayOfWeek,
                                                block.startSection,
                                                block.endSection,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    inspectedCell?.let { cell ->
        val matchingBlock = state.result?.commonFreeBlocks?.firstOrNull {
            it.dayOfWeek == cell.dayOfWeek && cell.section in it.startSection..it.endSection
        }
        CellInspectDialog(
            cell = cell,
            freeBlock = matchingBlock,
            onDismiss = { inspectedCell = null },
            onOpenClassroom = {
                val startSec = matchingBlock?.startSection ?: cell.section
                val endSec = matchingBlock?.endSection ?: cell.section
                inspectedCell = null
                onOpenClassroom(state.anchorTeachingWeek, cell.dayOfWeek, startSec, endSec)
            },
        )
    }
}

@Composable
private fun WeekNavigatorCard(
    state: TimetableCompareUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCurrent: () -> Unit,
) {
    val weekStart = state.weekStartDate
    val weekEnd = weekStart.plusDays(6)
    val weekTitle = if (state.anchorTeachingWeek > 0) {
        "第 ${state.anchorTeachingWeek} 周"
    } else {
        "非教学周"
    }
    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassTopBarIconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周")
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onCurrent),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = weekTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${weekStart.format(DATE_RANGE_FMT)} - ${weekEnd.format(DATE_RANGE_FMT)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlassTopBarIconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周")
            }
        }
    }
}

@Composable
private fun ComparisonGridCard(
    result: TimetableComparisonResult,
    onCellClick: (ComparisonSlotCell) -> Unit,
) {
    val accent = LocalBrand.current.accent
    val freeBg = accent.copy(alpha = 0.20f)
    val freeBorder = accent.copy(alpha = 0.55f)
    val busyBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    GlassSurface(
        shape = RoundedCornerShape(18.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "节",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(24.dp),
                )
                result.dates.forEachIndexed { idx, date ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = WEEKDAY_SHORT[idx],
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            for (sec in 1..result.maxSection) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$sec",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(24.dp),
                    )
                    for (day in 1..7) {
                        val cell = result.cells[day to sec] ?: continue
                        val isFree = cell.isCommonFree
                        val bg = if (isFree) freeBg else busyBg
                        val shape = RoundedCornerShape(8.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(shape)
                                .background(bg)
                                .then(
                                    if (isFree) Modifier.border(1.dp, freeBorder, shape) else Modifier,
                                )
                                .clickable { onCellClick(cell) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isFree) "空" else "${cell.busySources.size}人",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isFree) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isFree) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommonFreeBlockRow(
    block: CommonFreeBlock,
    teachingWeek: Int,
    onOpenClassroom: () -> Unit,
) {
    val sectionLabel = if (block.startSection == block.endSection) {
        "第${block.startSection}节"
    } else {
        "第${block.startSection}-${block.endSection}节"
    }
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "周${WEEKDAY_SHORT[(block.dayOfWeek - 1).coerceIn(0, 6)]} $sectionLabel",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (teachingWeek > 0) {
                        "第${teachingWeek}周 ${block.date.format(DATE_RANGE_FMT)} 共${block.sectionCount}节空闲"
                    } else {
                        "${block.date.format(DATE_RANGE_FMT)} 共${block.sectionCount}节空闲"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LiquidOutlinedButton(onClick = onOpenClassroom) {
                Text("查空教室")
            }
        }
    }
}

@Composable
private fun CellInspectDialog(
    cell: ComparisonSlotCell,
    freeBlock: CommonFreeBlock?,
    onDismiss: () -> Unit,
    onOpenClassroom: () -> Unit,
) {
    val dayText = "周${WEEKDAY_SHORT[(cell.dayOfWeek - 1).coerceIn(0, 6)]} 第${cell.section}节"
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dayText, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (cell.isCommonFree) {
                    Text(
                        text = "所有选中课表此时段均无课",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (freeBlock != null && freeBlock.sectionCount > 1) {
                        Text(
                            text = "连续空闲：第${freeBlock.startSection}-${freeBlock.endSection}节",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    cell.busySources.forEach { ref ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = ref.timetableName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = listOf(ref.courseName, ref.classroom)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" "),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (cell.isCommonFree) {
                TextButton(onClick = onOpenClassroom) { Text("查空教室") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
