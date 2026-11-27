package com.gfs.iyango.core.domain

/**
 * AI 接口有线协议类型。
 */
enum class AiApiProtocol(
    val displayName: String,
    val shortLabel: String,
) {
    OPENAI_CHAT("OpenAI Chat", "Chat"),
    OPENAI_RESPONSES("OpenAI Responses", "Responses"),
    ANTHROPIC_MESSAGES("Anthropic", "Anthropic"),
    ;

    companion object {
        fun fromStorage(raw: String?): AiApiProtocol =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OPENAI_CHAT
    }
}

/**
 * 内置 AI 服务商预设（仅辅助填入协议、地址与默认模型名，凭据由用户自持）。
 */
data class AiProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsVision: Boolean,
    val requiresPlaceholder: Boolean = false,
    val protocol: AiApiProtocol = AiApiProtocol.OPENAI_CHAT,
) {
    companion object {
        val QuickIds: Set<String> = setOf(
            "openai",
            "openai-responses",
            "anthropic",
            "deepseek",
            "openrouter",
            "qwen-token-plan-cn",
            "zai-coding-cn",
            "xiaomi",
        )

        val BuiltIns: List<AiProviderPreset> = listOf(
            AiProviderPreset(
                id = "openai",
                label = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o-mini",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "openai-responses",
                label = "OpenAI (Responses)",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-5-mini",
                supportsVision = true,
                protocol = AiApiProtocol.OPENAI_RESPONSES,
            ),
            AiProviderPreset(
                id = "openai-codex",
                label = "OpenAI Codex",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-5-codex",
                supportsVision = true,
                protocol = AiApiProtocol.OPENAI_RESPONSES,
            ),
            AiProviderPreset(
                id = "deepseek",
                label = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-chat",
                supportsVision = false,
            ),
            AiProviderPreset(
                id = "openrouter",
                label = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                defaultModel = "openai/gpt-4o-mini",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "anthropic",
                label = "Anthropic",
                baseUrl = "https://api.anthropic.com/v1",
                defaultModel = "claude-sonnet-4-20250514",
                supportsVision = true,
                protocol = AiApiProtocol.ANTHROPIC_MESSAGES,
            ),
            AiProviderPreset(
                id = "gemini",
                label = "Google Gemini",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                defaultModel = "gemini-2.5-flash",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "siliconflow",
                label = "硅基流动",
                baseUrl = "https://api.siliconflow.cn/v1",
                defaultModel = "Qwen/Qwen2.5-VL-72B-Instruct",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "moonshotai-cn",
                label = "Moonshot AI (国内)",
                baseUrl = "https://api.moonshot.cn/v1",
                defaultModel = "kimi-k2.5",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "moonshotai",
                label = "Moonshot AI (国际)",
                baseUrl = "https://api.moonshot.ai/v1",
                defaultModel = "kimi-k2.5",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "kimi-coding",
                label = "Kimi Coding",
                baseUrl = "https://api.kimi.com/coding/v1",
                defaultModel = "kimi-for-coding",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "qwen-token-plan-cn",
                label = "通义千问 Token Plan (国内)",
                baseUrl = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                defaultModel = "qwen-vl-max",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "qwen-token-plan-individual",
                label = "通义千问 Token Plan (国际)",
                baseUrl = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
                defaultModel = "qwen-vl-max",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "zai",
                label = "Z.AI / 智谱 (国际)",
                baseUrl = "https://api.z.ai/api/paas/v4",
                defaultModel = "glm-4.6v",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "zai-coding-cn",
                label = "智谱 Coding (国内)",
                baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
                defaultModel = "glm-4.7",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "minimax-cn",
                label = "MiniMax (国内)",
                baseUrl = "https://api.minimaxi.com/v1",
                defaultModel = "MiniMax-M1",
                supportsVision = false,
            ),
            AiProviderPreset(
                id = "minimax",
                label = "MiniMax (国际)",
                baseUrl = "https://api.minimax.io/v1",
                defaultModel = "MiniMax-M1",
                supportsVision = false,
            ),
            AiProviderPreset(
                id = "xiaomi",
                label = "小米 MiMo",
                baseUrl = "https://api.xiaomimimo.com/v1",
                defaultModel = "mimo-v2-flash",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "xiaomi-token-plan-cn",
                label = "小米 Token Plan (国内)",
                baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
                defaultModel = "mimo-v2-flash",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "xiaomi-token-plan-sgp",
                label = "小米 Token Plan (新加坡)",
                baseUrl = "https://token-plan-sgp.xiaomimimo.com/v1",
                defaultModel = "mimo-v2-flash",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "xiaomi-token-plan-ams",
                label = "小米 Token Plan (欧洲)",
                baseUrl = "https://token-plan-ams.xiaomimimo.com/v1",
                defaultModel = "mimo-v2-flash",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "ant-ling",
                label = "蚂蚁百灵 (Ant Ling)",
                baseUrl = "https://api.ant-ling.com/v1",
                defaultModel = "ling-1t",
                supportsVision = false,
            ),
            AiProviderPreset(
                id = "xai",
                label = "xAI (Grok)",
                baseUrl = "https://api.x.ai/v1",
                defaultModel = "grok-2-vision-latest",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "mistral",
                label = "Mistral AI",
                baseUrl = "https://api.mistral.ai/v1",
                defaultModel = "pixtral-12b-2409",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "groq",
                label = "Groq",
                baseUrl = "https://api.groq.com/openai/v1",
                defaultModel = "meta-llama/llama-4-scout-17b-16e-instruct",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "together",
                label = "Together AI",
                baseUrl = "https://api.together.ai/v1",
                defaultModel = "meta-llama/Llama-4-Scout-17B-16E-Instruct",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "fireworks",
                label = "Fireworks AI",
                baseUrl = "https://api.fireworks.ai/inference/v1",
                defaultModel = "accounts/fireworks/models/llama4-scout-instruct-basic",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "nvidia",
                label = "NVIDIA NIM",
                baseUrl = "https://integrate.api.nvidia.com/v1",
                defaultModel = "meta/llama-3.2-11b-vision-instruct",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "huggingface",
                label = "Hugging Face Router",
                baseUrl = "https://router.huggingface.co/v1",
                defaultModel = "Qwen/Qwen2.5-VL-7B-Instruct",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "cerebras",
                label = "Cerebras",
                baseUrl = "https://api.cerebras.ai/v1",
                defaultModel = "llama-3.3-70b",
                supportsVision = false,
            ),
            AiProviderPreset(
                id = "baseten",
                label = "Baseten",
                baseUrl = "https://inference.baseten.co/v1",
                defaultModel = "meta-llama/Llama-3.3-70B-Instruct",
                supportsVision = false,
            ),
            AiProviderPreset(
                id = "meta",
                label = "Meta Llama API",
                baseUrl = "https://api.llama.com/compat/v1",
                defaultModel = "Llama-4-Scout-17B-16E-Instruct-FP8",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "opencode",
                label = "OpenCode Zen",
                baseUrl = "https://opencode.ai/zen/v1",
                defaultModel = "gpt-4o-mini",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "opencode-go",
                label = "OpenCode Go",
                baseUrl = "https://opencode.ai/zen/go/v1",
                defaultModel = "gpt-4o-mini",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "vercel-ai-gateway",
                label = "Vercel AI Gateway",
                baseUrl = "https://ai-gateway.vercel.sh/v1",
                defaultModel = "openai/gpt-4o-mini",
                supportsVision = true,
            ),
            AiProviderPreset(
                id = "azure",
                label = "Azure OpenAI",
                baseUrl = "https://{resource}.openai.azure.com/openai/v1",
                defaultModel = "gpt-4o-mini",
                supportsVision = true,
                requiresPlaceholder = true,
            ),
            AiProviderPreset(
                id = "amazon-bedrock",
                label = "Amazon Bedrock",
                baseUrl = "https://bedrock-runtime.{region}.amazonaws.com/openai/v1",
                defaultModel = "anthropic.claude-3-5-sonnet-20241022-v2:0",
                supportsVision = true,
                requiresPlaceholder = true,
            ),
            AiProviderPreset(
                id = "cloudflare-ai-gateway",
                label = "Cloudflare AI Gateway",
                baseUrl = "https://gateway.ai.cloudflare.com/v1/{account_id}/{gateway_id}/compat",
                defaultModel = "openai/gpt-4o-mini",
                supportsVision = true,
                requiresPlaceholder = true,
            ),
            AiProviderPreset(
                id = "cloudflare-workers-ai",
                label = "Cloudflare Workers AI",
                baseUrl = "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1",
                defaultModel = "@cf/meta/llama-3.2-11b-vision-instruct",
                supportsVision = true,
                requiresPlaceholder = true,
            ),
            AiProviderPreset(
                id = "google-vertex",
                label = "Google Vertex AI",
                baseUrl = "https://aiplatform.googleapis.com/v1/projects/{project_id}/locations/{location}/endpoints/openapi",
                defaultModel = "google/gemini-2.5-flash",
                supportsVision = true,
                requiresPlaceholder = true,
            ),
        )

        val QuickBuiltIns: List<AiProviderPreset> by lazy {
            BuiltIns.filter { it.id in QuickIds }
        }
    }
}

/**
 * 远端接口返回的可用模型元信息。
 */
data class AiModelInfo(
    val id: String,
    val ownedBy: String = "",
)

/**
 * BYOK 模型接口配置；[apiKey] 仅经 Keystore 加密存储在本机，不进入设置备份或日志。
 */
data class AiProviderConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val apiKey: String = "",
    val protocol: AiApiProtocol = AiApiProtocol.OPENAI_CHAT,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

/**
 * 可命名、可切换的 AI 接口配置档案。
 */
data class AiProviderProfile(
    val id: String = DEFAULT_ID,
    val name: String = DEFAULT_NAME,
    val config: AiProviderConfig = AiProviderConfig(),
) {
    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "默认配置"
    }
}

/**
 * 本机全部 AI 配置档案快照及当前激活档案 ID。
 */
data class AiProfilesSnapshot(
    val profiles: List<AiProviderProfile> = listOf(AiProviderProfile()),
    val activeProfileId: String = AiProviderProfile.DEFAULT_ID,
) {
    val activeProfile: AiProviderProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull() ?: AiProviderProfile()
}

/**
 * 待识别的课表截图载荷。
 */
data class AiImagePayload(
    val bytes: ByteArray,
    val mimeType: String = "image/jpeg",
    val displayName: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AiImagePayload) return false
        return mimeType == other.mimeType &&
            displayName == other.displayName &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + displayName.hashCode()
        return result
    }
}

/**
 * AI 识别课表的结果。
 */
sealed interface AiImportParseResult {
    data class Ready(
        val scheduleJsonBytes: ByteArray,
        val summary: String = "",
        val warnings: List<String> = emptyList(),
    ) : AiImportParseResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AiImportParseResult.Ready) return false
            return summary == other.summary &&
                warnings == other.warnings &&
                scheduleJsonBytes.contentEquals(other.scheduleJsonBytes)
        }

        override fun hashCode(): Int {
            var result = scheduleJsonBytes.contentHashCode()
            result = 31 * result + summary.hashCode()
            result = 31 * result + warnings.hashCode()
            return result
        }
    }

    data class Failed(val message: String) : AiImportParseResult
}

/**
 * BYOK 接口配置的本机加密存储端口（Android 实现位于 platform:android）。
 */
interface AiConfigStorePort {
    suspend fun load(): AiProviderConfig
    suspend fun save(config: AiProviderConfig)
    suspend fun clearApiKey()

    suspend fun loadProfiles(): AiProfilesSnapshot {
        val current = load()
        return AiProfilesSnapshot(
            profiles = listOf(AiProviderProfile(config = current)),
            activeProfileId = AiProviderProfile.DEFAULT_ID,
        )
    }

    suspend fun saveProfile(
        profile: AiProviderProfile,
        activate: Boolean = true,
    ): AiProfilesSnapshot {
        if (activate) save(profile.config)
        return loadProfiles()
    }

    suspend fun selectProfile(profileId: String): AiProviderConfig = load()

    suspend fun deleteProfile(profileId: String): AiProfilesSnapshot = loadProfiles()
}

/**
 * BYOK 拍图/文本智能识别课表端口（实现位于 data 层，直连用户配置的兼容 OpenAI 接口）。
 */
interface AiScheduleImportPort {
    suspend fun parseSchedule(
        config: AiProviderConfig,
        textInput: String,
        images: List<AiImagePayload> = emptyList(),
        referenceDateIso: String = "",
    ): AiImportParseResult

    suspend fun testConnection(config: AiProviderConfig): Result<String>

    suspend fun fetchModels(config: AiProviderConfig): Result<List<AiModelInfo>>
}
