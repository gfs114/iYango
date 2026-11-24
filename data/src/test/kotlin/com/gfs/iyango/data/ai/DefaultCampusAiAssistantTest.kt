package com.gfs.iyango.data.ai

import com.gfs.iyango.core.domain.AdjustmentRepositoryPort
import com.gfs.iyango.core.domain.AiChatMessage
import com.gfs.iyango.core.domain.AiChatPort
import com.gfs.iyango.core.domain.AiChatTurnResult
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiToolCall
import com.gfs.iyango.core.domain.AiToolDefinition
import com.gfs.iyango.core.domain.AssistantActionStatus
import com.gfs.iyango.core.domain.ClassroomRepositoryPort
import com.gfs.iyango.core.domain.CourseOccurrence
import com.gfs.iyango.core.domain.EffectiveScheduleDay
import com.gfs.iyango.core.domain.EffectiveSchedulePort
import com.gfs.iyango.core.domain.ExamRepositoryPort
import com.gfs.iyango.core.domain.GradeRepositoryPort
import com.gfs.iyango.core.domain.ReminderCoordinatorPort
import com.gfs.iyango.core.domain.ReminderStatus
import com.gfs.iyango.core.domain.ScheduleDataPort
import com.gfs.iyango.core.domain.SchoolAuthPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.AdjustmentType
import com.gfs.iyango.core.model.Course
import com.gfs.iyango.core.model.Exam
import com.gfs.iyango.core.model.GradeRecord
import com.gfs.iyango.core.model.OccurrenceAdjustment
import com.gfs.iyango.core.model.SchoolConnection
import com.gfs.iyango.core.model.SchoolConnectionStatus
import com.gfs.iyango.core.model.Semester
import com.gfs.iyango.core.model.Timetable
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCampusAiAssistantTest {

    private val clock = Clock.fixed(Instant.parse("2026-11-24T04:00:00Z"), ZoneOffset.UTC)
    private val timetable = Timetable(id = 1L, name = "秋季课表", isCurrent = true)
    private val semester = Semester(
        id = 1L,
        timetableId = 1L,
        name = "2026-2027-1",
        startDate = LocalDate.of(2026, 9, 7),
        totalWeeks = 20,
        isCurrent = true,
    )
    private val existingCourse = Course(
        id = 10L,
        timetableId = 1L,
        name = "高等数学",
        teacher = "张老师",
        classroom = "明理楼101",
        dayOfWeek = 2,
        startSection = 1,
        endSection = 2,
        startWeek = 1,
        endWeek = 16,
    )

    private inline fun <reified T> proxy(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as T

    @Test
    fun `sendMessage runs read tools and GPA simulation cleanly`() = runTest {
        val capturedToolResults = mutableListOf<String>()
        var round = 0
        val aiChat = object : AiChatPort {
            override suspend fun completeTurn(
                config: AiProviderConfig,
                systemPrompt: String,
                messages: List<AiChatMessage>,
                tools: List<AiToolDefinition>,
            ): AiChatTurnResult {
                round++
                return when (round) {
                    1 -> AiChatTurnResult.Reply(
                        text = "",
                        toolCalls = listOf(
                            AiToolCall("c1", "get_schedule", """{"start_date":"2026-11-24","days":1}"""),
                            AiToolCall(
                                "c2",
                                "simulate_gpa",
                                """{"hypothetical_courses":[{"name":"编译原理","credit":3,"score":90}],"target_gpa":3.8,"remaining_credits":10}""",
                            ),
                        ),
                    )
                    else -> {
                        capturedToolResults += messages.filter { it.toolCallId.isNotEmpty() }.map { it.content }
                        AiChatTurnResult.Reply("今日有高等数学，模拟后 GPA 为 3.55。")
                    }
                }
            }
        }

        val assistant = createAssistant(
            aiChat = aiChat,
            grades = listOf(
                GradeRecord(
                    accountKey = "2024001",
                    semester = "2025-2026-2",
                    courseName = "大学英语",
                    credit = 3.0,
                    scoreText = "82",
                    scoreValue = 82.0,
                    gradePoint = 3.2,
                ),
            ),
        )

        val output = assistant.sendMessage(
            config = AiProviderConfig("https://api.openai.com/v1", "gpt-4o-mini", "sk-test"),
            history = emptyList(),
            userText = "帮我查今天课表顺便模拟编译原理考90分后的GPA",
        )

        assertEquals(2, output.toolTraces.size)
        assertTrue(output.toolTraces.all { it.success })
        assertTrue(capturedToolResults[0].contains("高等数学"))
        assertTrue(capturedToolResults[1].contains("3.6"))
        assertEquals("今日有高等数学，模拟后 GPA 为 3.55。", output.replyText)
        assertTrue(output.pendingActions.isEmpty())
    }

    @Test
    fun `write tools require explicit confirmation and only mutate repositories on executePendingAction`() = runTest {
        val savedCourses = mutableListOf<Course>()
        val savedAdjustments = mutableListOf<OccurrenceAdjustment>()
        val savedExams = mutableListOf<Exam>()
        var refreshCount = 0

        var callStep = 0
        val aiChat = object : AiChatPort {
            override suspend fun completeTurn(
                config: AiProviderConfig,
                systemPrompt: String,
                messages: List<AiChatMessage>,
                tools: List<AiToolDefinition>,
            ): AiChatTurnResult {
                callStep++
                return if (callStep == 1) {
                    AiChatTurnResult.Reply(
                        text = "",
                        toolCalls = listOf(
                            AiToolCall(
                                "w1",
                                "propose_add_course",
                                """{"name":"软件工程讲座","day_of_week":5,"start_section":7,"end_section":8,"start_week":13,"end_week":13,"classroom":"礼堂"}""",
                            ),
                            AiToolCall(
                                "w2",
                                "propose_adjust_course",
                                """{"course_name":"高等数学","source_date":"2026-11-24","action_type":"MOVE","target_date":"2026-11-26","target_start_section":3,"target_end_section":4,"target_classroom":"明理楼202"}""",
                            ),
                            AiToolCall(
                                "w3",
                                "propose_add_exam",
                                """{"subject":"离散数学期末","date":"2027-01-10","start_time":"09:00","end_time":"11:00","location":"知行楼301"}""",
                            ),
                        ),
                    )
                } else {
                    AiChatTurnResult.Reply("已为你生成 3 张待确认卡片，请点击确认执行。")
                }
            }
        }

        val assistant = createAssistant(
            aiChat = aiChat,
            onSaveCourse = { savedCourses += it; 99L },
            onSaveAdjustment = { savedAdjustments += it; 88L },
            onSaveExam = { savedExams += it; 77L },
            onRefreshReminders = { refreshCount++ },
        )

        val output = assistant.sendMessage(
            config = AiProviderConfig("https://api.openai.com/v1", "gpt-4o-mini", "sk-test"),
            history = emptyList(),
            userText = "帮我加个讲座、调一下今天高数到周四3-4节，再加离散数学考试",
        )

        // MUST NOT mutate DB before explicit user confirmation.
        assertTrue(savedCourses.isEmpty())
        assertTrue(savedAdjustments.isEmpty())
        assertTrue(savedExams.isEmpty())
        assertEquals(0, refreshCount)
        assertEquals(3, output.pendingActions.size)
        assertTrue(output.pendingActions.all { it.status == AssistantActionStatus.PENDING })
        assertFalse(output.replyText.contains('·'))

        output.pendingActions.forEach { action ->
            val result = assistant.executePendingAction(action)
            assertTrue(result.isSuccess)
        }
        assertEquals(1, savedCourses.size)
        assertEquals("软件工程讲座", savedCourses.single().name)
        assertEquals(1, savedAdjustments.size)
        assertEquals(AdjustmentType.MOVE, savedAdjustments.single().type)
        assertEquals("2026-11-26", savedAdjustments.single().targetDate)
        assertEquals(1, savedExams.size)
        assertEquals("离散数学期末", savedExams.single().subject)
        assertEquals(2, refreshCount)
    }

    private fun createAssistant(
        aiChat: AiChatPort,
        grades: List<GradeRecord> = emptyList(),
        onSaveCourse: (Course) -> Long = { 1L },
        onSaveAdjustment: (OccurrenceAdjustment) -> Long = { 1L },
        onSaveExam: (Exam) -> Long = { 1L },
        onRefreshReminders: () -> Unit = {},
    ): DefaultCampusAiAssistant {
        val sampleDay = EffectiveScheduleDay(
            date = LocalDate.of(2026, 11, 24),
            timetable = timetable,
            semester = semester,
            occurrences = listOf(
                CourseOccurrence(
                    occurrenceId = "occ-1",
                    course = existingCourse,
                    date = LocalDate.of(2026, 11, 24),
                    startMinute = 480,
                    endMinute = 575,
                    isOverride = false,
                ),
            ),
        )
        val effectiveSchedule = proxy<EffectiveSchedulePort> { name, _ ->
            when (name) {
                "resolveSelectedRange" -> listOf(sampleDay)
                "resolveDay" -> sampleDay
                else -> error("Unexpected effectiveSchedule call: $name")
            }
        }
        val timetableRepo = proxy<TimetableRepositoryPort> { name, _ ->
            when (name) {
                "observeCurrent" -> flowOf(timetable)
                else -> error("Unexpected timetableRepo call: $name")
            }
        }
        val scheduleData = proxy<ScheduleDataPort> { name, args ->
            when (name) {
                "observeSemester" -> flowOf(semester)
                "observeCourses" -> flowOf(listOf(existingCourse))
                "saveCourse" -> onSaveCourse(args[0] as Course)
                else -> error("Unexpected scheduleData call: $name")
            }
        }
        val adjustmentRepo = proxy<AdjustmentRepositoryPort> { name, args ->
            when (name) {
                "insert" -> onSaveAdjustment(args[0] as OccurrenceAdjustment)
                else -> error("Unexpected adjustmentRepo call: $name")
            }
        }
        val conn = SchoolConnection(
            id = "conn-1",
            accountSubject = "2024001",
            status = SchoolConnectionStatus.ACTIVE,
        )
        val schoolAuth = proxy<SchoolAuthPort> { name, _ ->
            when (name) {
                "observeActiveConnection" -> flowOf(conn)
                "observeConnections" -> flowOf(listOf(conn))
                else -> error("Unexpected schoolAuth call: $name")
            }
        }
        val gradeRepo = proxy<GradeRepositoryPort> { name, args ->
            when (name) {
                "observeSemesters" -> flowOf(listOf("*", "2025-2026-2"))
                "observeRecords" -> {
                    val sem = args[1] as String
                    if (sem == GradeRepositoryPort.ALL_SEMESTERS || sem == "2025-2026-2") {
                        flowOf(grades)
                    } else {
                        flowOf(emptyList())
                    }
                }
                else -> error("Unexpected gradeRepo call: $name")
            }
        }
        val examRepo = proxy<ExamRepositoryPort> { name, args ->
            when (name) {
                "observeAll" -> flowOf(emptyList<Exam>())
                "save" -> onSaveExam(args[0] as Exam)
                else -> error("Unexpected examRepo call: $name")
            }
        }
        val classroomRepo = proxy<ClassroomRepositoryPort> { name, _ ->
            error("Unexpected classroomRepo call: $name")
        }
        val reminderCoordinator = object : ReminderCoordinatorPort {
            override val status = MutableStateFlow(ReminderStatus())
            override suspend fun refresh() { onRefreshReminders() }
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun setLeadMinutes(minutes: Int) = Unit
            override suspend fun setCourseEnabled(key: String, enabled: Boolean) = Unit
        }
        return DefaultCampusAiAssistant(
            aiChat = aiChat,
            effectiveSchedule = effectiveSchedule,
            timetableRepository = timetableRepo,
            scheduleData = scheduleData,
            adjustmentRepository = adjustmentRepo,
            schoolAuth = schoolAuth,
            gradeRepository = gradeRepo,
            examRepository = examRepo,
            classroomRepository = classroomRepo,
            reminderCoordinator = reminderCoordinator,
            clock = clock,
        )
    }
}
