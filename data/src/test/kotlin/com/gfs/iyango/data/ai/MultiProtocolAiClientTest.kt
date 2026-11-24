package com.gfs.iyango.data.ai

import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiChatMessage
import com.gfs.iyango.core.domain.AiChatRole
import com.gfs.iyango.core.domain.AiChatTurnResult
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiImportParseResult
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiToolCall
import com.gfs.iyango.core.domain.AiToolDefinition
import com.gfs.iyango.core.domain.DiagnosticExport
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.domain.DiagnosticLogSummary
import com.gfs.iyango.core.model.DiagnosticEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiProtocolAiClientTest {

    private class FakeDiagnostics : DiagnosticLogPort {
        val events = mutableListOf<DiagnosticEvent>()
        override fun observeRecent(limit: Int): Flow<List<DiagnosticEvent>> = flowOf(events)
        override val summary: Flow<DiagnosticLogSummary> = flowOf(DiagnosticLogSummary())
        override suspend fun record(event: DiagnosticEvent) { events += event }
        override suspend fun export(): DiagnosticExport = DiagnosticExport("diag.txt", "")
        override suspend fun clear() { events.clear() }
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC)
    private val sampleTool = AiToolDefinition(
        name = "get_schedule",
        description = "查询课表",
        parametersSchemaJson = """{"type":"object","properties":{"days":{"type":"integer"}}}""",
    )

    @Test
    fun `resolveEndpointUrl and resolveModelsUrl handle all three protocols and reject insecure hosts`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            resolveEndpointUrl("https://api.openai.com/v1/", AiApiProtocol.OPENAI_CHAT).getOrThrow(),
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            resolveEndpointUrl("https://api.openai.com/v1/", AiApiProtocol.OPENAI_RESPONSES).getOrThrow(),
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            resolveEndpointUrl("https://api.openai.com/v1/chat/completions", AiApiProtocol.OPENAI_RESPONSES).getOrThrow(),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            resolveEndpointUrl("https://api.anthropic.com", AiApiProtocol.ANTHROPIC_MESSAGES).getOrThrow(),
        )
        assertEquals(
            "https://relay.example.com/anthropic/v1/messages",
            resolveEndpointUrl("https://relay.example.com/anthropic/v1", AiApiProtocol.ANTHROPIC_MESSAGES).getOrThrow(),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            resolveModelsUrl("https://api.anthropic.com", AiApiProtocol.ANTHROPIC_MESSAGES).getOrThrow(),
        )
        assertTrue(resolveEndpointUrl("http://public.example.com/v1", AiApiProtocol.OPENAI_RESPONSES).isFailure)
        assertTrue(resolveEndpointUrl("https://{host}/v1", AiApiProtocol.ANTHROPIC_MESSAGES).isFailure)
    }

    @Test
    fun `auth headers match protocol requirements and never send bearer to anthropic`() {
        val chatHeaders = buildAuthHeaders(AiApiProtocol.OPENAI_CHAT, "sk-openai")
        assertEquals("Bearer sk-openai", chatHeaders["Authorization"])
        assertNull(chatHeaders["x-api-key"])

        val respHeaders = buildAuthHeaders(AiApiProtocol.OPENAI_RESPONSES, "sk-resp")
        assertEquals("Bearer sk-resp", respHeaders["Authorization"])

        val claudeHeaders = buildAuthHeaders(AiApiProtocol.ANTHROPIC_MESSAGES, "sk-ant")
        assertEquals("sk-ant", claudeHeaders["x-api-key"])
        assertEquals(ANTHROPIC_API_VERSION, claudeHeaders["anthropic-version"])
        assertNull(claudeHeaders["Authorization"])
    }

    @Test
    fun `completeTurn handles OpenAI Chat tool calls and follow-up reply`() = runTest {
        val captured = mutableListOf<String>()
        val client = OpenAiScheduleImportClient(
            diagnostics = FakeDiagnostics(),
            clock = clock,
            headerPostTransport = { url, headers, body ->
                assertEquals("https://api.openai.com/v1/chat/completions", url)
                assertEquals("Bearer sk-chat", headers["Authorization"])
                captured += body
                if (captured.size == 1) {
                    AiHttpResponse(
                        200,
                        """{"choices":[{"message":{"content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_schedule","arguments":"{\"days\":1}"}}]}}]}""",
                    )
                } else {
                    AiHttpResponse(
                        200,
                        """{"choices":[{"message":{"content":"今天只有一节高等数学。"}}]}""",
                    )
                }
            },
            headerGetTransport = { _, _ -> AiHttpResponse(200, "") },
        )
        val config = AiProviderConfig(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            apiKey = "sk-chat",
            protocol = AiApiProtocol.OPENAI_CHAT,
        )

        val first = client.completeTurn(
            config = config,
            systemPrompt = "sys",
            messages = listOf(AiChatMessage(AiChatRole.USER, "今天有什么课")),
            tools = listOf(sampleTool),
        ) as AiChatTurnResult.Reply
        assertEquals(1, first.toolCalls.size)
        assertEquals("call_1", first.toolCalls.single().id)

        val second = client.completeTurn(
            config = config,
            systemPrompt = "sys",
            messages = listOf(
                AiChatMessage(AiChatRole.USER, "今天有什么课"),
                AiChatMessage(AiChatRole.ASSISTANT, "", toolCalls = first.toolCalls),
                AiChatMessage(AiChatRole.TOOL, """{"courses":[]}""", toolCallId = "call_1", toolName = "get_schedule"),
            ),
            tools = listOf(sampleTool),
        ) as AiChatTurnResult.Reply
        assertEquals("今天只有一节高等数学。", second.text)
        assertTrue(captured[1].contains("\"tool_call_id\":\"call_1\""))
    }

    @Test
    fun `completeTurn handles OpenAI Responses API flat tools and function_call roundtrip`() = runTest {
        val capturedBodies = mutableListOf<String>()
        val client = OpenAiScheduleImportClient(
            diagnostics = FakeDiagnostics(),
            clock = clock,
            headerPostTransport = { url, headers, body ->
                assertEquals("https://api.openai.com/v1/responses", url)
                assertEquals("Bearer sk-resp", headers["Authorization"])
                capturedBodies += body
                if (capturedBodies.size == 1) {
                    AiHttpResponse(
                        200,
                        """{"output":[{"type":"function_call","call_id":"fc_99","name":"get_schedule","arguments":"{\"days\":2}"}]}""",
                    )
                } else {
                    AiHttpResponse(
                        200,
                        """{"output":[{"type":"message","content":[{"type":"output_text","text":"未来两天共有 3 节课"}]}]}""",
                    )
                }
            },
            headerGetTransport = { _, _ -> AiHttpResponse(200, "") },
        )
        val config = AiProviderConfig(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-5-mini",
            apiKey = "sk-resp",
            protocol = AiApiProtocol.OPENAI_RESPONSES,
        )

        val first = client.completeTurn(
            config = config,
            systemPrompt = "system instructions",
            messages = listOf(AiChatMessage(AiChatRole.USER, "今明两天课表")),
            tools = listOf(sampleTool),
        ) as AiChatTurnResult.Reply
        assertEquals("fc_99", first.toolCalls.single().id)
        val reqObj = Json.parseToJsonElement(capturedBodies[0]) as JsonObject
        val firstTool = (reqObj["tools"] as JsonArray).first() as JsonObject
        assertEquals("function", (firstTool["type"] as JsonPrimitive).content)
        assertEquals("get_schedule", (firstTool["name"] as JsonPrimitive).content)
        assertNull(firstTool["function"])

        val second = client.completeTurn(
            config = config,
            systemPrompt = "system instructions",
            messages = listOf(
                AiChatMessage(AiChatRole.USER, "今明两天课表"),
                AiChatMessage(AiChatRole.ASSISTANT, "", toolCalls = first.toolCalls),
                AiChatMessage(AiChatRole.TOOL, """{"ok":true}""", toolCallId = "fc_99", toolName = "get_schedule"),
            ),
            tools = listOf(sampleTool),
        ) as AiChatTurnResult.Reply
        assertEquals("未来两天共有 3 节课", second.text)
        assertTrue(capturedBodies[1].contains("\"type\":\"function_call_output\""))
        assertTrue(capturedBodies[1].contains("\"call_id\":\"fc_99\""))
    }

    @Test
    fun `completeTurn handles Anthropic Messages API tool_use and merges consecutive tool results into one user turn`() = runTest {
        val capturedBodies = mutableListOf<String>()
        val client = OpenAiScheduleImportClient(
            diagnostics = FakeDiagnostics(),
            clock = clock,
            headerPostTransport = { url, headers, body ->
                assertEquals("https://api.anthropic.com/v1/messages", url)
                assertEquals("sk-ant-key", headers["x-api-key"])
                assertEquals(ANTHROPIC_API_VERSION, headers["anthropic-version"])
                assertNull(headers["Authorization"])
                capturedBodies += body
                if (capturedBodies.size == 1) {
                    AiHttpResponse(
                        200,
                        """{"content":[{"type":"tool_use","id":"toolu_1","name":"get_schedule","input":{"days":1}},{"type":"tool_use","id":"toolu_2","name":"get_grades_summary","input":{}}]}""",
                    )
                } else {
                    AiHttpResponse(
                        200,
                        """{"content":[{"type":"text","text":"今日课表和成绩均已核对。"}]}""",
                    )
                }
            },
            headerGetTransport = { _, _ -> AiHttpResponse(200, "") },
        )
        val config = AiProviderConfig(
            baseUrl = "https://api.anthropic.com/v1",
            model = "claude-sonnet-4-20250514",
            apiKey = "sk-ant-key",
            protocol = AiApiProtocol.ANTHROPIC_MESSAGES,
        )

        val first = client.completeTurn(
            config = config,
            systemPrompt = "claude sys",
            messages = listOf(AiChatMessage(AiChatRole.USER, "查一下课表和绩点")),
            tools = listOf(sampleTool),
        ) as AiChatTurnResult.Reply
        assertEquals(2, first.toolCalls.size)

        val second = client.completeTurn(
            config = config,
            systemPrompt = "claude sys",
            messages = listOf(
                AiChatMessage(AiChatRole.USER, "查一下课表和绩点"),
                AiChatMessage(AiChatRole.ASSISTANT, "", toolCalls = first.toolCalls),
                AiChatMessage(AiChatRole.TOOL, """{"days":[]}""", toolCallId = "toolu_1", toolName = "get_schedule"),
                AiChatMessage(AiChatRole.TOOL, """{"averageGradePoint":3.8}""", toolCallId = "toolu_2", toolName = "get_grades_summary"),
            ),
            tools = listOf(sampleTool),
        ) as AiChatTurnResult.Reply
        assertEquals("今日课表和成绩均已核对。", second.text)

        val followUpObj = Json.parseToJsonElement(capturedBodies[1]) as JsonObject
        val sentMsgs = followUpObj["messages"] as JsonArray
        assertEquals(3, sentMsgs.size)
        val mergedUserTurn = sentMsgs[2] as JsonObject
        assertEquals("user", (mergedUserTurn["role"] as JsonPrimitive).content)
        val mergedContent = mergedUserTurn["content"] as JsonArray
        assertEquals(2, mergedContent.size)
        assertEquals("tool_result", ((mergedContent[0] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("tool_result", ((mergedContent[1] as JsonObject)["type"] as JsonPrimitive).content)
    }

    @Test
    fun `parseSchedule and testConnection and fetchModels work across Responses and Anthropic and redact keys`() = runTest {
        val diagnostics = FakeDiagnostics()
        val capturedPosts = mutableListOf<Pair<String, String>>()
        val capturedGets = mutableListOf<Pair<String, Map<String, String>>>()

        val client = OpenAiScheduleImportClient(
            diagnostics = diagnostics,
            clock = clock,
            headerPostTransport = { url, _, body ->
                capturedPosts += url to body
                when {
                    url.endsWith("/responses") -> AiHttpResponse(
                        200,
                        """{"output_text":"[{\"name\":\"操作系统\",\"dayOfWeek\":4,\"startSection\":1,\"endSection\":2}]"}""",
                    )
                    url.endsWith("/messages") && body.contains("Reply OK") -> AiHttpResponse(
                        401,
                        """{"error":{"message":"bad secret sk-secret-xyz"}}""",
                    )
                    else -> AiHttpResponse(
                        200,
                        """{"content":[{"type":"text","text":"[{\"name\":\"算法\",\"dayOfWeek\":1,\"startSection\":3,\"endSection\":4}]"}]}""",
                    )
                }
            },
            headerGetTransport = { url, headers ->
                capturedGets += url to headers
                AiHttpResponse(200, """{"data":[{"id":"claude-sonnet-4-20250514"}]}""")
            },
        )

        val respResult = client.parseSchedule(
            config = AiProviderConfig("https://api.openai.com/v1", "gpt-5-mini", "sk-r", AiApiProtocol.OPENAI_RESPONSES),
            textInput = "",
            images = listOf(AiImagePayload(byteArrayOf(7, 8), "image/png", "t.png")),
        )
        assertTrue(respResult is AiImportParseResult.Ready)
        assertTrue(capturedPosts[0].second.contains("\"type\":\"input_image\""))

        val antResult = client.parseSchedule(
            config = AiProviderConfig("https://api.anthropic.com", "claude-sonnet-4-20250514", "sk-a", AiApiProtocol.ANTHROPIC_MESSAGES),
            textInput = "",
            images = listOf(AiImagePayload(byteArrayOf(7, 8), "image/png", "t.png")),
        )
        assertTrue(antResult is AiImportParseResult.Ready)
        assertTrue(capturedPosts[1].second.contains("\"type\":\"base64\""))

        val antModels = client.fetchModels(
            AiProviderConfig("https://api.anthropic.com", "claude-sonnet-4-20250514", "sk-ant-models", AiApiProtocol.ANTHROPIC_MESSAGES),
        ).getOrThrow()
        assertEquals(listOf("claude-sonnet-4-20250514"), antModels.map { it.id })
        assertEquals("https://api.anthropic.com/v1/models", capturedGets.single().first)
        assertEquals("sk-ant-models", capturedGets.single().second["x-api-key"])

        val failTest = client.testConnection(
            AiProviderConfig("https://api.anthropic.com", "claude-sonnet-4-20250514", "sk-secret-xyz", AiApiProtocol.ANTHROPIC_MESSAGES),
        )
        assertTrue(failTest.isFailure)
        assertFalse(failTest.exceptionOrNull()?.message.orEmpty().contains("sk-secret-xyz"))
    }
}
