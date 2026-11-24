package com.gfs.iyango.data.repository

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.CreditGradeSourceSnapshot
import com.gfs.iyango.core.domain.GradeCoverageMetadata
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSnapshot
import com.gfs.iyango.data.db.GradeRecordEntity
import com.gfs.iyango.data.db.GradeSnapshotEntity
import com.gfs.iyango.data.db.IYangoDatabase
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * [GradeRepositoryPort] 的 Room 实现。
 *
 * [saveCompleteResult] 在单事务内比较旧快照与新记录：首次缓存不标记新发布；
 * 之后新增记录标 isNew、分数变化的记录标 isModified 并保留旧分数文本；
 * 已读状态在同一分区内保留，直到 [markChangesRead]。
 */
@Singleton
class RoomGradeRepository @Inject constructor(
    private val db: IYangoDatabase,
    private val clock: Clock,
) : GradeRepositoryPort {

    override fun observeSnapshot(accountKey: String, semester: String): Flow<GradeSnapshot?> =
        if (semester == GradeRepositoryPort.ALL_SEMESTERS) {
            combine(
                db.gradeSnapshotDao().observe(accountKey, GradeRepositoryPort.ALL_SEMESTERS),
                db.gradeSnapshotDao().observeAllForAccount(accountKey),
            ) { allSnapshot, accountSnapshots ->
                allSnapshot?.toModel()
                    ?: accountSnapshots.firstOrNull { it.semester != GradeRepositoryPort.ALL_SEMESTERS }?.toModel()
            }
        } else {
            combine(
                db.gradeSnapshotDao().observe(accountKey, semester),
                db.gradeSnapshotDao().observe(accountKey, GradeRepositoryPort.ALL_SEMESTERS),
                db.gradeRecordDao().observe(accountKey, GradeRepositoryPort.ALL_SEMESTERS),
            ) { termSnapshot, allSnapshot, allRows ->
                termSnapshot?.toModel()
                    ?: allSnapshot?.takeIf {
                        allRows.any { row -> row.toDomainModel().semester == semester }
                    }?.toModel()
            }
        }

    override fun observeRecords(accountKey: String, semester: String): Flow<List<GradeRecord>> =
        if (semester == GradeRepositoryPort.ALL_SEMESTERS) {
            combine(
                db.gradeRecordDao().observe(accountKey, GradeRepositoryPort.ALL_SEMESTERS),
                db.gradeSnapshotDao().observe(accountKey, GradeRepositoryPort.ALL_SEMESTERS),
                db.gradeRecordDao().observeAllForAccount(accountKey),
            ) { allPartitionRows, allSnapshot, accountRows ->
                val source = if (allPartitionRows.isNotEmpty() || allSnapshot != null) {
                    allPartitionRows
                } else {
                    accountRows.filter { it.semester != GradeRepositoryPort.ALL_SEMESTERS }
                }
                source.map { it.toDomainModel() }
            }
        } else {
            combine(
                db.gradeRecordDao().observe(accountKey, semester),
                db.gradeSnapshotDao().observe(accountKey, semester),
                db.gradeRecordDao().observe(accountKey, GradeRepositoryPort.ALL_SEMESTERS),
            ) { termRows, termSnapshot, allPartitionRows ->
                if (termRows.isNotEmpty() || termSnapshot != null) {
                    termRows.map { it.toDomainModel() }
                } else {
                    allPartitionRows
                        .map { it.toDomainModel() }
                        .filter { it.semester == semester }
                }
            }
        }

    override fun observeSemesters(accountKey: String): Flow<List<String>> =
        db.gradeRecordDao().observeAllForAccount(accountKey).map { rows ->
            rows.mapNotNull { entity ->
                val resolved = entity.toDomainModel().semester
                resolved.takeIf { it.isNotBlank() && it != GradeRepositoryPort.ALL_SEMESTERS }
            }
                .distinct()
                .sortedDescending()
        }

    override fun observeCreditProgressSource(accountKey: String): Flow<CreditGradeSourceSnapshot> =
        combine(
            db.gradeSnapshotDao().observeAllForAccount(accountKey),
            db.gradeRecordDao().observe(accountKey, GradeRepositoryPort.ALL_SEMESTERS),
        ) { snapshots, allSemesterRecords ->
            val allSnapshot = snapshots.firstOrNull { it.semester == GradeRepositoryPort.ALL_SEMESTERS }
            val hasSingleSemester = snapshots.any { it.semester != GradeRepositoryPort.ALL_SEMESTERS }
            CreditGradeSourceSnapshot(
                accountKey = accountKey,
                allSemesterSnapshot = allSnapshot?.toModel(),
                allSemesterRecords = if (allSnapshot != null) {
                    allSemesterRecords.map { it.toModel() }
                } else {
                    emptyList()
                },
                hasAnySingleSemesterSnapshot = hasSingleSemester,
            )
        }

    override suspend fun saveCompleteResult(
        accountKey: String,
        semester: String,
        records: List<GradeRecord>,
        queryFingerprint: String,
        parserVersion: Int,
    ) {
        saveCompleteResultWithCoverage(
            accountKey = accountKey,
            semester = semester,
            records = records,
            queryFingerprint = queryFingerprint,
            parserVersion = parserVersion,
            coverageMetadata = GradeCoverageMetadata(),
        )
    }

    override suspend fun saveCompleteResultWithCoverage(
        accountKey: String,
        semester: String,
        records: List<GradeRecord>,
        queryFingerprint: String,
        parserVersion: Int,
        coverageMetadata: GradeCoverageMetadata,
    ) {
        if (accountKey.isBlank() || semester.isBlank()) return
        val now = clock.millis()
        db.withTransaction {
            val previous = db.gradeSnapshotDao().get(accountKey, semester)
            val previousRecords = db.gradeRecordDao().getAll(accountKey, semester)
                .associateBy { it.toModel().stableKey }
            val firstBaseline = previous == null
            db.gradeRecordDao().delete(accountKey, semester)
            db.gradeSnapshotDao().delete(accountKey, semester)

            val rows = records.mapIndexed { index, record ->
                val old = previousRecords[record.stableKey]
                val changedScore = old != null && old.scoreText != record.scoreText
                record.copy(
                    id = 0,
                    accountKey = accountKey,
                    semester = semester,
                    rowOrder = if (record.rowOrder > 0) record.rowOrder else index,
                    isNew = !firstBaseline && (old == null || old.isNew),
                    isModified = !firstBaseline && (changedScore || old?.isModified == true),
                    previousScoreText = if (!firstBaseline && changedScore) old?.scoreText.orEmpty() else "",
                    updatedAt = if (record.updatedAt > 0) record.updatedAt else now,
                ).toEntity()
            }
            if (rows.isNotEmpty()) {
                db.gradeRecordDao().insertAll(rows)
            }
            db.gradeSnapshotDao().insert(
                GradeSnapshotEntity(
                    accountKey = accountKey,
                    semester = semester,
                    fetchedAt = now,
                    complete = true,
                    parserVersion = parserVersion,
                    queryFingerprint = queryFingerprint,
                    targetSemestersJson = CreditJsonCodec.encodeStringList(coverageMetadata.targetSemesters),
                    queriedSemestersJson = CreditJsonCodec.encodeStringList(coverageMetadata.queriedSemesters),
                    truncatedTermCount = coverageMetadata.truncatedTermCount,
                    directAllEmptyUnverified = coverageMetadata.directAllEmptyUnverified,
                    invalidatedByPartialUpdate = false,
                ),
            )
            if (semester != GradeRepositoryPort.ALL_SEMESTERS) {
                db.gradeSnapshotDao().markAllSemestersInvalidatedByPartialUpdate(accountKey)
            }
        }
    }

    override suspend fun markChangesRead(accountKey: String, semester: String) {
        db.gradeRecordDao().markChangesRead(accountKey, semester)
    }

    override fun observeUnreadCount(accountKey: String): Flow<Int> =
        db.gradeRecordDao().observeUnreadCount(accountKey)

    override suspend fun deleteAccount(accountKey: String) {
        db.withTransaction {
            db.gradeRecordDao().deleteAccount(accountKey)
            db.gradeSnapshotDao().deleteAccount(accountKey)
        }
    }

    private fun GradeRecordEntity.toDomainModel(): GradeRecord {
        val model = toModel()
        if (model.semester == GradeRepositoryPort.ALL_SEMESTERS && model.schoolRecordId.startsWith("ygu:")) {
            val realSemester = model.schoolRecordId.removePrefix("ygu:").substringBefore('|')
            if (realSemester.isNotBlank()) {
                return model.copy(semester = realSemester)
            }
        }
        return model
    }
}
