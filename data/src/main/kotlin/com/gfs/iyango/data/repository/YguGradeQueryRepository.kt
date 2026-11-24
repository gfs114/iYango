package com.gfs.iyango.data.repository

import com.gfs.iyango.core.domain.GradeCoverageMetadata
import com.gfs.iyango.core.domain.GradeQueryPort
import com.gfs.iyango.core.domain.GradeQueryResult
import com.gfs.iyango.core.domain.GradeStatisticsCalculator
import com.gfs.iyango.core.domain.SchoolCredentialStore
import com.gfs.iyango.core.model.CreditValueStatus
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.GradeTerm
import com.gfs.iyango.core.model.parseCreditBigDecimal
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolConnectionEntity
import com.gfs.iyango.data.school.ygu.YguGradeRow
import com.gfs.iyango.data.school.ygu.YguGradesClient
import com.gfs.iyango.data.school.ygu.YguGradesClientStatus
import com.gfs.iyango.data.school.ygu.YguGradesOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GradeQueryPort] 的阳光学院实现。
 *
 * - 学期列表来自成绩表单实际提供的 kksj 选项，不按月份猜测；
 * - “全部学期”按学校列表逐学期查询，任一学期失败即整体失败，不发布半份结果；
 * - 每次请求后把续期后的 Cookie 写回 Keystore；登录失效时清空 Cookie 并标记连接过期；
 * - 成绩身份优先使用“学期+课程号+课程名+考试性质+补重学期”组合，同组合的多条记录
 *   （重修/补考）按学校顺序追加序号，避免按课程名去重。
 */
@Singleton
class YguGradeQueryRepository @Inject constructor(
    private val db: IYangoDatabase,
    private val credentialStore: SchoolCredentialStore,
    private val authRepository: RoomSchoolAuthRepository,
) : GradeQueryPort {

    /** Module-local test seam; production calls use the real school HTTP client. */
    internal var createClient: () -> YguGradesClient = { YguGradesClient() }

    override suspend fun listTerms(connectionId: String): Result<List<GradeTerm>> {
        val connection = db.schoolConnectionDao().getById(connectionId)
            ?: return Result.failure(IllegalStateException("未找到该教务账号，请重新登录"))
        val session = SchoolSessionRequest.open(db, credentialStore, authRepository, connection)
            ?: return Result.failure(IllegalStateException("登录状态已失效，请重新登录"))
        var credential = session.credential
        var client = createClient()
        var outcome = client.fetchOptions(credential.cookieHeader)
        if (outcome.status == YguGradesClientStatus.LOGIN) {
            val relogged = session.renew()
            if (relogged != null) {
                credential = relogged
                client = createClient()
                outcome = client.fetchOptions(credential.cookieHeader)
            }
        }
        if (outcome.status != YguGradesClientStatus.LOGIN && !session.finish(outcome.cookieHeader)) {
            return Result.failure(IllegalStateException("登录状态已失效，请重新登录"))
        }
        when (outcome.status) {
            YguGradesClientStatus.OK -> {
                val terms = outcome.semesters
                    .filter { it.value.isNotBlank() }
                    .map { GradeTerm(it.value, it.label.ifEmpty { it.value }) }
                if (terms.isEmpty()) {
                    return Result.failure(IllegalStateException("学校未提供可查询学期，请稍后重试"))
                }
                return Result.success(terms)
            }
            YguGradesClientStatus.LOGIN -> {
                session.expire()
                return Result.failure(IllegalStateException(outcome.message))
            }
            else -> return Result.failure(
                IllegalStateException(outcome.message.ifEmpty { "成绩学期读取失败，请稍后重试" }),
            )
        }
    }

    override suspend fun query(connectionId: String, semesterValue: String): GradeQueryResult {
        val connection = db.schoolConnectionDao().getById(connectionId)
            ?: return GradeQueryResult.Failed("未找到该教务账号，请重新登录")
        val session = SchoolSessionRequest.open(db, credentialStore, authRepository, connection)
            ?: return GradeQueryResult.SessionExpired("登录状态已失效，请重新登录")
        var credential = session.credential
        val accountKey = accountKey(connection)
        var client = createClient()
        suspend fun fetchTerm(cookie: String, term: String): YguGradesOutcome {
            val first = client.fetch(cookie, term)
            if (first.status != YguGradesClientStatus.LOGIN) return first
            val renewed = session.renew() ?: return first
            client = createClient()
            return client.fetch(renewed.cookieHeader, term)
        }
        val term = semesterValue.trim()
        if (term.isNotEmpty()) {
            val outcome = fetchTerm(credential.cookieHeader, term)
            if (outcome.status != YguGradesClientStatus.LOGIN && !session.finish(outcome.cookieHeader)) {
                return GradeQueryResult.SessionExpired("登录状态已失效，请重新登录")
            }
            return when (outcome.status) {
                YguGradesClientStatus.OK, YguGradesClientStatus.EMPTY -> GradeQueryResult.Success(
                    records = mapRecords(outcome.records.map { it to term }, accountKey),
                    terms = outcome.semesters.map { GradeTerm(it.value, it.label.ifEmpty { it.value }) },
                    complete = true,
                    coverageMetadata = GradeCoverageMetadata(
                        targetSemesters = listOf(term),
                        queriedSemesters = listOf(term),
                        truncatedTermCount = 0,
                        directAllEmptyUnverified = false,
                    ),
                )
                YguGradesClientStatus.LOGIN -> {
                    session.expire()
                    GradeQueryResult.SessionExpired(outcome.message)
                }
                YguGradesClientStatus.CANCELLED -> GradeQueryResult.Failed("成绩查询已取消")
                else -> GradeQueryResult.Failed(
                    outcome.message.ifEmpty { "成绩查询暂不可用，请稍后重试" },
                )
            }
        }

        var options = client.fetchOptions(credential.cookieHeader)
        if (options.status == YguGradesClientStatus.LOGIN) {
            val relogged = session.renew()
            if (relogged != null) {
                credential = relogged
                client = createClient()
                options = client.fetchOptions(credential.cookieHeader)
            }
        }
        if (options.status == YguGradesClientStatus.LOGIN) {
            session.expire()
            return GradeQueryResult.SessionExpired(options.message)
        }
        if (options.status != YguGradesClientStatus.OK || options.semesters.isEmpty()) {
            session.finish(options.cookieHeader)
            return GradeQueryResult.Failed(
                options.message.ifEmpty { "学校未提供可查询学期，请稍后重试" },
            )
        }
        val rows = mutableListOf<Pair<YguGradeRow, String>>()
        // Each client call clears its temporary cookie jar in finally. Carry the returned
        // session into the next call; reading client.cookieHeader() here yields an empty jar.
        var sessionCookie = options.cookieHeader
        val nonBlankTerms = options.semesters.map { it.value.trim() }.filter { it.isNotEmpty() }.distinct()
        val queriedTerms = linkedSetOf<String>()
        val canQueryAllDirectly = options.semesters.any { it.value.isEmpty() }
        var fetchedAllDirectly = false
        var directAllEmptyUnverified = false
        var truncatedTermCount = 0
        if (canQueryAllDirectly) {
            val allOutcome = fetchTerm(sessionCookie, "")
            sessionCookie = allOutcome.cookieHeader
            when (allOutcome.status) {
                YguGradesClientStatus.OK -> {
                    if (allOutcome.records.all { it.term.trim().isNotEmpty() }) {
                        val directRows = allOutcome.records.map { it to it.term.trim() }
                        rows += directRows
                        fetchedAllDirectly = true
                        queriedTerms += nonBlankTerms
                        queriedTerms += directRows.map { it.second }
                    }
                }
                YguGradesClientStatus.EMPTY -> {
                    fetchedAllDirectly = true
                    directAllEmptyUnverified = true
                }
                YguGradesClientStatus.LOGIN -> {
                    session.expire()
                    return GradeQueryResult.SessionExpired(allOutcome.message)
                }
                else -> Unit
            }
        }
        if (!fetchedAllDirectly) {
            val candidateTerms = options.semesters.filter { it.value.isNotBlank() }
            truncatedTermCount = (candidateTerms.size - MAX_TERMS).coerceAtLeast(0)
            for (option in candidateTerms.take(MAX_TERMS)) {
                val termValue = option.value.trim()
                val outcome = fetchTerm(sessionCookie, termValue)
                sessionCookie = outcome.cookieHeader
                when (outcome.status) {
                    YguGradesClientStatus.OK -> {
                        rows += outcome.records.map { it to termValue }
                        queriedTerms += termValue
                    }
                    YguGradesClientStatus.EMPTY -> {
                        queriedTerms += termValue
                    }
                    YguGradesClientStatus.LOGIN -> {
                        session.expire()
                        return GradeQueryResult.SessionExpired(outcome.message)
                    }
                    else -> {
                        session.finish(sessionCookie)
                        return GradeQueryResult.Failed(
                            outcome.message.ifEmpty { "部分学期成绩读取失败，本机缓存已保留" },
                        )
                    }
                }
            }
        }
        if (!session.finish(sessionCookie)) return GradeQueryResult.SessionExpired("登录状态已失效，请重新登录")
        val mappedRecords = mapRecords(rows, accountKey)
        val semestersWithGrades = mappedRecords.map { it.semester }.filter { it.isNotBlank() }.toSet()
        val targetSemesters = if (fetchedAllDirectly && !directAllEmptyUnverified) {
            (nonBlankTerms + semestersWithGrades).distinct()
        } else {
            nonBlankTerms
        }
        return GradeQueryResult.Success(
            records = mappedRecords,
            terms = options.semesters
                .filter { it.value.isNotBlank() && it.value in semestersWithGrades }
                .map { GradeTerm(it.value, it.label.ifEmpty { it.value }) },
            complete = true,
            coverageMetadata = GradeCoverageMetadata(
                targetSemesters = targetSemesters,
                queriedSemesters = if (directAllEmptyUnverified) emptyList() else queriedTerms.toList(),
                truncatedTermCount = truncatedTermCount,
                directAllEmptyUnverified = directAllEmptyUnverified,
            ),
        )
    }

    private fun mapRecords(rows: List<Pair<YguGradeRow, String>>, accountKey: String): List<GradeRecord> {
        val ordinals = HashMap<String, Int>()
        return rows.mapIndexed { index, (row, fallbackTerm) ->
            val term = row.term.trim().ifEmpty { fallbackTerm }
            val identity = listOf(
                term,
                row.courseCode.trim(),
                row.courseName.trim(),
                row.examType.trim(),
                row.retakeTerm.trim(),
            ).joinToString("|")
            val ordinal = (ordinals[identity] ?: 0) + 1
            ordinals[identity] = ordinal
            val rawCreditText = row.credit.trim()
            val parsedBigCredit = parseCreditBigDecimal(rawCreditText)
            val creditStatus = if (parsedBigCredit != null) {
                CreditValueStatus.VALID
            } else {
                CreditValueStatus.MISSING_OR_INVALID
            }
            GradeRecord(
                accountKey = accountKey,
                semester = term,
                courseName = row.courseName.trim(),
                courseCode = row.courseCode.trim(),
                credit = parsedBigCredit?.toDouble() ?: (rawCreditText.toDoubleOrNull() ?: 0.0),
                rawCreditText = rawCreditText,
                creditStatus = creditStatus,
                scoreText = row.score.trim(),
                scoreValue = GradeStatisticsCalculator.parseScore(row.score),
                gradePoint = row.gradePoint.trim().toDoubleOrNull(),
                attribute = resolveAttribute(row),
                rawCourseAttribute = row.courseAttribute.trim(),
                rawCourseType = row.courseType.trim(),
                rawGeneralElectiveType = row.generalElectiveType.trim(),
                rawAttributesKnown = true,
                examType = row.examType.trim().ifEmpty { row.assessmentMethod.trim() },
                schoolRecordId = "ygu:$identity" + if (ordinal > 1) "#$ordinal" else "",
                rowOrder = index,
            )
        }
    }

    private fun resolveAttribute(row: YguGradeRow): String {
        val attr = row.courseAttribute.trim()
        val type = row.courseType.trim()
        val elective = row.generalElectiveType.trim()
        val isPublicElective = GradeStatisticsCalculator.isPublicElectiveAttribute(attr) ||
            GradeStatisticsCalculator.isPublicElectiveAttribute(type) ||
            elective.isNotEmpty()
        return when {
            !isPublicElective -> attr.ifEmpty { type }
            GradeStatisticsCalculator.isPublicElectiveAttribute(attr) -> attr
            GradeStatisticsCalculator.isPublicElectiveAttribute(type) -> type
            attr.isNotEmpty() -> "$attr·公选"
            elective.isNotEmpty() -> "公选（$elective）"
            else -> "公选"
        }
    }

    private fun accountKey(connection: SchoolConnectionEntity): String =
        connection.accountSubject.ifEmpty { connection.id }

    private companion object {
        /** 全部学期查询的学校学期上限，避免异常页面导致无界请求。 */
        const val MAX_TERMS = 40
    }
}
