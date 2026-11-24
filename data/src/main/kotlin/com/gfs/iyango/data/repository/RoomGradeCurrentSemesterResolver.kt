package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.GradeCurrentSemesterResolverPort
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.data.db.IYangoDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 解析当前正在进行的教务学期（优先课表最新绑定的教务学期，回退到本地已有成绩的最近学期）。
 */
@Singleton
class RoomGradeCurrentSemesterResolver @Inject constructor(
    private val db: IYangoDatabase,
    private val gradeRepository: GradeRepositoryPort,
) : GradeCurrentSemesterResolverPort {

    override suspend fun resolveCurrentSemester(connectionId: String, accountKey: String): String? {
        if (connectionId.isNotBlank()) {
            val bound = db.timetableDao().getLatestYguBinding(connectionId)
                ?.schoolTerm
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != GradeRepositoryPort.ALL_SEMESTERS }
            if (bound != null) return bound
        }
        if (accountKey.isBlank()) return null
        return gradeRepository.observeSemesters(accountKey)
            .first()
            .firstOrNull { it.isNotBlank() && it != GradeRepositoryPort.ALL_SEMESTERS }
    }
}
