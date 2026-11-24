package com.gfs.iyango.feature.main.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiChatRole
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AssistantPendingAction
import com.gfs.iyango.core.domain.AssistantToolTrace
import com.gfs.iyango.core.domain.AssistantWritePayload
import com.gfs.iyango.feature.main.mine.MineScreen
import com.gfs.iyango.feature.main.today.TodayPageLayout
import com.gfs.iyango.feature.main.today.TodayUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
class AiScreensUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `AiConfigContent renders all three protocols and endpoint preview cleanly`() {
        var selectedProtocol = AiApiProtocol.OPENAI_CHAT
        compose.setContent {
            IYangoThemePreview {
                var protocol by remember { mutableStateOf(AiApiProtocol.OPENAI_CHAT) }
                AiConfigContent(
                    protocol = protocol,
                    baseUrl = "https://api.openai.com/v1",
                    model = "gpt-4o-mini",
                    apiKey = "sk-test",
                    configConfigured = true,
                    testingConnection = false,
                    connectionMessage = null,
                    fetchingModels = false,
                    availableModels = emptyList(),
                    showModelPicker = false,
                    showProviderPicker = false,
                    error = null,
                    onBack = {},
                    onProtocolChange = {
                        protocol = it
                        selectedProtocol = it
                    },
                    onBaseUrlChange = {},
                    onModelChange = {},
                    onApiKeyChange = {},
                    onApplyPreset = {},
                    onOpenProviderPicker = {},
                    onDismissProviderPicker = {},
                    onFetchModels = {},
                    onOpenModelPicker = {},
                    onDismissModelPicker = {},
                    onSelectModel = {},
                    onSaveConfig = {},
                    onTestConnection = {},
                    onClearApiKey = {},
                    onConsumeError = {},
                )
            }
        }

        compose.onNodeWithText("OpenAI Chat").assertIsDisplayed()
        compose.onNodeWithText("OpenAI Responses").assertIsDisplayed()
        compose.onNode(hasText("Anthropic") and hasAnyAncestor(hasTestTag(AiConfigTags.PROTOCOL_SECTION))).performClick()
        compose.runOnIdle { assertEquals(AiApiProtocol.ANTHROPIC_MESSAGES, selectedProtocol) }
        compose.onNodeWithTag(AiConfigTags.ENDPOINT_PREVIEW).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AiConfigTags.SAVE_BUTTON).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AiConfigTags.TEST_BUTTON).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `AiAssistantContent renders starter chips and triggers callback`() {
        var starterClicked = ""
        compose.setContent {
            IYangoThemePreview {
                AiAssistantContent(
                    config = AiProviderConfig(
                        baseUrl = "https://api.anthropic.com/v1",
                        model = "claude-sonnet-4-20250514",
                        apiKey = "sk-ant",
                        protocol = AiApiProtocol.ANTHROPIC_MESSAGES,
                    ),
                    messages = emptyList(),
                    inputText = "",
                    sending = false,
                    progressLabel = "",
                    error = null,
                    onBack = {},
                    onOpenAiConfig = {},
                    onInputTextChange = {},
                    onSend = {},
                    onSelectStarter = { starterClicked = it },
                    onCancelSending = {},
                    onConfirmAction = { _, _ -> },
                    onCancelAction = { _, _ -> },
                    onClearConversation = {},
                    onConsumeError = {},
                )
            }
        }

        compose.onNodeWithTag(AiAssistantTags.STARTER_SECTION).assertIsDisplayed()
        compose.onNodeWithText("今天还有什么课").performClick()
        compose.runOnIdle { assertEquals("今天还有什么课", starterClicked) }
    }

    @Test
    fun `AiAssistantContent renders tool badges and confirmation actions`() {
        var confirmedActionId = ""
        compose.setContent {
            IYangoThemePreview {
                AiAssistantContent(
                    config = AiProviderConfig(
                        baseUrl = "https://api.anthropic.com/v1",
                        model = "claude-sonnet-4-20250514",
                        apiKey = "sk-ant",
                        protocol = AiApiProtocol.ANTHROPIC_MESSAGES,
                    ),
                    messages = listOf(
                        AssistantChatItem(id = "u1", role = AiChatRole.USER, text = "加一门选修课"),
                        AssistantChatItem(
                            id = "a1",
                            role = AiChatRole.ASSISTANT,
                            text = "请确认以下课程信息。",
                            toolTraces = listOf(AssistantToolTrace("propose_add_course", "待确认加课", true)),
                            pendingActions = listOf(
                                AssistantPendingAction(
                                    id = "act-1",
                                    title = "添加课程",
                                    summaryLines = listOf("课程：心理学导论"),
                                    payload = AssistantWritePayload.AddCourse(name = "心理学导论"),
                                ),
                            ),
                        ),
                    ),
                    inputText = "",
                    sending = false,
                    progressLabel = "",
                    error = null,
                    onBack = {},
                    onOpenAiConfig = {},
                    onInputTextChange = {},
                    onSend = {},
                    onSelectStarter = {},
                    onCancelSending = {},
                    onConfirmAction = { _, actionId -> confirmedActionId = actionId },
                    onCancelAction = { _, _ -> },
                    onClearConversation = {},
                    onConsumeError = {},
                )
            }
        }

        compose.onNodeWithText("待确认加课").assertIsDisplayed()
        compose.onNodeWithText("添加课程").assertIsDisplayed()
        compose.onNodeWithText("确认执行").performClick()
        compose.runOnIdle { assertTrue(confirmedActionId == "act-1") }
    }

    @Test
    fun `TodayPageLayout renders robot AI assistant button in top bar`() {
        var clicked = 0
        compose.setContent {
            IYangoThemePreview {
                TodayPageLayout(
                    state = TodayUiState.FirstLoading,
                    onProfileClick = {},
                    onOpenAiAssistant = { clicked++ },
                ) {}
            }
        }
        compose.onNodeWithContentDescription("AI 助理").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test
    fun `MineScreen merges AI config into AI assistant instead of top-level row`() {
        compose.setContent {
            IYangoThemePreview {
                MineScreen()
            }
        }
        compose.onNodeWithText("AI 助理").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AI 接口").assertDoesNotExist()
    }
}
