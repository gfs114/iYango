package com.gfs.iyango.data.repository

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.ExamRepositoryPort
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.ExamStatus
import com.gfs.iyango.core.model.ExamValidator
import com.gfs.iyango.data.db.IYangoDatabase
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [ExamRepositoryPort] 的 Room 实现。 */
@Singleton
class RoomExamRepository @Inject constructor(
    private val db: IYangoDatabase,
    private val clock: Clock,
) : ExamRepositoryPort {

    override fun observeAll(): Flow<List<Exam>> =
        db.examDao().observeAll().map { rows ->
            rows.map { it.toModel() }
                .sortedWith(
                    compareBy(
                        { isEnded(it) },
                        { it.date },
                        { it.startTime.ifEmpty { "99:99" } },
                        { it.id },
                    ),
                )
        }

    override fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<Exam>> =
        db.examDao().observeInRange(from.toString(), to.toString())
            .map { rows -> rows.map { it.toModel() } }

    override suspend fun get(id: Long): Exam? = db.examDao().getById(id)?.toModel()

    override suspend fun save(exam: Exam): Long {
        val problem = ExamValidator.validate(exam)
        require(problem == null) { problem.orEmpty() }
        val entity = exam.copy(
            subject = exam.subject.trim(),
            updatedAt = if (exam.updatedAt > 0) exam.updatedAt else clock.millis(),
        ).toEntity()
        val rowId = db.examDao().upsert(entity)
        return if (rowId > 0) rowId else entity.id
    }

    override suspend fun delete(id: Long) {
        db.examDao().deleteById(id)
    }

    override suspend fun replaceSchoolExams(
        connectionId: String,
        semester: String,
        exams: List<Exam>,
    ) {
        require(connectionId.isNotBlank()) { "学校考试缺少连接标识" }
        db.withTransaction {
            db.examDao().deleteSchoolExams(connectionId, semester)
            if (exams.isNotEmpty()) {
                db.examDao().upsertAll(
                    exams.map { exam ->
                        exam.copy(
                            connectionId = connectionId,
                            semester = semester,
                            updatedAt = if (exam.updatedAt > 0) exam.updatedAt else clock.millis(),
                        ).toEntity()
                    },
                )
            }
        }
    }

    private fun isEnded(exam: Exam): Boolean {
        if (exam.status == ExamStatus.CANCELLED || exam.status == ExamStatus.COMPLETED) {
            return true
        }
        val endDate = exam.resolvedEndDateOrNull() ?: return false
        val endTime = exam.endTimeOrNull()
        val end = if (endTime != null) {
            LocalDateTime.of(endDate, endTime)
        } else {
            endDate.atTime(23, 59, 59)
        }
        return end.isBefore(LocalDateTime.now(clock))
    }
}
