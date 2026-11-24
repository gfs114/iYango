package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.CreditProgressPort
import com.gfs.iyango.core.domain.GradeChangeDetector
import com.gfs.iyango.core.domain.GradeQueryPort
import com.gfs.iyango.core.domain.GradeQueryResult
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.NoopCreditProgressPort
import com.gfs.iyango.core.domain.RefreshHandler
import com.gfs.iyango.core.domain.RefreshResource
import com.gfs.iyango.core.domain.SessionExpiredException
import com.gfs.iyango.data.db.IYangoDatabase
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * F05 成绩刷新：只用已确认的查询范围（`queryFingerprint` 即缓存分区键，
 * `*` 表示全部学期）重新查询并写入 E03 缓存；没有确认条件时协调器会跳过，
 * 不发无界查询。
 */
@Singleton
class GradeRefreshHandler @Inject constructor(
    private val db: IYangoDatabase,
    private val query: GradeQueryPort,
    private val repository: GradeRepositoryPort,
    private val creditProgressRepository: CreditProgressPort = NoopCreditProgressPort,
    private val notificationBridge: GradeRefreshNotificationBridge = GradeRefreshNotificationBridge(),
) : RefreshHandler {

    override val resource: RefreshResource = RefreshResource.GRADES

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val curriculumSyncRunning = AtomicBoolean(false)

    override fun accepts(queryFingerprint: String): Boolean = queryFingerprint.isNotEmpty()

    override suspend fun refresh(queryFingerprint: String) {
        val allConnections = db.schoolConnectionDao().getAll()
        val connection = db.schoolConnectionDao().getActive()
            ?: allConnections
                .firstOrNull { it.status != com.gfs.iyango.core.model.SchoolConnectionStatus.LOGGED_OUT && it.rememberPassword }
            ?: if (allConnections.any { it.status != com.gfs.iyango.core.model.SchoolConnectionStatus.LOGGED_OUT }) {
                throw SessionExpiredException("登录已过期，请重新登录")
            } else {
                return
            }
        val accountKey = connection.accountSubject.ifEmpty { connection.id }
        val partition = queryFingerprint
        val semester = partition.takeUnless { it == GradeRepositoryPort.ALL_SEMESTERS }.orEmpty()
        val result = try {
            query.query(connection.id, semester)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("成绩刷新失败，本机缓存已保留")
        }
        when (result) {
            is GradeQueryResult.Success -> {
                check(result.complete) { "成绩未完整读取，本机缓存已保留" }
                val termSnapshotExists = db.gradeSnapshotDao().get(accountKey, partition) != null
                val fallbackAllBaseline = !termSnapshotExists &&
                    partition != GradeRepositoryPort.ALL_SEMESTERS &&
                    db.gradeSnapshotDao().get(accountKey, GradeRepositoryPort.ALL_SEMESTERS) != null
                val hadBaseline = termSnapshotExists || fallbackAllBaseline
                val sourcePartition = if (fallbackAllBaseline) GradeRepositoryPort.ALL_SEMESTERS else partition
                val previousRecords = if (hadBaseline) {
                    db.gradeRecordDao().getAll(accountKey, sourcePartition).map { entity ->
                        val model = entity.toModel()
                        val realSemester = GradeChangeDetector.resolveRealSemester(model)
                        if (realSemester == model.semester) model else model.copy(semester = realSemester)
                    }.let { list ->
                        if (fallbackAllBaseline) list.filter { it.semester == partition } else list
                    }
                } else {
                    emptyList()
                }
                repository.saveCompleteResultWithCoverage(
                    accountKey = accountKey,
                    semester = partition,
                    records = result.records,
                    queryFingerprint = semester,
                    parserVersion = PARSER_VERSION,
                    coverageMetadata = result.coverageMetadata,
                )
                val normalizedNew = result.records.map { record ->
                    val realSemester = GradeChangeDetector.resolveRealSemester(record)
                    if (realSemester == record.semester) record else record.copy(semester = realSemester)
                }
                notificationBridge.recordCompletedSync(
                    accountKey = accountKey,
                    partition = partition,
                    hadBaseline = hadBaseline,
                    previousRecords = previousRecords,
                    newRecords = normalizedNew,
                )
                if (partition == GradeRepositoryPort.ALL_SEMESTERS) {
                    creditProgressRepository.verifyRestoredRulesAgainstGrades(
                        accountKey = accountKey,
                        records = result.records,
                    )
                    if (curriculumSyncRunning.compareAndSet(false, true)) {
                        backgroundScope.launch {
                            try {
                                creditProgressRepository.syncCurriculumProgress(connection.id)
                            } catch (_: Exception) {
                                // Keep grade refresh fast & resilient if curriculum page is temporarily unavailable.
                            } finally {
                                curriculumSyncRunning.set(false)
                            }
                        }
                    }
                }
            }
            is GradeQueryResult.SessionExpired -> throw SessionExpiredException(result.message)
            is GradeQueryResult.Failed -> throw IllegalStateException(result.message)
        }
    }

    private companion object {
        const val PARSER_VERSION = 1
    }
}
