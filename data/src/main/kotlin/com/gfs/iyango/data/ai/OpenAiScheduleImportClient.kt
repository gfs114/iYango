package com.gfs.iyango.data.ai

import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiChatMessage
import com.gfs.iyango.core.domain.AiChatPort
import com.gfs.iyango.core.domain.AiChatTurnResult
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiImportParseResult
import com.gfs.iyango.core.domain.AiModelInfo
import com.gfs.iyango.core.domain.AiProviderConfig
import com.gfs.iyango.core.domain.AiScheduleImportPort
import com.gfs.iyango.core.domain.AiToolDefinition
import com.gfs.iyango.core.domain.DiagnosticLogPort
import com.gfs.iyango.core.model.DiagnosticEvent
import com.gfs.iyango.data.json.ScheduleJsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal data class AiHttpResponse(
    val code: Int,
    val body: String,
)

internal fun interface AiHeaderPostTransport {
    suspend fun post(url: String, headers: Map<String, String>, bodyJson: String): AiHttpResponse
}

internal fun interface AiHeaderGetTransport {
    suspend fun get(url: String, headers: Map<String, String>): AiHttpResponse
}

internal fun interface AiHeaderStreamTransport {
    suspend fun streamPost(
        url: String,
        headers: Map<String, String>,
        bodyJson: String,
        protocol: AiApiProtocol,
        onTextDelta: (String) -> Unit,
    ): AiChatTurnResult
}

/**
 * BYOK 多协议 AI 客户端（OpenAI Chat / OpenAI Responses / Anthropic Messages）。
 *
 * - 使用独立 [OkHttpClient]（禁用重定向，避免 Authorization / x-api-key 请求头跨主机泄露）；
 * - 绝不把 API Key、提示词或完整响应写入诊断日志或异常文案。
 */
@Singleton
class OpenAiScheduleImportClient internal constructor(
    private val diagnostics: DiagnosticLogPort,
    private val clock: Clock,
    private val headerPostTransport: AiHeaderPostTransport,
    private val headerGetTransport: AiHeaderGetTransport,
    private val headerStreamTransport: AiHeaderStreamTransport? = null,
) : AiScheduleImportPort, AiChatPort {

    internal constructor(
        diagnostics: DiagnosticLogPort,
        clock: Clock,
        postTransport: suspend (url: String, apiKey: String, bodyJson: String) -> AiHttpResponse,
        getTransport: suspend (url: String, apiKey: String) -> AiHttpResponse = { _, _ -> AiHttpResponse(404, "") },
    ) : this(
        diagnostics = diagnostics,
        clock = clock,
        headerPostTransport = AiHeaderPostTransport { url, headers, body ->
            val key = headers["x-api-key"]
                ?: headers["Authorization"]?.removePrefix("Bearer ")
                ?: ""
            postTransport(url, key, body)
        },
        headerGetTransport = AiHeaderGetTransport { url, headers ->
            val key = headers["x-api-key"]
                ?: headers["Authorization"]?.removePrefix("Bearer ")
                ?: ""
            getTransport(url, key)
        },
    )

    @Inject
    constructor(
        diagnostics: DiagnosticLogPort,
        clock: Clock,
    ) : this(
        diagnostics = diagnostics,
        clock = clock,
        headerPostTransport = AiHeaderPostTransport(::defaultPost),
        headerGetTransport = AiHeaderGetTransport(::defaultGet),
        headerStreamTransport = AiHeaderStreamTransport { url, headers, body, protocol, onTextDelta ->
            defaultStreamPost(url, headers, body, protocol, onTextDelta)
        },
    )

    override suspend fun parseSchedule(
        config: AiProviderConfig,
        textInput: String,
        images: List<AiImagePayload>,
        referenceDateIso: String,
    ): AiImportParseResult = withContext(Dispatchers.IO) {
        val cleanText = textInput.trim()
        if (cleanText.isEmpty() && images.isEmpty()) {
            return@withContext AiImportParseResult.Failed("请先添加课表截图或输入文本")
        }
        val apiKey = config.apiKey.trim()
        val model = config.model.trim()
        if (apiKey.isEmpty() || model.isEmpty()) {
            return@withContext AiImportParseResult.Failed("请先完整配置模型名称与 API Key")
        }
        val url = resolveEndpointUrl(config.baseUrl, config.protocol).getOrElse { error ->
            return@withContext AiImportParseResult.Failed(error.message ?: "接口地址无效")
        }

        val startedAt = clock.millis()
        val requestJson = buildScheduleRequestJson(
            protocol = config.protocol,
            model = model,
            textInput = cleanText,
            images = images,
            referenceDateIso = referenceDateIso,
        )
        val headers = buildAuthHeaders(config.protocol, apiKey)
        try {
            val response = headerPostTransport.post(url, headers, requestJson)
            if (response.code !in 200..299) {
                val message = mapHttpError(response.code, response.body, apiKey)
                recordDiagnostic("ai_import", "FAILED", "Http${response.code}", startedAt, message)
                return@withContext AiImportParseResult.Failed(message)
            }
            val content = extractAssistantText(config.protocol, response.body)
                ?: run {
                    recordDiagnostic("ai_import", "FAILED", "EmptyContent", startedAt, "模型未返回有效文本")
                    return@withContext AiImportParseResult.Failed("模型未返回有效识别内容，请重试")
                }
            val normalized = normalizeModelOutputToScheduleJson(
                rawContent = content,
                exportedAt = ScheduleJsonParser.now(),
            )
            when (normalized) {
                is AiImportParseResult.Ready -> recordDiagnostic("ai_import", "SUCCESS", "", startedAt, normalized.summary)
                is AiImportParseResult.Failed -> recordDiagnostic("ai_import", "FAILED", "ParseEmpty", startedAt, normalized.message)
            }
            normalized
        } catch (cancelled: CancellationException) {
            recordDiagnostic("ai_import", "CANCELLED", "Cancelled", startedAt, "用户取消识别")
            throw cancelled
        } catch (error: Exception) {
            val message = sanitizeError(error.message ?: "网络请求失败", apiKey)
            recordDiagnostic("ai_import", "FAILED", error.javaClass.simpleName, startedAt, message)
            AiImportParseResult.Failed(message)
        }
    }

    override suspend fun completeTurn(
        config: AiProviderConfig,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        tools: List<AiToolDefinition>,
    ): AiChatTurnResult = withContext(Dispatchers.IO) {
        val apiKey = config.apiKey.trim()
        val model = config.model.trim()
        if (apiKey.isEmpty() || model.isEmpty()) {
            return@withContext AiChatTurnResult.Failed("请先完整配置 AI 接口与 API Key")
        }
        val url = resolveEndpointUrl(config.baseUrl, config.protocol).getOrElse { error ->
            return@withContext AiChatTurnResult.Failed(error.message ?: "接口地址无效")
        }
        val headers = buildAuthHeaders(config.protocol, apiKey)
        val body = buildChatTurnRequestJson(
            protocol = config.protocol,
            model = model,
            systemPrompt = systemPrompt,
            messages = messages,
            tools = tools,
        )
        val startedAt = clock.millis()
        try {
            val response = headerPostTransport.post(url, headers, body)
            if (response.code !in 200..299) {
                val msg = mapHttpError(response.code, response.body, apiKey)
                recordDiagnostic("ai_chat", "FAILED", "Http${response.code}", startedAt, msg)
                return@withContext AiChatTurnResult.Failed(msg)
            }
            val parsed = parseChatTurnResponse(config.protocol, response.body)
                ?: run {
                    recordDiagnostic("ai_chat", "FAILED", "EmptyContent", startedAt, "模型未返回有效回复")
                    return@withContext AiChatTurnResult.Failed("模型未返回有效内容，请重试")
                }
            recordDiagnostic(
                category = "ai_chat",
                outcome = "SUCCESS",
                errorType = "",
                startedAt = startedAt,
                message = "protocol=${config.protocol.shortLabel} tools=${parsed.toolCalls.size}",
            )
            parsed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val msg = sanitizeError(error.message ?: "网络请求失败", apiKey)
            recordDiagnostic("ai_chat", "FAILED", error.javaClass.simpleName, startedAt, msg)
            AiChatTurnResult.Failed(msg)
        }
    }

    override suspend fun streamTurn(
        config: AiProviderConfig,
        systemPrompt: String,
        messages: List<AiChatMessage>,
        tools: List<AiToolDefinition>,
        onTextDelta: (String) -> Unit,
    ): AiChatTurnResult = withContext(Dispatchers.IO) {
        val streamTransport = headerStreamTransport
        if (streamTransport == null) {
            return@withContext super.streamTurn(config, systemPrompt, messages, tools, onTextDelta)
        }
        val apiKey = config.apiKey.trim()
        val model = config.model.trim()
        if (apiKey.isEmpty() || model.isEmpty()) {
            return@withContext AiChatTurnResult.Failed("请先完整配置 AI 接口与 API Key")
        }
        val url = resolveEndpointUrl(config.baseUrl, config.protocol).getOrElse { error ->
            return@withContext AiChatTurnResult.Failed(error.message ?: "接口地址无效")
        }
        val headers = buildAuthHeaders(config.protocol, apiKey) + mapOf("Accept" to "text/event-stream")
        val body = buildChatTurnRequestJson(
            protocol = config.protocol,
            model = model,
            systemPrompt = systemPrompt,
            messages = messages,
            tools = tools,
            stream = true,
        )
        val startedAt = clock.millis()
        try {
            when (val result = streamTransport.streamPost(url, headers, body, config.protocol, onTextDelta)) {
                is AiChatTurnResult.Reply -> {
                    recordDiagnostic(
                        category = "ai_chat",
                        outcome = "SUCCESS",
                        errorType = "",
                        startedAt = startedAt,
                        message = "stream protocol=${config.protocol.shortLabel} tools=${result.toolCalls.size}",
                    )
                    result
                }
                is AiChatTurnResult.Failed -> {
                    val sanitized = sanitizeError(result.message, apiKey)
                    recordDiagnostic("ai_chat", "FAILED", "StreamFailed", startedAt, sanitized)
                    AiChatTurnResult.Failed(sanitized)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val msg = sanitizeError(error.message ?: "网络请求失败", apiKey)
            recordDiagnostic("ai_chat", "FAILED", error.javaClass.simpleName, startedAt, msg)
            AiChatTurnResult.Failed(msg)
        }
    }

    override suspend fun testConnection(config: AiProviderConfig): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = config.apiKey.trim()
        val model = config.model.trim()
        if (apiKey.isEmpty() || model.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("请填写模型名称与 API Key"))
        }
        val url = resolveEndpointUrl(config.baseUrl, config.protocol).getOrElse {
            return@withContext Result.failure(it)
        }
        val startedAt = clock.millis()
        val body = buildConnectionTestRequestJson(config.protocol, model)
        val headers = buildAuthHeaders(config.protocol, apiKey)
        try {
            val response = headerPostTransport.post(url, headers, body)
            if (response.code in 200..299) {
                recordDiagnostic("ai_import", "SUCCESS", "", startedAt, "测试连接成功 (${config.protocol.shortLabel})")
                Result.success("连接成功")
            } else {
                val msg = mapHttpError(response.code, response.body, apiKey)
                recordDiagnostic("ai_import", "FAILED", "Http${response.code}", startedAt, msg)
                Result.failure(IOException(msg))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val msg = sanitizeError(error.message ?: "连接测试失败", apiKey)
            recordDiagnostic("ai_import", "FAILED", error.javaClass.simpleName, startedAt, msg)
            Result.failure(IOException(msg))
        }
    }

    override suspend fun fetchModels(config: AiProviderConfig): Result<List<AiModelInfo>> = withContext(Dispatchers.IO) {
        val apiKey = config.apiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("请先填写 API Key"))
        }
        val url = resolveModelsUrl(config.baseUrl, config.protocol).getOrElse {
            return@withContext Result.failure(it)
        }
        val headers = buildAuthHeaders(config.protocol, apiKey)
        val startedAt = clock.millis()
        try {
            val response = headerGetTransport.get(url, headers)
            if (response.code !in 200..299) {
                val msg = if (response.code == 404) {
                    "该接口不支持自动拉取模型列表（HTTP 404），请手动填写模型名"
                } else {
                    mapHttpError(response.code, response.body, apiKey)
                }
                recordDiagnostic("ai_import", "FAILED", "Http${response.code}", startedAt, msg)
                return@withContext Result.failure(IOException(msg))
            }
            val models = parseModelsResponse(response.body)
            if (models.isEmpty()) {
                val msg = "接口未返回可用模型，请手动输入模型名称"
                recordDiagnostic("ai_import", "FAILED", "EmptyModels", startedAt, msg)
                return@withContext Result.failure(IOException(msg))
            }
            recordDiagnostic("ai_import", "SUCCESS", "", startedAt, "获取 ${models.size} 个可用模型")
            Result.success(models)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val msg = sanitizeError(error.message ?: "获取模型列表失败", apiKey)
            recordDiagnostic("ai_import", "FAILED", error.javaClass.simpleName, startedAt, msg)
            Result.failure(IOException(msg))
        }
    }

    private suspend fun recordDiagnostic(
        category: String,
        outcome: String,
        errorType: String,
        startedAt: Long,
        message: String,
    ) {
        runCatching {
            diagnostics.record(
                DiagnosticEvent(
                    timestamp = clock.millis(),
                    category = category,
                    durationMs = (clock.millis() - startedAt).coerceAtLeast(0L),
                    outcome = outcome,
                    errorType = errorType,
                    message = message.take(160),
                ),
            )
        }
    }

    internal companion object {
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val RESPONSE_JSON = Json { ignoreUnknownKeys = true }

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        internal fun buildScheduleRequestJson(
            model: String,
            textInput: String,
            images: List<AiImagePayload>,
            referenceDateIso: String,
            protocol: AiApiProtocol = AiApiProtocol.OPENAI_CHAT,
        ): String {
            val refNote = referenceDateIso.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { "今天是 $it。若遇到「本周五」「下周一」「明天」等相对日期，请据此推算准确 yyyy-MM-dd。\n" }
                .orEmpty()
            val systemPrompt = """
                你是高校课表结构化提取助手。请从用户提供的课表截图或文字通知中提取全部课程/调课/补课安排，且只输出纯 JSON（不要 markdown 代码块、不要解释文字）。
                输出格式：
                {
                  "courses": [
                    {
                      "name": "课程名称(必填)",
                      "teacher": "教师姓名(未知填空串)",
                      "classroom": "教室/地点(未知填空串)",
                      "dayOfWeek": 1,
                      "startSection": 1,
                      "endSection": 2,
                      "startWeek": 1,
                      "endWeek": 16,
                      "weekType": 0,
                      "timeMode": 0,
                      "startTime": "",
                      "endTime": "",
                      "date": "",
                      "selectedWeeks": [],
                      "credit": 0,
                      "remark": ""
                    }
                  ],
                  "semester": null,
                  "timeSlots": [],
                  "warnings": []
                }
                规则：
                1. dayOfWeek 为 1..7（1=周一 … 7=周日）。
                2. weekType 为 0=每周，1=单周，2=双周。
                3. 常规学期课表使用 timeMode=0，填写 startSection/endSection（1..20）和 startWeek/endWeek（1..30）；若某门课仅在特定离散周次上课（如第2,5,8周），可填 selectedWeeks 数组并让 startWeek/endWeek 覆盖它们。
                4. 若是微信群单次临时补课/讲座/考试通知且有明确日期和钟点，请使用 timeMode=2，填写 date="yyyy-MM-dd" 以及 HH:mm 格式的 startTime/endTime；若仅有钟点无节次且每周重复，使用 timeMode=1。
                5. 若图片或文字里给出了作息时间表或学期名称/开学日期，可一并填入 timeSlots（{section,startTime,endTime}）和 semester（{name,startDate,totalWeeks}）。
            """.trimIndent()

            val userPromptText = buildString {
                append(refNote)
                if (textInput.isNotEmpty()) {
                    append("用户补充文本：\n").append(textInput)
                } else {
                    append("请识别图片中的全部课程信息并输出 JSON。")
                }
            }

            return buildScheduleImportRequestJson(
                protocol = protocol,
                model = model,
                systemPrompt = systemPrompt,
                userPromptText = userPromptText,
                images = images,
            )
        }

        internal fun extractAssistantText(responseBody: String): String? =
            extractAssistantText(AiApiProtocol.OPENAI_CHAT, responseBody)

        internal fun mapHttpError(statusCode: Int, responseBody: String, apiKey: String): String {
            val serverMessage = extractErrorMessage(responseBody, apiKey)
            return when (statusCode) {
                401 -> "API Key 无效或未授权（HTTP 401）"
                403 -> "接口拒绝访问，请检查账号权限或额度（HTTP 403）"
                404 -> "接口地址或模型名称不存在（HTTP 404）"
                429 -> "请求过于频繁或额度不足（HTTP 429）"
                in 500..599 -> "模型服务暂时不可用（HTTP $statusCode）"
                else -> if (serverMessage.isNotEmpty()) {
                    "请求失败（HTTP $statusCode）：$serverMessage"
                } else {
                    "请求失败（HTTP $statusCode）"
                }
            }
        }

        private fun extractErrorMessage(responseBody: String, apiKey: String): String {
            val parsed = runCatching { RESPONSE_JSON.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
            val errorObj = parsed?.get("error") as? JsonObject
            val raw = ((errorObj?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content)
                ?: ((parsed?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content)
                .orEmpty()
            return sanitizeError(raw, apiKey).take(120)
        }

        private fun sanitizeError(message: String, apiKey: String): String {
            val trimmed = message.trim()
            if (trimmed.isEmpty()) return "网络请求失败"
            return if (apiKey.isNotBlank()) trimmed.replace(apiKey, "***") else trimmed
        }

        private suspend fun defaultPost(
            url: String,
            headers: Map<String, String>,
            bodyJson: String,
        ): AiHttpResponse {
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            val request = builder
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            return executeHttpRequest(request)
        }

        private suspend fun defaultGet(
            url: String,
            headers: Map<String, String>,
        ): AiHttpResponse {
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            val request = builder.get().build()
            return executeHttpRequest(request)
        }

        private suspend fun defaultStreamPost(
            url: String,
            headers: Map<String, String>,
            bodyJson: String,
            protocol: AiApiProtocol,
            onTextDelta: (String) -> Unit,
        ): AiChatTurnResult = suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }
            val request = builder
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            val errText = runCatching { readBoundedUtf8(res) }.getOrDefault("")
                            val apiKey = headers["x-api-key"]
                                ?: headers["Authorization"]?.removePrefix("Bearer ")
                                ?: ""
                            if (continuation.isActive) {
                                continuation.resume(AiChatTurnResult.Failed(mapHttpError(res.code, errText, apiKey)))
                            }
                            return
                        }
                        val contentType = res.header("Content-Type").orEmpty()
                        if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                            val fullBody = readBoundedUtf8(res)
                            val parsed = parseChatTurnResponse(protocol, fullBody)
                            if (parsed == null) {
                                if (continuation.isActive) {
                                    continuation.resume(AiChatTurnResult.Failed("模型未返回有效内容，请重试"))
                                }
                                return
                            }
                            if (parsed.text.isNotEmpty() && continuation.isActive) {
                                onTextDelta(parsed.text)
                            }
                            if (continuation.isActive) {
                                continuation.resume(parsed)
                            }
                            return
                        }
                        val parsed = res.body.charStream().use { reader ->
                            AiSseAccumulator.consumeSseReader(
                                reader = reader,
                                protocol = protocol,
                                isActive = { continuation.isActive },
                                onTextDelta = onTextDelta,
                            )
                        }
                        if (continuation.isActive) {
                            if (parsed != null) {
                                continuation.resume(parsed)
                            } else {
                                continuation.resume(AiChatTurnResult.Failed("模型未返回有效内容，请重试"))
                            }
                        }
                    }
                }
            })
        }

        private suspend fun executeHttpRequest(request: Request): AiHttpResponse =
            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            val text = readBoundedUtf8(res)
                            if (continuation.isActive) {
                                continuation.resume(AiHttpResponse(res.code, text))
                            }
                        }
                    }
                })
            }

        private fun readBoundedUtf8(response: Response): String {
            val body = response.body
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            body.byteStream().use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (output.size() + read > MAX_RESPONSE_BYTES) {
                        throw IOException("模型返回体积超出限制")
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
