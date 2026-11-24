package com.gfs.iyango.data.ai

import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiImportParseResult
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.DiagnosticExport
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.DiagnosticLogSummary
import com.gfs.iyango.core.model.DiagnosticEvent
import com.gfs.iyango.data.json.ScheduleJsonParser
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiScheduleResponseNormalizerTest {

    private class FakeDiagnostics : DiagnosticLogPort {
        val events = mutableListOf<DiagnosticEvent>()
        override fun observeRecent(limit: Int): Flow<List<DiagnosticEvent>> = flowOf(events)
        override val summary: Flow<DiagnosticLogSummary> = flowOf(DiagnosticLogSummary())
        override suspend fun record(event: DiagnosticEvent) { events += event }
        override suspend fun export(): DiagnosticExport = DiagnosticExport("diag.txt", "")
        override suspend fun clear() { events.clear() }
    }

    @Test
    fun `resolveChatCompletionsUrl enforces HTTPS except local hosts and appends endpoint`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            resolveChatCompletionsUrl("https://api.openai.com/v1/").getOrThrow(),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            resolveChatCompletionsUrl("https://api.deepseek.com/v1/chat/completions").getOrThrow(),
        )
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            resolveChatCompletionsUrl("http://127.0.0.1:11434/v1").getOrThrow(),
        )
        assertEquals(
            "http://192.168.1.12:8000/v1/chat/completions",
            resolveChatCompletionsUrl("http://192.168.1.12:8000/v1").getOrThrow(),
        )
        assertTrue(resolveChatCompletionsUrl("http://example.com/v1").isFailure)
        assertTrue(resolveChatCompletionsUrl("ftp://api.openai.com/v1").isFailure)
        assertTrue(resolveChatCompletionsUrl("  ").isFailure)
    }

    @Test
    fun `normalizeModelOutputToScheduleJson strips think tags and markdown fences and parses via ScheduleJsonParser`() {
        val raw = """
            <think>让我先分析图片中的课表</think>
            以下是识别结果：
            ```json
            {
              "courses": [
                {
                  "name": "高等数学",
                  "teacher": "张老师",
                  "classroom": "明理楼 A101",
                  "dayOfWeek": "周三",
                  "section": "1-2",
                  "weeks": "1-16(单)"
                },
                {
                  "name": "临时补课",
                  "classroom": "信息楼 202",
                  "date": "2026-11-27",
                  "startTime": "8:30",
                  "endTime": "10:10"
                }
              ],
              "semester": {
                "name": "2026-2027-1",
                "startDate": "2026-09-07",
                "totalWeeks": 20
              },
              "timeSlots": [
                { "section": 1, "startTime": "8:00", "endTime": "8:45" }
              ]
            }
            ```
        """.trimIndent()

        val result = normalizeModelOutputToScheduleJson(raw, exportedAt = "2026-11-22 14:00:00")
        assertTrue(result is AiImportParseResult.Ready)
        val ready = result as AiImportParseResult.Ready

        val parsed = ScheduleJsonParser.parse(ready.scheduleJsonBytes.toString(Charsets.UTF_8))
        val doc = parsed.document
        assertNotNull(doc)
        doc!!
        assertEquals(2, doc.courses.size)
        val math = doc.courses[0]
        assertEquals("高等数学", math.name)
        assertEquals(3, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals(1, math.startWeek)
        assertEquals(16, math.endWeek)
        assertEquals(1, math.weekType)

        val makeup = doc.courses[1]
        assertEquals(2, makeup.timeMode)
        assertEquals("2026-11-27", makeup.date)
        assertEquals(5, makeup.dayOfWeek)
        assertEquals("08:30", makeup.startTime)
        assertEquals("10:10", makeup.endTime)

        assertEquals("2026-2027-1", doc.semester?.name)
        assertEquals(1, doc.timeSlots.size)
        assertEquals("08:00", doc.timeSlots[0].startTime)
    }

    @Test
    fun `client redacts apiKey from diagnostics and errors and uses plain text content when no images`() = runTest {
        val diagnostics = FakeDiagnostics()
        val capturedBodies = mutableListOf<String>()
        val secretKey = "sk-test-secret-123456"
        val client = OpenAiScheduleImportClient(
            diagnostics = diagnostics,
            clock = Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC),
            postTransport = { _, _, body ->
                capturedBodies += body
                AiHttpResponse(
                    code = 400,
                    body = """{"error":{"message":"bad key $secretKey"}}""",
                )
            },
        )
        val result = client.parseSchedule(
            config = AiProviderConfig(
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
                apiKey = secretKey,
            ),
            textInput = "周一下午第5-6节 高数",
            images = emptyList(),
        )
        assertTrue(result is AiImportParseResult.Failed)
        val failed = result as AiImportParseResult.Failed
        assertFalse(failed.message.contains(secretKey))
        assertFalse(diagnostics.events.single().message.contains(secretKey))
        assertFalse(capturedBodies.single().contains("image_url"))
    }

    @Test
    fun `client encodes images as data url and returns normalized schedule`() = runTest {
        val diagnostics = FakeDiagnostics()
        val capturedBodies = mutableListOf<String>()
        val client = OpenAiScheduleImportClient(
            diagnostics = diagnostics,
            clock = Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC),
            postTransport = { _, _, body ->
                capturedBodies += body
                AiHttpResponse(
                    code = 200,
                    body = """{"choices":[{"message":{"content":"[{\"name\":\"线性代数\",\"dayOfWeek\":2,\"startSection\":3,\"endSection\":4}]"}}]}""",
                )
            },
        )
        val result = client.parseSchedule(
            config = AiProviderConfig(
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini",
                apiKey = "sk-valid",
            ),
            textInput = "",
            images = listOf(AiImagePayload(byteArrayOf(1, 2, 3), "image/jpeg", "shot.jpg")),
            referenceDateIso = "2026-11-22",
        )
        assertTrue(result is AiImportParseResult.Ready)
        assertTrue(capturedBodies.single().contains("data:image/jpeg;base64,"))
        assertTrue(capturedBodies.single().contains("2026-11-22"))
        assertEquals("SUCCESS", diagnostics.events.single().outcome)
    }

    @Test
    fun `resolveModelsUrl and parseModelsResponse handle placeholders and multiple JSON shapes`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            resolveModelsUrl("https://api.openai.com/v1/").getOrThrow(),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/coding/paas/v4/models",
            resolveModelsUrl("https://open.bigmodel.cn/api/coding/paas/v4/chat/completions").getOrThrow(),
        )
        val placeholderErr = resolveModelsUrl("https://{resource}.openai.azure.com/openai/v1")
        assertTrue(placeholderErr.isFailure)
        assertTrue(placeholderErr.exceptionOrNull()?.message?.contains("占位符") == true)

        val openAiShape = parseModelsResponse(
            """{"data":[{"id":"gpt-4o","owned_by":"openai"},{"id":"gpt-4o-mini","owned_by":"openai"},{"id":"gpt-4o"}]}""",
        )
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), openAiShape.map { it.id })

        val modelsShape = parseModelsResponse("""{"models":[{"name":"glm-4.7"},{"id":"glm-4.6v"}]}""")
        assertEquals(listOf("glm-4.6v", "glm-4.7"), modelsShape.map { it.id })

        val stringArray = parseModelsResponse("""["deepseek-reasoner","deepseek-chat"]""")
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), stringArray.map { it.id })
    }

    @Test
    fun `client fetchModels handles success, 404 fallback, and redacts apiKey`() = runTest {
        val diagnostics = FakeDiagnostics()
        val secretKey = "sk-models-secret-999"
        val successClient = OpenAiScheduleImportClient(
            diagnostics = diagnostics,
            clock = Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC),
            postTransport = { _, _, _ -> AiHttpResponse(200, "") },
            getTransport = { url, _ ->
                assertEquals("https://api.deepseek.com/v1/models", url)
                AiHttpResponse(200, """{"data":[{"id":"deepseek-reasoner"},{"id":"deepseek-chat"}]}""")
            },
        )
        val models = successClient.fetchModels(
            AiProviderConfig("https://api.deepseek.com/v1", "deepseek-chat", secretKey),
        ).getOrThrow()
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models.map { it.id })

        val notFoundClient = OpenAiScheduleImportClient(
            diagnostics = diagnostics,
            clock = Clock.fixed(Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC),
            postTransport = { _, _, _ -> AiHttpResponse(200, "") },
            getTransport = { _, _ -> AiHttpResponse(404, """{"error":{"message":"$secretKey not found"}}""") },
        )
        val fail = notFoundClient.fetchModels(
            AiProviderConfig("https://api.example.com/v1", "m", secretKey),
        )
        assertTrue(fail.isFailure)
        val msg = fail.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("404"))
        assertFalse(msg.contains(secretKey))
    }
}
