package com.gfs.iyango.feature.main.ai

import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiChatMessage
import com.gfs.iyango.core.domain.AiChatRole
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AssistantActionStatus
import com.gfs.iyango.core.domain.AssistantPendingAction
import com.gfs.iyango.core.domain.AssistantToolTrace
import com.gfs.iyango.core.domain.AssistantTurnOutput
import com.gfs.iyango.core.domain.AssistantWritePayload
import com.gfs.iyango.core.domain.CampusAiAssistantPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiAssistantViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sends message, records pending confirmation actions, confirms and cancels actions`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        var currentConfig = AiProviderConfig(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-5-mini",
            apiKey = "sk-live",
            protocol = AiApiProtocol.OPENAI_RESPONSES,
        )
        val store = object : AiConfigStorePort {
            override suspend fun load() = currentConfig
            override suspend fun save(config: AiProviderConfig) { currentConfig = config }
            override suspend fun clearApiKey() = Unit
        }
        val assistant = object : CampusAiAssistantPort {
            override suspend fun sendMessage(
                config: AiProviderConfig,
                history: List<AiChatMessage>,
                userText: String,
                onToolProgress: (String) -> Unit,
            ): AssistantTurnOutput {
                onToolProgress("已查课表")
                return AssistantTurnOutput(
                    replyText = "已准备两个待确认操作",
                    toolTraces = listOf(AssistantToolTrace("get_schedule", "已查课表", true)),
                    pendingActions = listOf(
                        AssistantPendingAction(
                            id = "a1",
                            title = "添加课程",
                            summaryLines = listOf("课程：算法竞赛"),
                            payload = AssistantWritePayload.AddCourse(name = "算法竞赛"),
                        ),
                        AssistantPendingAction(
                            id = "a2",
                            title = "添加考试",
                            summaryLines = listOf("科目：线性代数"),
                            payload = AssistantWritePayload.AddExam(subject = "线性代数", date = "2026-12-20"),
                        ),
                    ),
                )
            }

            override suspend fun executePendingAction(action: AssistantPendingAction): Result<String> =
                Result.success("已添加课程 算法竞赛")
        }

        val vm = AiAssistantViewModel(store, assistant)
        advanceUntilIdle()
        assertTrue(vm.config.value.isConfigured)

        vm.inputText.value = "帮我加一门算法竞赛课程"
        vm.send()
        advanceUntilIdle()

        assertEquals("", vm.inputText.value)
        assertEquals(2, vm.messages.value.size)
        val reply = vm.messages.value[1]
        assertEquals(AiChatRole.ASSISTANT, reply.role)
        assertEquals(2, reply.pendingActions.size)

        vm.confirmAction(reply.id, "a1")
        advanceUntilIdle()
        vm.cancelAction(reply.id, "a2")
        advanceUntilIdle()

        val updated = vm.messages.value[1]
        assertEquals(AssistantActionStatus.CONFIRMED, updated.pendingActions[0].status)
        assertEquals("已添加课程 算法竞赛", updated.pendingActions[0].resultMessage)
        assertEquals(AssistantActionStatus.CANCELLED, updated.pendingActions[1].status)

        currentConfig = currentConfig.copy(protocol = AiApiProtocol.ANTHROPIC_MESSAGES, model = "claude-sonnet-4-20250514")
        vm.reloadConfig()
        advanceUntilIdle()
        assertEquals(AiApiProtocol.ANTHROPIC_MESSAGES, vm.config.value.protocol)

        vm.clearConversation()
        assertTrue(vm.messages.value.isEmpty())
    }
}
