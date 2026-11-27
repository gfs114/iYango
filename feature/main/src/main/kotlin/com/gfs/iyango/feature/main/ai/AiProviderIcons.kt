package com.gfs.iyango.feature.main.ai

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gfs.iyango.core.designsystem.liquidglass.isGlassLightTheme
import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.feature.main.R
import kotlin.math.abs

internal sealed interface AiProviderIconSpec {
    data class Mono(@DrawableRes val resId: Int) : AiProviderIconSpec
    data class Color(
        @DrawableRes val resId: Int,
        val preferDarkBackdropInLight: Boolean = false,
    ) : AiProviderIconSpec

    data class Adaptive(
        @DrawableRes val lightResId: Int,
        @DrawableRes val darkResId: Int,
    ) : AiProviderIconSpec
}

private val OpenAiSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_openai)
private val OpenAiCodexSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_openai_codex)
private val DeepSeekSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_deepseek)
private val OpenRouterSpec = AiProviderIconSpec.Color(
    resId = R.drawable.ic_ai_provider_openrouter,
    preferDarkBackdropInLight = true,
)
private val AnthropicSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_anthropic)
private val GeminiSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_gemini)
private val SiliconFlowSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_siliconflow)
private val MoonshotSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_moonshot)
private val KimiCodingSpec = AiProviderIconSpec.Adaptive(
    lightResId = R.drawable.ic_ai_provider_kimi_coding_light,
    darkResId = R.drawable.ic_ai_provider_kimi_coding_dark,
)
private val QwenSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_qwen)
private val ZaiSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_zai)
private val ZhipuSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_zhipu)
private val MiniMaxSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_minimax)
private val XiaomiSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_xiaomi)
private val AntLingSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_ant_ling)
private val XaiSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_xai)
private val MistralSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_mistral)
private val GroqSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_groq)
private val TogetherSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_together)
private val FireworksSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_fireworks)
private val NvidiaSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_nvidia)
private val HuggingFaceSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_huggingface)
private val CerebrasSpec = AiProviderIconSpec.Adaptive(
    lightResId = R.drawable.ic_ai_provider_cerebras_light,
    darkResId = R.drawable.ic_ai_provider_cerebras_dark,
)
private val BasetenSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_baseten)
private val MetaSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_meta)
private val OpenCodeSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_opencode)
private val VercelSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_vercel)
private val AzureSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_azure)
private val BedrockSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_amazon_bedrock)
private val CloudflareSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_cloudflare)
private val GoogleVertexSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_google_vertex)

private val BailianSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_bailian)
private val DoubaoSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_doubao)
private val VolcengineSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_volcengine)
private val HunyuanSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_hunyuan)
private val QianfanSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_qianfan)
private val StepFunSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_stepfun)
private val SenseNovaSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_sensenova)
private val ZenMuxSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_zenmux)
private val CohereSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_cohere)
private val YiSpec = AiProviderIconSpec.Adaptive(
    lightResId = R.drawable.ic_ai_provider_yi_light,
    darkResId = R.drawable.ic_ai_provider_yi_dark,
)
private val ModelScopeSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_modelscope)
private val PpioSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_ppio)
private val Ai21Spec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_ai21)
private val AiHubMixSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_aihubmix)
private val BaichuanSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_baichuan)
private val DeepInfraSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_deepinfra)
private val GiteeSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_gitee)
private val GitHubSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_github)
private val HyperbolicSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_hyperbolic)
private val InfinigenceSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_infinigence)
private val NebiusSpec = AiProviderIconSpec.Mono(R.drawable.ic_ai_provider_nebius)
private val NovitaSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_novita)
private val PerplexitySpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_perplexity)
private val SambaNovaSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_sambanova)
private val SparkSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_spark)
private val UpstageSpec = AiProviderIconSpec.Color(R.drawable.ic_ai_provider_upstage)

private val PresetIdIconMap: Map<String, AiProviderIconSpec> = mapOf(
    "openai" to OpenAiSpec,
    "openai-responses" to OpenAiSpec,
    "openai-codex" to OpenAiCodexSpec,
    "deepseek" to DeepSeekSpec,
    "openrouter" to OpenRouterSpec,
    "anthropic" to AnthropicSpec,
    "gemini" to GeminiSpec,
    "siliconflow" to SiliconFlowSpec,
    "moonshotai-cn" to MoonshotSpec,
    "moonshotai" to MoonshotSpec,
    "kimi-coding" to KimiCodingSpec,
    "qwen-token-plan-cn" to QwenSpec,
    "qwen-token-plan-individual" to QwenSpec,
    "zai" to ZaiSpec,
    "zai-coding-cn" to ZhipuSpec,
    "minimax-cn" to MiniMaxSpec,
    "minimax" to MiniMaxSpec,
    "xiaomi" to XiaomiSpec,
    "xiaomi-token-plan-cn" to XiaomiSpec,
    "xiaomi-token-plan-sgp" to XiaomiSpec,
    "xiaomi-token-plan-ams" to XiaomiSpec,
    "ant-ling" to AntLingSpec,
    "xai" to XaiSpec,
    "mistral" to MistralSpec,
    "groq" to GroqSpec,
    "together" to TogetherSpec,
    "fireworks" to FireworksSpec,
    "nvidia" to NvidiaSpec,
    "huggingface" to HuggingFaceSpec,
    "cerebras" to CerebrasSpec,
    "baseten" to BasetenSpec,
    "meta" to MetaSpec,
    "opencode" to OpenCodeSpec,
    "opencode-go" to OpenCodeSpec,
    "vercel-ai-gateway" to VercelSpec,
    "azure" to AzureSpec,
    "amazon-bedrock" to BedrockSpec,
    "cloudflare-ai-gateway" to CloudflareSpec,
    "cloudflare-workers-ai" to CloudflareSpec,
    "google-vertex" to GoogleVertexSpec,
    // Candidates
    "zhipu" to ZhipuSpec,
    "dashscope" to BailianSpec,
    "bailian" to BailianSpec,
    "volcengine-ark" to DoubaoSpec,
    "doubao" to DoubaoSpec,
    "volcengine" to VolcengineSpec,
    "tencent-tokenhub" to HunyuanSpec,
    "hunyuan" to HunyuanSpec,
    "qianfan" to QianfanSpec,
    "stepfun" to StepFunSpec,
    "sensenova" to SenseNovaSpec,
    "zenmux" to ZenMuxSpec,
    "cohere" to CohereSpec,
    "yi" to YiSpec,
    "modelscope" to ModelScopeSpec,
    "ppio" to PpioSpec,
    "ai21" to Ai21Spec,
    "aihubmix" to AiHubMixSpec,
    "baichuan" to BaichuanSpec,
    "deepinfra" to DeepInfraSpec,
    "gitee" to GiteeSpec,
    "github" to GitHubSpec,
    "hyperbolic" to HyperbolicSpec,
    "infinigence" to InfinigenceSpec,
    "nebius" to NebiusSpec,
    "novita" to NovitaSpec,
    "perplexity" to PerplexitySpec,
    "sambanova" to SambaNovaSpec,
    "spark" to SparkSpec,
    "upstage" to UpstageSpec,
)

internal val AllAiProviderDrawableResIds: List<Int> = listOf(
    R.drawable.ic_ai_provider_ai21,
    R.drawable.ic_ai_provider_aihubmix,
    R.drawable.ic_ai_provider_amazon_bedrock,
    R.drawable.ic_ai_provider_ant_ling,
    R.drawable.ic_ai_provider_anthropic,
    R.drawable.ic_ai_provider_azure,
    R.drawable.ic_ai_provider_baichuan,
    R.drawable.ic_ai_provider_bailian,
    R.drawable.ic_ai_provider_baseten,
    R.drawable.ic_ai_provider_cerebras_dark,
    R.drawable.ic_ai_provider_cerebras_light,
    R.drawable.ic_ai_provider_cloudflare,
    R.drawable.ic_ai_provider_cohere,
    R.drawable.ic_ai_provider_deepinfra,
    R.drawable.ic_ai_provider_deepseek,
    R.drawable.ic_ai_provider_doubao,
    R.drawable.ic_ai_provider_fireworks,
    R.drawable.ic_ai_provider_gemini,
    R.drawable.ic_ai_provider_gitee,
    R.drawable.ic_ai_provider_github,
    R.drawable.ic_ai_provider_google_vertex,
    R.drawable.ic_ai_provider_groq,
    R.drawable.ic_ai_provider_huggingface,
    R.drawable.ic_ai_provider_hunyuan,
    R.drawable.ic_ai_provider_hyperbolic,
    R.drawable.ic_ai_provider_infinigence,
    R.drawable.ic_ai_provider_kimi_coding_dark,
    R.drawable.ic_ai_provider_kimi_coding_light,
    R.drawable.ic_ai_provider_meta,
    R.drawable.ic_ai_provider_minimax,
    R.drawable.ic_ai_provider_mistral,
    R.drawable.ic_ai_provider_modelscope,
    R.drawable.ic_ai_provider_moonshot,
    R.drawable.ic_ai_provider_nebius,
    R.drawable.ic_ai_provider_novita,
    R.drawable.ic_ai_provider_nvidia,
    R.drawable.ic_ai_provider_openai,
    R.drawable.ic_ai_provider_openai_codex,
    R.drawable.ic_ai_provider_opencode,
    R.drawable.ic_ai_provider_openrouter,
    R.drawable.ic_ai_provider_perplexity,
    R.drawable.ic_ai_provider_ppio,
    R.drawable.ic_ai_provider_qianfan,
    R.drawable.ic_ai_provider_qwen,
    R.drawable.ic_ai_provider_sambanova,
    R.drawable.ic_ai_provider_sensenova,
    R.drawable.ic_ai_provider_siliconflow,
    R.drawable.ic_ai_provider_spark,
    R.drawable.ic_ai_provider_stepfun,
    R.drawable.ic_ai_provider_together,
    R.drawable.ic_ai_provider_upstage,
    R.drawable.ic_ai_provider_vercel,
    R.drawable.ic_ai_provider_volcengine,
    R.drawable.ic_ai_provider_xai,
    R.drawable.ic_ai_provider_xiaomi,
    R.drawable.ic_ai_provider_yi_dark,
    R.drawable.ic_ai_provider_yi_light,
    R.drawable.ic_ai_provider_zai,
    R.drawable.ic_ai_provider_zenmux,
    R.drawable.ic_ai_provider_zhipu,
)

internal fun resolveAiProviderIcon(
    presetId: String? = null,
    name: String = "",
    baseUrl: String = "",
    @Suppress("UNUSED_PARAMETER") protocol: AiApiProtocol = AiApiProtocol.OPENAI_CHAT,
): AiProviderIconSpec? {
    val cleanId = presetId?.trim()?.lowercase().orEmpty()
    if (cleanId.isNotEmpty()) {
        PresetIdIconMap[cleanId]?.let { return it }
    }

    val urlLower = baseUrl.trim().lowercase()
    val combined = "${urlLower} ${name.trim().lowercase()}"

    return when {
        "openai.azure.com" in combined || "services.ai.azure.com" in combined || "azure" in combined -> AzureSpec
        "openrouter" in combined -> OpenRouterSpec
        "codex" in combined -> OpenAiCodexSpec
        "kimi.com/coding" in combined || "kimi-coding" in combined || "kimi coding" in combined -> KimiCodingSpec
        "open.bigmodel.cn" in combined || "智谱" in combined || "zhipu" in combined -> ZhipuSpec
        "api.z.ai" in combined || "z.ai" in combined -> ZaiSpec
        "dashscope" in combined || "百炼" in combined || "bailian" in combined -> BailianSpec
        "token-plan" in urlLower && "aliyuncs" in urlLower -> QwenSpec
        "qwen" in combined || "通义" in combined -> QwenSpec
        "volces.com" in combined || "豆包" in combined || "doubao" in combined -> DoubaoSpec
        "火山" in combined || "volcengine" in combined -> VolcengineSpec
        "tencentmaas" in combined || "hunyuan" in combined || "混元" in combined -> HunyuanSpec
        "baidubce" in combined || "千帆" in combined || "qianfan" in combined -> QianfanSpec
        "stepfun" in combined || "阶跃" in combined -> StepFunSpec
        "sensenova" in combined || "商汤" in combined -> SenseNovaSpec
        "zenmux" in combined -> ZenMuxSpec
        "cohere" in combined -> CohereSpec
        "lingyiwanwu" in combined || "零一万物" in combined -> YiSpec
        "modelscope" in combined || "魔搭" in combined -> ModelScopeSpec
        "ppinfra" in combined || "ppio" in combined -> PpioSpec
        "aihubmix" in combined -> AiHubMixSpec
        "xiaomimimo" in combined || "小米" in combined -> XiaomiSpec
        "deepseek" in combined -> DeepSeekSpec
        "anthropic" in combined || "claude" in combined -> AnthropicSpec
        "generativelanguage" in combined || "gemini" in combined -> GeminiSpec
        "aiplatform.googleapis" in combined || "vertex" in combined -> GoogleVertexSpec
        "siliconflow" in combined || "硅基" in combined -> SiliconFlowSpec
        "moonshot" in combined || "kimi" in combined -> MoonshotSpec
        "minimax" in combined -> MiniMaxSpec
        "ant-ling" in combined || "百灵" in combined -> AntLingSpec
        "api.x.ai" in combined || "grok" in combined || "xai" in combined -> XaiSpec
        "mistral" in combined -> MistralSpec
        "groq" in combined -> GroqSpec
        "together" in combined -> TogetherSpec
        "fireworks" in combined -> FireworksSpec
        "nvidia" in combined -> NvidiaSpec
        "huggingface" in combined -> HuggingFaceSpec
        "cerebras" in combined -> CerebrasSpec
        "baseten" in combined -> BasetenSpec
        "llama.com" in combined || "meta llama" in combined -> MetaSpec
        "opencode" in combined -> OpenCodeSpec
        "vercel" in combined -> VercelSpec
        "bedrock" in combined -> BedrockSpec
        "cloudflare" in combined -> CloudflareSpec
        "baichuan" in combined || "百川" in combined -> BaichuanSpec
        "deepinfra" in combined -> DeepInfraSpec
        "gitee" in combined -> GiteeSpec
        "github" in combined -> GitHubSpec
        "hyperbolic" in combined -> HyperbolicSpec
        "infinigence" in combined || "infini-ai" in combined || "无问芯穹" in combined -> InfinigenceSpec
        "nebius" in combined -> NebiusSpec
        "novita" in combined -> NovitaSpec
        "perplexity" in combined -> PerplexitySpec
        "sambanova" in combined -> SambaNovaSpec
        "xfyun" in combined || "spark" in combined || "星火" in combined -> SparkSpec
        "upstage" in combined -> UpstageSpec
        "ai21" in combined -> Ai21Spec
        "api.openai.com" in urlLower || "openai" in combined -> OpenAiSpec
        else -> null
    }
}

@Composable
internal fun AiProviderAvatar(
    name: String,
    baseUrl: String,
    protocol: AiApiProtocol,
    size: Dp,
    presetId: String? = null,
    modifier: Modifier = Modifier,
) {
    val iconSpec = remember(presetId, name, baseUrl, protocol) {
        resolveAiProviderIcon(
            presetId = presetId,
            name = name,
            baseUrl = baseUrl,
            protocol = protocol,
        )
    }

    if (iconSpec == null) {
        ProviderMonogramAvatar(
            name = name,
            baseUrl = baseUrl,
            protocol = protocol,
            size = size,
            modifier = modifier,
        )
        return
    }

    val isLight = isGlassLightTheme()
    val cornerRadius = if (size >= 40.dp) 14.dp else 10.dp
    val shape = RoundedCornerShape(cornerRadius)
    val containerColor = if (iconSpec is AiProviderIconSpec.Color && iconSpec.preferDarkBackdropInLight && isLight) {
        Color(0xFF1F2937)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isLight) 0.55f else 0.72f)
    }
    val resId = when (iconSpec) {
        is AiProviderIconSpec.Mono -> iconSpec.resId
        is AiProviderIconSpec.Color -> iconSpec.resId
        is AiProviderIconSpec.Adaptive -> if (isLight) iconSpec.lightResId else iconSpec.darkResId
    }
    val tint = when (iconSpec) {
        is AiProviderIconSpec.Mono -> MaterialTheme.colorScheme.onSurface
        is AiProviderIconSpec.Color,
        is AiProviderIconSpec.Adaptive -> Color.Unspecified
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = resId),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

@Composable
private fun ProviderMonogramAvatar(
    name: String,
    baseUrl: String,
    protocol: AiApiProtocol,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val monogram = remember(name, baseUrl, protocol) {
        resolveProviderMonogram(name = name, baseUrl = baseUrl, protocol = protocol)
    }
    val tint = remember(monogram, baseUrl) {
        resolveProviderToneColor(seed = "$monogram:$baseUrl")
    }
    val cornerRadius = if (size >= 40.dp) 14.dp else 10.dp
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogram,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = tint,
            maxLines = 1,
        )
    }
}

private fun resolveProviderMonogram(
    name: String,
    baseUrl: String,
    protocol: AiApiProtocol,
): String {
    val hostAndName = "${baseUrl.lowercase()} ${name.lowercase()}"
    return when {
        "anthropic" in hostAndName || "claude" in hostAndName -> "CL"
        "deepseek" in hostAndName -> "DS"
        "generativelanguage" in hostAndName || "gemini" in hostAndName -> "GM"
        "moonshot" in hostAndName || "kimi" in hostAndName -> "KM"
        "dashscope" in hostAndName || "qwen" in hostAndName || "通义" in hostAndName || "aliyuncs" in hostAndName -> "QW"
        "bigmodel" in hostAndName || "z.ai" in hostAndName || "智谱" in hostAndName -> "ZP"
        "minimax" in hostAndName -> "MM"
        "openrouter" in hostAndName -> "OR"
        "xiaomi" in hostAndName || "小米" in hostAndName -> "XM"
        "openai" in hostAndName -> "OA"
        protocol == AiApiProtocol.ANTHROPIC_MESSAGES -> "CL"
        else -> name.trim().firstOrNull { !it.isWhitespace() }?.uppercaseChar()?.toString() ?: "AI"
    }
}

private val ProviderAvatarPalette = listOf(
    Color(0xFF2563EB),
    Color(0xFF0D9488),
    Color(0xFF7C3AED),
    Color(0xFFD97706),
    Color(0xFFDC2626),
    Color(0xFF0284C7),
    Color(0xFF059669),
    Color(0xFFDB2777),
)

private fun resolveProviderToneColor(seed: String): Color {
    val index = abs(seed.hashCode()) % ProviderAvatarPalette.size
    return ProviderAvatarPalette[index]
}
