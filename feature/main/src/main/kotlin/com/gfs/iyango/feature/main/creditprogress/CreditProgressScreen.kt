package com.gfs.iyango.feature.main.creditprogress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.EmptyState
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.GlassTopBarTextButton
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.tokens.Dimens
import com.gfs.iyango.core.model.CountedCourseGroup
import com.gfs.iyango.core.model.CreditCategoryPlan
import com.gfs.iyango.core.model.CreditCategoryProgress
import com.gfs.iyango.core.model.CreditCoverageStatus
import com.gfs.iyango.core.model.CreditSplitAssignment
import com.gfs.iyango.core.model.CurriculumCourseItem
import com.gfs.iyango.core.model.CurriculumNatureGroup
import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import com.gfs.iyango.core.model.CurriculumStudyStatus
import com.gfs.iyango.core.model.NotCountedCourseGroup
import com.gfs.iyango.core.model.PendingCourseGroup
import com.gfs.iyango.core.model.deduplicateCoursesByName
import com.gfs.iyango.core.model.formatCreditBigDecimal
import java.math.BigDecimal

@Composable
fun CreditProgressScreen(
    onBack: () -> Unit = {},
    onOpenSchool: () -> Unit = {},
    viewModel: CreditProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CreditProgressPageLayout(
        state = state,
        onBack = onBack,
        onOpenSchool = onOpenSchool,
        onRefreshAllSemesters = viewModel::refreshAllSemesters,
        onSaveTotalTarget = viewModel::saveTotalTarget,
        onSaveCategory = viewModel::saveCategory,
        onDeleteCategory = viewModel::deleteCategory,
        onResetCategories = viewModel::resetCategoriesToDefault,
        onConfirmMerge = { group, categoryId, anchorKey, manualPass ->
            viewModel.resolveMergeSingleCourse(
                group = group,
                assignedCategoryId = categoryId,
                chosenAnchorKey = anchorKey,
                manualPassConfirmed = manualPass,
            )
        },
        onConfirmSplit = { group, assignments ->
            viewModel.resolveSplitSeparateCourses(group = group, assignments = assignments)
        },
        onExcludePending = { group -> viewModel.resolveExcludeFromPlan(group = group) },
        onConfirmPostRestore = viewModel::confirmPostRestoreRule,
        findAssociatedRuleKey = viewModel::findAssociatedRuleKey,
        onDeleteRule = viewModel::deleteRule,
        onDismissNotice = viewModel::dismissNotice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreditProgressPageLayout(
    state: CreditProgressUiState,
    onBack: () -> Unit = {},
    onOpenSchool: () -> Unit = {},
    onRefreshAllSemesters: () -> Unit = {},
    onSaveTotalTarget: (String, String) -> Boolean = { _, _ -> true },
    onSaveCategory: (String?, String, String, Set<String>, Int) -> Boolean = { _, _, _, _, _ -> true },
    onDeleteCategory: (String) -> Unit = {},
    onResetCategories: () -> Unit = {},
    onConfirmMerge: (PendingCourseGroup, String?, String?, Boolean) -> Unit = { _, _, _, _ -> },
    onConfirmSplit: (PendingCourseGroup, List<CreditSplitAssignment>) -> Unit = { _, _ -> },
    onExcludePending: (PendingCourseGroup) -> Unit = {},
    onConfirmPostRestore: (String) -> Unit = {},
    findAssociatedRuleKey: (String) -> String? = { null },
    onDeleteRule: (String) -> Unit = {},
    onDismissNotice: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        if (state.notice.isNotBlank()) {
            snackbarHostState.showSnackbar(state.notice)
            onDismissNotice()
        }
    }

    var activePendingGroup by remember { mutableStateOf<PendingCourseGroup?>(null) }
    var activeCountedGroup by remember { mutableStateOf<CountedCourseGroup?>(null) }
    var activeNotCountedGroup by remember { mutableStateOf<NotCountedCourseGroup?>(null) }
    var showCountedList by remember { mutableStateOf(false) }
    var showNotCountedList by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        GlassScaffold(
            topBar = {
                GlassTopBar(
                    title = "学分进度",
                    subtitle = null,
                    navigationIcon = {
                        GlassTopBarIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (state.hasConnection) {
                            GlassTopBarTextButton(
                                text = if (state.loggedIn) "更新全部学期" else "重新登录",
                                loading = state.refreshing,
                                onClick = {
                                    if (state.loggedIn) onRefreshAllSemesters() else onOpenSchool()
                                },
                            )
                        } else {
                            GlassTopBarTextButton(text = "去登录", onClick = onOpenSchool)
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val report = state.report
        if (!state.hasConnection) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(Dimens.spacingL),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    icon = Icons.Filled.Info,
                    title = "未绑定教务账号",
                    hint = "绑定并登录后可查询全部学期成绩与学分完成进度。",
                )
                Spacer(Modifier.height(Dimens.spacingM))
                LiquidButton(onClick = onOpenSchool) {
                    Text("去绑定教务账号")
                }
            }
            return@GlassScaffold
        }

        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = {
                if (state.loggedIn) onRefreshAllSemesters() else onOpenSchool()
            },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = innerPadding.calculateTopPadding()),
                )
            },
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dimens.spacingL,
                end = Dimens.spacingL,
                top = innerPadding.calculateTopPadding() + Dimens.spacingM,
                bottom = innerPadding.calculateBottomPadding() + Dimens.spacingXl,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingM),
        ) {
            item(key = "summary_card") {
                SummaryProgressCard(state = state)
            }

            item(key = "categories_header") {
                SectionCard(title = "分类学分统计") {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingM)) {
                        report.categoryProgressList.forEach { catProgress ->
                            CategoryProgressItem(
                                item = catProgress,
                                hasCurriculum = state.curriculumSnapshot != null,
                                isNotQueried = report.coverageStatus == CreditCoverageStatus.NOT_QUERIED,
                            )
                        }
                    }
                }
            }

            item(key = "curriculum_plan_section") {
                CurriculumPlanSectionCard(
                    snapshot = state.curriculumSnapshot,
                    loggedIn = state.loggedIn,
                    refreshing = state.refreshing,
                    onSyncClick = onRefreshAllSemesters,
                    onOpenSchool = onOpenSchool,
                )
            }

            if (state.actionablePendingCourses.isNotEmpty()) {
                item(key = "pending_section_header") {
                    Text(
                        "待确认课程（${state.actionablePendingCourses.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                items(
                    items = state.actionablePendingCourses,
                    key = { "pending:${it.groupKey}" },
                ) { group ->
                    PendingCourseCard(
                        group = group,
                        onResolveClick = { activePendingGroup = group },
                    )
                }
            }

            item(key = "counted_toggle") {
                SectionCard(
                    title = "已计入课程（${report.countedCourses.size}）",
                    action = {
                        TextButton(onClick = { showCountedList = !showCountedList }) {
                            Text(if (showCountedList) "收起" else "展开")
                        }
                    },
                ) {
                    if (showCountedList) {
                        if (report.countedCourses.isEmpty()) {
                            Text(
                                "暂无已计入课程",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
                                report.countedCourses.forEach { course ->
                                    CountedCourseRow(
                                        course = course,
                                        onClick = { activeCountedGroup = course },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "not_counted_toggle") {
                SectionCard(
                    title = "未通过或不计入（${report.notCountedCourses.size}）",
                    action = {
                        TextButton(onClick = { showNotCountedList = !showNotCountedList }) {
                            Text(if (showNotCountedList) "收起" else "展开")
                        }
                    },
                ) {
                    if (showNotCountedList) {
                        if (report.notCountedCourses.isEmpty()) {
                            Text(
                                "无未通过或不计入课程",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
                                report.notCountedCourses.forEach { course ->
                                    NotCountedCourseRow(
                                        course = course,
                                        onClick = { activeNotCountedGroup = course },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    activePendingGroup?.let { group ->
        PendingResolutionDialog(
            group = group,
            categories = state.plan.categories,
            onDismiss = { activePendingGroup = null },
            onConfirmMerge = { catId, anchorKey, manualPass ->
                onConfirmMerge(group, catId, anchorKey, manualPass)
            },
            onConfirmSplit = { assignments ->
                onConfirmSplit(group, assignments)
            },
            onExclude = { onExcludePending(group) },
            onConfirmPostRestoreOnly = onConfirmPostRestore,
        )
    }

    activeCountedGroup?.let { counted ->
        CountedCourseDetailDialog(
            course = counted,
            associatedRuleKey = findAssociatedRuleKey(counted.groupKey),
            onDismiss = { activeCountedGroup = null },
            onDeleteRule = onDeleteRule,
        )
    }

    activeNotCountedGroup?.let { notCounted ->
        NotCountedCourseDetailDialog(
            course = notCounted,
            associatedRuleKey = findAssociatedRuleKey(notCounted.groupKey),
            onDismiss = { activeNotCountedGroup = null },
            onDeleteRule = onDeleteRule,
        )
    }
    }
}

@Composable
private fun SummaryProgressCard(
    state: CreditProgressUiState,
) {
    val report = state.report
    val hasData = report.coverageStatus != CreditCoverageStatus.NOT_QUERIED || state.curriculumSnapshot != null
    val totalTarget = report.totalTargetCredits

    SectionCard(title = "总学分概览") {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
            val headlineText = when {
                !hasData && totalTarget != null ->
                    "— / ${formatCreditBigDecimal(totalTarget)} 学分"
                !hasData ->
                    "— 学分"
                totalTarget != null ->
                    "${formatCreditBigDecimal(report.earnedTotalCredits)} / ${formatCreditBigDecimal(totalTarget)} 学分"
                else ->
                    "已获得 ${formatCreditBigDecimal(report.earnedTotalCredits)} 学分"
            }

            Text(
                headlineText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            if (hasData && totalTarget != null && totalTarget > BigDecimal.ZERO) {
                LinearProgressIndicator(
                    progress = { report.overallProgressRatio ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CategoryProgressItem(
    item: CreditCategoryProgress,
    hasCurriculum: Boolean,
    isNotQueried: Boolean,
) {
    val hasEarned = !isNotQueried || hasCurriculum
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.category.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            val earnedStr = if (hasEarned) formatCreditBigDecimal(item.earnedCredits) else "—"
            val valueText = if (item.targetCredits != null) {
                "$earnedStr / ${formatCreditBigDecimal(item.targetCredits!!)}"
            } else {
                earnedStr
            }
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (hasEarned && item.targetCredits != null && item.targetCredits!! > BigDecimal.ZERO) {
            LinearProgressIndicator(
                progress = { item.progressRatio ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingCourseCard(
    group: PendingCourseGroup,
    onResolveClick: () -> Unit,
) {
    GlassSurface(
        material = GlassMaterial.CONTENT,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onResolveClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                .padding(Dimens.spacingM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val title = buildString {
                    append(group.courseName)
                    if (group.courseCode.isNotBlank()) append(" (${group.courseCode})")
                }
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    group.detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "涉及 ${group.records.size} 条成绩记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LiquidOutlinedButton(onClick = onResolveClick) {
                Text("确认处理")
            }
        }
    }
}

@Composable
private fun CountedCourseRow(
    course: CountedCourseGroup,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(course.courseName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${course.categoryName} ${course.detailText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatCreditBigDecimal(course.earnedCredits),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun NotCountedCourseRow(
    course: NotCountedCourseGroup,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(course.courseName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                course.detailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class CurriculumFilter(val label: String) {
    ALL("全部"),
    IN_PROGRESS("修读中"),
    PENDING_STUDY("待修读"),
    COMPLETED("已修读"),
}

@Composable
private fun CurriculumPlanSectionCard(
    snapshot: CurriculumProgressSnapshot?,
    loggedIn: Boolean,
    refreshing: Boolean,
    onSyncClick: () -> Unit,
    onOpenSchool: () -> Unit,
) {
    val effectiveSnapshot = remember(snapshot) { snapshot?.deduplicateCoursesByName() }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val groupFilters = remember { mutableStateMapOf<String, CurriculumFilter>() }

    SectionCard(
        title = "教务培养方案及修读情况",
        action = {
            TextButton(
                onClick = { if (loggedIn) onSyncClick() else onOpenSchool() },
                enabled = !refreshing,
            ) {
                Text(if (loggedIn) "同步培养方案" else "去登录")
            }
        },
    ) {
        if (effectiveSnapshot == null) {
            Text(
                "暂无教务培养方案数据；点击“更新全部学期”或右上角“同步培养方案”可拉取教务系统的已修、正修读与待修读课程列表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingS)) {
            effectiveSnapshot.groups.forEach { group ->
                val expanded = expandedGroups[group.courseNature] == true
                val selectedFilter = groupFilters[group.courseNature] ?: CurriculumFilter.ALL
                CurriculumNatureGroupCard(
                    group = group,
                    expanded = expanded,
                    selectedFilter = selectedFilter,
                    onToggleExpand = { expandedGroups[group.courseNature] = !expanded },
                    onSelectFilter = { groupFilters[group.courseNature] = it },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurriculumNatureGroupCard(
    group: CurriculumNatureGroup,
    expanded: Boolean,
    selectedFilter: CurriculumFilter,
    onToggleExpand: () -> Unit,
    onSelectFilter: (CurriculumFilter) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        group.courseNature,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val summaryLine = buildString {
                        append("已修 ${formatCreditBigDecimal(group.earnedCredits)}")
                        append("  正修读 ${formatCreditBigDecimal(group.inProgressCredits)}")
                        if (group.pendingCount > 0) {
                            append("  待修读 ${group.pendingCount} 门")
                        }
                    }
                    Text(
                        summaryLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (expanded) "收起" else "展开 (${group.courses.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (expanded) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CurriculumFilter.entries.forEach { filter ->
                        LiquidFilterChip(
                            selected = filter == selectedFilter,
                            onClick = { onSelectFilter(filter) },
                            label = { Text(filter.label) },
                        )
                    }
                }

                val visibleCourses = when (selectedFilter) {
                    CurriculumFilter.ALL -> group.courses
                    CurriculumFilter.IN_PROGRESS -> group.courses.filter {
                        it.studyStatus == CurriculumStudyStatus.IN_PROGRESS
                    }
                    CurriculumFilter.PENDING_STUDY -> group.courses.filter {
                        it.studyStatus == CurriculumStudyStatus.PENDING_STUDY
                    }
                    CurriculumFilter.COMPLETED -> group.courses.filter {
                        it.studyStatus == CurriculumStudyStatus.COMPLETED
                    }
                }
                if (visibleCourses.isEmpty()) {
                    Text(
                        "该状态下暂无课程",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        visibleCourses.forEach { course ->
                            CurriculumCourseRow(course = course)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurriculumCourseRow(course: CurriculumCourseItem) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val title = if (course.courseCode.isNotBlank()) {
                    "${course.courseName} (${course.courseCode})"
                } else {
                    course.courseName
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                val metaParts = buildList {
                    val creditDisplay = course.creditText.ifBlank { formatCreditBigDecimal(course.credit) }
                    add("$creditDisplay 学分")
                    if (course.courseAttribute.isNotBlank()) add(course.courseAttribute)
                    if (course.remark.isNotBlank()) add(course.remark)
                }
                Text(
                    metaParts.joinToString("  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val (statusText, statusColor) = when (course.studyStatus) {
                CurriculumStudyStatus.COMPLETED -> {
                    val label = if (course.scoreText.isNotBlank()) "已修 ${course.scoreText}" else "已修读"
                    label to MaterialTheme.colorScheme.primary
                }
                CurriculumStudyStatus.IN_PROGRESS -> "修读中" to MaterialTheme.colorScheme.tertiary
                CurriculumStudyStatus.PENDING_STUDY -> "待修读" to MaterialTheme.colorScheme.error
                CurriculumStudyStatus.UNKNOWN -> "未知" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
