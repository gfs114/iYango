package com.gfs.iyango.feature.main.ai

import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiConfigStorePort
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiImportParseResult
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiProviderPreset
import com.gfs.iyango.core.domain.AiScheduleImportPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiConfigViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `switching protocols, applying presets, fetching models, saving, testing and clearing key`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        var stored = AiProviderConfig()
        var testedProtocol: AiApiProtocol? = null

        val store = object : AiConfigStorePort {
            override suspend fun load(): AiProviderConfig = stored
            override suspend fun save(config: AiProviderConfig) { stored = config }
            override suspend fun clearApiKey() { stored = stored.copy(apiKey = "") }
        }
        val aiPort = object : AiScheduleImportPort {
            override suspend fun parseSchedule(
                config: AiProviderConfig,
                textInput: String,
                images: List<AiImagePayload>,
                referenceDateIso: String,
            ): AiImportParseResult = AiImportParseResult.Failed("unused")

            override suspend fun testConnection(config: AiProviderConfig): Result<String> {
                testedProtocol = config.protocol
                return Result.success("连接成功")
            }

            override suspend fun fetchModels(config: AiProviderConfig): Result<List<AiModelInfo>> =
                Result.success(listOf(AiModelInfo("claude-sonnet-4-20250514"), AiModelInfo("gpt-5-mini")))
        }

        val vm = AiConfigViewModel(aiConfigStore = store, aiImport = aiPort)
        advanceUntilIdle()
        assertEquals(AiApiProtocol.OPENAI_CHAT, vm.protocol.value)
        assertFalse(vm.configConfigured.value)

        vm.selectProtocol(AiApiProtocol.ANTHROPIC_MESSAGES)
        assertEquals(AiApiProtocol.ANTHROPIC_MESSAGES, vm.protocol.value)
        assertEquals("https://api.anthropic.com/v1", vm.baseUrl.value)

        // Custom relay URL must be preserved when switching protocols
        vm.baseUrl.value = "https://relay.custom.example/v1"
        vm.selectProtocol(AiApiProtocol.OPENAI_RESPONSES)
        assertEquals("https://relay.custom.example/v1", vm.baseUrl.value)

        val anthropicPreset = AiProviderPreset.BuiltIns.first { it.id == "anthropic" }
        vm.applyPreset(anthropicPreset)
        assertEquals(AiApiProtocol.ANTHROPIC_MESSAGES, vm.protocol.value)
        assertEquals(anthropicPreset.baseUrl, vm.baseUrl.value)
        assertEquals(anthropicPreset.defaultModel, vm.model.value)

        vm.apiKey.value = "sk-ant-test"
        vm.fetchAvailableModels()
        advanceUntilIdle()
        assertEquals(2, vm.availableModels.value.size)
        assertTrue(vm.showModelPicker.value)
        vm.selectModel("claude-sonnet-4-20250514")
        assertFalse(vm.showModelPicker.value)

        vm.testConnection()
        advanceUntilIdle()
        assertEquals(AiApiProtocol.ANTHROPIC_MESSAGES, testedProtocol)
        assertEquals("连接成功", vm.connectionMessage.value)
        assertTrue(vm.configConfigured.value)
        assertEquals(AiApiProtocol.ANTHROPIC_MESSAGES, stored.protocol)

        vm.clearApiKey()
        advanceUntilIdle()
        assertFalse(vm.configConfigured.value)
        assertEquals("", vm.apiKey.value)
    }
}
