package com.gfs.iyango.feature.main.importexport

import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiImportParseResult
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiProviderPreset
import com.gfs.iyango.core.domain.AiScheduleImportPort
import com.gfs.iyango.core.domain.ImportWriteMode
import com.gfs.iyango.core.domain.ScheduleExportResult
import com.gfs.iyango.core.domain.ScheduleImportCommitResult
import com.gfs.iyango.core.domain.ScheduleImportItem
import com.gfs.iyango.core.domain.ScheduleImportOptions
import com.gfs.iyango.core.domain.ScheduleImportPreview
import com.gfs.iyango.core.domain.ScheduleImportReadResult
import com.gfs.iyango.core.domain.ScheduleImportTargetInfo
import com.gfs.iyango.core.domain.ScheduleTransferPort
import com.gfs.iyango.core.domain.TimetableRepositoryPort
import com.gfs.iyango.core.model.Timetable
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiImportViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private inline fun <reified T> port(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    @Test
    fun `parses schedule text via AI port, builds preview, and commits via ScheduleTransferPort`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        var savedConfig = AiProviderConfig(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            apiKey = "sk-stored",
        )
        var capturedCommitOptions: ScheduleImportOptions? = null

        val store = object : AiConfigStorePort {
            override suspend fun load(): AiProviderConfig = savedConfig
            override suspend fun save(config: AiProviderConfig) { savedConfig = config }
            override suspend fun clearApiKey() { savedConfig = savedConfig.copy(apiKey = "") }
        }
        val aiPort = object : AiScheduleImportPort {
            override suspend fun parseSchedule(
                config: AiProviderConfig,
                textInput: String,
                images: List<AiImagePayload>,
                referenceDateIso: String,
            ): AiImportParseResult = AiImportParseResult.Ready(
                scheduleJsonBytes = """{"courses":[{"name":"编译原理"}]}""".toByteArray(),
                summary = "共识别 1 条课程",
                warnings = listOf("部分课程未识别出周次，已默认填 1-16 周，请在预览核对"),
            )

            override suspend fun testConnection(config: AiProviderConfig): Result<String> =
                Result.success("连接成功")

            override suspend fun fetchModels(config: AiProviderConfig): Result<List<AiModelInfo>> =
                Result.success(
                    listOf(
                        AiModelInfo(id = "Qwen/Qwen2.5-VL-72B-Instruct", ownedBy = "siliconflow"),
                        AiModelInfo(id = "deepseek-ai/DeepSeek-V3", ownedBy = "siliconflow"),
                    ),
                )
        }
        val transfer = object : ScheduleTransferPort {
            override suspend fun export(timetableId: Long): ScheduleExportResult = error("unused")
            override suspend fun read(bytes: ByteArray): ScheduleImportReadResult = ScheduleImportReadResult.Ready(
                ScheduleImportPreview(
                    courseCount = 1,
                    issueCount = 0,
                    issues = emptyList(),
                    semesterName = "2026-2027-1",
                    timeSlotCount = 0,
                    fileDigest = "digest-ai-1",
                    duplicateExisting = false,
                    token = "digest-ai-1",
                    items = listOf(
                        ScheduleImportItem(
                            rowKey = "r0000-aa",
                            fileId = 0,
                            name = "编译原理",
                            teacher = "李老师",
                            classroom = "信息楼 301",
                            dayOfWeek = 1,
                            startSection = 1,
                            endSection = 2,
                            startWeek = 1,
                            endWeek = 16,
                            weekType = 0,
                            isAdjustment = false,
                        ),
                    ),
                ),
            )

            override suspend fun inspectTarget(timetableId: Long): ScheduleImportTargetInfo? = null
            override suspend fun commit(
                token: String,
                options: ScheduleImportOptions,
            ): ScheduleImportCommitResult {
                capturedCommitOptions = options
                return ScheduleImportCommitResult(
                    timetableId = 10L,
                    replaced = false,
                    appended = false,
                    courseCount = 1,
                    issues = emptyList(),
                )
            }
        }
        val timetableRepo = port<TimetableRepositoryPort> { name, _ ->
            when (name) {
                "observeAll" -> flowOf(listOf(Timetable(id = 1, name = "本地课表", source = "LOCAL")))
                else -> error(name)
            }
        }

        val vm = AiImportViewModel(
            aiConfigStore = store,
            aiImport = aiPort,
            transfer = transfer,
            timetableRepository = timetableRepo,
            clock = Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC),
        )
        advanceUntilIdle()

        assertTrue(vm.configConfigured.value)
        assertFalse(vm.configExpanded.value)
        assertTrue(vm.modelsFetched.value)

        val siliconPreset = AiProviderPreset.BuiltIns.first { it.id == "siliconflow" }
        vm.applyPreset(siliconPreset)
        assertEquals(siliconPreset.baseUrl, vm.baseUrl.value)
        assertEquals(siliconPreset.defaultModel, vm.model.value)
        assertEquals("sk-stored", vm.apiKey.value)
        assertFalse(vm.modelsFetched.value)

        vm.fetchAvailableModels()
        advanceUntilIdle()
        assertTrue(vm.modelsFetched.value)
        assertEquals(2, vm.availableModels.value.size)
        assertTrue(vm.showModelPicker.value)
        vm.selectModel("deepseek-ai/DeepSeek-V3")
        assertEquals("deepseek-ai/DeepSeek-V3", vm.model.value)
        assertFalse(vm.showModelPicker.value)

        vm.textInput.value = "周一 1-2节 编译原理 信息楼301"
        vm.startParse()
        advanceUntilIdle()

        assertNotNull(vm.preview.value)
        assertEquals(setOf("r0000-aa"), vm.selectedKeys.value)
        assertEquals(1, vm.aiWarnings.value.size)

        vm.commit()
        advanceUntilIdle()

        assertNull(vm.preview.value)
        assertEquals(ImportWriteMode.CREATE, capturedCommitOptions?.mode)
        assertTrue(vm.doneMessage.value?.contains("1 条课程") == true)
    }

    @Test
    fun `single-date adjustment items default write mode to APPEND on first local timetable`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val store = object : AiConfigStorePort {
            override suspend fun load() = AiProviderConfig(
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini",
                apiKey = "sk-stored",
            )
            override suspend fun save(config: AiProviderConfig) = Unit
            override suspend fun clearApiKey() = Unit
        }
        val aiPort = object : AiScheduleImportPort {
            override suspend fun parseSchedule(
                config: AiProviderConfig,
                textInput: String,
                images: List<AiImagePayload>,
                referenceDateIso: String,
            ) = AiImportParseResult.Ready(
                scheduleJsonBytes = """{"courses":[]}""".toByteArray(),
                summary = "1 条调课",
                warnings = emptyList(),
            )

            override suspend fun testConnection(config: AiProviderConfig) = Result.success("ok")
            override suspend fun fetchModels(config: AiProviderConfig) = Result.success(emptyList<AiModelInfo>())
        }
        val transfer = object : ScheduleTransferPort {
            override suspend fun export(timetableId: Long): ScheduleExportResult = error("unused")
            override suspend fun read(bytes: ByteArray) = ScheduleImportReadResult.Ready(
                ScheduleImportPreview(
                    courseCount = 1,
                    issueCount = 0,
                    issues = emptyList(),
                    semesterName = "2026-2027-1",
                    timeSlotCount = 10,
                    fileDigest = "digest-adjust-1",
                    duplicateExisting = false,
                    token = "digest-adjust-1",
                    items = listOf(
                        ScheduleImportItem(
                            rowKey = "r0000-adj",
                            fileId = 0,
                            name = "高等数学",
                            teacher = "",
                            classroom = "信息楼 B202",
                            dayOfWeek = 5,
                            startSection = 5,
                            endSection = 6,
                            startWeek = 12,
                            endWeek = 12,
                            weekType = 0,
                            isAdjustment = true,
                            timeMode = 2,
                            date = "2026-11-27",
                        ),
                    ),
                ),
            )

            override suspend fun inspectTarget(timetableId: Long) = ScheduleImportTargetInfo(
                timetableId = timetableId,
                name = "本地课表",
                isLocal = true,
                courseCount = 12,
                timeSlotCount = 10,
                semesterName = "2026-2027-1",
            )

            override suspend fun commit(token: String, options: ScheduleImportOptions): ScheduleImportCommitResult =
                error("unused")
        }
        val timetableRepo = port<TimetableRepositoryPort> { name, _ ->
            when (name) {
                "observeAll" -> flowOf(
                    listOf(
                        Timetable(id = 9, name = "学校课表", source = "SCHOOL"),
                        Timetable(id = 1, name = "本地课表", source = "LOCAL"),
                    ),
                )
                else -> error(name)
            }
        }

        val vm = AiImportViewModel(
            aiConfigStore = store,
            aiImport = aiPort,
            transfer = transfer,
            timetableRepository = timetableRepo,
            clock = Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC),
        )
        advanceUntilIdle()

        vm.textInput.value = "本周五下午 14:00 信息楼 B202 补高数"
        vm.startParse()
        advanceUntilIdle()

        assertEquals(ImportWriteMode.APPEND, vm.mode.value)
        assertEquals(1L, vm.targetId.value)
        assertFalse(vm.applySemester.value)
        assertFalse(vm.applyTimeSlots.value)
    }

    @Test
    fun `failed fetchModels still unlocks modelsFetched for manual model entry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val store = object : AiConfigStorePort {
            override suspend fun load() = AiProviderConfig(
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini",
                apiKey = "",
            )
            override suspend fun save(config: AiProviderConfig) = Unit
            override suspend fun clearApiKey() = Unit
        }
        val aiPort = object : AiScheduleImportPort {
            override suspend fun parseSchedule(
                config: AiProviderConfig,
                textInput: String,
                images: List<AiImagePayload>,
                referenceDateIso: String,
            ) = error("unused")

            override suspend fun testConnection(config: AiProviderConfig) = Result.success("ok")
            override suspend fun fetchModels(config: AiProviderConfig): Result<List<AiModelInfo>> =
                Result.failure(IllegalStateException("404 /models not supported"))
        }
        val transfer = object : ScheduleTransferPort {
            override suspend fun export(timetableId: Long): ScheduleExportResult = error("unused")
            override suspend fun read(bytes: ByteArray): ScheduleImportReadResult = error("unused")
            override suspend fun inspectTarget(timetableId: Long): ScheduleImportTargetInfo? = null
            override suspend fun commit(token: String, options: ScheduleImportOptions): ScheduleImportCommitResult =
                error("unused")
        }
        val timetableRepo = port<TimetableRepositoryPort> { name, _ ->
            when (name) {
                "observeAll" -> flowOf(emptyList<Timetable>())
                else -> error(name)
            }
        }

        val vm = AiImportViewModel(
            aiConfigStore = store,
            aiImport = aiPort,
            transfer = transfer,
            timetableRepository = timetableRepo,
            clock = Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC),
        )
        advanceUntilIdle()
        assertFalse(vm.modelsFetched.value)

        vm.apiKey.value = "sk-custom"
        vm.fetchAvailableModels()
        advanceUntilIdle()

        assertTrue(vm.modelsFetched.value)
        assertEquals("404 /models not supported", vm.connectionMessage.value)
    }
}
