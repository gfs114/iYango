package com.gfs.iyango.data.ai

import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.domain.AiChatMessage
import com.gfs.iyango.core.domain.AiChatPort
import com.gfs.iyango.core.domain.AiChatRole
import com.gfs.iyango.core.domain.AiChatTurnResult
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiToolCall
import com.gfs.iyango.core.domain.AiToolDefinition
import com.gfs.iyango.core.domain.AssistantActionStatus
import com.gfs.iyango.core.domain.AssistantPendingAction
import com.gfs.iyango.core.domain.AssistantToolTrace
import com.gfs.iyango.core.domain.AssistantTurnOutput
import com.gfs.iyango.core.domain.AssistantWritePayload
import com.gfs.iyango.core.domain.CampusAiAssistantPort
import com.gfs.iyango.core.domain.CancelOccurrence
import com.gfs.iyango.core.domain.ClassroomQuickFilter
import com.gfs.iyango.core.domain.ClassroomQuickFilterEngine
import com.gfs.iyango.core.domain.ClassroomRepositoryPort
import com.gfs.iyango.core.domain.CourseRules
import com.gfs.iyango.core.domain.CreditProgressCalculator
import com.gfs.iyango.core.domain.CreditProgressPort
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.ExamRepositoryPort
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.GradeStatisticsCalculator
import com.gfs.iyango.core.domain.MoveOccurrence
import com.gfs.iyango.core.domain.NoopCreditProgressPort
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.AdjustmentType
import com.gfs.iyango.core.model.ClassroomCellState
import com.gfs.iyango.core.model.ClassroomQuery
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.CourseValidator
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.ExamSource
import com.gfs.iyango.core.model.ExamStatus
import com.gfs.iyango.core.model.ExamValidator
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.WeekType
import com.gfs.iyango.core.model.formatCreditBigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 本地 Tool-Calling 校园 AI 助理实现。
 *
 * - 只读工具（查课表、搜课程、查成绩/算均绩、GPA 模拟、查考试、查空教室）自动执行；
 * - 写入工具（加课、单次调课/停课、加考试）仅生成 [AssistantPendingAction] 确认卡片，
 *   绝不在模型响应过程中静默修改本地数据库。
 */
@Singleton
class DefaultCampusAiAssistant @Inject constructor(
    private val aiChat: AiChatPort,
    private val effectiveSchedule: EffectiveSchedulePort,
    private val timetableRepository: TimetableRepositoryPort,
    private val scheduleData: ScheduleDataPort,
    private val adjustmentRepository: AdjustmentRepositoryPort,
    private val schoolAuth: SchoolAuthPort,
    private val gradeRepository: GradeRepositoryPort,
    private val examRepository: ExamRepositoryPort,
    private val classroomRepository: ClassroomRepositoryPort,
    private val reminderCoordinator: ReminderCoordinatorPort,
    private val clock: Clock,
    private val creditProgress: CreditProgressPort,
) : CampusAiAssistantPort {

    internal constructor(
        aiChat: AiChatPort,
        effectiveSchedule: EffectiveSchedulePort,
        timetableRepository: TimetableRepositoryPort,
        scheduleData: ScheduleDataPort,
        adjustmentRepository: AdjustmentRepositoryPort,
        schoolAuth: SchoolAuthPort,
        gradeRepository: GradeRepositoryPort,
        examRepository: ExamRepositoryPort,
        classroomRepository: ClassroomRepositoryPort,
        reminderCoordinator: ReminderCoordinatorPort,
        clock: Clock,
    ) : this(
        aiChat = aiChat,
        effectiveSchedule = effectiveSchedule,
        timetableRepository = timetableRepository,
        scheduleData = scheduleData,
        adjustmentRepository = adjustmentRepository,
        schoolAuth = schoolAuth,
        gradeRepository = gradeRepository,
        examRepository = examRepository,
        classroomRepository = classroomRepository,
        reminderCoordinator = reminderCoordinator,
        clock = clock,
        creditProgress = NoopCreditProgressPort,
    )

    override suspend fun sendMessage(
        config: AiProviderConfig,
        history: List<AiChatMessage>,
        userText: String,
        onToolProgress: (String) -> Unit,
    ): AssistantTurnOutput = sendMessageStream(
        config = config,
        history = history,
        userText = userText,
        images = emptyList(),
        onToolProgress = onToolProgress,
        onTextDelta = {},
    )

    override suspend fun sendMessageStream(
        config: AiProviderConfig,
        history: List<AiChatMessage>,
        userText: String,
        images: List<AiImagePayload>,
        onToolProgress: (String) -> Unit,
        onTextDelta: (String) -> Unit,
    ): AssistantTurnOutput {
        val cleanUserText = userText.trim().ifEmpty {
            if (images.isNotEmpty()) "请识别图片内容并协助处理。" else ""
        }
        if (cleanUserText.isEmpty() && images.isEmpty()) {
            return AssistantTurnOutput(replyText = "", errorMessage = "请输入问题或指令")
        }
        if (!config.isConfigured) {
            return AssistantTurnOutput(replyText = "", errorMessage = "请先配置 AI 接口与 API Key")
        }

        val systemPrompt = buildSystemPrompt()
        val workingMessages = (
            history + AiChatMessage(
                role = AiChatRole.USER,
                content = cleanUserText,
                images = images,
            )
        ).toMutableList()
        val traces = mutableListOf<AssistantToolTrace>()
        val pendingActions = mutableListOf<AssistantPendingAction>()

        for (round in 0 until MAX_TOOL_ROUNDS) {
            when (val turn = aiChat.streamTurn(config, systemPrompt, workingMessages, TOOL_DEFINITIONS, onTextDelta)) {
                is AiChatTurnResult.Failed -> {
                    return AssistantTurnOutput(
                        replyText = "",
                        toolTraces = traces,
                        pendingActions = pendingActions,
                        errorMessage = turn.message,
                    )
                }
                is AiChatTurnResult.Reply -> {
                    if (turn.toolCalls.isEmpty()) {
                        return AssistantTurnOutput(
                            replyText = stripForbiddenBullets(turn.text),
                            toolTraces = traces,
                            pendingActions = pendingActions,
                        )
                    }
                    workingMessages += AiChatMessage(
                        role = AiChatRole.ASSISTANT,
                        content = turn.text,
                        toolCalls = turn.toolCalls,
                    )
                    for (call in turn.toolCalls) {
                        val label = toolProgressLabel(call.name)
                        onToolProgress(label)
                        val execution = runCatching { executeToolCall(call, pendingActions) }
                        val resultJson = execution.getOrElse { error ->
                            buildJsonObject {
                                put("error", error.message ?: "工具执行失败")
                            }.toString()
                        }
                        val ok = execution.isSuccess && !resultJson.contains("\"error\"")
                        traces += AssistantToolTrace(
                            toolName = call.name,
                            label = label,
                            success = ok,
                        )
                        workingMessages += AiChatMessage(
                            role = AiChatRole.TOOL,
                            content = resultJson,
                            toolCallId = call.id,
                            toolName = call.name,
                        )
                    }
                }
            }
        }

        return when (val finalTurn = aiChat.streamTurn(config, systemPrompt, workingMessages, tools = emptyList(), onTextDelta)) {
            is AiChatTurnResult.Reply -> AssistantTurnOutput(
                replyText = stripForbiddenBullets(finalTurn.text),
                toolTraces = traces,
                pendingActions = pendingActions,
            )
            is AiChatTurnResult.Failed -> AssistantTurnOutput(
                replyText = "",
                toolTraces = traces,
                pendingActions = pendingActions,
                errorMessage = finalTurn.message,
            )
        }
    }

    override suspend fun executePendingAction(action: AssistantPendingAction): Result<String> =
        when (val payload = action.payload) {
            is AssistantWritePayload.AddCourse -> executeAddCourse(payload)
            is AssistantWritePayload.EditCourse -> executeEditCourse(payload)
            is AssistantWritePayload.DeleteCourse -> executeDeleteCourse(payload)
            is AssistantWritePayload.AdjustCourse -> executeAdjustCourse(payload)
            is AssistantWritePayload.AddExam -> executeAddExam(payload)
            is AssistantWritePayload.EditExam -> executeEditExam(payload)
            is AssistantWritePayload.DeleteExam -> executeDeleteExam(payload)
        }

    private suspend fun executeAddCourse(payload: AssistantWritePayload.AddCourse): Result<String> {
        val currentTable = timetableRepository.observeCurrent().first()
            ?: return Result.failure(IllegalStateException("当前没有可用课表，请先创建课表"))
        val course = Course(
            timetableId = currentTable.id,
            name = payload.name.trim(),
            teacher = payload.teacher.trim(),
            classroom = payload.classroom.trim(),
            dayOfWeek = payload.dayOfWeek,
            startSection = payload.startSection,
            endSection = payload.endSection,
            startWeek = payload.startWeek,
            endWeek = payload.endWeek,
            weekType = payload.weekType,
            timeMode = payload.timeMode,
            startTime = payload.startTime.trim(),
            endTime = payload.endTime.trim(),
            date = payload.date.trim(),
            remark = payload.remark.trim(),
            enabled = true,
        )
        CourseValidator.validate(course)?.let { msg ->
            return Result.failure(IllegalArgumentException(msg))
        }
        scheduleData.saveCourse(course)
        runCatching { reminderCoordinator.refresh() }
        return Result.success("已添加课程 ${course.name}")
    }

    private suspend fun executeAdjustCourse(payload: AssistantWritePayload.AdjustCourse): Result<String> {
        val adjustment = when (payload.type) {
            AdjustmentType.MOVE -> {
                val targetDate = runCatching { LocalDate.parse(payload.targetDate) }.getOrNull()
                    ?: return Result.failure(IllegalArgumentException("目标日期无效"))
                MoveOccurrence.create(
                    timetableId = payload.timetableId,
                    sourceCourseId = payload.sourceCourseId,
                    sourceDate = payload.sourceDate,
                    targetDate = targetDate,
                    targetStartSection = payload.targetStartSection,
                    targetEndSection = payload.targetEndSection,
                    targetClassroom = payload.targetClassroom,
                    note = payload.note,
                    now = clock.millis(),
                )
            }
            AdjustmentType.CANCEL -> {
                CancelOccurrence.create(
                    timetableId = payload.timetableId,
                    sourceCourseId = payload.sourceCourseId,
                    sourceDate = payload.sourceDate,
                    note = payload.note,
                    now = clock.millis(),
                )
            }
            AdjustmentType.TEMPORARY_DAY -> {
                return Result.failure(IllegalArgumentException("暂不支持该调整类型"))
            }
        }
        adjustmentRepository.insert(adjustment)
        runCatching { reminderCoordinator.refresh() }
        val verb = if (payload.type == AdjustmentType.CANCEL) "已停课" else "已调整"
        return Result.success("$verb ${payload.courseName}")
    }

    private suspend fun executeAddExam(payload: AssistantWritePayload.AddExam): Result<String> {
        val exam = Exam(
            source = ExamSource.MANUAL,
            subject = payload.subject.trim(),
            date = payload.date.trim(),
            startTime = payload.startTime.trim(),
            endTime = payload.endTime.trim(),
            location = payload.location.trim(),
            seat = payload.seat.trim(),
            note = payload.note.trim(),
            status = ExamStatus.SCHEDULED,
            updatedAt = clock.millis(),
        )
        ExamValidator.validate(exam)?.let { msg ->
            return Result.failure(IllegalArgumentException(msg))
        }
        examRepository.save(exam)
        runCatching { reminderCoordinator.refresh() }
        return Result.success("已添加考试 ${exam.subject}")
    }

    private suspend fun executeEditCourse(payload: AssistantWritePayload.EditCourse): Result<String> {
        val existing = scheduleData.observeCourses(payload.timetableId).first()
            .firstOrNull { it.id == payload.courseId }
            ?: return Result.failure(IllegalStateException("未找到原课程，可能已被删除"))
        val updated = existing.copy(
            name = payload.name.trim(),
            teacher = payload.teacher.trim(),
            classroom = payload.classroom.trim(),
            dayOfWeek = payload.dayOfWeek,
            startSection = payload.startSection,
            endSection = payload.endSection,
            startWeek = payload.startWeek,
            endWeek = payload.endWeek,
            weekType = payload.weekType,
            remark = payload.remark.trim(),
        )
        CourseValidator.validate(updated)?.let { msg ->
            return Result.failure(IllegalArgumentException(msg))
        }
        scheduleData.saveCourse(updated)
        runCatching { reminderCoordinator.refresh() }
        return Result.success("已更新课程 ${updated.name}")
    }

    private suspend fun executeDeleteCourse(payload: AssistantWritePayload.DeleteCourse): Result<String> {
        scheduleData.deleteCourses(payload.timetableId, setOf(payload.courseId))
        runCatching { reminderCoordinator.refresh() }
        return Result.success("已删除课程 ${payload.courseName}")
    }

    private suspend fun executeEditExam(payload: AssistantWritePayload.EditExam): Result<String> {
        val existing = examRepository.get(payload.examId)
            ?: return Result.failure(IllegalStateException("未找到原考试记录，可能已被删除"))
        val updated = existing.copy(
            subject = payload.subject.trim(),
            date = payload.date.trim(),
            startTime = payload.startTime.trim(),
            endTime = payload.endTime.trim(),
            location = payload.location.trim(),
            seat = payload.seat.trim(),
            note = payload.note.trim(),
            updatedAt = clock.millis(),
        )
        ExamValidator.validate(updated)?.let { msg ->
            return Result.failure(IllegalArgumentException(msg))
        }
        examRepository.save(updated)
        runCatching { reminderCoordinator.refresh() }
        return Result.success("已更新考试 ${updated.subject}")
    }

    private suspend fun executeDeleteExam(payload: AssistantWritePayload.DeleteExam): Result<String> {
        examRepository.delete(payload.examId)
        runCatching { reminderCoordinator.refresh() }
        return Result.success("已删除考试 ${payload.subject}")
    }

    private suspend fun buildSystemPrompt(): String {
        val today = LocalDate.now(clock)
        val nowTime = LocalTime.now(clock).format(TIME_FORMATTER)
        val currentTable = runCatching { timetableRepository.observeCurrent().first() }.getOrNull()
        val semester = currentTable?.let {
            runCatching { scheduleData.observeSemester(it.id).first() }.getOrNull()
        }
        val currentWeek = CourseRules.weekOfDate(semester, today)
        val weekLabel = if (currentWeek > 0) "第${currentWeek}周" else "非教学周"
        val tableName = currentTable?.name?.ifBlank { "默认课表" } ?: "未选中课表"
        return """
            你是 iYango 校园助手，运行在用户手机本地，通过工具直接读取与维护用户的课表、成绩、学分进度、考试和教室数据。
            当前日期：${today}（${weekdayLabel(today.dayOfWeek.value)}），当前时间：$nowTime
            当前课表：$tableName，当前学期教学周：$weekLabel

            工作准则：
            1. 回答课表、课程、成绩、绩点、学分进度、考试、空教室问题时必须先调用对应工具获取真实数据，严禁凭空编造课程、时间、地点或分数。
            2. 新增/修改/删除课程、单次调课/停课、新增/修改/删除考试时，必须调用对应 propose_* 工具生成待确认卡片，并在回复中提示用户在下方确认卡片中点击「确认执行」。
            3. 当用户发送群通知、考表、调课截图时，提取核心信息后主动调用 propose_add_course / propose_adjust_course / propose_add_exam 生成确认卡片。
            4. 回复简洁清晰，列出条目时直接换行，禁止使用圆点分隔符「·」或「•」。
        """.trimIndent()
    }

    private suspend fun executeToolCall(
        call: AiToolCall,
        pendingActions: MutableList<AssistantPendingAction>,
    ): String {
        val args = parseArgsObject(call.argumentsJson)
        return when (call.name) {
            "get_schedule" -> handleGetSchedule(args)
            "search_courses" -> handleSearchCourses(args)
            "get_grades_summary" -> handleGetGradesSummary(args)
            "simulate_gpa" -> handleSimulateGpa(args)
            "get_credit_progress" -> handleGetCreditProgress(args)
            "get_upcoming_exams" -> handleGetUpcomingExams(args)
            "find_free_classrooms" -> handleFindFreeClassrooms(args)
            "propose_add_course" -> handleProposeAddCourse(args, pendingActions)
            "propose_edit_course" -> handleProposeEditCourse(args, pendingActions)
            "propose_delete_course" -> handleProposeDeleteCourse(args, pendingActions)
            "propose_adjust_course" -> handleProposeAdjustCourse(args, pendingActions)
            "propose_add_exam" -> handleProposeAddExam(args, pendingActions)
            "propose_edit_exam" -> handleProposeEditExam(args, pendingActions)
            "propose_delete_exam" -> handleProposeDeleteExam(args, pendingActions)
            else -> buildJsonObject { put("error", "未知工具: ${call.name}") }.toString()
        }
    }

    private suspend fun handleGetSchedule(args: JsonObject): String {
        val today = LocalDate.now(clock)
        val startStr = args.stringArg("start_date")
        val startDate = if (startStr.isBlank()) {
            today
        } else {
            runCatching { LocalDate.parse(startStr) }.getOrElse {
                return jsonError("start_date 格式需为 yyyy-MM-dd")
            }
        }
        val days = (args.intArg("days") ?: 1).coerceIn(1, 14)
        val endDate = startDate.plusDays((days - 1).toLong())
        val resolvedDays = effectiveSchedule.resolveSelectedRange(startDate..endDate)

        return buildJsonObject {
            put("startDate", startDate.toString())
            put("endDate", endDate.toString())
            putJsonArray("days") {
                resolvedDays.forEach { day ->
                    val week = CourseRules.weekOfDate(day.semester, day.date)
                    addJsonObject {
                        put("date", day.date.toString())
                        put("weekday", weekdayLabel(day.date.dayOfWeek.value))
                        put("dayOfWeek", day.date.dayOfWeek.value)
                        put("teachingWeek", week)
                        put("timetable", day.timetable?.name.orEmpty())
                        put("isTemporaryDay", day.isTemporaryDay)
                        putJsonArray("courses") {
                            day.occurrences.forEach { occ ->
                                addJsonObject {
                                    put("courseId", occ.course.id)
                                    put("name", occ.course.name)
                                    put("teacher", occ.course.teacher)
                                    put("classroom", occ.course.classroom)
                                    put("startSection", occ.course.startSection)
                                    put("endSection", occ.course.endSection)
                                    put("startTime", occ.startMinute?.let(::formatMinute).orEmpty())
                                    put("endTime", occ.endMinute?.let(::formatMinute).orEmpty())
                                    put("anchorDate", occ.anchorDate.toString())
                                    put("isOverride", occ.isOverride)
                                    put("isMoved", occ.isMoved)
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    private suspend fun handleSearchCourses(args: JsonObject): String {
        val currentTable = timetableRepository.observeCurrent().first()
            ?: return jsonError("当前没有选中课表")
        val query = args.stringArg("query")
        val courses = scheduleData.observeCourses(currentTable.id).first()
            .filter { course ->
                if (query.isEmpty()) true
                else {
                    course.name.contains(query, ignoreCase = true) ||
                        course.teacher.contains(query, ignoreCase = true) ||
                        course.classroom.contains(query, ignoreCase = true)
                }
            }
            .take(40)

        return buildJsonObject {
            put("timetable", currentTable.name)
            put("totalMatched", courses.size)
            putJsonArray("courses") {
                courses.forEach { course ->
                    addJsonObject {
                        put("id", course.id)
                        put("name", course.name)
                        put("teacher", course.teacher)
                        put("classroom", course.classroom)
                        put("dayOfWeek", course.dayOfWeek)
                        put("weekday", weekdayLabel(course.dayOfWeek))
                        put("startSection", course.startSection)
                        put("endSection", course.endSection)
                        put("startWeek", course.startWeek)
                        put("endWeek", course.endWeek)
                        put("weekType", course.weekType.name)
                        put("credit", course.credit)
                        put("enabled", course.enabled)
                    }
                }
            }
        }.toString()
    }

    private suspend fun handleGetGradesSummary(args: JsonObject): String {
        val semesterFilter = args.stringArg("semester")
        val includeOptional = args.boolArg("include_optional") ?: false
        val connection = resolveGradeConnection()
            ?: return jsonError("未找到教务账号或暂无成绩缓存，请先在「我的」登录教务账号并同步成绩")
        val accountKey = connection.accountSubject.ifEmpty { connection.id }
        val loaded = loadCachedGrades(accountKey, semesterFilter)
        val stats = GradeStatisticsCalculator.calculate(loaded.records, includeOptional = includeOptional)

        return buildJsonObject {
            put("semester", semesterFilter.ifBlank { "全部学期" })
            put("availableSemesters", JsonArray(loaded.availableSemesters.map { JsonPrimitive(it) }))
            put("recordCount", loaded.records.size)
            put("includedCount", stats.includedCount)
            put("excludedCount", stats.excludedCount)
            put("totalCredits", round2(stats.totalCredits))
            stats.averageGradePoint?.let { put("averageGradePoint", round2(it)) }
            stats.weightedAverage?.let { put("weightedAverage", round2(it)) }
            putJsonArray("records") {
                loaded.records.take(50).forEach { rec ->
                    addJsonObject {
                        put("courseName", rec.courseName)
                        put("semester", rec.semester)
                        put("credit", rec.credit)
                        put("scoreText", rec.scoreText)
                        GradeStatisticsCalculator.resolvedScore(rec)?.let { put("scoreValue", round2(it)) }
                        GradeStatisticsCalculator.resolvedGradePoint(rec)?.let { put("gradePoint", round2(it)) }
                        put("attribute", rec.attribute)
                        put("isNew", rec.isNew)
                    }
                }
            }
        }.toString()
    }

    private suspend fun handleSimulateGpa(args: JsonObject): String {
        val semesterFilter = args.stringArg("semester")
        val includeOptional = args.boolArg("include_optional") ?: false
        val connection = resolveGradeConnection()
        val existingRecords = if (connection != null) {
            val accountKey = connection.accountSubject.ifEmpty { connection.id }
            loadCachedGrades(accountKey, semesterFilter).records
        } else {
            emptyList()
        }
        val baseStats = GradeStatisticsCalculator.calculate(existingRecords, includeOptional = includeOptional)

        var baseCredit = 0.0
        var baseScoreSum = 0.0
        var baseGpaCredit = 0.0
        var baseGpaSum = 0.0
        existingRecords.forEach { rec ->
            val score = GradeStatisticsCalculator.resolvedScore(rec)
            if (rec.credit > 0.0 && score != null && (includeOptional || !GradeStatisticsCalculator.isPublicElective(rec))) {
                baseCredit += rec.credit
                baseScoreSum += score * rec.credit
                GradeStatisticsCalculator.resolvedGradePoint(rec)?.let { gp ->
                    baseGpaCredit += rec.credit
                    baseGpaSum += gp * rec.credit
                }
            }
        }

        var simCredit = baseCredit
        var simScoreSum = baseScoreSum
        var simGpaCredit = baseGpaCredit
        var simGpaSum = baseGpaSum
        val addedCount: Int

        val hypothetical = args["hypothetical_courses"] as? JsonArray
        var validHypothetical = 0
        hypothetical?.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            val credit = obj.doubleArg("credit") ?: return@forEach
            if (credit <= 0.0) return@forEach
            val score = obj.doubleArg("score")
            val explicitGp = obj.doubleArg("grade_point") ?: obj.doubleArg("gradePoint")
            val gp = explicitGp ?: score?.let(::scoreToDefaultGradePoint) ?: return@forEach
            validHypothetical++
            simGpaCredit += credit
            simGpaSum += gp * credit
            if (score != null) {
                simCredit += credit
                simScoreSum += score * credit
            }
        }
        addedCount = validHypothetical

        val currentGpa = baseStats.averageGradePoint
        val simulatedGpa = if (simGpaCredit > 0.0) simGpaSum / simGpaCredit else null
        val simulatedWeightedScore = if (simCredit > 0.0) simScoreSum / simCredit else null
        val deltaGpa = if (currentGpa != null && simulatedGpa != null) simulatedGpa - currentGpa else null

        val targetGpa = args.doubleArg("target_gpa")
        val remainingCredits = args.doubleArg("remaining_credits")

        return buildJsonObject {
            put("baseIncludedCount", baseStats.includedCount)
            put("baseCredits", round2(baseStats.totalCredits))
            currentGpa?.let { put("currentGpa", round2(it)) }
            put("hypotheticalCount", addedCount)
            put("simulatedGpaCredits", round2(simGpaCredit))
            simulatedGpa?.let { put("simulatedGpa", round2(it)) }
            deltaGpa?.let { put("deltaGpa", round2(it)) }
            simulatedWeightedScore?.let { put("simulatedWeightedAverage", round2(it)) }
            if (targetGpa != null && remainingCredits != null && remainingCredits > 0.0) {
                val needed = (targetGpa * (simGpaCredit + remainingCredits) - simGpaSum) / remainingCredits
                put("targetGpa", round2(targetGpa))
                put("remainingCredits", round2(remainingCredits))
                put("requiredAverageGpa", round2(needed))
                put("achievableOn5PointScale", needed <= 5.0)
                put("achievableOn4PointScale", needed <= 4.0)
            }
        }.toString()
    }

    private suspend fun handleGetUpcomingExams(args: JsonObject): String {
        val today = LocalDate.now(clock)
        val daysAhead = (args.intArg("days_ahead") ?: 60).coerceIn(1, 365)
        val includePast = args.boolArg("include_past") ?: false
        val cutoff = today.plusDays(daysAhead.toLong())

        val exams = examRepository.observeAll().first()
            .filter { exam -> exam.status != ExamStatus.CANCELLED }
            .filter { exam ->
                val start = exam.startDateOrNull() ?: return@filter true
                val end = exam.resolvedEndDateOrNull() ?: start
                (includePast || !end.isBefore(today)) && !start.isAfter(cutoff)
            }
            .take(30)

        return buildJsonObject {
            put("today", today.toString())
            put("count", exams.size)
            putJsonArray("exams") {
                exams.forEach { exam ->
                    val start = exam.startDateOrNull()
                    addJsonObject {
                        put("id", exam.id)
                        put("subject", exam.subject)
                        put("type", exam.type)
                        put("date", exam.date)
                        put("endDate", exam.endDate)
                        put("startTime", exam.startTime)
                        put("endTime", exam.endTime)
                        put("location", exam.location)
                        put("seat", exam.seat)
                        put("status", exam.status.name)
                        if (start != null) {
                            put("daysLeft", ChronoUnit.DAYS.between(today, start))
                        }
                    }
                }
            }
        }.toString()
    }

    private suspend fun handleFindFreeClassrooms(args: JsonObject): String {
        val active = schoolAuth.observeActiveConnection().first()
            ?.takeIf { it.status == SchoolConnectionStatus.ACTIVE }
            ?: return jsonError("查询空教室需要先登录有效的教务账号（当前未登录或会话已过期）")

        val metadata = classroomRepository.metadata(active.id).getOrElse { err ->
            return jsonError(err.message ?: "获取教室筛选条件失败")
        }
        val term = metadata.terms.firstOrNull { it.selected }?.value
            ?: metadata.terms.firstOrNull()?.value.orEmpty()
        val timeMode = metadata.timeModes.firstOrNull { it.selected }?.value
            ?: metadata.timeModes.firstOrNull()?.value.orEmpty()
        val campus = metadata.campuses.firstOrNull { it.selected }?.value
            ?: metadata.campuses.firstOrNull()?.value.orEmpty()
        val selectedWeek = args.intArg("week")
            ?: metadata.weeks.firstOrNull { it.selected }?.value?.toIntOrNull()
            ?: resolveCurrentTeachingWeek()
            ?: 1
        val today = LocalDate.now(clock)
        val dayOfWeek = (args.intArg("day_of_week") ?: today.dayOfWeek.value).coerceIn(1, 7)
        val buildingKeyword = args.stringArg("building_keyword")
        val buildings = if (metadata.buildings.isNotEmpty()) {
            metadata.buildings.map { com.gfs.iyango.core.model.ClassroomBuilding(it.value, it.label) }
        } else if (campus.isNotBlank()) {
            classroomRepository.buildings(active.id, campus).getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val matchedBuilding = if (buildingKeyword.isNotBlank()) {
            buildings.firstOrNull { it.name.contains(buildingKeyword, ignoreCase = true) }
        } else {
            null
        }
        val query = ClassroomQuery(
            termValue = term,
            timeModeId = timeMode,
            campusCode = campus,
            buildingCode = matchedBuilding?.code.orEmpty(),
            roomType = "",
            week = selectedWeek,
            dayOfWeek = dayOfWeek,
            sectionCode = "",
            allSectionCodes = metadata.sections.map { it.value },
        )
        val result = classroomRepository.query(active.id, query).getOrElse { err ->
            return jsonError(err.message ?: "查询空教室失败")
        }
        val minConsecutive = (args.intArg("min_consecutive") ?: 1).coerceIn(1, 4)
        val availableNowOnly = args.boolArg("available_now_only") ?: false
        val filter = ClassroomQuickFilter(
            availableNowOnly = availableNowOnly,
            minConsecutiveFreeGroups = minConsecutive,
        )
        val filtered = if (filter.active) {
            ClassroomQuickFilterEngine.project(result, filter, LocalTime.now(clock))
        } else {
            null
        }

        val periodLabels = result.periodGroups.map { group ->
            group.label.ifBlank { group.rawCode }
        }

        return buildJsonObject {
            put("week", selectedWeek)
            put("dayOfWeek", dayOfWeek)
            put("weekday", weekdayLabel(dayOfWeek))
            put("building", matchedBuilding?.name ?: "全部教学楼")
            put("availableBuildings", JsonArray(buildings.take(20).map { JsonPrimitive(it.name) }))
            if (filtered != null) {
                put("emptyReason", filtered.emptyReason)
                put("matchedCount", filtered.rooms.size)
                putJsonArray("rooms") {
                    filtered.rooms.take(15).forEach { proj ->
                        addJsonObject {
                            put("name", proj.room.name)
                            put("seatInfo", proj.room.seatInfo)
                            put("roomType", proj.room.roomType)
                            put("badge", proj.badgeText)
                            put("consecutiveFreeCount", proj.consecutiveFreeCount)
                            put("freeUntilTime", proj.freeUntilTime)
                        }
                    }
                }
            } else {
                val roomsWithFree = result.rooms.mapNotNull { room ->
                    val freeLabels = result.periodGroups.indices.mapNotNull { gIdx ->
                        if (room.cell(dayOfWeek, gIdx)?.state == ClassroomCellState.FREE) {
                            periodLabels.getOrNull(gIdx)
                        } else {
                            null
                        }
                    }
                    if (freeLabels.isEmpty()) null else room to freeLabels
                }
                put("matchedCount", roomsWithFree.size)
                putJsonArray("rooms") {
                    roomsWithFree.take(15).forEach { (room, freeLabels) ->
                        addJsonObject {
                            put("name", room.name)
                            put("seatInfo", room.seatInfo)
                            put("roomType", room.roomType)
                            put("freeSlots", JsonArray(freeLabels.map { JsonPrimitive(it) }))
                        }
                    }
                }
            }
        }.toString()
    }

    private fun handleProposeAddCourse(
        args: JsonObject,
        pendingActions: MutableList<AssistantPendingAction>,
    ): String {
        val name = args.stringArg("name")
        if (name.isEmpty()) return jsonError("课程名称不能为空")
        val date = args.stringArg("date")
        val startTime = args.stringArg("start_time")
        val endTime = args.stringArg("end_time")
        val explicitDay = args.intArg("day_of_week")
        val parsedDate = date.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (date.isNotEmpty() && parsedDate == null) {
            return jsonError("date 格式需为 yyyy-MM-dd")
        }
        val dayOfWeek = explicitDay ?: parsedDate?.dayOfWeek?.value ?: 1
        val timeMode = when {
            date.isNotEmpty() && startTime.isNotEmpty() && endTime.isNotEmpty() -> 2
            startTime.isNotEmpty() && endTime.isNotEmpty() -> 1
            else -> 0
        }
        val startSection = (args.intArg("start_section") ?: 1).coerceIn(1, 20)
        val endSection = (args.intArg("end_section") ?: startSection).coerceIn(startSection, 20)
        val startWeek = (args.intArg("start_week") ?: 1).coerceIn(1, 30)
        val endWeek = (args.intArg("end_week") ?: 16).coerceIn(startWeek, 30)
        val weekType = when (args.stringArg("week_type").uppercase(Locale.ROOT)) {
            "ODD", "1", "单周" -> WeekType.ODD
            "EVEN", "2", "双周" -> WeekType.EVEN
            else -> WeekType.EVERY
        }
        val teacher = args.stringArg("teacher")
        val classroom = args.stringArg("classroom")
        val remark = args.stringArg("remark")

        val testCourse = Course(
            name = name,
            teacher = teacher,
            classroom = classroom,
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            endSection = endSection,
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = weekType,
            timeMode = timeMode,
            startTime = startTime,
            endTime = endTime,
            date = date,
            remark = remark,
        )
        CourseValidator.validate(testCourse)?.let { msg -> return jsonError(msg) }

        val payload = AssistantWritePayload.AddCourse(
            name = name,
            teacher = teacher,
            classroom = classroom,
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            endSection = endSection,
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = weekType,
            timeMode = timeMode,
            startTime = startTime,
            endTime = endTime,
            date = date,
            remark = remark,
        )
        val summary = buildList {
            add("课程：$name")
            if (timeMode == 2) {
                add("时间：$date $startTime-$endTime")
            } else {
                val weekSuffix = when (weekType) {
                    WeekType.ODD -> "（单周）"
                    WeekType.EVEN -> "（双周）"
                    WeekType.EVERY -> ""
                }
                add("时间：${weekdayLabel(dayOfWeek)} 第${startSection}-${endSection}节")
                add("周次：第${startWeek}-${endWeek}周$weekSuffix")
                if (startTime.isNotEmpty() && endTime.isNotEmpty()) {
                    add("钟点：$startTime-$endTime")
                }
            }
            if (classroom.isNotEmpty()) add("地点：$classroom")
            if (teacher.isNotEmpty()) add("教师：$teacher")
        }
        val actionId = UUID.randomUUID().toString()
        pendingActions += AssistantPendingAction(
            id = actionId,
            title = "添加课程",
            summaryLines = summary,
            payload = payload,
            status = AssistantActionStatus.PENDING,
        )
        return buildJsonObject {
            put("status", "pending_confirmation")
            put("actionId", actionId)
            put("message", "已生成添加课程确认卡片，等待用户点击确认")
        }.toString()
    }

    private suspend fun handleProposeAdjustCourse(
        args: JsonObject,
        pendingActions: MutableList<AssistantPendingAction>,
    ): String {
        val courseName = args.stringArg("course_name")
        val sourceDateStr = args.stringArg("source_date")
        val actionTypeRaw = args.stringArg("action_type").uppercase(Locale.ROOT)
        if (courseName.isEmpty() || sourceDateStr.isEmpty()) {
            return jsonError("course_name 与 source_date 不能为空")
        }
        val sourceDate = runCatching { LocalDate.parse(sourceDateStr) }.getOrElse {
            return jsonError("source_date 格式需为 yyyy-MM-dd")
        }
        val daySchedule = effectiveSchedule.resolveDay(sourceDate)
        val timetable = daySchedule.timetable
            ?: return jsonError("当天没有有效课表")
        val matched = daySchedule.occurrences.firstOrNull {
            it.course.name.contains(courseName, ignoreCase = true)
        } ?: return buildJsonObject {
            put("error", "未在 $sourceDateStr 找到名称包含「$courseName」的课程")
            putJsonArray("availableCourses") {
                daySchedule.occurrences.forEach { add(it.course.name) }
            }
        }.toString()

        val sourceCourseId = if (matched.course.overrideOfId > 0) {
            matched.course.overrideOfId
        } else {
            matched.course.id
        }
        if (sourceCourseId <= 0L) {
            return jsonError("该课程缺少有效课程 ID，无法创建单次调课")
        }

        val type = if (actionTypeRaw == "CANCEL") AdjustmentType.CANCEL else AdjustmentType.MOVE
        val targetDateStr = args.stringArg("target_date").ifBlank { sourceDateStr }
        if (type == AdjustmentType.MOVE) {
            runCatching { LocalDate.parse(targetDateStr) }.getOrElse {
                return jsonError("target_date 格式需为 yyyy-MM-dd")
            }
        }
        val targetStartSection = (args.intArg("target_start_section") ?: matched.course.startSection).coerceIn(1, 20)
        val targetEndSection = (args.intArg("target_end_section") ?: matched.course.endSection)
            .coerceIn(targetStartSection, 20)
        val targetClassroom = args.stringArg("target_classroom").ifBlank { matched.course.classroom }
        val note = args.stringArg("note")

        val payload = AssistantWritePayload.AdjustCourse(
            timetableId = timetable.id,
            sourceCourseId = sourceCourseId,
            courseName = matched.course.name,
            sourceDate = matched.anchorDate.toString(),
            type = type,
            targetDate = if (type == AdjustmentType.MOVE) targetDateStr else "",
            targetStartSection = if (type == AdjustmentType.MOVE) targetStartSection else 0,
            targetEndSection = if (type == AdjustmentType.MOVE) targetEndSection else 0,
            targetClassroom = if (type == AdjustmentType.MOVE) targetClassroom else "",
            note = note,
        )
        val summary = buildList {
            add("课程：${matched.course.name}")
            add("原安排：${matched.anchorDate} 第${matched.course.startSection}-${matched.course.endSection}节")
            if (type == AdjustmentType.CANCEL) {
                add("操作：单次停课")
            } else {
                add("调整为：$targetDateStr 第${targetStartSection}-${targetEndSection}节")
                if (targetClassroom.isNotEmpty()) add("教室：$targetClassroom")
            }
            if (note.isNotEmpty()) add("备注：$note")
        }
        val actionId = UUID.randomUUID().toString()
        pendingActions += AssistantPendingAction(
            id = actionId,
            title = if (type == AdjustmentType.CANCEL) "单次停课" else "单次调课",
            summaryLines = summary,
            payload = payload,
            status = AssistantActionStatus.PENDING,
        )
        return buildJsonObject {
            put("status", "pending_confirmation")
            put("actionId", actionId)
            put("message", "已生成调课确认卡片，等待用户点击确认")
        }.toString()
    }

    private fun handleProposeAddExam(
        args: JsonObject,
        pendingActions: MutableList<AssistantPendingAction>,
    ): String {
        val subject = args.stringArg("subject")
        val date = args.stringArg("date")
        val startTime = args.stringArg("start_time")
        val endTime = args.stringArg("end_time")
        val location = args.stringArg("location")
        val seat = args.stringArg("seat")
        val note = args.stringArg("note")

        val candidate = Exam(
            source = ExamSource.MANUAL,
            subject = subject,
            date = date,
            startTime = startTime,
            endTime = endTime,
            location = location,
            seat = seat,
            note = note,
        )
        ExamValidator.validate(candidate)?.let { msg -> return jsonError(msg) }

        val payload = AssistantWritePayload.AddExam(
            subject = subject,
            date = date,
            startTime = startTime,
            endTime = endTime,
            location = location,
            seat = seat,
            note = note,
        )
        val summary = buildList {
            add("科目：$subject")
            val timePart = if (startTime.isNotEmpty() && endTime.isNotEmpty()) " $startTime-$endTime" else ""
            add("时间：$date$timePart")
            if (location.isNotEmpty()) add("考场：$location")
            if (seat.isNotEmpty()) add("座位：$seat")
        }
        val actionId = UUID.randomUUID().toString()
        pendingActions += AssistantPendingAction(
            id = actionId,
            title = "添加考试",
            summaryLines = summary,
            payload = payload,
            status = AssistantActionStatus.PENDING,
        )
        return buildJsonObject {
            put("status", "pending_confirmation")
            put("actionId", actionId)
            put("message", "已生成添加考试确认卡片，等待用户点击确认")
        }.toString()
    }

    private suspend fun handleGetCreditProgress(args: JsonObject): String {
        val connection = resolveGradeConnection()
            ?: return jsonError("当前未绑定教务账号，暂无学分进度数据")
        val accountKey = connection.accountSubject.ifEmpty { connection.id }
        val source = gradeRepository.observeCreditProgressSource(accountKey).first()
        val plan = creditProgress.getAccountPlan(accountKey)
        val report = CreditProgressCalculator.calculate(source, plan)
        val categoryKeyword = args.stringArg("category")
        val categories = if (categoryKeyword.isNotEmpty()) {
            report.categoryProgressList.filter {
                it.category.displayName.contains(categoryKeyword, ignoreCase = true)
            }
        } else {
            report.categoryProgressList
        }
        return buildJsonObject {
            put("coverageStatus", report.coverageStatus.name)
            put("coveredSemesterCount", report.coveredSemesterCount)
            put("targetSemesterCount", report.targetSemesterCount)
            put("earnedTotalCredits", formatCreditBigDecimal(report.earnedTotalCredits))
            report.totalTargetCredits?.let { put("totalTargetCredits", formatCreditBigDecimal(it)) }
            report.remainingTotalCredits?.let { put("remainingTotalCredits", formatCreditBigDecimal(it)) }
            put("pendingCourseCount", report.pendingCourses.size)
            put("notCountedCourseCount", report.notCountedCourses.size)
            putJsonArray("categories") {
                categories.forEach { cat ->
                    addJsonObject {
                        put("id", cat.category.categoryId)
                        put("name", cat.category.displayName)
                        put("earnedCredits", formatCreditBigDecimal(cat.earnedCredits))
                        cat.targetCredits?.let { put("targetCredits", formatCreditBigDecimal(it)) }
                        cat.remainingCredits?.let { put("remainingCredits", formatCreditBigDecimal(it)) }
                        put("pendingCount", cat.pendingCount)
                    }
                }
            }
        }.toString()
    }

    private suspend fun handleProposeEditCourse(
        args: JsonObject,
        pendingActions: MutableList<AssistantPendingAction>,
    ): String {
        val currentTable = timetableRepository.observeCurrent().first()
            ?: return jsonError("当前没有可用课表")
        val baseCourses = scheduleData.observeCourses(currentTable.id).first()
            .filter { it.overrideOfId == 0L }
        val courseId = args.intArg("course_id")?.toLong()
        val courseName = args.stringArg("course_name")
        val target = when {
            courseId != null && courseId > 0L -> baseCourses.firstOrNull { it.id == courseId }
            courseName.isNotEmpty() -> baseCourses.firstOrNull { it.name.contains(courseName, ignoreCase = true) }
            else -> null
        } ?: return jsonError("未在当前课表中找到匹配课程")

        val newName = args.stringArg("new_name").ifBlank { target.name }
        val newTeacher = args.stringArg("teacher").ifBlank { target.teacher }
        val newClassroom = args.stringArg("classroom").ifBlank { target.classroom }
        val newDay = args.intArg("day_of_week") ?: target.dayOfWeek
        val newStartSection = (args.intArg("start_section") ?: target.startSection).coerceIn(1, 20)
        val newEndSection = (args.intArg("end_section") ?: target.endSection).coerceIn(newStartSection, 20)
        val newStartWeek = (args.intArg("start_week") ?: target.startWeek).coerceIn(1, 30)
        val newEndWeek = (args.intArg("end_week") ?: target.endWeek).coerceIn(newStartWeek, 30)
        val newWeekType = when (args.stringArg("week_type").uppercase(Locale.ROOT)) {
            "ODD", "1", "单周" -> WeekType.ODD
            "EVEN", "2", "双周" -> WeekType.EVEN
            "EVERY", "0", "每周" -> WeekType.EVERY
            else -> target.weekType
        }
        val newRemark = args.stringArg("remark").ifBlank { target.remark }

        val candidate = target.copy(
            name = newName,
            teacher = newTeacher,
            classroom = newClassroom,
            dayOfWeek = newDay,
            startSection = newStartSection,
            endSection = newEndSection,
            startWeek = newStartWeek,
            endWeek = newEndWeek,
            weekType = newWeekType,
            remark = newRemark,
        )
        CourseValidator.validate(candidate)?.let { msg -> return jsonError(msg) }

        val payload = AssistantWritePayload.EditCourse(
            courseId = target.id,
            timetableId = currentTable.id,
            name = newName,
            teacher = newTeacher,
            classroom = newClassroom,
            dayOfWeek = newDay,
            startSection = newStartSection,
            endSection = newEndSection,
            startWeek = newStartWeek,
            endWeek = newEndWeek,
            weekType = newWeekType,
            remark = newRemark,
        )
        val summary = buildList {
            add("课程：$newName")
            add("时间：${weekdayLabel(newDay)} 第${newStartSection}-${newEndSection}节")
            add("周次：第${newStartWeek}-${newEndWeek}周")
            if (newClassroom.isNotEmpty()) add("地点：$newClassroom")
            if (newTeacher.isNotEmpty()) add("教师：$newTeacher")
        }
        val actionId = UUID.randomUUID().toString()
        pendingActions += AssistantPendingAction(
            id = actionId,
            title = "修改课程",
            summaryLines = summary,
            payload = payload,
            status = AssistantActionStatus.PENDING,
        )
        return buildJsonObject {
            put("status", "pending_confirmation")
            put("actionId", actionId)
            put("message", "已生成修改课程确认卡片，等待用户点击确认")
        }.toString()
    }

    private suspend fun handleProposeDeleteCourse(
        args: JsonObject,
        pendingActions: MutableList<AssistantPendingAction>,
    ): String {
        val currentTable = timetableRepository.observeCurrent().first()
            ?: return jsonError("当前没有可用课表")
        val baseCourses = scheduleData.observeCourses(currentTable.id).first()
            .filter { it.overrideOfId == 0L }
        val courseId = args.intArg("course_id")?.toLong()
        val courseName = args.stringArg("course_name")
        val target = when {
            courseId != null && courseId > 0L -> baseCourses.firstOrNull { it.id == courseId }
            courseName.isNotEmpty() -> baseCourses.firstOrNull { it.name.contains(courseName, ignoreCase = true) }
            else -> null
        } ?: return jsonError("未在当前课表中找到要删除的课程")

        val payload = AssistantWritePayload.DeleteCourse(
            courseId = target.id,
            timetableId = currentTable.id,
            courseName = target.name,
        )
        val summary = buildList {
            add("课程：${target.name}")
            add("时间：${weekdayLabel(target.dayOfWeek)} 第${target.startSection}-${target.endSection}节")
            if (target.classroom.isNotEmpty()) add("地点：${target.classroom}")
        }
        val actionId = UUID.randomUUID().toString()
        pendingActions += AssistantPendingAction(
            id = actionId,
            title = "删除课程",
            summaryLines = summary,
            payload = payload,
            status = AssistantActionStatus.PENDING,
        )
        return buildJsonObject {
            put("status", "pending_confirmation")
            put("actionId", actionId)
            put("message", "已生成删除课程确认卡片，等待用户点击确认")
        }.toString()
    }

    private suspend fun handleProposeEditExam(
        args: JsonObject,
        pendingActions: MutableList<AssistantPendingAction>,
    ): String {
        val exams = examRepository.observeAll().first()
        val examId = args.intArg("exam_id")?.toLong()
        val subjectQuery = args.stringArg("subject")
        val target = when {
            examId != null && examId > 0L -> exams.firstOrNull { it.id == examId }
            subjectQuery.isNotEmpty() -> exams.firstOrNull { it.subject.contains(subjectQuery, ignoreCase = true) }
            else -> null
        } ?: return jsonError("未找到要修改的考试记录")

        val newSubject = args.stringArg("new_subject").ifBlank { target.subject }
        val newDate = args.stringArg("date").ifBlank { target.date }
        val newStartTime = args.stringArg("start_time").ifBlank { target.startTime }
        val newEndTime = args.stringArg("end_time").ifBlank { target.endTime }
        val newLocation = args.stringArg("location").ifBlank { target.location }
        val newSeat = args.stringArg("seat").ifBlank { target.seat }
        val newNote = args.stringArg("note").ifBlank { target.note }

        val candidate = target.copy(
            subject = newSubject,
            date = newDate,
            startTime = newStartTime,
            endTime = newEndTime,
            location = newLocation,
            seat = newSeat,
            note = newNote,
        )
        ExamValidator.validate(candidate)?.let { msg -> return jsonError(msg) }

        val payload = AssistantWritePayload.EditExam(
            examId = target.id,
            subject = newSubject,
            date = newDate,
            startTime = newStartTime,
            endTime = newEndTime,
            location = newLocation,
            seat = newSeat,
            note = newNote,
        )
        val summary = buildList {
            add("科目：$newSubject")
            val timePart = if (newStartTime.isNotEmpty() && newEndTime.isNotEmpty()) " $newStartTime-$newEndTime" else ""
            add("时间：$newDate$timePart")
            if (newLocation.isNotEmpty()) add("考场：$newLocation")
            if (newSeat.isNotEmpty()) add("座位：$newSeat")
        }
        val actionId = UUID.randomUUID().toString()
        pendingActions += AssistantPendingAction(
            id = actionId,
            title = "修改考试",
            summaryLines = summary,
            payload = payload,
            status = AssistantActionStatus.PENDING,
        )
        return buildJsonObject {
            put("status", "pending_confirmation")
            put("actionId", actionId)
            put("message", "已生成修改考试确认卡片，等待用户点击确认")
        }.toString()
    }

    private suspend fun handleProposeDeleteExam(
        args: JsonObject,
        pendingActions: MutableList<AssistantPendingAction>,
    ): String {
        val exams = examRepository.observeAll().first()
        val examId = args.intArg("exam_id")?.toLong()
        val subjectQuery = args.stringArg("subject")
        val target = when {
            examId != null && examId > 0L -> exams.firstOrNull { it.id == examId }
            subjectQuery.isNotEmpty() -> exams.firstOrNull { it.subject.contains(subjectQuery, ignoreCase = true) }
            else -> null
        } ?: return jsonError("未找到要删除的考试记录")

        val payload = AssistantWritePayload.DeleteExam(
            examId = target.id,
            subject = target.subject,
        )
        val summary = buildList {
            add("科目：${target.subject}")
            add("日期：${target.date}")
            if (target.location.isNotEmpty()) add("考场：${target.location}")
        }
        val actionId = UUID.randomUUID().toString()
        pendingActions += AssistantPendingAction(
            id = actionId,
            title = "删除考试",
            summaryLines = summary,
            payload = payload,
            status = AssistantActionStatus.PENDING,
        )
        return buildJsonObject {
            put("status", "pending_confirmation")
            put("actionId", actionId)
            put("message", "已生成删除考试确认卡片，等待用户点击确认")
        }.toString()
    }

    private suspend fun resolveGradeConnection(): SchoolConnection? {
        val active = schoolAuth.observeActiveConnection().first()
        if (active != null && active.status != SchoolConnectionStatus.LOGGED_OUT) return active
        return schoolAuth.observeConnections().first()
            .firstOrNull { it.status != SchoolConnectionStatus.LOGGED_OUT }
    }

    private data class LoadedGrades(
        val records: List<GradeRecord>,
        val availableSemesters: List<String>,
    )

    private suspend fun loadCachedGrades(accountKey: String, semesterFilter: String): LoadedGrades {
        val semesters = gradeRepository.observeSemesters(accountKey).first()
            .filter { it.isNotBlank() && it != GradeRepositoryPort.ALL_SEMESTERS }
        if (semesterFilter.isNotEmpty()) {
            val direct = gradeRepository.observeRecords(accountKey, semesterFilter).first()
            if (direct.isNotEmpty()) {
                return LoadedGrades(direct, semesters)
            }
            val fromAll = gradeRepository.observeRecords(accountKey, GradeRepositoryPort.ALL_SEMESTERS).first()
                .filter { it.semester == semesterFilter }
            return LoadedGrades(fromAll, semesters)
        }
        val allPartition = gradeRepository.observeRecords(accountKey, GradeRepositoryPort.ALL_SEMESTERS).first()
        if (allPartition.isNotEmpty()) {
            return LoadedGrades(allPartition, semesters)
        }
        val merged = LinkedHashMap<String, GradeRecord>()
        for (term in semesters) {
            gradeRepository.observeRecords(accountKey, term).first().forEach { rec ->
                merged.putIfAbsent(rec.stableKey, rec)
            }
        }
        return LoadedGrades(merged.values.toList(), semesters)
    }

    private suspend fun resolveCurrentTeachingWeek(): Int? {
        val currentTable = timetableRepository.observeCurrent().first() ?: return null
        val semester = scheduleData.observeSemester(currentTable.id).first() ?: return null
        val week = CourseRules.weekOfDate(semester, LocalDate.now(clock))
        return week.takeIf { it > 0 }
    }

    private companion object {
        private const val MAX_TOOL_ROUNDS = 6
        private val ARGS_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

        private val TOOL_DEFINITIONS: List<AiToolDefinition> = listOf(
            AiToolDefinition(
                name = "get_schedule",
                description = "查询指定日期范围的真实生效课表（含临时调课与整天替换）。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "start_date": {"type": "string", "description": "起始日期 yyyy-MM-dd，默认今天"},
                        "days": {"type": "integer", "description": "查询天数 1..14，默认 1"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "search_courses",
                description = "按课程名、教师或教室关键词检索当前课表的全部课程定义。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "query": {"type": "string", "description": "搜索关键词，留空返回全部课程"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "get_grades_summary",
                description = "读取本机已缓存成绩，返回加权均分、平均绩点、总学分与各科明细。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "semester": {"type": "string", "description": "学期过滤（可选），留空表示全部学期"},
                        "include_optional": {"type": "boolean", "description": "是否纳入公选课，默认 false"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "simulate_gpa",
                description = "结合已缓存成绩精确模拟新增假想成绩后的 GPA，并计算达到目标 GPA 所需的剩余课程均绩。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "hypothetical_courses": {
                          "type": "array",
                          "description": "假想未出分或待修课程列表",
                          "items": {
                            "type": "object",
                            "properties": {
                              "name": {"type": "string"},
                              "credit": {"type": "number"},
                              "score": {"type": "number"},
                              "grade_point": {"type": "number"}
                            },
                            "required": ["credit"]
                          }
                        },
                        "target_gpa": {"type": "number", "description": "目标总绩点（可选）"},
                        "remaining_credits": {"type": "number", "description": "剩余学分数（可选）"},
                        "semester": {"type": "string"},
                        "include_optional": {"type": "boolean"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "get_credit_progress",
                description = "查询本机已修学分进度、总目标/剩余学分及各分类（如通识选修、专业必修等）学分完成情况。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "category": {"type": "string", "description": "可选分类名称关键词，留空返回全部分类"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "get_upcoming_exams",
                description = "查询近期考试安排、考场、座位号和倒计时天数。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "days_ahead": {"type": "integer", "description": "向后查看天数，默认 60"},
                        "include_past": {"type": "boolean", "description": "是否包含已结束考试，默认 false"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "find_free_classrooms",
                description = "实时查询学校空闲教室（需要已登录教务账号）。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "building_keyword": {"type": "string", "description": "教学楼名称关键词，如 启智楼"},
                        "week": {"type": "integer", "description": "教学周"},
                        "day_of_week": {"type": "integer", "description": "星期 1..7"},
                        "min_consecutive": {"type": "integer", "description": "最少连续空闲节次组数 1..4"},
                        "available_now_only": {"type": "boolean", "description": "仅看当前/下一节空闲"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "propose_add_course",
                description = "生成一条待用户确认的新增课程卡片（不会直接写入，需用户确认）。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "name": {"type": "string"},
                        "teacher": {"type": "string"},
                        "classroom": {"type": "string"},
                        "day_of_week": {"type": "integer", "description": "1=周一..7=周日"},
                        "start_section": {"type": "integer"},
                        "end_section": {"type": "integer"},
                        "start_week": {"type": "integer"},
                        "end_week": {"type": "integer"},
                        "week_type": {"type": "string", "description": "EVERY / ODD / EVEN"},
                        "date": {"type": "string", "description": "单次日期课程填写 yyyy-MM-dd"},
                        "start_time": {"type": "string", "description": "HH:mm"},
                        "end_time": {"type": "string", "description": "HH:mm"},
                        "remark": {"type": "string"}
                      },
                      "required": ["name"]
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "propose_adjust_course",
                description = "生成一条单次调课或单次停课确认卡片（不会直接写入，需用户确认）。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "course_name": {"type": "string"},
                        "source_date": {"type": "string", "description": "原上课日期 yyyy-MM-dd"},
                        "action_type": {"type": "string", "description": "MOVE 或 CANCEL"},
                        "target_date": {"type": "string", "description": "目标日期 yyyy-MM-dd（MOVE时填写）"},
                        "target_start_section": {"type": "integer"},
                        "target_end_section": {"type": "integer"},
                        "target_classroom": {"type": "string"},
                        "note": {"type": "string"}
                      },
                      "required": ["course_name", "source_date", "action_type"]
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "propose_add_exam",
                description = "生成一条新增考试确认卡片（不会直接写入，需用户确认）。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "subject": {"type": "string"},
                        "date": {"type": "string", "description": "yyyy-MM-dd"},
                        "start_time": {"type": "string", "description": "HH:mm"},
                        "end_time": {"type": "string", "description": "HH:mm"},
                        "location": {"type": "string"},
                        "seat": {"type": "string"},
                        "note": {"type": "string"}
                      },
                      "required": ["subject", "date"]
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "propose_edit_course",
                description = "生成一条修改已有课程定义的确认卡片（需先用 search_courses 查到课程，不会直接写入）。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "course_id": {"type": "integer"},
                        "course_name": {"type": "string"},
                        "new_name": {"type": "string"},
                        "teacher": {"type": "string"},
                        "classroom": {"type": "string"},
                        "day_of_week": {"type": "integer"},
                        "start_section": {"type": "integer"},
                        "end_section": {"type": "integer"},
                        "start_week": {"type": "integer"},
                        "end_week": {"type": "integer"},
                        "week_type": {"type": "string"},
                        "remark": {"type": "string"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "propose_delete_course",
                description = "生成一条删除已有课程的确认卡片（需用户确认）。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "course_id": {"type": "integer"},
                        "course_name": {"type": "string"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "propose_edit_exam",
                description = "生成一条修改已有考试安排的确认卡片（需先用 get_upcoming_exams 获取考试 id 或科目）。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "exam_id": {"type": "integer"},
                        "subject": {"type": "string"},
                        "new_subject": {"type": "string"},
                        "date": {"type": "string"},
                        "start_time": {"type": "string"},
                        "end_time": {"type": "string"},
                        "location": {"type": "string"},
                        "seat": {"type": "string"},
                        "note": {"type": "string"}
                      }
                    }
                """.trimIndent(),
            ),
            AiToolDefinition(
                name = "propose_delete_exam",
                description = "生成一条删除已有考试的确认卡片（需用户确认）。",
                parametersSchemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "exam_id": {"type": "integer"},
                        "subject": {"type": "string"}
                      }
                    }
                """.trimIndent(),
            ),
        )

        private fun toolProgressLabel(name: String): String = when (name) {
            "get_schedule" -> "已查课表"
            "search_courses" -> "已检索课程"
            "get_grades_summary" -> "已查成绩"
            "simulate_gpa" -> "已计算 GPA"
            "get_credit_progress" -> "已查学分进度"
            "get_upcoming_exams" -> "已查考试"
            "find_free_classrooms" -> "已查空教室"
            "propose_add_course" -> "待确认加课"
            "propose_edit_course" -> "待确认改课"
            "propose_delete_course" -> "待确认删课"
            "propose_adjust_course" -> "待确认调课"
            "propose_add_exam" -> "待确认加考试"
            "propose_edit_exam" -> "待确认改考试"
            "propose_delete_exam" -> "待确认删考试"
            else -> name
        }

        private fun parseArgsObject(raw: String): JsonObject {
            if (raw.isBlank()) return JsonObject(emptyMap())
            return runCatching { ARGS_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
        }

        private fun JsonObject.stringArg(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

        private fun JsonObject.intArg(key: String): Int? =
            (this[key] as? JsonPrimitive)?.let { prim ->
                prim.intOrNull ?: if (prim.isString) prim.content.trim().toIntOrNull() else null
            }

        private fun JsonObject.doubleArg(key: String): Double? =
            (this[key] as? JsonPrimitive)?.let { prim ->
                prim.doubleOrNull ?: if (prim.isString) prim.content.trim().toDoubleOrNull() else null
            }

        private fun JsonObject.boolArg(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.let { prim ->
                prim.booleanOrNull ?: if (prim.isString) prim.content.trim().toBooleanStrictOrNull() else null
            }

        private fun jsonError(message: String): String = buildJsonObject {
            put("error", message)
        }.toString()

        private fun scoreToDefaultGradePoint(score: Double): Double =
            if (score < 60.0) 0.0 else ((score - 50.0) / 10.0).coerceIn(0.0, 5.0)

        private fun formatMinute(totalMinutes: Int): String {
            val h = (totalMinutes / 60).coerceIn(0, 23)
            val m = (totalMinutes % 60).coerceIn(0, 59)
            return String.format(Locale.ROOT, "%02d:%02d", h, m)
        }

        private fun weekdayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7 -> "周日"
            else -> "周$dayOfWeek"
        }

        private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

        private fun stripForbiddenBullets(text: String): String =
            text.replace('·', ' ').replace("• ", "").replace('•', ' ').trim()
    }
}
