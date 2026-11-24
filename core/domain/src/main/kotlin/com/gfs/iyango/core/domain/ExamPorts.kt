package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.Exam
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** 考试仓储端口。 */
interface ExamRepositoryPort {

    /** 观察全部考试（近期优先，已结束在后）。 */
    fun observeAll(): Flow<List<Exam>>

    /** 观察 [from, to] 日期范围内的考试（含跨日考试）。 */
    fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<Exam>>

    suspend fun get(id: Long): Exam?

    /** 新增或更新；返回持久化 id。 */
    suspend fun save(exam: Exam): Long

    suspend fun delete(id: Long)

    /**
     * 学校同步：仅替换同一连接/学期的 SCHOOL 记录，手动记录不受影响。
     */
    suspend fun replaceSchoolExams(connectionId: String, semester: String, exams: List<Exam>)
}
