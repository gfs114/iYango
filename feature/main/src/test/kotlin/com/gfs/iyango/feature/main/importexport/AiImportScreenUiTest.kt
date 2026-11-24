package com.gfs.iyango.feature.main.importexport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.gfs.iyango.core.designsystem.theme.IYangoThemePreview
import com.gfs.iyango.core.domain.AiProviderPreset
import com.gfs.iyango.core.domain.ImportWriteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
class AiImportScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `configured state leads with connection strip and input card without prose hints`() {
        compose.setContent {
            IYangoThemePreview {
                var expanded by remember { mutableStateOf(false) }
                var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
                var model by remember { mutableStateOf("gpt-4o-mini") }
                var apiKey by remember { mutableStateOf("sk-test") }
                var text by remember { mutableStateOf("") }

                AiImportContent(
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = apiKey,
                    configExpanded = expanded,
                    configConfigured = true,
                    testingConnection = false,
                    connectionMessage = null,
                    fetchingModels = false,
                    availableModels = emptyList(),
                    modelsFetched = true,
                    showModelPicker = false,
                    showProviderPicker = false,
                    textInput = text,
                    attachedImages = emptyList(),
                    aiWarnings = emptyList(),
                    parsing = false,
                    preview = null,
                    mode = ImportWriteMode.CREATE,
                    targetId = null,
                    targetInfo = null,
                    selectedKeys = emptySet(),
                    applySemester = true,
                    applyTimeSlots = true,
                    applyDisplayConfig = false,
                    localTimetables = emptyList(),
                    confirmDuplicate = false,
                    doneMessage = null,
                    error = null,
                    busy = false,
                    onBack = {},
                    onToggleConfigExpanded = { expanded = !expanded },
                    onBaseUrlChange = { baseUrl = it },
                    onModelChange = { model = it },
                    onApiKeyChange = { apiKey = it },
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
                    onCaptureClick = {},
                    onGalleryClick = {},
                    onRemoveImage = {},
                    onTextInputChange = { text = it },
                    onStartParse = {},
                    onCancelParse = {},
                    onMode = {},
                    onTarget = {},
                    onToggleRow = {},
                    onSelectVisible = {},
                    onClearSelection = {},
                    onApplySemester = {},
                    onApplyTimeSlots = {},
                    onApplyDisplayConfig = {},
                    onConfirmImport = {},
                    onCancelPreview = {},
                    onConfirmDuplicate = {},
                    onDismissDuplicate = {},
                    onConsumeError = {},
                    onConsumeDone = {},
                )
            }
        }

        compose.onNodeWithTag(AiImportTags.CONNECTION_STRIP).assertIsDisplayed()
        compose.onNodeWithContentDescription("接口配置，gpt-4o-mini，api.openai.com").assertIsDisplayed()
        compose.onNodeWithText("导入内容")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        compose.onNodeWithTag(AiImportTags.CAPTURE_BUTTON).assertIsDisplayed()
        compose.onNodeWithTag(AiImportTags.GALLERY_BUTTON).assertIsDisplayed()
        compose.onNodeWithTag(AiImportTags.PARSE_BUTTON).assertIsDisplayed()

        compose.onNodeWithText("Android Keystore", substring = true).assertDoesNotExist()
        compose.onNodeWithText("BYOK", substring = true).assertDoesNotExist()
        compose.onNodeWithText("可上传教务系统", substring = true).assertDoesNotExist()

        compose.onNodeWithText("配置").performClick()

        compose.onNodeWithTag(AiImportTags.CONFIG_SECTION).assertExists()
        compose.onNodeWithText("接口配置")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        compose.onNodeWithText("全部 (${AiProviderPreset.BuiltIns.size})").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AiImportTags.API_KEY_INPUT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AiImportTags.FETCH_MODELS_BUTTON).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AiImportTags.MODEL_INPUT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Base URL", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("常用接口预设").assertDoesNotExist()
        compose.onNodeWithText("仅文本", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("DeepSeek，仅文本").assertExists()

        val keyBounds = compose.onNodeWithTag(AiImportTags.API_KEY_INPUT).getUnclippedBoundsInRoot()
        val fetchBounds = compose.onNodeWithTag(AiImportTags.FETCH_MODELS_BUTTON).getUnclippedBoundsInRoot()
        val modelBounds = compose.onNodeWithTag(AiImportTags.MODEL_INPUT).getUnclippedBoundsInRoot()
        assertTrue(keyBounds.bottom <= fetchBounds.top)
        assertTrue(fetchBounds.bottom <= modelBounds.top)
    }

    @Test
    fun `model field unlocks after entering API key and fetching models`() {
        var typedModel = ""
        compose.setContent {
            IYangoThemePreview {
                var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
                var model by remember { mutableStateOf("gpt-4o-mini") }
                var apiKey by remember { mutableStateOf("") }
                var modelsFetched by remember { mutableStateOf(false) }

                AiImportContent(
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = apiKey,
                    configExpanded = true,
                    configConfigured = false,
                    testingConnection = false,
                    connectionMessage = null,
                    fetchingModels = false,
                    availableModels = emptyList(),
                    modelsFetched = modelsFetched,
                    showModelPicker = false,
                    showProviderPicker = false,
                    textInput = "",
                    attachedImages = emptyList(),
                    aiWarnings = emptyList(),
                    parsing = false,
                    preview = null,
                    mode = ImportWriteMode.CREATE,
                    targetId = null,
                    targetInfo = null,
                    selectedKeys = emptySet(),
                    applySemester = true,
                    applyTimeSlots = true,
                    applyDisplayConfig = false,
                    localTimetables = emptyList(),
                    confirmDuplicate = false,
                    doneMessage = null,
                    error = null,
                    busy = false,
                    onBack = {},
                    onToggleConfigExpanded = {},
                    onBaseUrlChange = { baseUrl = it },
                    onModelChange = {
                        model = it
                        typedModel = it
                    },
                    onApiKeyChange = { apiKey = it },
                    onApplyPreset = {},
                    onOpenProviderPicker = {},
                    onDismissProviderPicker = {},
                    onFetchModels = { modelsFetched = true },
                    onOpenModelPicker = {},
                    onDismissModelPicker = {},
                    onSelectModel = {},
                    onSaveConfig = {},
                    onTestConnection = {},
                    onClearApiKey = {},
                    onCaptureClick = {},
                    onGalleryClick = {},
                    onRemoveImage = {},
                    onTextInputChange = {},
                    onStartParse = {},
                    onCancelParse = {},
                    onMode = {},
                    onTarget = {},
                    onToggleRow = {},
                    onSelectVisible = {},
                    onClearSelection = {},
                    onApplySemester = {},
                    onApplyTimeSlots = {},
                    onApplyDisplayConfig = {},
                    onConfirmImport = {},
                    onCancelPreview = {},
                    onConfirmDuplicate = {},
                    onDismissDuplicate = {},
                    onConsumeError = {},
                    onConsumeDone = {},
                )
            }
        }

        compose.onNodeWithTag(AiImportTags.MODEL_INPUT).assertDoesNotExist()
        compose.onNodeWithTag(AiImportTags.FETCH_MODELS_BUTTON).performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag(AiImportTags.API_KEY_INPUT).performScrollTo().performTextInput("sk-live-123")
        compose.onNodeWithTag(AiImportTags.FETCH_MODELS_BUTTON).performScrollTo().assertIsEnabled().performClick()

        compose.onNodeWithTag(AiImportTags.MODEL_INPUT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(AiImportTags.MODEL_INPUT).performTextReplacement("custom-model")
        compose.runOnIdle { assertEquals("custom-model", typedModel) }
    }
}
