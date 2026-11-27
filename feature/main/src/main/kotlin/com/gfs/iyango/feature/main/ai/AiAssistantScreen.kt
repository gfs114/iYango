@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gfs.iyango.feature.main.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gfs.iyango.core.designsystem.component.GlassOverlayHeader
import com.gfs.iyango.core.designsystem.component.GlassScaffold
import com.gfs.iyango.core.designsystem.component.GlassTopBar
import com.gfs.iyango.core.designsystem.component.GlassTopBarIconButton
import com.gfs.iyango.core.designsystem.liquidglass.GlassMaterial
import com.gfs.iyango.core.designsystem.liquidglass.GlassSurface
import com.gfs.iyango.core.designsystem.liquidglass.LiquidButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidFilterChip
import com.gfs.iyango.core.designsystem.liquidglass.LiquidModalBottomSheet
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedButton
import com.gfs.iyango.core.designsystem.liquidglass.LiquidOutlinedTextField
import com.gfs.iyango.core.designsystem.theme.LocalBrand
import com.gfs.iyango.core.domain.AiChatRole
import com.gfs.iyango.core.domain.AiChatSessionSummary
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiProviderProfile
import com.gfs.iyango.core.domain.AssistantActionStatus
import com.gfs.iyango.core.domain.AssistantPendingAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object AiAssistantTags {
    const val CONFIG_STRIP = "ai_assistant_config_strip"
    const val UNCONFIGURED_CTA = "ai_assistant_unconfigured_cta"
    const val STARTER_SECTION = "ai_assistant_starter_section"
    const val MESSAGE_LIST = "ai_assistant_message_list"
    const val INPUT_FIELD = "ai_assistant_input_field"
    const val SEND_BUTTON = "ai_assistant_send_button"
}

private val STARTER_PROMPTS = listOf(
    "今天还有什么课",
    "明天有什么课",
    "算一下当前均绩",
    "通识选修还差多少学分",
    "近期考试安排",
    "现在有哪些空教室",
)

@Composable
fun AiAssistantScreen(
    onBack: () -> Unit,
    onOpenAiConfig: () -> Unit,
    viewModel: AiAssistantViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val attachedImages by viewModel.attachedImages.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val progressLabel by viewModel.progressLabel.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val (decoded, skipped) = withContext(Dispatchers.IO) {
                    decodeSelectedAiImages(context, uris, AiAssistantViewModel.MAX_ATTACHED_IMAGES)
                }
                viewModel.addImages(decoded, skipped)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadConfig()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AiAssistantContent(
        config = config,
        messages = messages,
        inputText = inputText,
        sending = sending,
        progressLabel = progressLabel,
        error = error,
        onBack = onBack,
        onOpenAiConfig = onOpenAiConfig,
        onInputTextChange = { viewModel.inputText.value = it },
        onSend = { viewModel.send(null) },
        onSelectStarter = { prompt -> viewModel.send(prompt) },
        onCancelSending = viewModel::cancelSending,
        onConfirmAction = viewModel::confirmAction,
        onCancelAction = viewModel::cancelAction,
        onClearConversation = viewModel::clearConversation,
        onConsumeError = viewModel::consumeError,
        streamingText = streamingText,
        attachedImages = attachedImages,
        onPickImages = { imagePicker.launch("image/*") },
        onRemoveImage = viewModel::removeImage,
        profiles = profiles,
        activeProfileId = activeProfileId,
        onSelectProfile = viewModel::selectProfile,
        sessions = sessions,
        activeSessionId = activeSessionId,
        onSelectSession = viewModel::switchToSession,
        onNewSession = viewModel::createNewSession,
        onDeleteSession = viewModel::deleteSession,
    )
}

@Composable
internal fun AiAssistantContent(
    config: AiProviderConfig,
    messages: List<AssistantChatItem>,
    inputText: String,
    sending: Boolean,
    progressLabel: String,
    error: String?,
    onBack: () -> Unit,
    onOpenAiConfig: () -> Unit,
    onInputTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSelectStarter: (String) -> Unit,
    onCancelSending: () -> Unit,
    onConfirmAction: (String, String) -> Unit,
    onCancelAction: (String, String) -> Unit,
    onClearConversation: () -> Unit,
    onConsumeError: () -> Unit,
    streamingText: String = "",
    attachedImages: List<AiImagePayload> = emptyList(),
    onPickImages: (() -> Unit)? = null,
    onRemoveImage: (Int) -> Unit = {},
    profiles: List<AiProviderProfile> = listOf(AiProviderProfile()),
    activeProfileId: String = AiProviderProfile.DEFAULT_ID,
    onSelectProfile: (String) -> Unit = {},
    sessions: List<AiChatSessionSummary> = emptyList(),
    activeSessionId: String? = null,
    onSelectSession: (String) -> Unit = {},
    onNewSession: () -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var showProfileSheet by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size, sending, streamingText.length) {
        val total = messages.size + if (sending) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    GlassScaffold(
        topBar = {
            GlassTopBar(
                title = "AI 助理",
                navigationIcon = {
                    GlassTopBarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (sessions.isNotEmpty()) {
                        GlassTopBarIconButton(onClick = { showSessionSheet = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "历史对话")
                        }
                    }
                    if (messages.isNotEmpty()) {
                        GlassTopBarIconButton(onClick = onNewSession) {
                            Icon(Icons.Filled.Add, contentDescription = "新对话")
                        }
                        GlassTopBarIconButton(onClick = onClearConversation) {
                            Icon(Icons.Filled.Delete, contentDescription = "清空对话")
                        }
                    }
                    GlassTopBarIconButton(onClick = onOpenAiConfig) {
                        Icon(Icons.Filled.Settings, contentDescription = "AI 接口")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .imePadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name.orEmpty()
                AssistantConnectionBar(
                    config = config,
                    profileName = activeProfileName,
                    hasMultipleProfiles = profiles.size > 1,
                    onConfigure = {
                        if (profiles.size > 1) {
                            showProfileSheet = true
                        } else {
                            onOpenAiConfig()
                        }
                    },
                )

                error?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    GlassSurface(
                        shape = RoundedCornerShape(12.dp),
                        material = GlassMaterial.CONTENT,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onConsumeError) { Text("知道了") }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (!config.isConfigured && messages.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassSurface(
                            shape = RoundedCornerShape(18.dp),
                            material = GlassMaterial.CONTENT,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(AiAssistantTags.UNCONFIGURED_CTA),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "未配置 AI 接口",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                LiquidButton(onClick = onOpenAiConfig) {
                                    Text("配置 AI 接口")
                                }
                            }
                        }
                    }
                } else if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag(AiAssistantTags.STARTER_SECTION),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            STARTER_PROMPTS.forEach { prompt ->
                                LiquidFilterChip(
                                    selected = false,
                                    enabled = config.isConfigured && !sending,
                                    onClick = { onSelectStarter(prompt) },
                                    label = { Text(prompt) },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag(AiAssistantTags.MESSAGE_LIST),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(messages, key = { it.id }) { item ->
                            AssistantMessageRow(
                                item = item,
                                onConfirmAction = { actionId -> onConfirmAction(item.id, actionId) },
                                onCancelAction = { actionId -> onCancelAction(item.id, actionId) },
                            )
                        }
                        if (sending) {
                            item(key = "sending_indicator") {
                                if (streamingText.isNotEmpty()) {
                                    AssistantStreamingBubble(
                                        text = streamingText,
                                        onCancel = onCancelSending,
                                    )
                                } else {
                                    AssistantLoadingRow(
                                        label = progressLabel.ifBlank { "处理中" },
                                        onCancel = onCancelSending,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AssistantInputBar(
                inputText = inputText,
                attachedImages = attachedImages,
                sending = sending,
                configured = config.isConfigured,
                onInputTextChange = onInputTextChange,
                onPickImages = onPickImages,
                onRemoveImage = onRemoveImage,
                onSend = onSend,
            )
        }
    }

    if (showProfileSheet) {
        LiquidModalBottomSheet(onDismissRequest = { showProfileSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassOverlayHeader(
                    title = "切换 AI 接口档案",
                    actions = {
                        TextButton(
                            onClick = {
                                showProfileSheet = false
                                onOpenAiConfig()
                            },
                        ) { Text("管理接口") }
                    },
                )
                profiles.forEach { profile ->
                    val selected = profile.id == activeProfileId
                    GlassSurface(
                        shape = RoundedCornerShape(14.dp),
                        material = GlassMaterial.CONTENT,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectProfile(profile.id)
                                showProfileSheet = false
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Text(
                                    text = "${profile.config.protocol.shortLabel}  ${profile.config.model}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selected) {
                                Text(
                                    text = "当前",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LocalBrand.current.accent,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showSessionSheet) {
        LiquidModalBottomSheet(onDismissRequest = { showSessionSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassOverlayHeader(
                    title = "对话历史",
                    actions = {
                        TextButton(
                            onClick = {
                                showSessionSheet = false
                                onNewSession()
                            },
                        ) { Text("新对话") }
                    },
                )
                sessions.forEach { session ->
                    val selected = session.id == activeSessionId
                    GlassSurface(
                        shape = RoundedCornerShape(14.dp),
                        material = GlassMaterial.CONTENT,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectSession(session.id)
                                showSessionSheet = false
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${session.messageCount} 条消息",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDeleteSession(session.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "删除会话",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun AssistantConnectionBar(
    config: AiProviderConfig,
    profileName: String,
    hasMultipleProfiles: Boolean,
    onConfigure: () -> Unit,
) {
    val label = if (config.isConfigured) {
        listOf(
            profileName.takeIf { it.isNotBlank() && hasMultipleProfiles },
            config.protocol.shortLabel,
            config.model,
        ).filterNotNull().joinToString("  ")
    } else {
        "未配置 AI 接口"
    }
    GlassSurface(
        shape = RoundedCornerShape(14.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConfigure)
            .testTag(AiAssistantTags.CONFIG_STRIP),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (config.isConfigured) "切换" else "前往配置",
                style = MaterialTheme.typography.labelMedium,
                color = LocalBrand.current.accent,
            )
        }
    }
}

@Composable
private fun AssistantMessageRow(
    item: AssistantChatItem,
    onConfirmAction: (String) -> Unit,
    onCancelAction: (String) -> Unit,
) {
    val isUser = item.role == AiChatRole.USER
    val brand = LocalBrand.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!isUser && item.toolTraces.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.toolTraces.forEach { trace ->
                    GlassSurface(
                        shape = RoundedCornerShape(8.dp),
                        material = GlassMaterial.CONTENT,
                    ) {
                        Text(
                            text = trace.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (trace.success) {
                                brand.accent
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        GlassSurface(
            shape = RoundedCornerShape(16.dp),
            material = GlassMaterial.CONTENT,
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isUser && item.imageCount > 0) {
                    Text(
                        text = "已附带 ${item.imageCount} 张图片",
                        style = MaterialTheme.typography.labelSmall,
                        color = brand.accent,
                    )
                }
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        item.isError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        item.pendingActions.forEach { action ->
            AssistantActionCard(
                action = action,
                onConfirm = { onConfirmAction(action.id) },
                onCancel = { onCancelAction(action.id) },
            )
        }
    }
}

@Composable
private fun AssistantStreamingBubble(
    text: String,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GlassSurface(
            shape = RoundedCornerShape(16.dp),
            material = GlassMaterial.CONTENT,
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        TextButton(onClick = onCancel) {
            Text("停止生成")
        }
    }
}

@Composable
private fun AssistantActionCard(
    action: AssistantPendingAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        material = GlassMaterial.CONTENT,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                action.summaryLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (action.status) {
                AssistantActionStatus.PENDING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LiquidButton(
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("确认执行")
                        }
                        LiquidOutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("取消")
                        }
                    }
                }
                AssistantActionStatus.CONFIRMED -> {
                    Text(
                        text = action.resultMessage.ifBlank { "已执行" },
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalBrand.current.accent,
                    )
                }
                AssistantActionStatus.CANCELLED -> {
                    Text(
                        text = "已取消",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistantActionStatus.FAILED -> {
                    Text(
                        text = action.resultMessage.ifBlank { "执行失败" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantLoadingRow(
    label: String,
    onCancel: () -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(14.dp),
        material = GlassMaterial.CONTENT,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlassTopBarIconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "停止",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantInputBar(
    inputText: String,
    attachedImages: List<AiImagePayload>,
    sending: Boolean,
    configured: Boolean,
    onInputTextChange: (String) -> Unit,
    onPickImages: (() -> Unit)?,
    onRemoveImage: (Int) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 840.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (attachedImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachedImages.forEachIndexed { index, img ->
                    GlassSurface(
                        shape = RoundedCornerShape(10.dp),
                        material = GlassMaterial.CONTENT,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = img.displayName.ifBlank { "图片 ${index + 1}" },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp),
                            )
                            IconButton(
                                onClick = { onRemoveImage(index) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "移除图片",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onPickImages != null) {
                GlassTopBarIconButton(
                    onClick = onPickImages,
                    enabled = configured && !sending,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加图片")
                }
            }
            LiquidOutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                placeholder = { Text("输入问题或指令") },
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .testTag(AiAssistantTags.INPUT_FIELD),
            )
            LiquidButton(
                onClick = onSend,
                enabled = configured && !sending && (inputText.isNotBlank() || attachedImages.isNotEmpty()),
                modifier = Modifier
                    .height(48.dp)
                    .testTag(AiAssistantTags.SEND_BUTTON),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}
