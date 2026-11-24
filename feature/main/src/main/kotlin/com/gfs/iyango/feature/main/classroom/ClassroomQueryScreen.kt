@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.classroom

import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import androidx.compose.material.icons.filled.Close
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.EmptyState
import com.gfs.iyango.core.designsystem.component.ErrorState
import com.gfs.iyango.core.designsystem.component.LoadingState
import com.gfs.iyango.core.designsystem.component.RefreshNoticeCapsule
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.ActiveSlotResolution
import com.gfs.iyango.core.domain.ClassroomRoomProjection
import com.gfs.iyango.core.model.ClassroomCell
import com.gfs.iyango.core.model.ClassroomCellState
import com.gfs.iyango.core.model.ClassroomCourseInfo
import com.gfs.iyango.core.model.ClassroomMetadata
import com.gfs.iyango.core.model.ClassroomOccupancyKind
import com.gfs.iyango.core.model.ClassroomPeriodGroup
import com.gfs.iyango.core.model.ClassroomQuery
import com.gfs.iyango.core.model.ClassroomResult
import com.gfs.iyango.core.model.ClassroomRoom
import com.gfs.iyango.core.model.PinnedClassroomBuilding
import java.time.Instant
import java.time.ZoneId

/**
 * 教室查询页（F04）：条件筛选 + 单日占用列表 + 占用详情弹层。
 *
 * - 只有点击“查询”才提交草稿条件；修改后显示“条件已更改”；
 * - 校区切换立即清空教学楼并重新加载；
 * - 占用格只展示所选星期；点击“上课/借用”按当前条件单独请求详情，
 *   失败时只重试该时段。
 *
 * @param onBack 返回上级页。
 * @param onOpenSchool 未登录/会话失效时前往教务账号页。
 */
@Composable
fun ClassroomQueryScreen(
    onBack: () -> Unit,
    onOpenSchool: () -> Unit,
    viewModel: ClassroomViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "教室查询",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    GlassTopBarIconButton(
                        onClick = viewModel::refresh,
                        enabled = state.hasAccount && state.loggedIn &&
                            state.metadataReady && state.initialized && !state.querying,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新查询")
                    }
                },
            )
        },
        overlay = { innerPadding ->
            RefreshNoticeCapsule(
                message = state.refreshNotice,
                onDismiss = viewModel::consumeRefreshNotice,
                topPadding = innerPadding.calculateTopPadding(),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (state.hasAccount && state.loggedIn && state.metadataReady) PaddingValues(0.dp) else innerPadding),
        ) {
            when {
                !state.hasAccount -> AccountRequired(onOpenSchool = onOpenSchool)
                !state.loggedIn -> SessionExpired(onOpenSchool = onOpenSchool)
                !state.metadataReady -> {
                    if (state.loadingMetadata || state.metadataError.isEmpty()) {
                        LoadingState(modifier = Modifier.align(Alignment.Center))
                    } else {
                        ErrorState(
                            title = "教室查询条件加载失败",
                            detail = state.metadataError,
                            onRetry = viewModel::reloadMetadata,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                else -> ClassroomContent(
                    contentPadding = innerPadding,
                    state = state,
                    onTerm = viewModel::selectTerm,
                    onTimeMode = viewModel::selectTimeMode,
                    onCampus = viewModel::selectCampus,
                    onBuilding = viewModel::selectBuilding,
                    onRoomType = viewModel::selectRoomType,
                    onWeek = viewModel::selectWeek,
                    onDay = viewModel::selectDay,
                    onSection = viewModel::selectSection,
                    onQuery = viewModel::confirm,
                    onReloadBuildings = viewModel::reloadBuildings,
                    onOpenDetail = viewModel::openDetail,
                    onSelectPinned = viewModel::selectPinnedBuilding,
                    onRemovePinned = viewModel::removePinnedBuilding,
                    onTogglePinCurrent = viewModel::togglePinCurrentBuilding,
                    onToggleAvailableNow = viewModel::toggleAvailableNow,
                    onToggleConsecutiveTwoPlus = viewModel::toggleConsecutiveTwoPlus,
                )
            }
        }
    }

    if (state.detailTarget != null) {
        DetailSheet(
            state = state,
            onRetry = viewModel::retryDetail,
            onDismiss = viewModel::closeDetail,
        )
    }
}

/** 未绑定教务账号。 */
@Composable
private fun AccountRequired(
    onOpenSchool: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Filled.Person,
            title = "还没有绑定教务账号",
            hint = "绑定阳光学院教务账号后才能查询教室占用",
        )
        Spacer(Modifier.height(8.dp))
        LiquidButton(onClick = onOpenSchool) { Text("去绑定教务账号") }
    }
}

/** 会话过期或读取失败。 */
@Composable
private fun SessionExpired(
    onOpenSchool: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Filled.Lock,
            title = "教务会话已失效",
            hint = "重新登录后即可继续查询教室占用",
        )
        Spacer(Modifier.height(8.dp))
        LiquidButton(onClick = onOpenSchool) { Text("重新登录") }
    }
}

/** 条件 + 结果主内容。 */
@Composable
private fun ClassroomContent(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: ClassroomUiState,
    onTerm: (String) -> Unit,
    onTimeMode: (String) -> Unit,
    onCampus: (String) -> Unit,
    onBuilding: (String) -> Unit,
    onRoomType: (String) -> Unit,
    onWeek: (Int) -> Unit,
    onDay: (Int) -> Unit,
    onSection: (String) -> Unit,
    onQuery: () -> Unit,
    onReloadBuildings: () -> Unit,
    onOpenDetail: (ClassroomRoom, Int) -> Unit,
    onSelectPinned: (PinnedClassroomBuilding) -> Unit,
    onRemovePinned: (PinnedClassroomBuilding) -> Unit,
    onTogglePinCurrent: () -> Unit,
    onToggleAvailableNow: () -> Unit,
    onToggleConsecutiveTwoPlus: () -> Unit,
) {
    val result = state.result
    val visibleRooms = state.filteredRooms
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(LocalLayoutDirection.current) + 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            end = contentPadding.calculateEndPadding(LocalLayoutDirection.current) + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FilterCard(
                state = state,
                onTerm = onTerm,
                onTimeMode = onTimeMode,
                onCampus = onCampus,
                onBuilding = onBuilding,
                onRoomType = onRoomType,
                onWeek = onWeek,
                onDay = onDay,
                onSection = onSection,
                onQuery = onQuery,
                onReloadBuildings = onReloadBuildings,
                onSelectPinned = onSelectPinned,
                onRemovePinned = onRemovePinned,
                onTogglePinCurrent = onTogglePinCurrent,
            )
        }
        item {
            QuickFilterBar(
                state = state,
                onToggleAvailableNow = onToggleAvailableNow,
                onToggleConsecutiveTwoPlus = onToggleConsecutiveTwoPlus,
            )
        }
        if (state.queryError.isNotEmpty()) {
            item {
                Text(
                    text = state.queryError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (state.buildingsError.isNotEmpty()) {
            item {
                Text(
                    text = state.buildingsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        when {
            state.querying && result == null -> item {
                LoadingState(modifier = Modifier.padding(vertical = 24.dp))
            }
            result == null -> item {
                Text(
                    text = "选择条件后点击查询",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            else -> {
                item { ResultHeader(state = state, result = result) }
                if (visibleRooms.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = state.quickFilterEmptyReason.ifBlank { "所选条件下没有符合要求的教室" },
                            hint = "",
                        )
                    }
                } else {
                    items(
                        count = visibleRooms.size,
                        key = { index ->
                            val r = visibleRooms[index].room
                            r.identifier.ifBlank { "${r.name}_$index" }
                        },
                        contentType = { "classroom_room" },
                    ) { index ->
                        RoomCard(
                            projection = visibleRooms[index],
                            result = result,
                            onOpenDetail = onOpenDetail,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QuickFilterBar(
    state: ClassroomUiState,
    onToggleAvailableNow: () -> Unit,
    onToggleConsecutiveTwoPlus: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidFilterChip(
                selected = state.quickFilter.availableNowOnly,
                onClick = onToggleAvailableNow,
                label = { Text("此刻可用") },
            )
            LiquidFilterChip(
                selected = state.quickFilter.minConsecutiveFreeGroups >= 2,
                onClick = onToggleConsecutiveTwoPlus,
                label = { Text("连续空 2 节以上") },
            )
        }
        if (state.quickFilter.availableNowOnly) {
            val slotText = when (val slot = state.activeSlot) {
                is ActiveSlotResolution.InProgress -> "当前时段 ${slot.label}"
                is ActiveSlotResolution.UpcomingBreak -> "下一时段 ${slot.label}"
                ActiveSlotResolution.AfterLastPeriod -> "今日课程已结束"
                ActiveSlotResolution.Unavailable, null -> ""
            }
            if (slotText.isNotEmpty()) {
                Text(
                    text = slotText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 查询条件卡。 */
@Composable
private fun FilterCard(
    state: ClassroomUiState,
    onTerm: (String) -> Unit,
    onTimeMode: (String) -> Unit,
    onCampus: (String) -> Unit,
    onBuilding: (String) -> Unit,
    onRoomType: (String) -> Unit,
    onWeek: (Int) -> Unit,
    onDay: (Int) -> Unit,
    onSection: (String) -> Unit,
    onQuery: () -> Unit,
    onReloadBuildings: () -> Unit,
    onSelectPinned: (PinnedClassroomBuilding) -> Unit,
    onRemovePinned: (PinnedClassroomBuilding) -> Unit,
    onTogglePinCurrent: () -> Unit,
) {
    val metadata = state.metadata
    val draft = state.draft
    val duplicateNames = androidx.compose.runtime.remember(state.pinnedBuildings) {
        state.pinnedBuildings.groupingBy { it.buildingName }.eachCount()
    }
    GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "查询条件",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.maskedAccount.isNotEmpty()) {
                    Text(
                        text = state.maskedAccount,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.pinnedBuildings.isNotEmpty()) {
                FilterLabel("常用教学楼")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.pinnedBuildings.forEach { pin ->
                        val selected = draft.campusCode == pin.campusCode &&
                            draft.buildingCode == pin.buildingCode
                        val multiCampus = (duplicateNames[pin.buildingName] ?: 0) > 1 &&
                            pin.campusName.isNotEmpty()
                        val label = if (multiCampus) {
                            "${pin.buildingName} ${pin.campusName}"
                        } else {
                            pin.buildingName.ifBlank { pin.buildingCode }
                        }
                        LiquidFilterChip(
                            selected = selected,
                            onClick = { onSelectPinned(pin) },
                            label = { Text(label) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除置顶",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onRemovePinned(pin) },
                                )
                            },
                        )
                    }
                }
            }

            FilterLabel("学期")
            OptionChips(
                options = metadata.terms.map { it.value to it.label.ifBlank { it.value } },
                selected = draft.termValue,
                onSelect = onTerm,
            )

            if (metadata.timeModes.size > 1) {
                FilterLabel("时间模式")
                OptionChips(
                    options = metadata.timeModes.map { it.value to it.label.ifBlank { it.value } },
                    selected = draft.timeModeId,
                    onSelect = onTimeMode,
                )
            }

            FilterLabel("校区")
            OptionChips(
                options = listOf("" to "全部校区") +
                    metadata.campuses.map { it.value to it.label.ifBlank { it.value } },
                selected = draft.campusCode,
                onSelect = onCampus,
            )

            if (draft.campusCode.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterLabel("教学楼")
                    Spacer(Modifier.weight(1f))
                    if (draft.buildingCode.isNotEmpty()) {
                        val pinned = state.isCurrentBuildingPinned
                        IconButton(
                            onClick = onTogglePinCurrent,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = if (pinned) "取消置顶" else "置顶常用",
                                tint = if (pinned) LocalBrand.current.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                when {
                    state.buildingsLoading -> Text(
                        text = "正在加载教学楼…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.buildings.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.buildingsError.ifEmpty { "暂无教学楼选项" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onReloadBuildings) { Text("重试") }
                    }
                    else -> {
                        val ordered = if (state.orderedBuildings.isNotEmpty()) {
                            state.orderedBuildings.map {
                                it.building.code to if (it.isPinned) "${it.building.name} ★" else it.building.name
                            }
                        } else {
                            state.buildings.map { it.code to it.name }
                        }
                        OptionChips(
                            options = listOf("" to "全部教学楼") + ordered,
                            selected = draft.buildingCode,
                            onSelect = onBuilding,
                        )
                    }
                }
            }

            if (metadata.roomTypes.isNotEmpty()) {
                FilterLabel("教室类型")
                OptionChips(
                    options = listOf("" to "全部类型") +
                        metadata.roomTypes.map { it.value to it.label.ifBlank { it.value } },
                    selected = draft.roomType,
                    onSelect = onRoomType,
                )
            }

            FilterLabel("周次")
            OptionChips(
                options = metadata.weeks.mapNotNull { option ->
                    val week = weekOf(option.value)
                    if (week == 0) {
                        null
                    } else {
                        week to option.label.takeIf { it.contains("周") }.orEmpty().ifEmpty { "第${week}周" }
                    }
                }.distinctBy { it.first },
                selected = draft.week,
                onSelect = onWeek,
            )

            FilterLabel("星期")
            OptionChips(
                options = metadata.days.map { option ->
                    (option.value.toIntOrNull() ?: 0) to option.label.ifBlank { option.value }
                }.filter { it.first in 1..7 },
                selected = draft.dayOfWeek,
                onSelect = onDay,
            )

            FilterLabel("节次")
            OptionChips(
                options = listOf("" to "全部节次") +
                    metadata.sections.map { it.value to it.label.ifBlank { it.value } },
                selected = draft.sectionCode,
                onSelect = onSection,
            )

            if (state.draftDirty) {
                Text(
                    text = "条件已更改，点击查询后生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LiquidButton(
                onClick = onQuery,
                enabled = state.canQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (state.querying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("查询中…")
                } else {
                    Text(if (state.draftDirty) "按新条件查询" else "查询")
                }
            }
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 横向滚动的筛选标签；窄屏时可左右滑动，不挤压布局。 */
@Composable
private fun <T> OptionChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            LiquidFilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

/** 已确认条件摘要 + 图例。 */
@Composable
private fun ResultHeader(state: ClassroomUiState, result: ClassroomResult) {
    val query = result.query
    val term = state.metadata.terms.firstOrNull { it.value == query.termValue }
        ?.label?.takeIf { it.isNotBlank() } ?: query.termValue
    val summary = listOf(
        term,
        "第${query.week}周",
        dayText(state.metadata, query.dayOfWeek),
    ).filter { it.isNotBlank() }.joinToString(" ")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "查询结果",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(result.fetchedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append("空闲 / 上课 / 借用 / 未知；点击“上课”或“借用”查看详情")
                if (query.sectionCode.isNotEmpty()) append("。当前仅显示所选节次")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 教室卡：所选星期的各节次占用。 */
@Composable
private fun RoomCard(
    projection: ClassroomRoomProjection,
    result: ClassroomResult,
    onOpenDetail: (ClassroomRoom, Int) -> Unit,
) {
    val room = projection.room
    val query = result.query
    val brand = LocalBrand.current
    val groups = androidx.compose.runtime.remember(result.periodGroups, query.sectionCode) {
        result.periodGroups.withIndex().filter { (_, group) ->
            query.sectionCode.isEmpty() || group.rawCode == query.sectionCode
        }
    }
    GlassSurface(material = GlassMaterial.CONTENT, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (projection.badgeText.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = brand.accent.copy(alpha = 0.14f),
                        contentColor = brand.accent,
                    ) {
                        Text(
                            text = projection.badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                if (room.roomType.isNotBlank()) {
                    Text(
                        text = room.roomType,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val meta = listOfNotNull(
                room.identifier.takeIf { it.isNotBlank() }?.let { "编号 $it" },
                room.seatInfo.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEach { indexed ->
                    val group = indexed.value
                    val cell = room.cell(query.dayOfWeek, indexed.index)
                        ?: ClassroomCell(query.dayOfWeek, indexed.index, ClassroomCellState.NOT_QUERIED)
                    ClassroomCellChip(
                        group = group,
                        cell = cell,
                        highlighted = projection.highlightedGroupIndex == indexed.index,
                        onClick = { onOpenDetail(room, indexed.index) },
                    )
                }
            }
        }
    }
}

/** 单个节次占用标签；仅“上课/借用”可点击。 */
@Composable
private fun ClassroomCellChip(
    group: ClassroomPeriodGroup,
    cell: ClassroomCell,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val (background, content) = cellColors(cell.state)
    val actionable = cell.state == ClassroomCellState.COURSE ||
        cell.state == ClassroomCellState.BORROWED
    val label = group.label.ifBlank { group.rawCode }
    val shape = RoundedCornerShape(999.dp)
    Surface(
        shape = shape,
        color = background,
        contentColor = content,
        modifier = Modifier
            .then(
                if (highlighted) Modifier.border(1.5.dp, LocalBrand.current.accent, shape) else Modifier,
            )
            .then(
                if (actionable) Modifier.clickable(onClick = onClick) else Modifier,
            ),
    ) {
        Text(
            text = "$label ${cellLabel(cell.state)}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** 占用详情弹层。 */
@Composable
private fun DetailSheet(
    state: ClassroomUiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val target = state.detailTarget ?: return
    LiquidModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            GlassOverlayHeader(
                title = "占用详情",
                actions = {
                    GlassTopBarIconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.detailLoading -> LoadingState()
                    state.detailError.isNotEmpty() -> {
                        Text(
                            text = state.detailError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.detailKey.isNotEmpty()) {
                                LiquidButton(onClick = onRetry) { Text("重试") }
                            }
                            TextButton(onClick = onDismiss) { Text("关闭") }
                        }
                    }
                    else -> {
                        val courses = state.detail?.courses.orEmpty()
                        if (courses.isEmpty()) {
                            Text(
                                text = "学校未返回此时段详情（占用状态仍以使用情况为准）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            courses.forEach { info -> DetailCard(info) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(info: ClassroomCourseInfo) {
    val kindLabel = when (info.kind) {
        ClassroomOccupancyKind.COURSE -> "上课"
        ClassroomOccupancyKind.BORROWED -> "借用"
        ClassroomOccupancyKind.UNKNOWN -> "占用"
    }
    val brand = LocalBrand.current
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info.courseName.ifBlank { kindLabel },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = brand.accent,
                )
            }
            detailLines(info).forEach { (label, value) ->
                Text(
                    text = "$label：$value",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun detailLines(info: ClassroomCourseInfo): List<Pair<String, String>> = buildList {
    if (info.roomName.isNotBlank()) add("教室" to info.roomName)
    if (info.teacherName.isNotBlank()) add("教师" to info.teacherName)
    if (info.className.isNotBlank()) add("班级" to info.className)
    if (info.time.isNotBlank()) add("时间" to info.time)
    if (info.applicant.isNotBlank()) add("申请人" to info.applicant)
    if (info.remark.isNotBlank()) add("备注" to info.remark)
}

@Composable
private fun cellColors(state: ClassroomCellState): Pair<Color, Color> {
    val brand = LocalBrand.current
    return when (state) {
        ClassroomCellState.FREE -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
        ClassroomCellState.COURSE -> brand.brandSoft to brand.accent
        ClassroomCellState.BORROWED -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        ClassroomCellState.UNKNOWN -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        ClassroomCellState.NOT_QUERIED -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun cellLabel(state: ClassroomCellState): String = when (state) {
    ClassroomCellState.FREE -> "空闲"
    ClassroomCellState.COURSE -> "上课"
    ClassroomCellState.BORROWED -> "借用"
    ClassroomCellState.UNKNOWN -> "未知"
    ClassroomCellState.NOT_QUERIED -> "未查询"
}

private fun weekOf(value: String): Int {
    val parsed = value.toIntOrNull() ?: return 0
    return if (parsed in 1..60) parsed else 0
}

private fun dayText(metadata: ClassroomMetadata, day: Int): String =
    metadata.days.firstOrNull { it.value.toIntOrNull() == day }
        ?.label?.takeIf { it.isNotBlank() }
        ?: if (day in 1..7) "星期" + "一二三四五六日"[day - 1] else "星期$day"

private fun formatTime(millis: Long): String {
    if (millis <= 0) return ""
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

private fun previewGroups(): List<ClassroomPeriodGroup> = listOf(
    ClassroomPeriodGroup("12", "第1-2节", "08:00", "09:40"),
    ClassroomPeriodGroup("34", "第3-4节", "10:00", "11:40"),
    ClassroomPeriodGroup("1112", "第十一节", "20:10", "20:55"),
)

private fun previewRoom(): ClassroomRoom = ClassroomRoom(
    name = "A101",
    identifier = "A101",
    seatInfo = "60座",
    roomType = "普通教室",
    cells = listOf(
        ClassroomCell(dayOfWeek = 1, groupIndex = 0, state = ClassroomCellState.FREE),
        ClassroomCell(dayOfWeek = 1, groupIndex = 1, state = ClassroomCellState.COURSE),
        ClassroomCell(dayOfWeek = 1, groupIndex = 2, state = ClassroomCellState.BORROWED),
    ),
)

private fun previewResult(): ClassroomResult = ClassroomResult(
    query = ClassroomQuery(week = 8, dayOfWeek = 1),
    fetchedAt = System.currentTimeMillis(),
    periodGroups = previewGroups(),
    rooms = listOf(previewRoom()),
)

/** 亮色预览。 */
@Preview(name = "教室查询 - 亮色", showBackground = true, widthDp = 390)
@Composable
private fun ClassroomLightPreview() {
    IYangoThemePreview(darkTheme = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoomCard(
                    projection = ClassroomRoomProjection(room = previewRoom()),
                    result = previewResult(),
                    onOpenDetail = { _, _ -> },
                )
                DetailCard(
                    ClassroomCourseInfo(
                        kind = ClassroomOccupancyKind.COURSE,
                        courseName = "高等数学",
                        roomName = "A101",
                        teacherName = "李老师",
                        className = "计算机 2401",
                        time = "08:00-09:40",
                    ),
                )
            }
        }
    }
}

/** 暗色预览。 */
@Preview(name = "教室查询 - 暗色", showBackground = true, widthDp = 390)
@Composable
private fun ClassroomDarkPreview() {
    IYangoThemePreview(darkTheme = true) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoomCard(
                    projection = ClassroomRoomProjection(room = previewRoom()),
                    result = previewResult(),
                    onOpenDetail = { _, _ -> },
                )
                DetailCard(
                    ClassroomCourseInfo(
                        kind = ClassroomOccupancyKind.BORROWED,
                        courseName = "社团活动",
                        roomName = "A101",
                        applicant = "学生会",
                        remark = "布置场地",
                        time = "20:10-20:55",
                    ),
                )
            }
        }
    }
}
