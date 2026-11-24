package com.gfs.iyango.data.repository

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.CreditProgressCalculator
import com.gfs.iyango.core.domain.CreditProgressPort
import com.gfs.iyango.core.domain.SchoolCredentialStore
import com.gfs.iyango.core.model.CreditAccountPlan
import com.gfs.iyango.core.model.CreditAccountPlanBackup
import com.gfs.iyango.core.model.CreditBackupConflict
import com.gfs.iyango.core.model.CreditBackupPreview
import com.gfs.iyango.core.model.CreditCategoryPlan
import com.gfs.iyango.core.model.CreditCategoryPlanBackup
import com.gfs.iyango.core.model.CreditRecordAnchor
import com.gfs.iyango.core.model.CreditResolutionRule
import com.gfs.iyango.core.model.CreditResolutionRuleBackup
import com.gfs.iyango.core.model.CreditSplitAssignment
import com.gfs.iyango.core.model.CreditSplitAssignmentBackup
import com.gfs.iyango.core.model.CurriculumProgressSnapshot
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.defaultCreditCategories
import com.gfs.iyango.core.model.formatCreditBigDecimal
import com.gfs.iyango.core.model.parseCreditBigDecimal
import com.gfs.iyango.core.model.toCreditScale
import com.gfs.iyango.data.db.CreditPlanEntity
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.school.ygu.YguCurriculumClient
import com.gfs.iyango.data.school.ygu.YguCurriculumClientStatus
import java.math.BigDecimal
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** [CreditProgressPort] 的 Room 持久化与备份协同实现。 */
@Singleton
class RoomCreditProgressRepository private constructor(
    private val db: IYangoDatabase,
    private val clock: Clock,
    private val credentialStore: SchoolCredentialStore?,
    private val authRepositoryProvider: Provider<RoomSchoolAuthRepository>?,
    @Suppress("UNUSED_PARAMETER") internalMarker: Int,
) : CreditProgressPort {

    @Inject
    constructor(
        db: IYangoDatabase,
        clock: Clock,
        credentialStore: SchoolCredentialStore,
        authRepositoryProvider: Provider<RoomSchoolAuthRepository>,
    ) : this(
        db = db,
        clock = clock,
        credentialStore = credentialStore,
        authRepositoryProvider = authRepositoryProvider,
        internalMarker = 0,
    )

    constructor(
        db: IYangoDatabase,
        clock: Clock,
        credentialStore: SchoolCredentialStore? = null,
    ) : this(
        db = db,
        clock = clock,
        credentialStore = credentialStore,
        authRepositoryProvider = null,
        internalMarker = 0,
    )

    internal var createCurriculumClient: () -> YguCurriculumClient = {
        YguCurriculumClient(clockMillis = clock::millis)
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val curriculumSyncLock = Any()
    private val inFlightCurriculumSync =
        ConcurrentHashMap<String, Deferred<Result<CurriculumProgressSnapshot>>>()

    private val dao get() = db.creditProgressDao()

    override fun observeAccountPlan(accountKey: String): Flow<CreditAccountPlan> =
        combine(
            dao.observePlan(accountKey),
            dao.observeCategories(accountKey),
            dao.observeRules(accountKey),
        ) { plan, categories, rules ->
            CreditAccountPlan(
                accountKey = accountKey,
                schoolId = plan?.schoolId ?: DEFAULT_SCHOOL_ID,
                totalTargetCredits = plan?.totalTargetCreditsText?.let(::parseCreditBigDecimal),
                userNote = plan?.userNote.orEmpty(),
                categories = if (categories.isEmpty()) defaultCreditCategories() else categories.map { it.toModel() },
                rules = rules.map { it.toModel() },
                updatedAtEpochMillis = plan?.updatedAtEpochMillis ?: 0L,
                curriculumSnapshot = plan?.curriculumSnapshotJson?.let(CreditJsonCodec::decodeCurriculumSnapshot),
            )
        }

    override suspend fun getAccountPlan(accountKey: String): CreditAccountPlan {
        val plan = dao.getPlan(accountKey)
        val categories = dao.getCategories(accountKey)
        val rules = dao.getRules(accountKey)
        return CreditAccountPlan(
            accountKey = accountKey,
            schoolId = plan?.schoolId ?: DEFAULT_SCHOOL_ID,
            totalTargetCredits = plan?.totalTargetCreditsText?.let(::parseCreditBigDecimal),
            userNote = plan?.userNote.orEmpty(),
            categories = if (categories.isEmpty()) defaultCreditCategories() else categories.map { it.toModel() },
            rules = rules.map { it.toModel() },
            updatedAtEpochMillis = plan?.updatedAtEpochMillis ?: 0L,
            curriculumSnapshot = plan?.curriculumSnapshotJson?.let(CreditJsonCodec::decodeCurriculumSnapshot),
        )
    }

    override suspend fun updateTotalTargetCredits(accountKey: String, totalTargetCredits: BigDecimal?) {
        if (accountKey.isBlank()) return
        val now = clock.millis()
        val normalizedText = totalTargetCredits?.toCreditScale()?.toPlainString()
        db.withTransaction {
            val existing = dao.getPlan(accountKey)
            dao.upsertPlan(
                CreditPlanEntity(
                    accountKey = accountKey,
                    schoolId = existing?.schoolId ?: DEFAULT_SCHOOL_ID,
                    totalTargetCreditsText = normalizedText,
                    userNote = existing?.userNote.orEmpty(),
                    curriculumSnapshotJson = existing?.curriculumSnapshotJson.orEmpty(),
                    updatedAtEpochMillis = now,
                ),
            )
        }
    }

    override suspend fun updateUserNote(accountKey: String, userNote: String) {
        if (accountKey.isBlank()) return
        val now = clock.millis()
        db.withTransaction {
            val existing = dao.getPlan(accountKey)
            dao.upsertPlan(
                CreditPlanEntity(
                    accountKey = accountKey,
                    schoolId = existing?.schoolId ?: DEFAULT_SCHOOL_ID,
                    totalTargetCreditsText = existing?.totalTargetCreditsText,
                    userNote = userNote,
                    curriculumSnapshotJson = existing?.curriculumSnapshotJson.orEmpty(),
                    updatedAtEpochMillis = now,
                ),
            )
        }
    }

    override suspend fun saveCurriculumSnapshot(
        accountKey: String,
        snapshot: CurriculumProgressSnapshot,
    ) {
        val trimmed = accountKey.trim()
        if (trimmed.isEmpty()) return
        val now = if (snapshot.fetchedAtEpochMillis > 0L) snapshot.fetchedAtEpochMillis else clock.millis()
        val encoded = CreditJsonCodec.encodeCurriculumSnapshot(snapshot)
        db.withTransaction {
            val existing = dao.getPlan(trimmed)
            dao.upsertPlan(
                CreditPlanEntity(
                    accountKey = trimmed,
                    schoolId = existing?.schoolId ?: DEFAULT_SCHOOL_ID,
                    totalTargetCreditsText = existing?.totalTargetCreditsText,
                    userNote = existing?.userNote.orEmpty(),
                    curriculumSnapshotJson = encoded,
                    updatedAtEpochMillis = maxOf(existing?.updatedAtEpochMillis ?: 0L, now),
                ),
            )
        }
    }

    override suspend fun syncCurriculumProgress(connectionId: String): Result<CurriculumProgressSnapshot> {
        if (connectionId.isBlank()) {
            return Result.failure(IllegalStateException("未找到该教务账号，请重新登录"))
        }
        val deferred = synchronized(curriculumSyncLock) {
            inFlightCurriculumSync[connectionId]?.takeIf { it.isActive } ?: run {
                val created = syncScope.async(start = CoroutineStart.LAZY) {
                    executeSyncCurriculumProgress(connectionId)
                }
                inFlightCurriculumSync[connectionId] = created
                created.invokeOnCompletion {
                    synchronized(curriculumSyncLock) {
                        if (inFlightCurriculumSync[connectionId] === created) {
                            inFlightCurriculumSync.remove(connectionId)
                        }
                    }
                }
                created.start()
                created
            }
        }
        return deferred.await()
    }

    private suspend fun executeSyncCurriculumProgress(connectionId: String): Result<CurriculumProgressSnapshot> {
        val store = credentialStore
            ?: return Result.failure(IllegalStateException("未配置教务凭据存储，无法同步培养方案"))
        val connection = db.schoolConnectionDao().getById(connectionId)
            ?: return Result.failure(IllegalStateException("未找到该教务账号，请重新登录"))
        val session = SchoolSessionRequest.open(db, store, authRepositoryProvider?.get(), connection)
            ?: return Result.failure(IllegalStateException("登录状态已失效，请重新登录"))
        var credential = session.credential
        var client = createCurriculumClient()
        var outcome = try {
            client.fetch(credential.cookieHeader)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.failure(IllegalStateException("培养方案网络暂不可用，请检查网络后重试"))
        }
        if (outcome.status == YguCurriculumClientStatus.LOGIN) {
            val relogged = session.renew()
            if (relogged != null) {
                credential = relogged
                client = createCurriculumClient()
                outcome = try {
                    client.fetch(credential.cookieHeader)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return Result.failure(IllegalStateException("培养方案网络暂不可用，请检查网络后重试"))
                }
            }
        }
        return when (outcome.status) {
            YguCurriculumClientStatus.OK -> {
                val snapshot = outcome.snapshot
                    ?: return Result.failure(IllegalStateException("未能从教务页面解析培养方案数据"))
                val accepted = session.finish(outcome.cookieHeader) { current ->
                    saveCurriculumSnapshot(current.accountSubject.ifEmpty { current.id }, snapshot)
                }
                if (!accepted) return Result.failure(IllegalStateException("登录状态已失效，请重新登录"))
                Result.success(snapshot)
            }
            YguCurriculumClientStatus.LOGIN -> {
                session.expire()
                Result.failure(IllegalStateException(outcome.message.ifEmpty { "登录状态已失效，请重新登录" }))
            }
            else -> {
                session.finish(outcome.cookieHeader)
                Result.failure(IllegalStateException(outcome.message.ifEmpty { "培养方案同步失败，请稍后重试" }))
            }
        }
    }

    override suspend fun saveCategoryPlan(accountKey: String, category: CreditCategoryPlan) {
        if (accountKey.isBlank() || category.categoryId.isBlank()) return
        val now = clock.millis()
        db.withTransaction {
            ensureCategoriesSeededLocked(accountKey)
            dao.upsertCategory(category.toEntity(accountKey))
            touchPlanTimestampLocked(accountKey, now)
        }
    }

    override suspend fun deleteCategoryPlan(accountKey: String, categoryId: String) {
        if (accountKey.isBlank() || categoryId.isBlank()) return
        val now = clock.millis()
        db.withTransaction {
            ensureCategoriesSeededLocked(accountKey)
            dao.deleteCategory(accountKey, categoryId)
            touchPlanTimestampLocked(accountKey, now)
        }
    }

    override suspend fun resetCategoriesToDefault(accountKey: String) {
        if (accountKey.isBlank()) return
        val now = clock.millis()
        db.withTransaction {
            dao.deleteAllCategories(accountKey)
            touchPlanTimestampLocked(accountKey, now)
        }
    }

    override suspend fun saveResolutionRule(accountKey: String, rule: CreditResolutionRule) {
        if (accountKey.isBlank() || rule.ruleKey.isBlank()) return
        val now = clock.millis()
        val stampedRule = rule.copy(
            requiresPostRestoreVerification = false,
            updatedAtEpochMillis = if (rule.updatedAtEpochMillis > 0L) rule.updatedAtEpochMillis else now,
        )
        db.withTransaction {
            dao.upsertRule(stampedRule.toEntity(accountKey))
            touchPlanTimestampLocked(accountKey, now)
        }
    }

    override suspend fun deleteResolutionRule(accountKey: String, ruleKey: String) {
        if (accountKey.isBlank() || ruleKey.isBlank()) return
        val now = clock.millis()
        db.withTransaction {
            dao.deleteRule(accountKey, ruleKey)
            touchPlanTimestampLocked(accountKey, now)
        }
    }

    override suspend fun clearPostRestoreVerification(accountKey: String, ruleKey: String) {
        if (accountKey.isBlank() || ruleKey.isBlank()) return
        db.withTransaction {
            dao.clearPostRestoreVerification(accountKey, ruleKey)
        }
    }

    override suspend fun verifyRestoredRulesAgainstGrades(
        accountKey: String,
        records: List<GradeRecord>,
    ) {
        if (accountKey.isBlank()) return
        val fingerprintsByRuleKey = CreditProgressCalculator.bucketFingerprintsByRuleKey(records)
        db.withTransaction {
            val rules = dao.getRules(accountKey)
            if (rules.none { it.requiresPostRestoreVerification }) return@withTransaction
            val categories = dao.getCategories(accountKey)
                .map { it.categoryId }
                .ifEmpty { defaultCreditCategories().map { it.categoryId } }
                .toSet()

            for (entity in rules) {
                if (!entity.requiresPostRestoreVerification) continue
                val rule = entity.toModel()
                val actualFingerprints = fingerprintsByRuleKey[rule.ruleKey] ?: continue
                val expected = rule.expectedFingerprints
                if (expected.isEmpty() || expected.sorted() != actualFingerprints.sorted()) continue

                val categoryValid = rule.assignedCategoryId == null || rule.assignedCategoryId in categories
                val splitCategoriesValid = rule.splitAssignments.all {
                    it.assignedCategoryId == null || it.assignedCategoryId in categories
                }
                if (categoryValid && splitCategoriesValid) {
                    dao.clearPostRestoreVerification(accountKey, rule.ruleKey)
                }
            }
        }
    }

    override suspend fun migrateAccountIdentity(
        fromAccountKey: String,
        toAccountKey: String,
        schoolId: String,
    ) {
        val targetKey = toAccountKey.trim()
        if (targetKey.isEmpty()) return
        val pendingKey = pendingKeyFor(schoolId, targetKey)
        val candidates = listOf(fromAccountKey.trim(), pendingKey)
            .filter { it.isNotEmpty() && it != targetKey }
            .distinct()
        if (candidates.isEmpty()) return

        db.withTransaction {
            for (sourceKey in candidates) {
                val sourcePlan = dao.getPlan(sourceKey)
                val sourceCategories = dao.getCategories(sourceKey)
                val sourceRules = dao.getRules(sourceKey)
                val hasSourceData = sourcePlan != null || sourceCategories.isNotEmpty() || sourceRules.isNotEmpty()
                if (!hasSourceData) continue

                val targetHasCustomization = hasCustomPlanLocked(targetKey)
                if (!targetHasCustomization) {
                    if (sourcePlan != null) {
                        dao.upsertPlan(sourcePlan.copy(accountKey = targetKey))
                    } else {
                        touchPlanTimestampLocked(targetKey, clock.millis())
                    }
                    if (sourceCategories.isNotEmpty()) {
                        dao.upsertCategories(sourceCategories.map { it.copy(accountKey = targetKey) })
                    }
                    if (sourceRules.isNotEmpty()) {
                        dao.upsertRules(sourceRules.map { it.copy(accountKey = targetKey) })
                    }
                }
                dao.deletePlan(sourceKey)
                dao.deleteAllCategories(sourceKey)
                dao.deleteAllRules(sourceKey)
            }
        }
    }

    override suspend fun deleteAccount(accountKey: String) {
        val trimmed = accountKey.trim()
        if (trimmed.isEmpty()) return
        db.withTransaction {
            dao.deletePlan(trimmed)
            dao.deleteAllCategories(trimmed)
            dao.deleteAllRules(trimmed)
            if (!trimmed.startsWith(PENDING_PREFIX)) {
                val pendingKey = pendingKeyFor(DEFAULT_SCHOOL_ID, trimmed)
                dao.deletePlan(pendingKey)
                dao.deleteAllCategories(pendingKey)
                dao.deleteAllRules(pendingKey)
            }
        }
    }

    override suspend fun exportAllPlans(): List<CreditAccountPlanBackup> {
        val plans = dao.getAllPlans()
        val result = mutableListOf<CreditAccountPlanBackup>()
        for (plan in plans) {
            val key = plan.accountKey
            val categories = dao.getCategories(key).map { it.toModel() }
            val rules = dao.getRules(key).map { it.toModel() }
            val curriculumSnapshot = CreditJsonCodec.decodeCurriculumSnapshot(plan.curriculumSnapshotJson)
            val hasCustomization = plan.totalTargetCreditsText != null ||
                plan.userNote.isNotBlank() ||
                categories.isNotEmpty() ||
                rules.isNotEmpty() ||
                curriculumSnapshot != null
            if (!hasCustomization) continue

            val fingerprint = if (key.startsWith(PENDING_PREFIX)) {
                key.removePrefix(PENDING_PREFIX)
            } else {
                CreditRecordAnchor.accountSubjectFingerprint(plan.schoolId, key)
            }
            result += CreditAccountPlanBackup(
                schoolId = plan.schoolId,
                subjectFingerprint = fingerprint,
                totalTargetCreditsText = plan.totalTargetCreditsText,
                userNote = plan.userNote,
                categories = categories.map { cat ->
                    CreditCategoryPlanBackup(
                        categoryId = cat.categoryId,
                        displayName = cat.displayName,
                        targetCreditsText = cat.targetCredits?.toCreditScale()?.toPlainString(),
                        mappedRawAttributes = cat.mappedRawAttributes.sorted(),
                        sortOrder = cat.sortOrder,
                    )
                },
                rules = rules.map { rule ->
                    CreditResolutionRuleBackup(
                        ruleKey = rule.ruleKey,
                        action = rule.action,
                        assignedCategoryId = rule.assignedCategoryId,
                        chosenAnchorKey = rule.chosenAnchorKey,
                        splitAssignments = rule.splitAssignments.map { s ->
                            CreditSplitAssignmentBackup(
                                anchorKey = s.anchorKey,
                                occurrenceIndex = s.occurrenceIndex,
                                customGroupKey = s.customGroupKey,
                                assignedCategoryId = s.assignedCategoryId,
                                expectedFingerprint = s.expectedFingerprint,
                                exclude = s.exclude,
                                manualPassConfirmed = s.manualPassConfirmed,
                            )
                        },
                        expectedFingerprints = rule.expectedFingerprints,
                        manualPassConfirmed = rule.manualPassConfirmed,
                        note = rule.note,
                        updatedAtEpochMillis = rule.updatedAtEpochMillis,
                    )
                },
                updatedAtEpochMillis = plan.updatedAtEpochMillis,
                curriculumSnapshot = curriculumSnapshot,
            )
        }
        return result
    }

    override suspend fun previewBackupImport(
        backups: List<CreditAccountPlanBackup>,
        knownAccountKeysByFingerprint: Map<String, String>,
    ): CreditBackupPreview {
        if (backups.isEmpty()) return CreditBackupPreview()
        val combinedMap = buildFingerprintToLocalAccountMap(knownAccountKeysByFingerprint)
        var matched = 0
        var unmatched = 0
        val conflicts = mutableListOf<CreditBackupConflict>()

        for (backup in backups) {
            val localAccountKey = combinedMap[backup.subjectFingerprint]
            if (localAccountKey == null) {
                unmatched++
            } else {
                matched++
                if (hasCustomPlanLocked(localAccountKey)) {
                    val localPlan = getAccountPlan(localAccountKey)
                    conflicts += CreditBackupConflict(
                        matchedAccountKey = localAccountKey,
                        schoolId = backup.schoolId,
                        subjectFingerprint = backup.subjectFingerprint,
                        localUpdatedAtEpochMillis = localPlan.updatedAtEpochMillis,
                        backupUpdatedAtEpochMillis = backup.updatedAtEpochMillis,
                        localSummary = summarizeLocalPlan(localPlan),
                        backupSummary = summarizeBackupPlan(backup),
                    )
                }
            }
        }
        return CreditBackupPreview(
            matchedPlansCount = matched,
            unmatchedPendingPlansCount = unmatched,
            conflicts = conflicts,
        )
    }

    override suspend fun importBackupPlans(
        backups: List<CreditAccountPlanBackup>,
        overwriteConflicts: Boolean,
        knownAccountKeysByFingerprint: Map<String, String>,
    ) {
        if (backups.isEmpty()) return
        val combinedMap = buildFingerprintToLocalAccountMap(knownAccountKeysByFingerprint)
        val now = clock.millis()

        db.withTransaction {
            for (backup in backups) {
                val matchedAccountKey = combinedMap[backup.subjectFingerprint]
                val targetAccountKey = matchedAccountKey ?: "$PENDING_PREFIX${backup.subjectFingerprint}"
                if (matchedAccountKey != null && !overwriteConflicts && hasCustomPlanLocked(matchedAccountKey)) {
                    continue
                }
                if (matchedAccountKey == null && !overwriteConflicts && hasCustomPlanLocked(targetAccountKey)) {
                    continue
                }

                val parsedTarget = backup.totalTargetCreditsText?.let(::parseCreditBigDecimal)
                val existingSnapshotJson = dao.getPlan(targetAccountKey)?.curriculumSnapshotJson.orEmpty()
                val snapshotJson = backup.curriculumSnapshot
                    ?.let(CreditJsonCodec::encodeCurriculumSnapshot)
                    ?: existingSnapshotJson
                dao.deletePlan(targetAccountKey)
                dao.deleteAllCategories(targetAccountKey)
                dao.deleteAllRules(targetAccountKey)

                dao.upsertPlan(
                    CreditPlanEntity(
                        accountKey = targetAccountKey,
                        schoolId = backup.schoolId.ifBlank { DEFAULT_SCHOOL_ID },
                        totalTargetCreditsText = parsedTarget?.toPlainString(),
                        userNote = backup.userNote,
                        curriculumSnapshotJson = snapshotJson,
                        updatedAtEpochMillis = if (backup.updatedAtEpochMillis > 0L) backup.updatedAtEpochMillis else now,
                    ),
                )
                if (backup.categories.isNotEmpty()) {
                    dao.upsertCategories(
                        backup.categories.map { cat ->
                            CreditCategoryPlan(
                                categoryId = cat.categoryId,
                                displayName = cat.displayName,
                                targetCredits = cat.targetCreditsText?.let(::parseCreditBigDecimal),
                                mappedRawAttributes = cat.mappedRawAttributes.filter { it.isNotBlank() }.toSet(),
                                sortOrder = cat.sortOrder,
                            ).toEntity(targetAccountKey)
                        },
                    )
                }
                if (backup.rules.isNotEmpty()) {
                    dao.upsertRules(
                        backup.rules.map { rule ->
                            CreditResolutionRule(
                                ruleKey = rule.ruleKey,
                                action = rule.action,
                                assignedCategoryId = rule.assignedCategoryId,
                                chosenAnchorKey = rule.chosenAnchorKey,
                                splitAssignments = rule.splitAssignments.map { s ->
                                    CreditSplitAssignment(
                                        anchorKey = s.anchorKey,
                                        occurrenceIndex = s.occurrenceIndex,
                                        customGroupKey = s.customGroupKey,
                                        assignedCategoryId = s.assignedCategoryId,
                                        expectedFingerprint = s.expectedFingerprint,
                                        exclude = s.exclude,
                                        manualPassConfirmed = s.manualPassConfirmed,
                                    )
                                },
                                expectedFingerprints = rule.expectedFingerprints,
                                requiresPostRestoreVerification = true,
                                manualPassConfirmed = rule.manualPassConfirmed,
                                note = rule.note,
                                updatedAtEpochMillis = rule.updatedAtEpochMillis,
                            ).toEntity(targetAccountKey)
                        },
                    )
                }
            }
        }
    }

    private suspend fun buildFingerprintToLocalAccountMap(
        externalMap: Map<String, String>,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (plan in dao.getAllPlans()) {
            val key = plan.accountKey
            if (!key.startsWith(PENDING_PREFIX)) {
                val fp = CreditRecordAnchor.accountSubjectFingerprint(plan.schoolId, key)
                result[fp] = key
            }
        }
        result.putAll(externalMap)
        return result
    }

    private suspend fun hasCustomPlanLocked(accountKey: String): Boolean {
        val plan = dao.getPlan(accountKey)
        val categories = dao.getCategories(accountKey)
        val rules = dao.getRules(accountKey)
        return plan?.totalTargetCreditsText != null ||
            !plan?.userNote.isNullOrBlank() ||
            categories.isNotEmpty() ||
            rules.isNotEmpty()
    }

    private suspend fun ensureCategoriesSeededLocked(accountKey: String) {
        if (dao.getCategories(accountKey).isEmpty()) {
            dao.upsertCategories(defaultCreditCategories().map { it.toEntity(accountKey) })
        }
    }

    private suspend fun touchPlanTimestampLocked(accountKey: String, now: Long) {
        val existing = dao.getPlan(accountKey)
        dao.upsertPlan(
            CreditPlanEntity(
                accountKey = accountKey,
                schoolId = existing?.schoolId ?: DEFAULT_SCHOOL_ID,
                totalTargetCreditsText = existing?.totalTargetCreditsText,
                userNote = existing?.userNote.orEmpty(),
                curriculumSnapshotJson = existing?.curriculumSnapshotJson.orEmpty(),
                updatedAtEpochMillis = now,
            ),
        )
    }

    private fun summarizeLocalPlan(plan: CreditAccountPlan): String {
        val targetText = plan.totalTargetCredits?.let(::formatCreditBigDecimal) ?: "未设置"
        return "总目标 $targetText 学分 · ${plan.categories.size} 个分类 · ${plan.rules.size} 条确认规则"
    }

    private fun summarizeBackupPlan(backup: CreditAccountPlanBackup): String {
        val parsed = backup.totalTargetCreditsText?.let(::parseCreditBigDecimal)
        val targetText = parsed?.let(::formatCreditBigDecimal) ?: "未设置"
        val categoryCount = if (backup.categories.isEmpty()) defaultCreditCategories().size else backup.categories.size
        return "总目标 $targetText 学分 · $categoryCount 个分类 · ${backup.rules.size} 条确认规则"
    }

    companion object {
        const val DEFAULT_SCHOOL_ID = "YGU"
        const val PENDING_PREFIX = "pending:"

        fun pendingKeyFor(schoolId: String, accountKey: String): String =
            "$PENDING_PREFIX${CreditRecordAnchor.accountSubjectFingerprint(schoolId, accountKey)}"
    }
}
