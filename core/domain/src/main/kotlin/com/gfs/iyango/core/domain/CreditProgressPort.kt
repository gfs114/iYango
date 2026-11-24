package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.CreditAccountPlan
import com.gfs.iyango.core.model.CreditAccountPlanBackup
import com.gfs.iyango.core.model.CreditBackupPreview
import com.gfs.iyango.core.model.CreditCategoryPlan
import com.gfs.iyango.core.model.CreditResolutionRule
import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import com.gfs.iyango.core.model.GradeRecord
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 学分进度目标、分类、人工消歧规则及备份迁移端口（与成绩快照隔离持久化）。 */
interface CreditProgressPort {

    fun observeAccountPlan(accountKey: String): Flow<CreditAccountPlan>

    suspend fun getAccountPlan(accountKey: String): CreditAccountPlan

    suspend fun updateTotalTargetCredits(accountKey: String, totalTargetCredits: BigDecimal?)

    suspend fun updateUserNote(accountKey: String, userNote: String)

    suspend fun saveCategoryPlan(accountKey: String, category: CreditCategoryPlan)

    suspend fun deleteCategoryPlan(accountKey: String, categoryId: String)

    suspend fun resetCategoriesToDefault(accountKey: String)

    suspend fun saveResolutionRule(accountKey: String, rule: CreditResolutionRule)

    suspend fun deleteResolutionRule(accountKey: String, ruleKey: String)

    suspend fun clearPostRestoreVerification(accountKey: String, ruleKey: String)

    /** 在重新获取全学期成绩后，核验来自备份的人工规则指纹，仅完全一致者自动解除待校验状态。 */
    suspend fun verifyRestoredRulesAgainstGrades(accountKey: String, records: List<GradeRecord>)

    /** 从临时连接 id / 旧账号主体 / 待匹配备份指纹迁移计划到真实账号主体。 */
    suspend fun migrateAccountIdentity(
        fromAccountKey: String,
        toAccountKey: String,
        schoolId: String = "YGU",
    )

    suspend fun deleteAccount(accountKey: String)

    suspend fun exportAllPlans(): List<CreditAccountPlanBackup>

    suspend fun previewBackupImport(
        backups: List<CreditAccountPlanBackup>,
        knownAccountKeysByFingerprint: Map<String, String> = emptyMap(),
    ): CreditBackupPreview

    suspend fun importBackupPlans(
        backups: List<CreditAccountPlanBackup>,
        overwriteConflicts: Boolean = false,
        knownAccountKeysByFingerprint: Map<String, String> = emptyMap(),
    )

    suspend fun saveCurriculumSnapshot(
        accountKey: String,
        snapshot: CurriculumProgressSnapshot,
    ) = Unit

    suspend fun syncCurriculumProgress(
        connectionId: String,
    ): Result<CurriculumProgressSnapshot> =
        Result.failure(UnsupportedOperationException("Not supported"))
}

/** 测试或未接入实现时的默认空操作端口。 */
object NoopCreditProgressPort : CreditProgressPort {
    override fun observeAccountPlan(accountKey: String): Flow<CreditAccountPlan> =
        flowOf(CreditAccountPlan(accountKey = accountKey))

    override suspend fun getAccountPlan(accountKey: String): CreditAccountPlan =
        CreditAccountPlan(accountKey = accountKey)

    override suspend fun updateTotalTargetCredits(accountKey: String, totalTargetCredits: BigDecimal?) = Unit
    override suspend fun updateUserNote(accountKey: String, userNote: String) = Unit
    override suspend fun saveCategoryPlan(accountKey: String, category: CreditCategoryPlan) = Unit
    override suspend fun deleteCategoryPlan(accountKey: String, categoryId: String) = Unit
    override suspend fun resetCategoriesToDefault(accountKey: String) = Unit
    override suspend fun saveResolutionRule(accountKey: String, rule: CreditResolutionRule) = Unit
    override suspend fun deleteResolutionRule(accountKey: String, ruleKey: String) = Unit
    override suspend fun clearPostRestoreVerification(accountKey: String, ruleKey: String) = Unit
    override suspend fun verifyRestoredRulesAgainstGrades(accountKey: String, records: List<GradeRecord>) = Unit
    override suspend fun migrateAccountIdentity(fromAccountKey: String, toAccountKey: String, schoolId: String) = Unit
    override suspend fun deleteAccount(accountKey: String) = Unit
    override suspend fun exportAllPlans(): List<CreditAccountPlanBackup> = emptyList()
    override suspend fun previewBackupImport(
        backups: List<CreditAccountPlanBackup>,
        knownAccountKeysByFingerprint: Map<String, String>,
    ): CreditBackupPreview = CreditBackupPreview()

    override suspend fun importBackupPlans(
        backups: List<CreditAccountPlanBackup>,
        overwriteConflicts: Boolean,
        knownAccountKeysByFingerprint: Map<String, String>,
    ) = Unit
}
