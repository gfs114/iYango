package com.gfs.iyango.data.repository

import androidx.room.withTransaction
import com.gfs.iyango.core.domain.CourseRawStorePort
import com.gfs.iyango.core.domain.ScheduleDefaults
import com.gfs.iyango.core.domain.SchoolCredentialStore
import com.gfs.iyango.core.domain.SchoolSyncArrangement
import com.gfs.iyango.core.domain.SchoolSyncCommitResult
import com.gfs.iyango.core.domain.SchoolSyncCommitSummary
import com.gfs.iyango.core.domain.SchoolSyncGroup
import com.gfs.iyango.core.domain.SchoolSyncPrepareResult
import com.gfs.iyango.core.domain.SchoolSyncPreview
import com.gfs.iyango.core.domain.SchoolSyncTarget
import com.gfs.iyango.core.domain.SchoolTimetablePort
import com.gfs.iyango.core.domain.SchoolTimetableSyncPort
import com.gfs.iyango.core.domain.SessionExpiredException
import com.gfs.iyango.core.model.SchoolCampusOption
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.SchoolCourseData
import com.gfs.iyango.core.model.SchoolTermOption
import com.gfs.iyango.core.model.SchoolTimetableData
import com.gfs.iyango.core.model.SchoolTimetableResult
import com.gfs.iyango.core.model.SyncBatchResult
import com.gfs.iyango.core.model.SyncChangeType
import com.gfs.iyango.core.model.TimeSlot
import com.gfs.iyango.core.model.WeekType
import com.gfs.iyango.data.db.CourseEntity
import com.gfs.iyango.data.db.IYangoDatabase
import com.gfs.iyango.data.db.SchoolConnectionEntity
import com.gfs.iyango.data.db.SchoolSnapshotEntity
import com.gfs.iyango.data.db.SemesterEntity
import com.gfs.iyango.data.db.SyncBatchEntity
import com.gfs.iyango.data.db.SyncChangeEntity
import com.gfs.iyango.data.db.TimetableEntity
import com.gfs.iyango.data.school.ygu.YguClassTimes
import com.gfs.iyango.data.school.ygu.YguEduCourse
import com.gfs.iyango.data.school.ygu.YguTimetableClient
import com.gfs.iyango.data.school.ygu.YguTimetableMetadata
import com.gfs.iyango.data.school.ygu.YguTimetableParser
import java.security.MessageDigest
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [SchoolTimetablePort] 与 [SchoolTimetableSyncPort] 的 YGU + Room 实现（F02 / E02）。
 *
 * 关键约束：
 * - 凭据只从 [SchoolCredentialStore] 读取，读到的会话续期后只写回 Keystore；
 * - 预览与提交之间用目标作用域摘要做乐观并发控制，提交事务内重新解析目标、
 *   重算摘要并比较，任何不一致都返回 [SchoolSyncCommitResult.Conflict] 且不写库；
 * - 合并按学校稳定 `sourceKey` 保留基础课行 id，从而让单次调整（overrideOfId）
 *   继续指向同一门课；无法匹配且被调整引用的基础课保留并停用，绝不静默删除；
 * - 本地新增课程（sourceKey 为空）与全部单次调整记录不参与覆盖；
 * - 目标绑定为 `(connectionId, schoolTerm)`；同账号重登可接管旧的同条课表，
 *   但绝不跨账号接管，也绝不写入 LOCAL 课表。
 */
@Singleton
class RoomSchoolTimetableRepository @Inject constructor(
    private val db: IYangoDatabase,
    private val credentialStore: SchoolCredentialStore,
    private val clock: Clock,
    private val authRepository: RoomSchoolAuthRepository,
    private val courseRawStore: CourseRawStorePort,
) : SchoolTimetablePort, SchoolTimetableSyncPort {

    private val preparedRawHtml = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 8
        },
    )

    private val metadataCache = ConcurrentHashMap<String, CachedTimetableMetadata>()

    override suspend fun listTerms(connectionId: String): Result<List<SchoolTermOption>> {
        val connection = db.schoolConnectionDao().getById(connectionId)
            ?: return Result.failure(IllegalStateException("未找到该教务账号，请重新登录"))
        val session = SchoolSessionRequest.open(db, credentialStore, authRepository, connection)
            ?: return Result.failure(SessionExpiredException("登录状态已失效，请重新登录"))
        var credential = session.credential
        var client = YguTimetableClient()
        var read = client.readMetadata(credential.cookieHeader)
        if (read.expired) {
            metadataCache.remove(connectionId)
            val relogged = session.renew()
            if (relogged != null) {
                credential = relogged
                client = YguTimetableClient()
                read = client.readMetadata(credential.cookieHeader)
            }
        }
        if (read.expired) {
            metadataCache.remove(connectionId)
            session.expire()
            return Result.failure(
                SessionExpiredException(read.errorMessage.ifEmpty { "登录状态已失效，请重新登录" }),
            )
        }
        if (read.errorMessage.isNotEmpty()) {
            return Result.failure(IllegalStateException(read.errorMessage))
        }
        if (!session.finish(client.cookieHeader())) {
            metadataCache.remove(connectionId)
            return Result.failure(SessionExpiredException("登录状态已失效，请重新登录"))
        }
        if (read.metadata.status == "timetable" && read.metadata.templateId.isNotBlank()) {
            metadataCache[connectionId] = CachedTimetableMetadata(
                metadata = read.metadata,
                cachedAt = clock.millis(),
                sessionRevision = connection.sessionRevision,
            )
        }
        val options = read.metadata.semesters.map { term ->
            SchoolTermOption(term, read.metadata.semesterLabels[term] ?: term)
        }
        if (options.isEmpty()) {
            return Result.failure(IllegalStateException("学校未提供可查询学期，请稍后重试"))
        }
        // 学校入口页当前选中的学期排在最前：手动同步默认选中它，首次登录自动同步直接取首项。
        return Result.success(options.sortedByDescending { it.value == read.metadata.schoolTerm })
    }

    override suspend fun listCampuses(connectionId: String): Result<List<SchoolCampusOption>> {
        val connection = db.schoolConnectionDao().getById(connectionId)
            ?: return Result.failure(IllegalStateException("未找到该教务账号，请重新登录"))
        if (connection.status == SchoolConnectionStatus.LOGGED_OUT) {
            return Result.failure(IllegalStateException("该账号已退出登录，请重新登录"))
        }
        return Result.success(
            listOf(
                SchoolCampusOption(YguClassTimes.CAMPUS_SOUTH, YguClassTimes.campusName(YguClassTimes.CAMPUS_SOUTH)),
                SchoolCampusOption(YguClassTimes.CAMPUS_NORTH, YguClassTimes.campusName(YguClassTimes.CAMPUS_NORTH)),
            ),
        )
    }

    override suspend fun fetchTimetable(
        connectionId: String,
        termValue: String,
        campusCode: String,
    ): SchoolTimetableResult {
        return when (val read = readTimetableInternal(connectionId, termValue)) {
            is SchoolTimetableResult.Success -> {
                val data = read.data
                val forced = campusCode.trim().uppercase()
                if (forced == YguClassTimes.CAMPUS_SOUTH || forced == YguClassTimes.CAMPUS_NORTH) {
                    val version = YguClassTimes.versionOf(termValue, "YGU")
                    if (version.isNotEmpty()) {
                        SchoolTimetableResult.Success(
                            data.copy(
                                campusCode = forced,
                                campusName = YguClassTimes.campusName(forced),
                                timeSlots = YguClassTimes.slotsFor(version, forced),
                            ),
                        )
                    } else {
                        read
                    }
                } else {
                    read
                }
            }
            is SchoolTimetableResult.SessionExpired -> read
            is SchoolTimetableResult.Failed -> read
        }
    }

    override suspend fun prepare(
        connectionId: String,
        termValue: String,
    ): SchoolSyncPrepareResult {
        val connection = db.schoolConnectionDao().getById(connectionId)
            ?: return SchoolSyncPrepareResult.Failed("未找到该教务账号，请重新登录", retryable = false)
        val data = when (val read = readTimetableInternal(connectionId, termValue)) {
            is SchoolTimetableResult.Success -> read.data
            is SchoolTimetableResult.SessionExpired -> return SchoolSyncPrepareResult.SessionExpired(read.message)
            is SchoolTimetableResult.Failed -> return SchoolSyncPrepareResult.Failed(read.message, read.retryable)
        }
        if (!data.complete) {
            return SchoolSyncPrepareResult.Failed("课表解析不完整，本机课表已保留，请稍后重试")
        }
        if (data.courses.isEmpty()) {
            return SchoolSyncPrepareResult.Failed("该学期没有可同步的课程安排")
        }
        val target = resolveTarget(connection, termValue)
        val targetSemesters = target?.let { db.semesterDao().getAllByTimetable(it.id) } ?: emptyList()
        val targetCurrent = targetSemesters.firstOrNull { it.isCurrent }
        val fallbackSemester = if (target == null) {
            db.timetableDao().getCurrent()?.let { current -> db.semesterDao().getCurrent(current.id) }
        } else {
            null
        }
        val baseline = targetCurrent ?: fallbackSemester
        val startDate = baseline?.startDate?.toString() ?: mondayOfCurrentWeek().toString()
        val totalWeeks = (baseline?.totalWeeks ?: DEFAULT_TOTAL_WEEKS).coerceIn(1, 30)

        val arrangements = arrangementsOf(data.courses)
        val warnings = data.warnings.toMutableList()
        val termLabel = data.termLabel.ifBlank { termValue }
        val semesterName = termLabel
        if (target == null) {
            warnings += "将为「$termLabel」新建教务课表"
        } else if (target.connectionId != connectionId) {
            warnings += "已接管同账号此前同步的课表「${target.name}」，不会新建重复课表"
        }
        val siblingCount = db.timetableDao().getYguByTerm(termValue).count { it.id != target?.id }
        if (siblingCount > 0) {
            warnings += "同学期还有 $siblingCount 张其他教务课表，本次只更新一张，其余保持不变"
        }

        val version = YguClassTimes.versionOf(termValue, "YGU")
        if (data.rawPayload.isNotBlank()) {
            preparedRawHtml["${connectionId}|${termValue}|${data.fetchedAt}"] = data.rawPayload
        }
        return SchoolSyncPrepareResult.Ready(
            SchoolSyncPreview(
                connectionId = connectionId,
                termValue = termValue,
                termLabel = termLabel,
                semesterName = semesterName,
                target = SchoolSyncTarget(
                    timetableId = target?.id ?: 0L,
                    name = target?.name ?: defaultTimetableName(termLabel),
                    adopted = target != null && target.connectionId != connectionId,
                    isCurrent = target?.isCurrent ?: false,
                    existingCourses = db.courseDao().getAllByTimetable(target?.id ?: 0L).size,
                ),
                courses = arrangements,
                groups = groupsOf(arrangements),
                warnings = warnings,
                defaultStartDate = startDate,
                defaultTotalWeeks = totalWeeks,
                existingStartDate = baseline?.startDate?.toString().orEmpty(),
                baseSignature = signatureOf(connectionId, termValue, target),
                officialTimes = version.isNotEmpty() && data.timeSlots.size >= 11,
                timeUnconfirmed = data.courses.any { it.timeUnconfirmed },
                timeSlots = data.timeSlots,
                fetchedAt = data.fetchedAt,
            ),
        )
    }

    override suspend fun commit(
        preview: SchoolSyncPreview,
        startDate: String,
        totalWeeks: Int,
        selectedRowKeys: Set<String>?,
    ): SchoolSyncCommitResult {
        if (preview.connectionId.isEmpty() || preview.termValue.isEmpty()) {
            return SchoolSyncCommitResult.Failed("预览数据不完整，请重新获取")
        }
        if (preview.courses.isEmpty()) {
            return SchoolSyncCommitResult.Failed("课表没有可写入的安排")
        }
        if (preview.courses.any { it.course.sourceKey.isEmpty() }) {
            return SchoolSyncCommitResult.Failed("课表安排缺少稳定标识，请重新获取")
        }
        val semesterStart = runCatching { LocalDate.parse(startDate) }.getOrNull()
            ?: return SchoolSyncCommitResult.Failed("开学日期格式应为 yyyy-MM-dd")
        if (totalWeeks !in 1..30) {
            return SchoolSyncCommitResult.Failed("总周数应在 1–30 之间")
        }
        val selected = (if (selectedRowKeys == null) preview.courses else preview.courses.filter { it.rowKey in selectedRowKeys })
            .map { it.course }
        if (selected.isEmpty()) {
            return SchoolSyncCommitResult.Failed("请至少选择一条课程安排")
        }

        val now = clock.millis()
        val syncedAt = LocalDateTime.now(clock).format(TIME_FORMAT)
        val commitResult = try {
            db.withTransaction {
                val connection = db.schoolConnectionDao().getById(preview.connectionId)
                    ?: return@withTransaction SchoolSyncCommitResult.Failed("未找到该教务账号，请重新登录")
                val resolved = resolveTarget(connection, preview.termValue)
                if ((resolved?.id ?: 0L) != preview.target.timetableId) {
                    return@withTransaction SchoolSyncCommitResult.Conflict(
                        "课表目标已变化（可能新建或切换了同学期课表），请重新获取预览",
                    )
                }
                if (signatureOf(preview.connectionId, preview.termValue, resolved) != preview.baseSignature) {
                    return@withTransaction SchoolSyncCommitResult.Conflict(
                        "预览后本地课表已发生变化，请重新获取后再写入",
                    )
                }

                val previousSnapshot = resolved?.let {
                    db.schoolSnapshotDao().get(preview.connectionId, it.id, preview.termValue)
                        ?: db.schoolSnapshotDao().getByTarget(it.id, preview.termValue)
                }
                val existingCourses = resolved?.let { db.courseDao().getAllByTimetable(it.id) } ?: emptyList()
                val previous = parseSnapshot(previousSnapshot?.payloadJson)?.takeIf { it.isNotEmpty() }
                    ?: existingCourses.filter { it.sourceKey.isNotEmpty() }.map { it.toSnapshotCourse() }

                val targetId = writeTimetable(resolved, preview, syncedAt)
                writeSemester(targetId, preview.semesterName.ifBlank { preview.termValue }, semesterStart, totalWeeks)
                val slotWarning = writeTimeSlots(targetId, preview)
                val merge = mergeCourses(targetId, selected)
                val changes = diffChanges(previous, preview.courses.map { it.course })
                val firstBaseline = previous.isEmpty()
                val warnings = buildList {
                    addAll(preview.warnings)
                    slotWarning?.let(::add)
                    if (merge.disabled > 0) {
                        add("${merge.disabled} 条已消失的学校安排仍被单次调整引用，已保留但停用")
                    }
                    if (merge.localKept > 0) {
                        add("本机新增的 ${merge.localKept} 条课程未被同步覆盖")
                    }
                }

                val batchChanges = if (firstBaseline) emptyList() else changes
                val result = if (warnings.isEmpty()) SyncBatchResult.SUCCESS else SyncBatchResult.PARTIAL
                val message = if (firstBaseline) {
                    "首次同步 ${preview.courses.size} 条安排"
                } else {
                    buildString {
                        append("新增 ${batchChanges.count { it.changeType == SyncChangeType.ADDED }}，")
                        append("修改 ${batchChanges.count { it.changeType == SyncChangeType.MODIFIED }}，")
                        append("移除 ${batchChanges.count { it.changeType == SyncChangeType.REMOVED }}")
                    }
                }
                val version = (db.syncBatchDao().maxVersion(
                    preview.connectionId,
                    targetId,
                    preview.termValue,
                ) ?: 0) + 1
                val batchId = db.syncBatchDao().insert(
                    SyncBatchEntity(
                        connectionId = preview.connectionId,
                        timetableId = targetId,
                        semester = preview.termValue,
                        version = version,
                        startedAt = preview.fetchedAt.takeIf { it > 0 } ?: now,
                        finishedAt = now,
                        result = result,
                        changeCount = batchChanges.size,
                        firstBaseline = firstBaseline,
                        message = message,
                        read = false,
                    ),
                )
                if (batchChanges.isNotEmpty()) {
                    db.syncChangeDao().insertAll(
                        batchChanges.map { change ->
                            SyncChangeEntity(
                                batchId = batchId,
                                stableKey = change.stableKey,
                                courseName = change.courseName,
                                changeType = change.changeType,
                                field = change.field,
                                beforeValue = change.beforeValue,
                                afterValue = change.afterValue,
                            )
                        },
                    )
                }
                db.syncBatchDao().trim(preview.connectionId, HISTORY_LIMIT)
                val payload = snapshotJson(preview.termValue, preview.courses.map { it.course })
                db.schoolSnapshotDao().insert(
                    SchoolSnapshotEntity(
                        connectionId = preview.connectionId,
                        timetableId = targetId,
                        semester = preview.termValue,
                        payloadJson = payload,
                        digest = sha256Hex16(payload),
                        savedAt = now,
                    ),
                )
                SchoolSyncCommitResult.Success(
                    SchoolSyncCommitSummary(
                        timetableId = targetId,
                        timetableName = resolved?.name ?: preview.target.name,
                        created = resolved == null,
                        adopted = preview.target.adopted,
                        firstBaseline = firstBaseline,
                        added = merge.added,
                        modified = merge.modified,
                        removed = merge.removed,
                        disabled = merge.disabled,
                        localKept = merge.localKept,
                        warnings = warnings,
                        batchId = batchId,
                        syncedAt = syncedAt,
                    ),
                )
            }
        } catch (error: Exception) {
            SchoolSyncCommitResult.Failed(
                error.message?.takeIf { it.isNotBlank() }?.let { "写入课表失败：$it" }
                    ?: "写入课表失败，本机原有数据已保留",
            )
        }
        if (commitResult is SchoolSyncCommitResult.Success) {
            val rawHtml = preparedRawHtml.remove("${preview.connectionId}|${preview.termValue}|${preview.fetchedAt}")
            if (!rawHtml.isNullOrBlank()) {
                runCatching {
                    courseRawStore.saveHtml(
                        timetableId = commitResult.summary.timetableId,
                        html = rawHtml,
                        term = preview.termValue,
                        savedAt = now,
                    )
                }
            }
        }
        return commitResult
    }

    override suspend fun quickSync(connectionId: String, termValue: String): SchoolSyncCommitResult {
        // 与鸿蒙 YguQuickSync 对齐：同学期已有课表则沿用，没有则直接新建并同步，
        // 开学日期/总周数沿用预览的默认值（本机当前学期 → 本周一 + 20 周兜底）。
        return when (val prepared = prepare(connectionId, termValue)) {
            is SchoolSyncPrepareResult.Ready -> commit(
                prepared.preview,
                prepared.preview.defaultStartDate,
                prepared.preview.defaultTotalWeeks,
            )
            is SchoolSyncPrepareResult.SessionExpired -> SchoolSyncCommitResult.SessionExpired(prepared.message)
            is SchoolSyncPrepareResult.Failed -> SchoolSyncCommitResult.Failed(prepared.message, prepared.retryable)
        }
    }

    private suspend fun readTimetableInternal(connectionId: String, termValue: String): SchoolTimetableResult {
        val connection = db.schoolConnectionDao().getById(connectionId)
            ?: return SchoolTimetableResult.Failed("未找到该教务账号，请重新登录", retryable = false)
        if (termValue.isBlank()) {
            return SchoolTimetableResult.Failed("请选择学期后再读取课表")
        }
        val session = SchoolSessionRequest.open(db, credentialStore, authRepository, connection)
            ?: return SchoolTimetableResult.SessionExpired("登录状态已失效，请重新登录")
        var credential = session.credential
        val nowMillis = clock.millis()
        val cachedMetadata = metadataCache[connectionId]
            ?.takeIf {
                it.sessionRevision == connection.sessionRevision &&
                    nowMillis - it.cachedAt in 0..METADATA_CACHE_TTL_MILLIS
            }
            ?.metadata
        var client = YguTimetableClient()
        var read = client.readTimetable(credential.cookieHeader, termValue, cachedMetadata) {}
        if (read.expired) {
            metadataCache.remove(connectionId)
            val relogged = session.renew()
            if (relogged != null) {
                credential = relogged
                client = YguTimetableClient()
                read = client.readTimetable(credential.cookieHeader, termValue, null) {}
            }
        }
        if (read.expired) {
            metadataCache.remove(connectionId)
            session.expire()
            return SchoolTimetableResult.SessionExpired(read.errorMessage.ifEmpty { "登录状态已失效，请重新登录" })
        }
        if (read.errorMessage.isNotEmpty()) {
            return SchoolTimetableResult.Failed(read.errorMessage, retryable = true)
        }
        val payload = read.payload
            ?: return SchoolTimetableResult.Failed("课表内容未能读取，请重试", retryable = true)
        if (!session.finish(client.cookieHeader())) {
            metadataCache.remove(connectionId)
            return SchoolTimetableResult.SessionExpired("登录状态已失效，请重新登录")
        }
        if (read.metadata.status == "timetable" && read.metadata.templateId.isNotBlank() &&
            (cachedMetadata == null || read.metadata.templateId != cachedMetadata.templateId)
        ) {
            val latestRevision = db.schoolConnectionDao().getById(connectionId)?.sessionRevision
                ?: connection.sessionRevision
            metadataCache[connectionId] = CachedTimetableMetadata(
                metadata = read.metadata,
                cachedAt = clock.millis(),
                sessionRevision = latestRevision,
            )
        }
        val parsed = YguTimetableParser.parse(payload.body)
        if (parsed.issues.isNotEmpty() || (parsed.courses.isEmpty() && !parsed.verifiedEmpty)) {
            return SchoolTimetableResult.Failed(
                parsed.issues.firstOrNull()?.let { "课表课程未能完整解析：${it.message}" }
                    ?: "课表课程未能完整解析，本机课表已保留",
            )
        }
        val version = YguClassTimes.versionOf(termValue, "YGU")
        val warnings = mutableListOf<String>()
        val courses = mapCourses(termValue, version, parsed.courses)
        val campusMode = if (version.isEmpty()) "" else YguClassTimes.campusModeOf(version, courses)
        val slots = when {
            version.isEmpty() -> {
                warnings += "未识别该学期的学校作息，写入时仅在目标尚无作息的情况下使用本机默认作息"
                ScheduleDefaults.defaultTimeSlots(0)
            }
            else -> {
                if (campusMode != YguClassTimes.CAMPUS_SOUTH &&
                    campusMode != YguClassTimes.CAMPUS_NORTH &&
                    courses.any { touchesSplit(it) }
                ) {
                    warnings += "第 3、4 节课程所属校区不一致或无法识别，作息表中这两节时间留空"
                }
                YguClassTimes.slotsFor(version, campusMode)
            }
        }
        val unconfirmed = courses.count { it.timeUnconfirmed }
        if (unconfirmed > 0) {
            warnings += "$unconfirmed 条安排无法确认官方上下课时间，已标记为时间待确认"
        }
        return SchoolTimetableResult.Success(
            SchoolTimetableData(
                termValue = termValue,
                termLabel = read.metadata.semesterLabels[termValue] ?: termValue,
                startDate = "",
                totalWeeks = DEFAULT_TOTAL_WEEKS,
                campusCode = campusMode.takeIf {
                    it == YguClassTimes.CAMPUS_SOUTH || it == YguClassTimes.CAMPUS_NORTH
                }.orEmpty(),
                campusName = YguClassTimes.campusName(campusMode),
                courses = courses,
                warnings = warnings,
                complete = true,
                rawPayload = payload.body,
                fetchedAt = clock.millis(),
                timeSlots = slots,
            ),
        )
    }

    private fun mapCourses(
        termValue: String,
        version: String,
        parsed: List<YguEduCourse>,
    ): List<SchoolCourseData> {
        val ordinals = HashMap<String, Int>()
        return parsed
            .sortedWith(
                compareBy(
                    { it.dayOfWeek },
                    { it.startSection },
                    { it.endSection },
                    { it.startWeek },
                    { it.endWeek },
                    { it.weekType.ordinal },
                    { it.name },
                    { it.teacher },
                    { it.classroom },
                ),
            )
            .map { course ->
                val identity = listOf(
                    course.name,
                    course.dayOfWeek,
                    course.startSection,
                    course.endSection,
                    course.startWeek,
                    course.endWeek,
                    course.weekType.name,
                ).joinToString("|")
                val ordinal = ordinals[identity] ?: 0
                ordinals[identity] = ordinal + 1
                val campus = if (version.isEmpty()) {
                    YguClassTimes.CAMPUS_UNKNOWN
                } else {
                    YguClassTimes.campusOf(course.classroom, version)
                }
                val range = if (version.isEmpty()) {
                    YguClassTimes.CourseTimeRange("", "", false)
                } else {
                    YguClassTimes.courseRange(version, campus, course.startSection, course.endSection)
                }
                SchoolCourseData(
                    sourceKey = "ygu:" + sha256Hex16("$termValue|$identity|$ordinal"),
                    name = course.name,
                    teacher = course.teacher,
                    classroom = course.classroom,
                    dayOfWeek = course.dayOfWeek,
                    startSection = course.startSection,
                    endSection = course.endSection,
                    startWeek = course.startWeek,
                    endWeek = course.endWeek,
                    weekType = course.weekType,
                    selectedWeeks = emptyList(),
                    campusCode = campus,
                    campusName = YguClassTimes.campusName(campus),
                    rawSections = course.sectionText,
                    rawSectionCode = "${course.startSection}-${course.endSection}",
                    note = "",
                    groupKey = "ygu:" + sha256Hex16(
                        listOf(
                            termValue,
                            course.name,
                            course.teacher,
                            course.classroom,
                            course.startWeek,
                            course.endWeek,
                            course.weekType.name,
                        ).joinToString("|"),
                    ),
                    teacherConfirmed = course.teacher.isNotEmpty(),
                    timeUnconfirmed = version.isNotEmpty() && !range.confirmed,
                    startTime = range.startTime,
                    endTime = range.endTime,
                )
            }
    }

    private fun arrangementsOf(courses: List<SchoolCourseData>): List<SchoolSyncArrangement> {
        val used = HashSet<String>()
        return courses.mapIndexed { index, course ->
            var rowKey = course.sourceKey.ifEmpty { "r%04d".format(index) }
            if (!used.add(rowKey)) {
                rowKey = "$rowKey#$index"
                used += rowKey
            }
            SchoolSyncArrangement(rowKey = rowKey, course = course)
        }
    }

    private fun groupsOf(arrangements: List<SchoolSyncArrangement>): List<SchoolSyncGroup> =
        arrangements
            .groupBy { it.course.groupKey }
            .map { (key, items) ->
                val first = items.first().course
                SchoolSyncGroup(
                    key = key,
                    name = first.name,
                    teacher = first.teacher,
                    classroom = first.classroom,
                    arrangements = items.sortedWith(
                        compareBy(
                            { it.course.dayOfWeek },
                            { it.course.startSection },
                            { it.course.startWeek },
                        ),
                    ),
                )
            }
            .sortedWith(compareBy({ it.name }, { it.teacher }, { it.classroom }))

    private suspend fun resolveTarget(connection: SchoolConnectionEntity, termValue: String): TimetableEntity? {
        db.timetableDao().getBinding(connection.id, termValue)?.let { return it }
        val subject = connection.accountSubject
        if (subject.isEmpty()) return null
        for (row in db.timetableDao().getYguByTerm(termValue)) {
            val owner = db.schoolConnectionDao().getById(row.connectionId) ?: continue
            if (owner.accountSubject == subject) return row
        }
        return null
    }

    private suspend fun writeTimetable(
        target: TimetableEntity?,
        preview: SchoolSyncPreview,
        syncedAt: String,
    ): Long {
        if (target != null) {
            db.timetableDao().upsert(
                target.copy(
                    source = "YGU",
                    schoolTerm = preview.termValue,
                    connectionId = preview.connectionId,
                    isCurrent = true,
                    lastSyncAt = syncedAt,
                ),
            )
            db.timetableDao().setCurrent(target.id)
            return target.id
        }
        val rowId = db.timetableDao().upsert(
            TimetableEntity(
                name = preview.target.name.ifBlank { defaultTimetableName(preview.termLabel) },
                source = "YGU",
                schoolTerm = preview.termValue,
                connectionId = preview.connectionId,
                isCurrent = false,
                lastSyncAt = syncedAt,
                groupName = "",
                autoEnabled = false,
                enabledDays = (1..7).toList(),
                cycleLength = 1,
                cycleWeek = 0,
            ),
        )
        check(rowId > 0) { "创建教务课表失败" }
        db.timetableDao().setCurrent(rowId)
        return rowId
    }

    private suspend fun writeSemester(
        timetableId: Long,
        name: String,
        startDate: LocalDate,
        totalWeeks: Int,
    ) {
        val existing = db.semesterDao().getAllByTimetable(timetableId)
        val base = existing.firstOrNull { it.isCurrent } ?: existing.firstOrNull { it.name == name }
        db.semesterDao().clearCurrent(timetableId)
        db.semesterDao().upsert(
            base?.copy(
                name = name,
                startDate = startDate,
                totalWeeks = totalWeeks,
                isCurrent = true,
            ) ?: SemesterEntity(
                timetableId = timetableId,
                name = name,
                startDate = startDate,
                totalWeeks = totalWeeks,
                isCurrent = true,
            ),
        )
    }

    /** 写入作息；返回需要追加到提交结果的提示。 */
    private suspend fun writeTimeSlots(timetableId: Long, preview: SchoolSyncPreview): String? {
        val existing = db.timeSlotDao().getByTimetable(timetableId)
        if (preview.officialTimes && preview.timeSlots.size >= 11) {
            val bySection = existing.associateBy { it.section }
            db.timeSlotDao().deleteByTimetable(timetableId)
            db.timeSlotDao().upsertAll(
                preview.timeSlots.map { slot ->
                    val old = bySection[slot.section]
                    slot.copy(
                        id = old?.id ?: 0,
                        timetableId = timetableId,
                        name = old?.name?.takeIf {
                            it.isNotBlank() && it != "第${slot.section}节"
                        } ?: slot.name,
                        hidden = old?.hidden ?: slot.hidden,
                        defaultCourseName = old?.defaultCourseName ?: slot.defaultCourseName,
                        sortOrder = old?.sortOrder ?: slot.sortOrder,
                    ).toEntity()
                },
            )
            return null
        }
        if (existing.isEmpty()) {
            db.timeSlotDao().upsertAll(ScheduleDefaults.defaultTimeSlots(timetableId).map { it.toEntity() })
            return "未识别该学期官方作息，已写入本机默认作息"
        }
        return "已保留本机作息（该学期官方作息未识别）"
    }

    private suspend fun mergeCourses(
        timetableId: Long,
        selected: List<SchoolCourseData>,
    ): MergeResult {
        val existing = db.courseDao().getAllByTimetable(timetableId)
        val baseRows = existing.filter { (it.overrideOfId ?: 0L) == 0L }
        val overrideRows = existing.filter { (it.overrideOfId ?: 0L) > 0L }
        val referenced = overrideRows.mapNotNull { it.overrideOfId }.toSet()
        val schoolRows = baseRows.filter { it.sourceKey.isNotEmpty() }
        val localKept = baseRows.count { it.sourceKey.isEmpty() }
        val byKey = schoolRows.groupBy { it.sourceKey }
        val matchedIds = HashSet<Long>()
        var added = 0
        var modified = 0
        for (course in selected) {
            val match = byKey[course.sourceKey]?.firstOrNull { it.id !in matchedIds }
            if (match == null) {
                db.courseDao().upsert(newCourseEntity(timetableId, course))
                added++
            } else {
                matchedIds += match.id
                db.courseDao().upsert(mergeCourseEntity(match, course))
                modified++
            }
        }
        var removed = 0
        var disabled = 0
        for (row in schoolRows) {
            if (row.id in matchedIds) continue
            if (row.id in referenced) {
                if (row.enabled) {
                    db.courseDao().upsert(row.copy(enabled = false))
                }
                disabled++
            } else {
                db.courseDao().deleteById(row.id)
                removed++
            }
        }
        return MergeResult(
            added = added,
            modified = modified,
            removed = removed,
            disabled = disabled,
            localKept = localKept,
        )
    }

    private fun newCourseEntity(timetableId: Long, course: SchoolCourseData): CourseEntity = CourseEntity(
        timetableId = timetableId,
        name = course.name,
        teacher = course.teacher,
        classroom = course.classroom,
        abbreviation = "",
        remark = "",
        dayOfWeek = course.dayOfWeek,
        startSection = course.startSection,
        endSection = course.endSection,
        sourceStartSection = course.startSection,
        sourceEndSection = course.endSection,
        startWeek = course.startWeek,
        endWeek = course.endWeek,
        weekType = course.weekType,
        selectedWeeks = course.selectedWeeks,
        color = colorFor(course.name, course.teacher),
        credit = 0.0,
        isOutdoor = false,
        enabled = true,
        timeMode = 0,
        startTime = course.startTime,
        endTime = course.endTime,
        date = "",
        cycleLength = 1,
        cycleWeek = 0,
        overrideOfId = null,
        overrideWeek = null,
        groupKey = course.groupKey,
        sourceKey = course.sourceKey,
    )

    /** 保留本机展示/调整字段（颜色、启用、缩写、备注、学分、户外、轮换），更新学校字段。 */
    private fun mergeCourseEntity(existing: CourseEntity, course: SchoolCourseData): CourseEntity =
        existing.copy(
            name = course.name,
            teacher = course.teacher,
            classroom = course.classroom,
            dayOfWeek = course.dayOfWeek,
            startSection = course.startSection,
            endSection = course.endSection,
            sourceStartSection = course.startSection,
            sourceEndSection = course.endSection,
            startWeek = course.startWeek,
            endWeek = course.endWeek,
            weekType = course.weekType,
            selectedWeeks = course.selectedWeeks,
            timeMode = 0,
            startTime = course.startTime,
            endTime = course.endTime,
            date = "",
            groupKey = course.groupKey,
            sourceKey = course.sourceKey,
        )

    private fun diffChanges(
        previous: List<SnapshotCourse>,
        current: List<SchoolCourseData>,
    ): List<Change> {
        val before = previous.associateBy { it.key }
        val after = current.associateBy { it.sourceKey }
        val changes = mutableListOf<Change>()
        for ((key, course) in after) {
            val old = before[key]
            if (old == null) {
                changes += Change(key, course.name, SyncChangeType.ADDED, "", "", "")
                continue
            }
            compareField(changes, key, course.name, "课程名称", old.name, course.name)
            compareField(changes, key, course.name, "教师", old.teacher, course.teacher)
            compareField(changes, key, course.name, "教室", old.classroom, course.classroom)
            compareField(changes, key, course.name, "上课时间", timeText(old.startTime, old.endTime), timeText(course.startTime, course.endTime))
            compareField(changes, key, course.name, "周次", weekText(old.startWeek, old.endWeek, old.weekType), weekText(course.startWeek, course.endWeek, course.weekType.name))
        }
        for ((key, course) in before) {
            if (key !in after) {
                changes += Change(key, course.name, SyncChangeType.REMOVED, "", "", "")
            }
        }
        return changes
    }

    private fun compareField(
        output: MutableList<Change>,
        key: String,
        name: String,
        field: String,
        before: String,
        after: String,
    ) {
        if (before == after) return
        output += Change(key, name, SyncChangeType.MODIFIED, field, before, after)
    }

    private fun timeText(start: String, end: String): String =
        if (start.isEmpty() || end.isEmpty()) "" else "$start-$end"

    private fun weekText(start: Int, end: Int, type: String): String {
        val suffix = when (type) {
            WeekType.ODD.name -> "单周"
            WeekType.EVEN.name -> "双周"
            else -> "周"
        }
        return "$start-$end$suffix"
    }

    private suspend fun signatureOf(
        connectionId: String,
        termValue: String,
        target: TimetableEntity?,
    ): String {
        val builder = StringBuilder("v1|").append(connectionId).append('|').append(termValue)
        if (target == null) {
            builder.append("|none")
        } else {
            builder.append("|t:").append(target.canonical())
            // 同步查询均为挂起函数；摘要计算在 prepare 与提交事务内各执行一次。
            return signatureWithDetails(builder, connectionId, termValue, target)
        }
        return sha256Hex16(builder.toString())
    }

    private suspend fun signatureWithDetails(
        builder: StringBuilder,
        connectionId: String,
        termValue: String,
        target: TimetableEntity,
    ): String {
        db.semesterDao().getAllByTimetable(target.id).sortedBy { it.id }.forEach {
            builder.append("|s:").append(it.canonical())
        }
        db.timeSlotDao().getByTimetable(target.id).forEach {
            builder.append("|l:").append(it.canonical())
        }
        db.courseDao().getAllByTimetable(target.id).forEach {
            builder.append("|c:").append(it.canonical())
        }
        val snapshot = db.schoolSnapshotDao().get(connectionId, target.id, termValue)
            ?: db.schoolSnapshotDao().getByTarget(target.id, termValue)
        builder.append("|n:").append(snapshot?.digest.orEmpty())
        return sha256Hex16(builder.toString())
    }

    private fun parseSnapshot(json: String?): List<SnapshotCourse>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            JSON.decodeFromString<SnapshotDocument>(json).courses
        }.getOrNull()
    }

    private fun snapshotJson(termValue: String, courses: List<SchoolCourseData>): String =
        JSON.encodeToString(
            SnapshotDocument(
                term = termValue,
                courses = courses.map { course ->
                    SnapshotCourse(
                        key = course.sourceKey,
                        name = course.name,
                        teacher = course.teacher,
                        classroom = course.classroom,
                        day = course.dayOfWeek,
                        start = course.startSection,
                        end = course.endSection,
                        startWeek = course.startWeek,
                        endWeek = course.endWeek,
                        weekType = course.weekType.name,
                        startTime = course.startTime,
                        endTime = course.endTime,
                    )
                },
            ),
        )

    private fun CourseEntity.toSnapshotCourse(): SnapshotCourse = SnapshotCourse(
        key = sourceKey,
        name = name,
        teacher = teacher,
        classroom = classroom,
        day = dayOfWeek,
        start = startSection,
        end = endSection,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType.name,
        startTime = startTime,
        endTime = endTime,
    )

    private fun mondayOfCurrentWeek(): LocalDate =
        LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun defaultTimetableName(termLabel: String): String =
        "阳光学院 ${termLabel.ifBlank { "教务课表" }}"

    private fun touchesSplit(course: SchoolCourseData): Boolean =
        course.endSection >= 3 && course.startSection <= 4

    private fun colorFor(name: String, teacher: String): String {
        val hash = sha256Hex16("$name|$teacher")
        val index = (hash.take(8).toLong(16) % PALETTE.size).toInt()
        return PALETTE[index]
    }

    private fun sha256Hex16(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(16)

    private fun TimetableEntity.canonical(): String = listOf(
        id, name, source, schoolTerm, connectionId, isCurrent, lastSyncAt, groupName,
        autoEnabled, enabledDays.joinToString(","), cycleLength, cycleWeek,
    ).joinToString("\u001f")

    private fun SemesterEntity.canonical(): String = listOf(
        id, name, startDate.toString(), totalWeeks, isCurrent,
    ).joinToString("\u001f")

    private fun com.gfs.iyango.data.db.TimeSlotEntity.canonical(): String = listOf(
        id, section, startTime, endTime, name, kind, hidden, defaultCourseName, sortOrder,
    ).joinToString("\u001f")

    private fun CourseEntity.canonical(): String = listOf(
        id, name, teacher, classroom, abbreviation, remark, dayOfWeek, startSection, endSection,
        sourceStartSection, sourceEndSection, startWeek, endWeek, weekType.name,
        selectedWeeks.joinToString(","), color, credit, isOutdoor, enabled, timeMode,
        startTime, endTime, date, cycleLength, cycleWeek, overrideOfId, overrideWeek,
        groupKey, sourceKey,
    ).joinToString("\u001f")

    private data class MergeResult(
        val added: Int,
        val modified: Int,
        val removed: Int,
        val disabled: Int,
        val localKept: Int,
    )

    private data class Change(
        val stableKey: String,
        val courseName: String,
        val changeType: SyncChangeType,
        val field: String,
        val beforeValue: String,
        val afterValue: String,
    )

    @Serializable
    private data class SnapshotDocument(
        val term: String = "",
        val courses: List<SnapshotCourse> = emptyList(),
    )

    @Serializable
    private data class SnapshotCourse(
        val key: String = "",
        val name: String = "",
        val teacher: String = "",
        val classroom: String = "",
        val day: Int = 0,
        val start: Int = 0,
        val end: Int = 0,
        val startWeek: Int = 0,
        val endWeek: Int = 0,
        val weekType: String = WeekType.EVERY.name,
        val startTime: String = "",
        val endTime: String = "",
    )

    private data class CachedTimetableMetadata(
        val metadata: YguTimetableMetadata,
        val cachedAt: Long,
        val sessionRevision: Int,
    )

    private companion object {
        const val DEFAULT_TOTAL_WEEKS = 20

        /** 入口页学期/模板元数据的内存复用窗口，避免短期重复同步多发一次入口页 RTT。 */
        const val METADATA_CACHE_TTL_MILLIS = 30L * 60L * 1000L

        /** 每个账号保留的同步批次上限；变化明细随批次级联清理。 */
        const val HISTORY_LIMIT = 50

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val JSON = Json { ignoreUnknownKeys = true }

        /** 与 `core:designsystem` 的 CourseSwatch 调色板保持一致（data 不能依赖 design system）。 */
        val PALETTE = listOf(
            "#3E7BFA", "#2BB673", "#F2A03D", "#F2634E", "#8B6BF0", "#2BB2B2",
            "#EE5A96", "#7A8794", "#5E6AD2", "#2E9FD9", "#7C9B3F", "#B08050",
        )
    }
}
