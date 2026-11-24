package com.gfs.iyango.core.domain

import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeSnapshot
import com.gfs.iyango.core.model.GradeTerm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 全量成绩查询覆盖范围元信息（用于学分进度数据完备性判定）。 */
data class GradeCoverageMetadata(
    val targetSemesters: List<String> = emptyList(),
    val queriedSemesters: List<String> = emptyList(),
    val truncatedTermCount: Int = 0,
    val directAllEmptyUnverified: Boolean = false,
    val invalidatedByPartialUpdate: Boolean = false,
)

/** 学分进度专用读取投影：快照元信息与记录严格同源，不混用单学期回落与全学期并集。 */
data class CreditGradeSourceSnapshot(
    val accountKey: String = "",
    val allSemesterSnapshot: GradeSnapshot? = null,
    val allSemesterRecords: List<GradeRecord> = emptyList(),
    val hasAnySingleSemesterSnapshot: Boolean = false,
)

/**
 * 成绩离线仓储端口。
 *
 * 分区键使用“学校 + 已验证账号主体 + 实际学期”，连接 id 只用于映射账号；
 * 同一账号重新登录后仍能读到旧缓存。
 */
interface GradeRepositoryPort {

    /** 最近一次完整快照；无缓存时为 null。 */
    fun observeSnapshot(accountKey: String, semester: String): Flow<GradeSnapshot?>

    /** 快照内的成绩记录（学校行顺序）。 */
    fun observeRecords(accountKey: String, semester: String): Flow<List<GradeRecord>>

    /** 已完成学期列表（用于界面展示缓存覆盖范围）。 */
    fun observeSemesters(accountKey: String): Flow<List<String>>

    /** 学分进度专用读取投影：仅读取 `ALL_SEMESTERS` 分区与是否存在单学期缓存标记。 */
    fun observeCreditProgressSource(accountKey: String): Flow<CreditGradeSourceSnapshot> =
        flowOf(CreditGradeSourceSnapshot(accountKey = accountKey))

    /**
     * 保存一次**完整成功**的查询结果。
     *
     * 实现方在同一事务中：比较旧记录 → 写入新快照/记录 → 计算 isNew/isModified →
     * 首次缓存不标记为“新发布”。部分/失败结果不得调用本方法。
     */
    suspend fun saveCompleteResult(
        accountKey: String,
        semester: String,
        records: List<GradeRecord>,
        queryFingerprint: String,
        parserVersion: Int = 1,
    )

    /** 带覆盖元信息的完整结果保存；默认实现兼容已有测试桩。 */
    suspend fun saveCompleteResultWithCoverage(
        accountKey: String,
        semester: String,
        records: List<GradeRecord>,
        queryFingerprint: String,
        parserVersion: Int = 1,
        coverageMetadata: GradeCoverageMetadata = GradeCoverageMetadata(),
    ) {
        saveCompleteResult(accountKey, semester, records, queryFingerprint, parserVersion)
    }

    /** 标记当前分区的变化为已读。 */
    suspend fun markChangesRead(accountKey: String, semester: String)

    /** 未读变化数量（新发布 + 已修改）。 */
    fun observeUnreadCount(accountKey: String): Flow<Int>

    /** 主动退出账号时清理该账号缓存。 */
    suspend fun deleteAccount(accountKey: String)

    companion object {
        /** “全部学期”查询范围的缓存分区键（实际学期值不会是 `*`）。 */
        const val ALL_SEMESTERS = "*"
    }
}

/** 成绩查询服务结果（网络 + 解析）。 */
sealed interface GradeQueryResult {
    data class Success(
        val records: List<GradeRecord>,
        val terms: List<GradeTerm> = emptyList(),
        val complete: Boolean = true,
        val coverageMetadata: GradeCoverageMetadata = GradeCoverageMetadata(),
    ) : GradeQueryResult

    /** 会话失效：保留缓存，提示重新登录。 */
    data class SessionExpired(val message: String) : GradeQueryResult

    data class Failed(val message: String) : GradeQueryResult
}

/**
 * 成绩查询端口（实现在学校适配层）。
 *
 * @param semesterValue 学校实际学期值；空字符串表示全部学期。
 */
interface GradeQueryPort {
    suspend fun listTerms(connectionId: String): Result<List<GradeTerm>>

    suspend fun query(connectionId: String, semesterValue: String): GradeQueryResult
}
