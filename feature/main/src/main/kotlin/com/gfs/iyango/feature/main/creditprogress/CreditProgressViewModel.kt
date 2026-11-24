package com.gfs.iyango.feature.main.creditprogress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gfs.iyango.core.domain.CreditProgressCalculator
import com.gfs.iyango.core.domain.CreditProgressPort
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.RefreshCoordinatorPort
import com.gfs.iyango.core.domain.RefreshOutcome
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.model.CreditAccountPlan
import com.gfs.iyango.core.model.CreditCategoryPlan
import com.gfs.iyango.core.model.CreditCoverageStatus
import com.gfs.iyango.core.model.CreditProgressReport
import com.gfs.iyango.core.model.CreditRecordAnchor
import com.gfs.iyango.core.model.CreditResolutionAction
import com.gfs.iyango.core.model.CreditResolutionRule
import com.gfs.iyango.core.model.CreditSplitAssignment
import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import com.gfs.iyango.core.model.PendingCourseGroup
import com.gfs.iyango.core.model.PendingReason
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.parseCreditBigDecimal
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CreditProgressUiState(
    val hasConnection: Boolean = false,
    val loggedIn: Boolean = false,
    val connectionId: String = "",
    val accountKey: String = "",
    val accountDisplayName: String = "",
    val refreshing: Boolean = false,
    val notice: String = "",
    val plan: CreditAccountPlan = CreditAccountPlan(),
    val curriculumSnapshot: CurriculumProgressSnapshot? = plan.curriculumSnapshot,
    val report: CreditProgressReport = CreditProgressReport(
        accountKey = "",
        coverageStatus = CreditCoverageStatus.NOT_QUERIED,
        coveredSemesterCount = 0,
        targetSemesterCount = 0,
        truncatedTermCount = 0,
        fetchedAtEpochMillis = null,
        earnedTotalCredits = BigDecimal("0.00"),
        totalTargetCredits = null,
        remainingTotalCredits = null,
        overallProgressRatio = null,
        targetValidation = com.gfs.iyango.core.model.CreditTargetValidation(
            hasCategoryTargets = false,
            categoryTargetSum = BigDecimal("0.00"),
            totalTargetCredits = null,
            sumMismatch = false,
            difference = null,
        ),
        categoryProgressList = emptyList(),
        countedCourses = emptyList(),
        notCountedCourses = emptyList(),
        pendingCourses = emptyList(),
        availableRawAttributes = emptySet(),
    ),
    val legacyRefreshOnlyCount: Int = 0,
    val actionablePendingCourses: List<PendingCourseGroup> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CreditProgressViewModel @Inject constructor(
    private val schoolAuth: SchoolAuthPort,
    private val gradeRepository: GradeRepositoryPort,
    private val creditProgress: CreditProgressPort,
    private val refreshCoordinator: RefreshCoordinatorPort,
) : ViewModel() {

    private val noticeFlow = MutableStateFlow("")
    private val curriculumSyncingFlow = MutableStateFlow(false)
    private var refreshJob: Job? = null

    private val selectedConnectionFlow = schoolAuth.observeConnections()
        .map(::pickConnection)
        .distinctUntilChanged()

    val uiState: StateFlow<CreditProgressUiState> = selectedConnectionFlow
        .flatMapLatest { connection ->
            if (connection == null) {
                combine(refreshCoordinator.state, noticeFlow, curriculumSyncingFlow) { refreshState, notice, curriculumSyncing ->
                    val gradeTask = refreshState.task(RefreshResource.GRADES)
                    CreditProgressUiState(
                        hasConnection = false,
                        loggedIn = false,
                        refreshing = curriculumSyncing || gradeTask.outcome == RefreshOutcome.RUNNING,
                        notice = notice,
                    )
                }
            } else {
                val accountKey = connection.accountSubject.ifEmpty { connection.id }
                val label = connection.profile.displayName
                    .ifBlank { connection.maskedAccount }
                    .ifBlank { accountKey }
                combine(
                    gradeRepository.observeCreditProgressSource(accountKey),
                    creditProgress.observeAccountPlan(accountKey),
                    refreshCoordinator.state,
                    noticeFlow,
                    curriculumSyncingFlow,
                ) { source, plan, refreshState, notice, curriculumSyncing ->
                    val report = CreditProgressCalculator.calculate(source, plan)
                    val legacyCount = report.pendingCourses.count { it.isLegacyRefreshOnly }
                    val actionable = report.pendingCourses.filterNot { it.isLegacyRefreshOnly }
                    val gradeTask = refreshState.task(RefreshResource.GRADES)
                    CreditProgressUiState(
                        hasConnection = true,
                        loggedIn = connection.status == SchoolConnectionStatus.ACTIVE,
                        connectionId = connection.id,
                        accountKey = accountKey,
                        accountDisplayName = label,
                        refreshing = curriculumSyncing || gradeTask.outcome == RefreshOutcome.RUNNING,
                        notice = notice,
                        plan = plan,
                        curriculumSnapshot = plan.curriculumSnapshot,
                        report = report,
                        legacyRefreshOnlyCount = legacyCount,
                        actionablePendingCourses = actionable,
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CreditProgressUiState(),
        )

    fun dismissNotice() {
        noticeFlow.value = ""
    }

    fun refreshAllSemesters() {
        val current = uiState.value
        if (!current.hasConnection || !current.loggedIn || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            curriculumSyncingFlow.value = true
            try {
                val beforeFetchedAt = creditProgress.getAccountPlan(current.accountKey).curriculumSnapshot?.fetchedAtEpochMillis
                    ?: current.curriculumSnapshot?.fetchedAtEpochMillis
                val task = refreshCoordinator.refresh(
                    resource = RefreshResource.GRADES,
                    queryFingerprint = GradeRepositoryPort.ALL_SEMESTERS,
                )
                val afterFetchedAt = creditProgress.getAccountPlan(current.accountKey).curriculumSnapshot?.fetchedAtEpochMillis
                    ?: uiState.value.curriculumSnapshot?.fetchedAtEpochMillis
                val syncedWithGrades = task.outcome == RefreshOutcome.SUCCESS &&
                    afterFetchedAt != null &&
                    afterFetchedAt != beforeFetchedAt
                val curriculumResult = if (!syncedWithGrades && !task.sessionExpired && current.connectionId.isNotBlank()) {
                    creditProgress.syncCurriculumProgress(current.connectionId)
                } else {
                    Result.failure(UnsupportedOperationException("Skipped redundant curriculum sync"))
                }
                val curriculumUpdated = syncedWithGrades || curriculumResult.isSuccess
                noticeFlow.value = when (task.outcome) {
                    RefreshOutcome.SUCCESS ->
                        if (curriculumUpdated) "培养方案与全部学期成绩已更新" else "全部学期成绩已更新"
                    RefreshOutcome.FAILED ->
                        if (!task.sessionExpired && curriculumResult.isSuccess) {
                            "教务培养方案已同步；${task.message.ifBlank { "全部学期成绩刷新失败，本机缓存已保留" }}"
                        } else {
                            task.message.ifBlank { "全部学期成绩刷新失败，本机缓存已保留" }
                        }
                    RefreshOutcome.CANCELLED -> task.message.ifBlank { "刷新已取消" }
                    else -> ""
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
            } finally {
                curriculumSyncingFlow.value = false
            }
        }
    }

    fun saveTotalTarget(rawTargetInput: String, noteInput: String): Boolean {
        val accountKey = uiState.value.accountKey
        if (accountKey.isBlank()) {
            noticeFlow.value = "请先连接教务账号后再设置学分目标"
            return false
        }
        val trimmed = rawTargetInput.trim()
        val parsedTarget: BigDecimal? = if (trimmed.isEmpty()) {
            null
        } else {
            parseCreditBigDecimal(trimmed) ?: run {
                noticeFlow.value = "请输入有效的非负数字（最多两位小数）"
                return false
            }
        }
        viewModelScope.launch {
            creditProgress.updateTotalTargetCredits(accountKey, parsedTarget)
            creditProgress.updateUserNote(accountKey, noteInput.trim())
            noticeFlow.value = if (parsedTarget == null) "已清除总学分目标" else "已保存总学分目标"
        }
        return true
    }

    fun saveCategory(
        existingCategoryId: String?,
        displayName: String,
        targetInput: String,
        mappedRawAttributes: Set<String>,
        sortOrder: Int,
    ): Boolean {
        val accountKey = uiState.value.accountKey
        if (accountKey.isBlank()) {
            noticeFlow.value = "请先连接教务账号"
            return false
        }
        val cleanName = displayName.trim()
        if (cleanName.isEmpty()) {
            noticeFlow.value = "请输入分类名称"
            return false
        }
        val trimmedTarget = targetInput.trim()
        val parsedTarget: BigDecimal? = if (trimmedTarget.isEmpty()) {
            null
        } else {
            parseCreditBigDecimal(trimmedTarget) ?: run {
                noticeFlow.value = "分类学分目标格式无效"
                return false
            }
        }
        val categoryId = existingCategoryId?.takeIf { it.isNotBlank() }
            ?: "custom_${System.currentTimeMillis()}"
        viewModelScope.launch {
            creditProgress.saveCategoryPlan(
                accountKey,
                CreditCategoryPlan(
                    categoryId = categoryId,
                    displayName = cleanName,
                    targetCredits = parsedTarget,
                    mappedRawAttributes = mappedRawAttributes.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    sortOrder = sortOrder,
                ),
            )
            noticeFlow.value = "已保存分类「$cleanName」"
        }
        return true
    }

    fun deleteCategory(categoryId: String) {
        val accountKey = uiState.value.accountKey
        if (accountKey.isBlank()) return
        viewModelScope.launch {
            creditProgress.deleteCategoryPlan(accountKey, categoryId)
            noticeFlow.value = "已删除分类"
        }
    }

    fun resetCategoriesToDefault() {
        val accountKey = uiState.value.accountKey
        if (accountKey.isBlank()) return
        viewModelScope.launch {
            creditProgress.resetCategoriesToDefault(accountKey)
            noticeFlow.value = "已恢复默认学分分类"
        }
    }

    fun resolveMergeSingleCourse(
        group: PendingCourseGroup,
        assignedCategoryId: String?,
        chosenAnchorKey: String?,
        manualPassConfirmed: Boolean,
        note: String = "",
    ) {
        val accountKey = uiState.value.accountKey
        if (accountKey.isBlank()) return
        val expectedFingerprints = group.records.map { CreditRecordAnchor.buildContentFingerprint(it) }
        viewModelScope.launch {
            creditProgress.saveResolutionRule(
                accountKey,
                CreditResolutionRule(
                    ruleKey = group.suggestedRuleKey,
                    action = CreditResolutionAction.MERGE_AS_SINGLE_COURSE,
                    assignedCategoryId = assignedCategoryId,
                    chosenAnchorKey = chosenAnchorKey,
                    expectedFingerprints = expectedFingerprints,
                    requiresPostRestoreVerification = false,
                    manualPassConfirmed = manualPassConfirmed || PendingReason.SCORE_PENDING_CONFIRMATION in group.reasons && manualPassConfirmed,
                    note = note.trim(),
                ),
            )
            noticeFlow.value = "已确认「${group.courseName}」按单门课程核算"
        }
    }

    fun resolveSplitSeparateCourses(
        group: PendingCourseGroup,
        assignments: List<CreditSplitAssignment>,
        note: String = "",
    ) {
        val accountKey = uiState.value.accountKey
        if (accountKey.isBlank()) return
        val expectedFingerprints = group.records.map { CreditRecordAnchor.buildContentFingerprint(it) }
        viewModelScope.launch {
            creditProgress.saveResolutionRule(
                accountKey,
                CreditResolutionRule(
                    ruleKey = group.suggestedRuleKey,
                    action = CreditResolutionAction.SPLIT_INTO_SEPARATE_COURSES,
                    splitAssignments = assignments,
                    expectedFingerprints = expectedFingerprints,
                    requiresPostRestoreVerification = false,
                    note = note.trim(),
                ),
            )
            noticeFlow.value = "已拆分「${group.courseName}」的学分归属"
        }
    }

    fun resolveExcludeFromPlan(group: PendingCourseGroup, note: String = "") {
        val accountKey = uiState.value.accountKey
        if (accountKey.isBlank()) return
        val expectedFingerprints = group.records.map { CreditRecordAnchor.buildContentFingerprint(it) }
        viewModelScope.launch {
            creditProgress.saveResolutionRule(
                accountKey,
                CreditResolutionRule(
                    ruleKey = group.suggestedRuleKey,
                    action = CreditResolutionAction.EXCLUDE_FROM_PLAN,
                    expectedFingerprints = expectedFingerprints,
                    requiresPostRestoreVerification = false,
                    note = note.trim(),
                ),
            )
            noticeFlow.value = "已将「${group.courseName}」设为不计入当前计划"
        }
    }

    fun confirmPostRestoreRule(ruleKey: String) {
        val accountKey = uiState.value.accountKey
        if (accountKey.isBlank()) return
        viewModelScope.launch {
            creditProgress.clearPostRestoreVerification(accountKey, ruleKey)
            noticeFlow.value = "已核认恢复的确认规则"
        }
    }

    fun findAssociatedRuleKey(groupKey: String): String? {
        val rules = uiState.value.plan.rules
        return rules.firstOrNull { rule ->
            rule.ruleKey == groupKey || rule.splitAssignments.any { it.customGroupKey == groupKey }
        }?.ruleKey
    }

    fun deleteRule(ruleKey: String) {
        val accountKey = uiState.value.accountKey
        if (accountKey.isBlank()) return
        viewModelScope.launch {
            creditProgress.deleteResolutionRule(accountKey, ruleKey)
            noticeFlow.value = "已撤销人工确认规则，恢复自动判定"
        }
    }

    private fun pickConnection(connections: List<SchoolConnection>): SchoolConnection? =
        connections.firstOrNull { it.status == SchoolConnectionStatus.ACTIVE }
            ?: connections.firstOrNull { it.status != SchoolConnectionStatus.LOGGED_OUT }
            ?: connections.maxByOrNull { it.lastLoginAt }
}
