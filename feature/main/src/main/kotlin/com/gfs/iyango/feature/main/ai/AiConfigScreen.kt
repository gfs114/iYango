@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.compose.NavigationBackHandler
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.component.SectionCard
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidAlertDialog
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.liquidglass.LiquidToggle
import com.gfs.iyango.core.designsystem.motion.AppMotion
import com.gfs.iyango.core.designsystem.motion.LocalMotionEnabled
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.domain.AiProviderPreset
import com.gfs.iyango.core.domain.AiProviderProfile
import com.gfs.iyango.feature.main.backPreviewLayer
import com.gfs.iyango.feature.main.rememberBackPreviewState
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal object AiConfigTags {
    const val LIST_SCREEN = "ai_config_list_screen"
    const val DETAIL_SCREEN = "ai_config_detail_screen"
    const val PROVIDER_CARD_PREFIX = "ai_config_provider_card_"
    const val SWIPE_DELETE_BUTTON_PREFIX = "ai_config_swipe_delete_button_"
    const val ADD_PROVIDER_BUTTON = "ai_config_add_provider_button"
    const val ADD_CUSTOM_PROVIDER_BUTTON = "ai_config_add_custom_provider_button"
    const val PROFILE_NAME_INPUT = "ai_config_profile_name_input"
    const val DELETE_PROFILE_BUTTON = "ai_config_delete_profile_button"
    const val PROTOCOL_SECTION = "ai_config_protocol_section"
    const val BASE_URL_INPUT = "ai_config_base_url_input"
    const val ENDPOINT_PREVIEW = "ai_config_endpoint_preview"
    const val API_KEY_INPUT = "ai_config_api_key_input"
    const val FETCH_MODELS_BUTTON = "ai_config_fetch_models_button"
    const val MODEL_INPUT = "ai_config_model_input"
    const val SAVE_BUTTON = "ai_config_save_button"
    const val TEST_BUTTON = "ai_config_test_button"
    const val PROVIDER_PICKER_BUTTON = "ai_config_provider_picker_button"
}

@Composable
fun AiConfigScreen(
    onBack: () -> Unit,
    onOpenProviderDetail: ((String) -> Unit)? = null,
    viewModel: AiConfigViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val editingProfileId by viewModel.editingProfileId.collectAsStateWithLifecycle()
    val profileName by viewModel.profileName.collectAsStateWithLifecycle()
    val protocol by viewModel.protocol.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val configConfigured by viewModel.configConfigured.collectAsStateWithLifecycle()
    val testingConnection by viewModel.testingConnection.collectAsStateWithLifecycle()
    val connectionMessage by viewModel.connectionMessage.collectAsStateWithLifecycle()
    val fetchingModels by viewModel.fetchingModels.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val showModelPicker by viewModel.showModelPicker.collectAsStateWithLifecycle()
    val showProviderPicker by viewModel.showProviderPicker.collectAsStateWithLifecycle()
    val showAddProviderSheet by viewModel.showAddProviderSheet.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val multiActivityMode = onOpenProviderDetail != null
    val latestEditingProfileId by rememberUpdatedState(editingProfileId)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, multiActivityMode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && (multiActivityMode || latestEditingProfileId == null)) {
                viewModel.reload()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val backPreview = rememberBackPreviewState()
    NavigationBackHandler(
        backPreview.eventState,
        isBackEnabled = !multiActivityMode && editingProfileId != null,
        onBackCompleted = {
            backPreview.complete(
                restoreBeforeCompletion = true,
                onCompleted = viewModel::closeProviderDetail,
            )
        },
    )

    AiConfigContent(
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        configConfigured = configConfigured,
        testingConnection = testingConnection,
        connectionMessage = connectionMessage,
        fetchingModels = fetchingModels,
        availableModels = availableModels,
        showModelPicker = showModelPicker,
        showProviderPicker = showProviderPicker,
        error = error,
        onBack = onBack,
        onProtocolChange = viewModel::selectProtocol,
        onBaseUrlChange = { viewModel.baseUrl.value = it },
        onModelChange = { viewModel.model.value = it },
        onApiKeyChange = { viewModel.apiKey.value = it },
        onApplyPreset = viewModel::applyPreset,
        onOpenProviderPicker = viewModel::openProviderPicker,
        onDismissProviderPicker = viewModel::dismissProviderPicker,
        onFetchModels = viewModel::fetchAvailableModels,
        onOpenModelPicker = viewModel::openModelPicker,
        onDismissModelPicker = viewModel::dismissModelPicker,
        onSelectModel = viewModel::selectModel,
        onSaveConfig = viewModel::saveConfig,
        onTestConnection = viewModel::testConnection,
        onClearApiKey = viewModel::clearApiKey,
        onConsumeError = viewModel::consumeError,
        profiles = profiles,
        activeProfileId = activeProfileId,
        editingProfileId = if (multiActivityMode) null else editingProfileId,
        profileName = profileName,
        showAddProviderSheet = showAddProviderSheet,
        detailModifier = Modifier.backPreviewLayer(backPreview, cardStyle = true),
        onSelectProfile = viewModel::selectProfile,
        onProfileNameChange = { viewModel.profileName.value = it },
        onCreateNewProfile = viewModel::createNewProfile,
        onDeleteActiveProfile = viewModel::deleteActiveProfile,
        onOpenProviderDetail = { id ->
            if (onOpenProviderDetail != null) {
                onOpenProviderDetail(id)
            } else {
                viewModel.openProviderDetail(id)
            }
        },
        onCloseProviderDetail = viewModel::closeProviderDetail,
        onOpenAddProviderSheet = viewModel::openAddProviderSheet,
        onDismissAddProviderSheet = viewModel::dismissAddProviderSheet,
        onAddPreset = { preset ->
            if (onOpenProviderDetail != null) {
                viewModel.addProviderFromPreset(
                    preset = preset,
                    openDetailLocally = false,
                    onCreated = onOpenProviderDetail,
                )
            } else {
                viewModel.addProviderFromPreset(preset)
            }
        },
        onAddCustomProvider = {
            if (onOpenProviderDetail != null) {
                viewModel.addCustomProvider(
                    openDetailLocally = false,
                    onCreated = onOpenProviderDetail,
                )
            } else {
                viewModel.addCustomProvider()
            }
        },
        onActivateEditingProfile = viewModel::activateEditingProfile,
        onDeleteProfile = viewModel::deleteProfile,
    )
}

@Composable
fun AiProviderDetailScreen(
    profileId: String,
    onBack: () -> Unit,
    viewModel: AiConfigViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val profileName by viewModel.profileName.collectAsStateWithLifecycle()
    val protocol by viewModel.protocol.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val configConfigured by viewModel.configConfigured.collectAsStateWithLifecycle()
    val testingConnection by viewModel.testingConnection.collectAsStateWithLifecycle()
    val connectionMessage by viewModel.connectionMessage.collectAsStateWithLifecycle()
    val fetchingModels by viewModel.fetchingModels.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val showModelPicker by viewModel.showModelPicker.collectAsStateWithLifecycle()
    val showProviderPicker by viewModel.showProviderPicker.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(profileId, profiles) {
        if (viewModel.editingProfileId.value != profileId && profiles.any { it.id == profileId }) {
            viewModel.openProviderDetail(profileId)
        }
    }

    AiProviderDetailPage(
        editingProfileId = profileId,
        isEditingActive = profileId == activeProfileId,
        canDelete = profiles.size > 1,
        profileName = profileName,
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        configConfigured = configConfigured,
        testingConnection = testingConnection,
        connectionMessage = connectionMessage,
        fetchingModels = fetchingModels,
        availableModels = availableModels,
        error = error,
        onBack = onBack,
        onProfileNameChange = { viewModel.profileName.value = it },
        onProtocolChange = viewModel::selectProtocol,
        onBaseUrlChange = { viewModel.baseUrl.value = it },
        onModelChange = { viewModel.model.value = it },
        onApiKeyChange = { viewModel.apiKey.value = it },
        onOpenProviderPicker = viewModel::openProviderPicker,
        onFetchModels = viewModel::fetchAvailableModels,
        onOpenModelPicker = viewModel::openModelPicker,
        onSaveConfig = viewModel::saveConfig,
        onTestConnection = viewModel::testConnection,
        onClearApiKey = viewModel::clearApiKey,
        onActivateProfile = viewModel::activateEditingProfile,
        onDeleteProfile = { viewModel.deleteProfile(profileId, onDeleted = onBack) },
        onConsumeError = viewModel::consumeError,
    )

    if (showProviderPicker) {
        AiConfigProviderPickerDialog(
            currentBaseUrl = baseUrl,
            currentProtocol = protocol,
            onSelect = viewModel::applyPreset,
            onDismiss = viewModel::dismissProviderPicker,
        )
    }

    if (showModelPicker) {
        AiConfigModelPickerDialog(
            models = availableModels,
            selectedModelId = model,
            onSelect = viewModel::selectModel,
            onDismiss = viewModel::dismissModelPicker,
        )
    }
}

@Composable
internal fun AiConfigContent(
    protocol: AiApiProtocol,
    baseUrl: String,
    model: String,
    apiKey: String,
    configConfigured: Boolean,
    testingConnection: Boolean,
    connectionMessage: String?,
    fetchingModels: Boolean,
    availableModels: List<AiModelInfo>,
    showModelPicker: Boolean,
    showProviderPicker: Boolean,
    error: String?,
    onBack: () -> Unit,
    onProtocolChange: (AiApiProtocol) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onApplyPreset: (AiProviderPreset) -> Unit,
    onOpenProviderPicker: () -> Unit,
    onDismissProviderPicker: () -> Unit,
    onFetchModels: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onDismissModelPicker: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onTestConnection: () -> Unit,
    onClearApiKey: () -> Unit,
    onConsumeError: () -> Unit,
    profiles: List<AiProviderProfile> = listOf(AiProviderProfile()),
    activeProfileId: String = AiProviderProfile.DEFAULT_ID,
    editingProfileId: String? = null,
    profileName: String = AiProviderProfile.DEFAULT_NAME,
    showAddProviderSheet: Boolean = false,
    detailModifier: Modifier = Modifier,
    onSelectProfile: (String) -> Unit = {},
    onProfileNameChange: (String) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onCreateNewProfile: () -> Unit = {},
    onDeleteActiveProfile: () -> Unit = {},
    onOpenProviderDetail: (String) -> Unit = {},
    onCloseProviderDetail: () -> Unit = {},
    onOpenAddProviderSheet: () -> Unit = {},
    onDismissAddProviderSheet: () -> Unit = {},
    onAddPreset: (AiProviderPreset) -> Unit = {},
    onAddCustomProvider: () -> Unit = {},
    onActivateEditingProfile: () -> Unit = {},
    onDeleteProfile: (String) -> Unit = { onDeleteActiveProfile() },
) {
    val motionEnabled = LocalMotionEnabled.current

    AnimatedContent(
        targetState = editingProfileId,
        label = "aiProviderTwoLevel",
        transitionSpec = {
            if (!motionEnabled) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                val forward = targetState != null && initialState == null
                if (forward) {
                    (slideInHorizontally(tween(AppMotion.PageEnter)) { it / 4 } +
                        fadeIn(tween(AppMotion.PageEnter))) togetherWith
                        (slideOutHorizontally(tween(AppMotion.PageExit)) { -it / 6 } +
                            fadeOut(tween(AppMotion.PageExit)))
                } else {
                    (slideInHorizontally(tween(AppMotion.PageEnter)) { -it / 4 } +
                        fadeIn(tween(AppMotion.PageEnter))) togetherWith
                        (slideOutHorizontally(tween(AppMotion.PageExit)) { it / 4 } +
                            fadeOut(tween(AppMotion.PageExit)))
                }
            }
        },
    ) { targetEditingId ->
        if (targetEditingId == null) {
            AiProviderListPage(
                profiles = profiles,
                activeProfileId = activeProfileId,
                error = error,
                onBack = onBack,
                onOpenAddProviderSheet = onOpenAddProviderSheet,
                onOpenProviderDetail = onOpenProviderDetail,
                onSelectProfile = onSelectProfile,
                onDeleteProfile = onDeleteProfile,
                onConsumeError = onConsumeError,
            )
        } else {
            AiProviderDetailPage(
                editingProfileId = targetEditingId,
                isEditingActive = targetEditingId == activeProfileId,
                canDelete = profiles.size > 1,
                profileName = profileName,
                protocol = protocol,
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey,
                configConfigured = configConfigured,
                testingConnection = testingConnection,
                connectionMessage = connectionMessage,
                fetchingModels = fetchingModels,
                availableModels = availableModels,
                error = error,
                modifier = detailModifier,
                onBack = onCloseProviderDetail,
                onProfileNameChange = onProfileNameChange,
                onProtocolChange = onProtocolChange,
                onBaseUrlChange = onBaseUrlChange,
                onModelChange = onModelChange,
                onApiKeyChange = onApiKeyChange,
                onOpenProviderPicker = onOpenProviderPicker,
                onFetchModels = onFetchModels,
                onOpenModelPicker = onOpenModelPicker,
                onSaveConfig = onSaveConfig,
                onTestConnection = onTestConnection,
                onClearApiKey = onClearApiKey,
                onActivateProfile = onActivateEditingProfile,
                onDeleteProfile = { onDeleteProfile(targetEditingId) },
                onConsumeError = onConsumeError,
            )
        }
    }

    if (showAddProviderSheet) {
        AddProviderBottomSheet(
            onSelectPreset = onAddPreset,
            onAddCustom = onAddCustomProvider,
            onDismiss = onDismissAddProviderSheet,
        )
    }

    if (showProviderPicker) {
        AiConfigProviderPickerDialog(
            currentBaseUrl = baseUrl,
            currentProtocol = protocol,
            onSelect = onApplyPreset,
            onDismiss = onDismissProviderPicker,
        )
    }

    if (showModelPicker) {
        AiConfigModelPickerDialog(
            models = availableModels,
            selectedModelId = model,
            onSelect = onSelectModel,
            onDismiss = onDismissModelPicker,
        )
    }
}

@Composable
private fun AiProviderListPage(
    profiles: List<AiProviderProfile>,
    activeProfileId: String,
    error: String?,
    onBack: () -> Unit,
    onOpenAddProviderSheet: () -> Unit,
    onOpenProviderDetail: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onConsumeError: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var revealedProfileId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteProfile by remember { mutableStateOf<AiProviderProfile?>(null) }
    val canDelete = profiles.size > 1

    LaunchedEffect(canDelete) {
        if (!canDelete) {
            revealedProfileId = null
            pendingDeleteProfile = null
        }
    }

    fun requestDelete(profile: AiProviderProfile) {
        revealedProfileId = null
        if (!profile.config.isConfigured) {
            onDeleteProfile(profile.id)
        } else {
            pendingDeleteProfile = profile
        }
    }

    val filteredProfiles = remember(profiles, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            profiles
        } else {
            profiles.filter { profile ->
                profile.name.contains(needle, ignoreCase = true) ||
                    profile.config.model.contains(needle, ignoreCase = true) ||
                    profile.config.baseUrl.contains(needle, ignoreCase = true)
            }
        }
    }

    GlassScaffold(
        modifier = Modifier.testTag(AiConfigTags.LIST_SCREEN),
        topBar = {
            GlassTopBar(
                title = "AI 服务商",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    GlassTopBarIconButton(
                        onClick = onOpenAddProviderSheet,
                        modifier = Modifier.testTag(AiConfigTags.ADD_PROVIDER_BUTTON),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "添加服务商")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    end = 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (error != null) {
                    item(key = "error") {
                        AiConfigErrorBanner(message = error, onConsume = onConsumeError)
                    }
                }

                if (profiles.size >= 5) {
                    item(key = "search") {
                        LiquidOutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索服务商") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                items(filteredProfiles, key = { it.id }) { profile ->
                    val isRevealed = revealedProfileId == profile.id
                    SwipeableAiProviderListCard(
                        profile = profile,
                        isActive = profile.id == activeProfileId,
                        canDelete = canDelete,
                        revealed = isRevealed,
                        onDragStart = {
                            if (revealedProfileId != null && revealedProfileId != profile.id) {
                                revealedProfileId = null
                            }
                        },
                        onRevealChange = { next ->
                            revealedProfileId = if (next) profile.id else if (isRevealed) null else revealedProfileId
                        },
                        onOpenDetail = {
                            if (revealedProfileId != null) {
                                revealedProfileId = null
                            } else {
                                onOpenProviderDetail(profile.id)
                            }
                        },
                        onActivate = {
                            revealedProfileId = null
                            onSelectProfile(profile.id)
                        },
                        onRequestDelete = { requestDelete(profile) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    pendingDeleteProfile?.let { target ->
        LiquidAlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            title = { Text("删除服务商", style = MaterialTheme.typography.titleLarge) },
            text = { Text(target.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteProfile = null
                        onDeleteProfile(target.id)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProfile = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SwipeableAiProviderListCard(
    profile: AiProviderProfile,
    isActive: Boolean,
    canDelete: Boolean,
    revealed: Boolean,
    onDragStart: () -> Unit,
    onRevealChange: (Boolean) -> Unit,
    onOpenDetail: () -> Unit,
    onActivate: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val motionEnabled = LocalMotionEnabled.current
    val scope = rememberCoroutineScope()
    val revealWidthPx = remember(density) { with(density) { 84.dp.toPx() } }
    var cardWidthPx by remember { mutableFloatStateOf(0f) }
    val offsetX = remember(profile.id) { Animatable(0f) }

    LaunchedEffect(revealed, motionEnabled, revealWidthPx) {
        val target = if (revealed && canDelete) -revealWidthPx else 0f
        if (motionEnabled) {
            offsetX.animateTo(target, tween(AppMotion.Release))
        } else {
            offsetX.snapTo(target)
        }
    }

    val shape = RoundedCornerShape(20.dp)
    val maxDragPx = ((cardWidthPx * 0.65f).takeIf { it > 0f } ?: (revealWidthPx * 2.2f))
        .coerceAtLeast(revealWidthPx * 1.8f)

    val draggableState = rememberDraggableState { delta ->
        if (!canDelete) return@rememberDraggableState
        val current = offsetX.value
        val adjusted = if (current <= -revealWidthPx && delta < 0f) delta * 0.45f else delta
        val next = (current + adjusted).coerceIn(-maxDragPx, 0f)
        scope.launch { offsetX.snapTo(next) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { cardWidthPx = it.width.toFloat() }
            .testTag("${AiConfigTags.PROVIDER_CARD_PREFIX}${profile.id}")
            .semantics {
                if (canDelete) {
                    customActions = listOf(
                        CustomAccessibilityAction("删除") {
                            onRequestDelete()
                            true
                        },
                    )
                }
            },
    ) {
        if (canDelete && offsetX.value < -1f) {
            val progress = (-offsetX.value / revealWidthPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.82f + 0.14f * progress)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    modifier = Modifier
                        .width(84.dp)
                        .fillMaxHeight()
                        .testTag("${AiConfigTags.SWIPE_DELETE_BUTTON_PREFIX}${profile.id}")
                        .clickable(role = Role.Button, onClick = onRequestDelete),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }

        AiProviderListCard(
            profile = profile,
            isActive = isActive,
            onOpenDetail = onOpenDetail,
            onActivate = onActivate,
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    enabled = canDelete,
                    onDragStarted = { onDragStart() },
                    onDragStopped = { velocity ->
                        val fullSwipeThreshold = (cardWidthPx * 0.45f).coerceAtLeast(revealWidthPx * 1.6f)
                        when {
                            offsetX.value <= -fullSwipeThreshold ||
                                (velocity < -1400f && offsetX.value < -revealWidthPx * 0.8f) -> {
                                onRevealChange(false)
                                scope.launch {
                                    if (motionEnabled) {
                                        offsetX.animateTo(0f, tween(AppMotion.Release))
                                    } else {
                                        offsetX.snapTo(0f)
                                    }
                                }
                                onRequestDelete()
                            }

                            offsetX.value <= -revealWidthPx * 0.45f || velocity < -600f -> {
                                onRevealChange(true)
                            }

                            else -> {
                                onRevealChange(false)
                                scope.launch {
                                    if (motionEnabled) {
                                        offsetX.animateTo(0f, tween(AppMotion.Release))
                                    } else {
                                        offsetX.snapTo(0f)
                                    }
                                }
                            }
                        }
                    },
                ),
        )
    }
}

@Composable
private fun AiProviderListCard(
    profile: AiProviderProfile,
    isActive: Boolean,
    onOpenDetail: () -> Unit,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val primary = MaterialTheme.colorScheme.primary
    val borderModifier = if (isActive) {
        Modifier.border(1.2.dp, primary.copy(alpha = 0.6f), shape)
    } else {
        Modifier
    }

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .then(borderModifier)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onOpenDetail),
        shape = shape,
        material = GlassMaterial.CONTENT,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AiProviderAvatar(
                name = profile.name,
                baseUrl = profile.config.baseUrl,
                protocol = profile.config.protocol,
                size = 42.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profile.config.model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusPill(
                        text = profile.config.protocol.shortLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (profile.config.isConfigured) {
                        StatusPill(
                            text = "已配置",
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        StatusPill(
                            text = "未配置",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isActive) {
                    StatusPill(
                        text = "当前",
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        horizontalPadding = 10.dp,
                        verticalPadding = 4.dp,
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onActivate),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "启用",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AiProviderDetailPage(
    editingProfileId: String,
    isEditingActive: Boolean,
    canDelete: Boolean,
    profileName: String,
    protocol: AiApiProtocol,
    baseUrl: String,
    model: String,
    apiKey: String,
    configConfigured: Boolean,
    testingConnection: Boolean,
    connectionMessage: String?,
    fetchingModels: Boolean,
    availableModels: List<AiModelInfo>,
    error: String?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onProfileNameChange: (String) -> Unit,
    onProtocolChange: (AiApiProtocol) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onOpenProviderPicker: () -> Unit,
    onFetchModels: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onSaveConfig: () -> Unit,
    onTestConnection: () -> Unit,
    onClearApiKey: () -> Unit,
    onActivateProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onConsumeError: () -> Unit,
) {
    var showApiKey by remember(editingProfileId) { mutableStateOf(false) }
    var showDeleteConfirm by remember(editingProfileId) { mutableStateOf(false) }
    val endpointLabel = remember(baseUrl, protocol) { buildEndpointPreview(baseUrl, protocol) }

    GlassScaffold(
        modifier = modifier.testTag(AiConfigTags.DETAIL_SCREEN),
        topBar = {
            GlassTopBar(
                title = profileName.trim().ifEmpty { AiProviderProfile.DEFAULT_NAME },
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (canDelete) {
                        GlassTopBarIconButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.testTag(AiConfigTags.DELETE_PROFILE_BUTTON),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                error?.let { msg ->
                    AiConfigErrorBanner(message = msg, onConsume = onConsumeError)
                }

                if (canDelete) {
                    GlassSurface(
                        shape = RoundedCornerShape(18.dp),
                        material = GlassMaterial.CONTENT,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "当前使用",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            LiquidToggle(
                                checked = isEditingActive,
                                enabled = !isEditingActive,
                                onCheckedChange = { checked ->
                                    if (checked) onActivateProfile()
                                },
                            )
                        }
                    }
                }

                SectionCard(title = "基本") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LiquidOutlinedTextField(
                            value = profileName,
                            onValueChange = onProfileNameChange,
                            label = { Text("名称") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AiConfigTags.PROFILE_NAME_INPUT),
                        )

                        FlowRow(
                            modifier = Modifier.testTag(AiConfigTags.PROTOCOL_SECTION),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AiApiProtocol.entries.forEach { item ->
                                LiquidFilterChip(
                                    selected = protocol == item,
                                    onClick = { onProtocolChange(item) },
                                    label = { Text(item.displayName) },
                                )
                            }
                        }
                    }
                }

                SectionCard(
                    title = "接口与模型",
                    action = {
                        TextButton(
                            onClick = onOpenProviderPicker,
                            modifier = Modifier.testTag(AiConfigTags.PROVIDER_PICKER_BUTTON),
                        ) {
                            Text("套用预设")
                        }
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LiquidOutlinedTextField(
                            value = baseUrl,
                            onValueChange = onBaseUrlChange,
                            label = { Text("Base URL") },
                            placeholder = {
                                Text(
                                    if (protocol == AiApiProtocol.ANTHROPIC_MESSAGES) {
                                        "https://api.anthropic.com/v1"
                                    } else {
                                        "https://api.openai.com/v1"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AiConfigTags.BASE_URL_INPUT),
                        )

                        Text(
                            text = endpointLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(AiConfigTags.ENDPOINT_PREVIEW),
                        )

                        if ('{' in baseUrl || '}' in baseUrl) {
                            Text(
                                text = "请替换地址中的占位符",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        LiquidOutlinedTextField(
                            value = apiKey,
                            onValueChange = onApiKeyChange,
                            label = { Text("API Key") },
                            placeholder = {
                                Text(
                                    if (protocol == AiApiProtocol.ANTHROPIC_MESSAGES) {
                                        "sk-ant-..."
                                    } else {
                                        "sk-..."
                                    },
                                )
                            },
                            singleLine = true,
                            visualTransformation = if (showApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                TextButton(onClick = { showApiKey = !showApiKey }) {
                                    Text(if (showApiKey) "隐藏" else "显示")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AiConfigTags.API_KEY_INPUT),
                        )

                        LiquidOutlinedTextField(
                            value = model,
                            onValueChange = onModelChange,
                            label = { Text("模型") },
                            placeholder = { Text("gpt-4o-mini") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AiConfigTags.MODEL_INPUT),
                        )

                        val canFetchModels = !fetchingModels &&
                            baseUrl.isNotBlank() &&
                            apiKey.isNotBlank() &&
                            '{' !in baseUrl &&
                            '}' !in baseUrl
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LiquidOutlinedButton(
                                onClick = onFetchModels,
                                enabled = canFetchModels,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(AiConfigTags.FETCH_MODELS_BUTTON),
                            ) {
                                if (fetchingModels) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("获取中...")
                                } else {
                                    Text("拉取模型")
                                }
                            }
                            if (availableModels.isNotEmpty()) {
                                LiquidOutlinedButton(
                                    onClick = onOpenModelPicker,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("选择模型 (${availableModels.size})")
                                }
                            }
                        }
                    }
                }

                connectionMessage?.let { msg ->
                    GlassSurface(
                        shape = RoundedCornerShape(14.dp),
                        material = GlassMaterial.CONTENT,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LiquidButton(
                        onClick = onSaveConfig,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(AiConfigTags.SAVE_BUTTON),
                    ) { Text("保存") }
                    LiquidOutlinedButton(
                        onClick = onTestConnection,
                        enabled = !testingConnection,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(AiConfigTags.TEST_BUTTON),
                    ) {
                        Text(if (testingConnection) "测试中..." else "测试连接")
                    }
                }

                if (apiKey.isNotBlank() || configConfigured) {
                    TextButton(
                        onClick = onClearApiKey,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("清除 Key") }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        LiquidAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除服务商", style = MaterialTheme.typography.titleLarge) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteProfile()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AddProviderBottomSheet(
    onSelectPreset: (AiProviderPreset) -> Unit,
    onAddCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            AiProviderPreset.BuiltIns
        } else {
            AiProviderPreset.BuiltIns.filter { preset ->
                preset.label.contains(needle, ignoreCase = true) ||
                    preset.id.contains(needle, ignoreCase = true) ||
                    preset.baseUrl.contains(needle, ignoreCase = true) ||
                    preset.protocol.displayName.contains(needle, ignoreCase = true)
            }
        }
    }

    LiquidModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "添加服务商",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                LiquidOutlinedButton(
                    onClick = onAddCustom,
                    modifier = Modifier.testTag(AiConfigTags.ADD_CUSTOM_PROVIDER_BUTTON),
                ) {
                    Text("自定义")
                }
            }

            LiquidOutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索服务商") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            if (filtered.isEmpty()) {
                Text(
                    text = "无匹配项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { preset ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(role = Role.Button) { onSelectPreset(preset) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AiProviderAvatar(
                                    name = preset.label,
                                    baseUrl = preset.baseUrl,
                                    protocol = preset.protocol,
                                    size = 36.dp,
                                    presetId = preset.id,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = preset.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = preset.baseUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                StatusPill(
                                    text = preset.protocol.shortLabel,
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiConfigErrorBanner(
    message: String,
    onConsume: () -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(12.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onConsume) { Text("知道了") }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    fontWeight: FontWeight = FontWeight.Normal,
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 3.dp,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = fontWeight,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            maxLines = 1,
        )
    }
}

internal fun buildEndpointPreview(
    rawBaseUrl: String,
    protocol: AiApiProtocol,
): String {
    val suffix = when (protocol) {
        AiApiProtocol.OPENAI_CHAT -> "/chat/completions"
        AiApiProtocol.OPENAI_RESPONSES -> "/responses"
        AiApiProtocol.ANTHROPIC_MESSAGES -> "/messages"
    }
    val trimmed = rawBaseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return "POST $suffix"
    val path = runCatching { URI(trimmed).path }.getOrNull()?.trimEnd('/').orEmpty()
    val cleanPath = when {
        path.endsWith("/chat/completions", ignoreCase = true) ->
            path.dropLast("/chat/completions".length).trimEnd('/')
        path.endsWith("/responses", ignoreCase = true) ->
            path.dropLast("/responses".length).trimEnd('/')
        path.endsWith("/messages", ignoreCase = true) ->
            path.dropLast("/messages".length).trimEnd('/')
        else -> path
    }
    val finalPath = if (protocol == AiApiProtocol.ANTHROPIC_MESSAGES && cleanPath.isEmpty()) {
        "/v1/messages"
    } else {
        "$cleanPath$suffix"
    }
    return "POST $finalPath"
}

@Composable
private fun AiConfigProviderPickerDialog(
    currentBaseUrl: String,
    currentProtocol: AiApiProtocol,
    onSelect: (AiProviderPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            AiProviderPreset.BuiltIns
        } else {
            AiProviderPreset.BuiltIns.filter { preset ->
                preset.label.contains(needle, ignoreCase = true) ||
                    preset.id.contains(needle, ignoreCase = true) ||
                    preset.baseUrl.contains(needle, ignoreCase = true) ||
                    preset.protocol.displayName.contains(needle, ignoreCase = true)
            }
        }
    }
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择服务商", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidOutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索服务商") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = "无匹配项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id }) { preset ->
                            val selected = currentBaseUrl.trim().equals(preset.baseUrl, ignoreCase = true) &&
                                currentProtocol == preset.protocol
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(preset) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                AiProviderAvatar(
                                    name = preset.label,
                                    baseUrl = preset.baseUrl,
                                    protocol = preset.protocol,
                                    size = 28.dp,
                                    presetId = preset.id,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = preset.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = preset.protocol.shortLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (selected) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        text = preset.baseUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun AiConfigModelPickerDialog(
    models: List<AiModelInfo>,
    selectedModelId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            models
        } else {
            models.filter {
                it.id.contains(needle, ignoreCase = true) || it.ownedBy.contains(needle, ignoreCase = true)
            }
        }
    }
    LiquidAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("可用模型 (${models.size})", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidOutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = "无匹配项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id }) { info ->
                            val selected = info.id == selectedModelId.trim()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(info.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = info.id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
