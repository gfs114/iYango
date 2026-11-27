package com.gfs.iyango.data.ai

import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiChatMessage
import com.gfs.iyango.core.domain.AiChatRole
import com.gfs.iyango.core.domain.AiChatTurnResult
import com.gfs.iyango.core.domain.AiImagePayload
import com.gfs.iyango.core.domain.AiToolCall
import com.gfs.iyango.core.domain.AiToolDefinition
import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val ANTHROPIC_API_VERSION = "2023-06-01"
private const val ANTHROPIC_DEFAULT_MAX_TOKENS = 4096

private val WIRE_JSON = Json { ignoreUnknownKeys = true }

private val KNOWN_ENDPOINT_SUFFIXES = listOf(
    "/chat/completions",
    "/responses",
    "/messages",
    "/models",
)

internal fun resolveEndpointUrl(
    rawBaseUrl: String,
    protocol: AiApiProtocol,
): Result<String> {
    if (protocol == AiApiProtocol.OPENAI_CHAT) {
        val (trimmed, uri) = validateOpenAiBaseUrl(rawBaseUrl).getOrElse { return Result.failure(it) }
        val clean = stripKnownSuffixes(trimmed, uri, keepSuffix = "/chat/completions")
        return if (clean.endsWith("/chat/completions", ignoreCase = true)) {
            Result.success(clean)
        } else {
            Result.success("$clean/chat/completions")
        }
    }
    val (trimmed, uri) = validateOpenAiBaseUrl(rawBaseUrl).getOrElse { return Result.failure(it) }
    return when (protocol) {
        AiApiProtocol.OPENAI_CHAT -> Result.success(trimmed)
        AiApiProtocol.OPENAI_RESPONSES -> {
            val clean = stripKnownSuffixes(trimmed, uri, keepSuffix = "/responses")
            if (clean.endsWith("/responses", ignoreCase = true)) {
                Result.success(clean)
            } else {
                Result.success("$clean/responses")
            }
        }
        AiApiProtocol.ANTHROPIC_MESSAGES -> {
            val clean = stripKnownSuffixes(trimmed, uri, keepSuffix = "/messages")
            if (clean.endsWith("/messages", ignoreCase = true)) {
                Result.success(clean)
            } else {
                val strippedUriPath = runCatching { URI(clean).path }.getOrNull()?.trimEnd('/').orEmpty()
                if (strippedUriPath.isEmpty()) {
                    Result.success("$clean/v1/messages")
                } else {
                    Result.success("$clean/messages")
                }
            }
        }
    }
}

internal fun resolveModelsUrl(
    rawBaseUrl: String,
    protocol: AiApiProtocol,
): Result<String> {
    val (trimmed, uri) = validateOpenAiBaseUrl(rawBaseUrl).getOrElse { return Result.failure(it) }
    val clean = stripKnownSuffixes(trimmed, uri, keepSuffix = "/models")
    if (clean.endsWith("/models", ignoreCase = true)) {
        return Result.success(clean)
    }
    val strippedUriPath = runCatching { URI(clean).path }.getOrNull()?.trimEnd('/').orEmpty()
    val resolved = if (protocol == AiApiProtocol.ANTHROPIC_MESSAGES && strippedUriPath.isEmpty()) {
        "$clean/v1/models"
    } else {
        "$clean/models"
    }
    return Result.success(resolved)
}

internal fun endpointPreviewPath(
    rawBaseUrl: String,
    protocol: AiApiProtocol,
): String {
    val fallback = when (protocol) {
        AiApiProtocol.OPENAI_CHAT -> "POST /v1/chat/completions"
        AiApiProtocol.OPENAI_RESPONSES -> "POST /v1/responses"
        AiApiProtocol.ANTHROPIC_MESSAGES -> "POST /v1/messages"
    }
    val url = resolveEndpointUrl(rawBaseUrl, protocol).getOrNull() ?: return fallback
    val path = runCatching { URI(url).path }.getOrNull()?.takeIf { it.isNotBlank() } ?: return fallback
    return "POST $path"
}

internal fun buildAuthHeaders(
    protocol: AiApiProtocol,
    apiKey: String,
): Map<String, String> = when (protocol) {
    AiApiProtocol.OPENAI_CHAT,
    AiApiProtocol.OPENAI_RESPONSES,
    -> mapOf(
        "Authorization" to "Bearer $apiKey",
        "Accept" to "application/json",
    )
    AiApiProtocol.ANTHROPIC_MESSAGES -> mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to ANTHROPIC_API_VERSION,
        "Accept" to "application/json",
    )
}

internal fun buildConnectionTestRequestJson(
    protocol: AiApiProtocol,
    model: String,
): String = when (protocol) {
    AiApiProtocol.OPENAI_CHAT -> buildJsonObject {
        put("model", model)
        put("max_tokens", 16)
        put("temperature", 0)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", "Reply OK")
            }
        }
    }.toString()

    AiApiProtocol.OPENAI_RESPONSES -> buildJsonObject {
        put("model", model)
        put("max_output_tokens", 16)
        put("input", "Reply OK")
    }.toString()

    AiApiProtocol.ANTHROPIC_MESSAGES -> buildJsonObject {
        put("model", model)
        put("max_tokens", 16)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", "Reply OK")
            }
        }
    }.toString()
}

internal fun buildScheduleImportRequestJson(
    protocol: AiApiProtocol,
    model: String,
    systemPrompt: String,
    userPromptText: String,
    images: List<AiImagePayload>,
): String = when (protocol) {
    AiApiProtocol.OPENAI_CHAT -> buildJsonObject {
        put("model", model)
        put("temperature", 0.1)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            }
            addJsonObject {
                put("role", "user")
                if (images.isEmpty()) {
                    put("content", userPromptText)
                } else {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", userPromptText)
                        }
                        images.forEach { image ->
                            val b64 = Base64.getEncoder().encodeToString(image.bytes)
                            val mime = image.mimeType.ifBlank { "image/jpeg" }
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:$mime;base64,$b64")
                                }
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    AiApiProtocol.OPENAI_RESPONSES -> buildJsonObject {
        put("model", model)
        put("instructions", systemPrompt)
        put("temperature", 0.1)
        putJsonArray("input") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "input_text")
                        put("text", userPromptText)
                    }
                    images.forEach { image ->
                        val b64 = Base64.getEncoder().encodeToString(image.bytes)
                        val mime = image.mimeType.ifBlank { "image/jpeg" }
                        addJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:$mime;base64,$b64")
                        }
                    }
                }
            }
        }
    }.toString()

    AiApiProtocol.ANTHROPIC_MESSAGES -> buildJsonObject {
        put("model", model)
        put("max_tokens", ANTHROPIC_DEFAULT_MAX_TOKENS)
        put("temperature", 0.1)
        put("system", systemPrompt)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                if (images.isEmpty()) {
                    put("content", userPromptText)
                } else {
                    putJsonArray("content") {
                        images.forEach { image ->
                            val b64 = Base64.getEncoder().encodeToString(image.bytes)
                            val mime = image.mimeType.ifBlank { "image/jpeg" }
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", mime)
                                    put("data", b64)
                                }
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", userPromptText)
                        }
                    }
                }
            }
        }
    }.toString()
}

internal fun buildChatTurnRequestJson(
    protocol: AiApiProtocol,
    model: String,
    systemPrompt: String,
    messages: List<AiChatMessage>,
    tools: List<AiToolDefinition>,
    stream: Boolean = false,
): String = when (protocol) {
    AiApiProtocol.OPENAI_CHAT -> buildJsonObject {
        put("model", model)
        put("temperature", 0.2)
        if (stream) put("stream", true)
        putJsonArray("messages") {
            if (systemPrompt.isNotBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            }
            messages.forEach { msg ->
                when (msg.role) {
                    AiChatRole.USER -> addJsonObject {
                        put("role", "user")
                        if (msg.images.isEmpty()) {
                            put("content", msg.content)
                        } else {
                            putJsonArray("content") {
                                if (msg.content.isNotBlank()) {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", msg.content)
                                    }
                                }
                                msg.images.forEach { image ->
                                    val b64 = Base64.getEncoder().encodeToString(image.bytes)
                                    val mime = image.mimeType.ifBlank { "image/jpeg" }
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:$mime;base64,$b64")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AiChatRole.ASSISTANT -> addJsonObject {
                        put("role", "assistant")
                        if (msg.content.isNotEmpty()) {
                            put("content", msg.content)
                        } else if (msg.toolCalls.isNotEmpty()) {
                            put("content", "")
                        }
                        if (msg.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                msg.toolCalls.forEach { call ->
                                    addJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", call.name)
                                            put("arguments", call.argumentsJson.ifBlank { "{}" })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AiChatRole.TOOL -> addJsonObject {
                        put("role", "tool")
                        put("tool_call_id", msg.toolCallId)
                        put("content", msg.content)
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", parseSchemaJsonObject(tool.parametersSchemaJson))
                        }
                    }
                }
            }
        }
    }.toString()

    AiApiProtocol.OPENAI_RESPONSES -> buildJsonObject {
        put("model", model)
        put("temperature", 0.2)
        if (stream) put("stream", true)
        if (systemPrompt.isNotBlank()) {
            put("instructions", systemPrompt)
        }
        putJsonArray("input") {
            messages.forEach { msg ->
                when (msg.role) {
                    AiChatRole.USER -> addJsonObject {
                        put("role", "user")
                        if (msg.images.isEmpty()) {
                            put("content", msg.content)
                        } else {
                            putJsonArray("content") {
                                if (msg.content.isNotBlank()) {
                                    addJsonObject {
                                        put("type", "input_text")
                                        put("text", msg.content)
                                    }
                                }
                                msg.images.forEach { image ->
                                    val b64 = Base64.getEncoder().encodeToString(image.bytes)
                                    val mime = image.mimeType.ifBlank { "image/jpeg" }
                                    addJsonObject {
                                        put("type", "input_image")
                                        put("image_url", "data:$mime;base64,$b64")
                                    }
                                }
                            }
                        }
                    }
                    AiChatRole.ASSISTANT -> {
                        if (msg.content.isNotEmpty()) {
                            addJsonObject {
                                put("role", "assistant")
                                put("content", msg.content)
                            }
                        }
                        msg.toolCalls.forEach { call ->
                            addJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.name)
                                put("arguments", call.argumentsJson.ifBlank { "{}" })
                            }
                        }
                    }
                    AiChatRole.TOOL -> addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", msg.toolCallId)
                        put("output", msg.content)
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", parseSchemaJsonObject(tool.parametersSchemaJson))
                    }
                }
            }
        }
    }.toString()

    AiApiProtocol.ANTHROPIC_MESSAGES -> buildJsonObject {
        put("model", model)
        put("max_tokens", ANTHROPIC_DEFAULT_MAX_TOKENS)
        put("temperature", 0.2)
        if (stream) put("stream", true)
        if (systemPrompt.isNotBlank()) {
            put("system", systemPrompt)
        }
        putJsonArray("messages") {
            var idx = 0
            while (idx < messages.size) {
                val msg = messages[idx]
                when (msg.role) {
                    AiChatRole.USER -> {
                        addJsonObject {
                            put("role", "user")
                            if (msg.images.isEmpty()) {
                                put("content", msg.content)
                            } else {
                                putJsonArray("content") {
                                    msg.images.forEach { image ->
                                        val b64 = Base64.getEncoder().encodeToString(image.bytes)
                                        val mime = image.mimeType.ifBlank { "image/jpeg" }
                                        addJsonObject {
                                            put("type", "image")
                                            putJsonObject("source") {
                                                put("type", "base64")
                                                put("media_type", mime)
                                                put("data", b64)
                                            }
                                        }
                                    }
                                    if (msg.content.isNotBlank()) {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", msg.content)
                                        }
                                    }
                                }
                            }
                        }
                        idx++
                    }
                    AiChatRole.ASSISTANT -> {
                        addJsonObject {
                            put("role", "assistant")
                            if (msg.toolCalls.isEmpty()) {
                                put("content", msg.content)
                            } else {
                                putJsonArray("content") {
                                    if (msg.content.isNotBlank()) {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", msg.content)
                                        }
                                    }
                                    msg.toolCalls.forEach { call ->
                                        addJsonObject {
                                            put("type", "tool_use")
                                            put("id", call.id)
                                            put("name", call.name)
                                            put("input", parseJsonObjectOrEmpty(call.argumentsJson))
                                        }
                                    }
                                }
                            }
                        }
                        idx++
                    }
                    AiChatRole.TOOL -> {
                        // Coalesce consecutive tool results into one user turn as required by Anthropic API.
                        val groupedToolResults = mutableListOf<AiChatMessage>()
                        while (idx < messages.size && messages[idx].role == AiChatRole.TOOL) {
                            groupedToolResults += messages[idx]
                            idx++
                        }
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                groupedToolResults.forEach { toolMsg ->
                                    addJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", toolMsg.toolCallId)
                                        put("content", toolMsg.content)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", parseSchemaJsonObject(tool.parametersSchemaJson))
                    }
                }
            }
        }
    }.toString()
}

internal fun parseChatTurnResponse(
    protocol: AiApiProtocol,
    responseBody: String,
): AiChatTurnResult.Reply? {
    val root = runCatching { WIRE_JSON.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
        ?: return null
    return when (protocol) {
        AiApiProtocol.OPENAI_CHAT -> parseOpenAiChatResponse(root)
        AiApiProtocol.OPENAI_RESPONSES -> parseOpenAiResponsesResponse(root)
        AiApiProtocol.ANTHROPIC_MESSAGES -> parseAnthropicMessagesResponse(root)
    }
}

internal fun extractAssistantText(
    protocol: AiApiProtocol,
    responseBody: String,
): String? = parseChatTurnResponse(protocol, responseBody)?.text?.takeIf { it.isNotBlank() }

private fun parseOpenAiChatResponse(root: JsonObject): AiChatTurnResult.Reply? {
    val choices = root["choices"] as? JsonArray ?: return null
    val first = choices.firstOrNull() as? JsonObject ?: return null
    val message = first["message"] as? JsonObject ?: return null
    val text = when (val content = message["content"]) {
        is JsonPrimitive -> content.takeIf { it.isString }?.content.orEmpty()
        is JsonArray -> content.mapNotNull { part ->
            val partObj = part as? JsonObject ?: return@mapNotNull null
            (partObj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.joinToString("\n")
        else -> ""
    }
    val toolCalls = (message["tool_calls"] as? JsonArray)?.mapIndexedNotNull { index, item ->
        val obj = item as? JsonObject ?: return@mapIndexedNotNull null
        val fn = obj["function"] as? JsonObject ?: return@mapIndexedNotNull null
        val name = (fn["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (name.isEmpty()) return@mapIndexedNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "call_${index + 1}"
        val rawArgs = fn["arguments"]
        val argsJson = when (rawArgs) {
            is JsonPrimitive -> if (rawArgs.isString) rawArgs.content else rawArgs.toString()
            is JsonObject -> rawArgs.toString()
            else -> "{}"
        }
        AiToolCall(id = id, name = name, argumentsJson = argsJson)
    }.orEmpty()
    if (text.isBlank() && toolCalls.isEmpty()) return null
    return AiChatTurnResult.Reply(text = text, toolCalls = toolCalls)
}

private fun parseOpenAiResponsesResponse(root: JsonObject): AiChatTurnResult.Reply? {
    val output = root["output"] as? JsonArray
    val textParts = mutableListOf<String>()
    val toolCalls = mutableListOf<AiToolCall>()

    output?.forEachIndexed { index, item ->
        val obj = item as? JsonObject ?: return@forEachIndexed
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        when (type) {
            "function_call" -> {
                val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val callId = (obj["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                        ?: "call_${index + 1}"
                    val args = when (val rawArgs = obj["arguments"]) {
                        is JsonPrimitive -> if (rawArgs.isString) rawArgs.content else rawArgs.toString()
                        is JsonObject -> rawArgs.toString()
                        else -> "{}"
                    }
                    toolCalls += AiToolCall(id = callId, name = name, argumentsJson = args)
                }
            }
            "message" -> {
                val content = obj["content"] as? JsonArray
                content?.forEach { part ->
                    val partObj = part as? JsonObject ?: return@forEach
                    val partType = (partObj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    if (partType == "output_text" || partType == "text") {
                        val value = (partObj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (!value.isNullOrBlank()) {
                            textParts += value
                        }
                    }
                }
            }
        }
    }

    if (textParts.isEmpty()) {
        val topOutputText = (root["output_text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!topOutputText.isNullOrBlank()) {
            textParts += topOutputText
        }
    }
    val text = textParts.joinToString("\n")
    if (text.isBlank() && toolCalls.isEmpty()) return null
    return AiChatTurnResult.Reply(text = text, toolCalls = toolCalls)
}

private fun parseAnthropicMessagesResponse(root: JsonObject): AiChatTurnResult.Reply? {
    val content = root["content"] as? JsonArray ?: return null
    val textParts = mutableListOf<String>()
    val toolCalls = mutableListOf<AiToolCall>()

    content.forEachIndexed { index, item ->
        val obj = item as? JsonObject ?: return@forEachIndexed
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        when (type) {
            "text" -> {
                val value = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!value.isNullOrBlank()) {
                    textParts += value
                }
            }
            "tool_use" -> {
                val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "toolu_${index + 1}"
                    val inputJson = (obj["input"] as? JsonObject)?.toString() ?: "{}"
                    toolCalls += AiToolCall(id = id, name = name, argumentsJson = inputJson)
                }
            }
        }
    }

    val text = textParts.joinToString("\n")
    if (text.isBlank() && toolCalls.isEmpty()) return null
    return AiChatTurnResult.Reply(text = text, toolCalls = toolCalls)
}

private fun stripKnownSuffixes(
    trimmed: String,
    uri: URI,
    keepSuffix: String,
): String {
    val path = uri.path?.trimEnd('/').orEmpty()
    if (path.endsWith(keepSuffix, ignoreCase = true)) {
        return trimmed
    }
    KNOWN_ENDPOINT_SUFFIXES.forEach { suffix ->
        if (path.endsWith(suffix, ignoreCase = true)) {
            return trimmed.dropLast(suffix.length).trimEnd('/')
        }
    }
    return trimmed
}

private fun parseJsonObjectOrEmpty(raw: String): JsonObject {
    if (raw.isBlank()) return JsonObject(emptyMap())
    return runCatching { WIRE_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: JsonObject(emptyMap())
}

private fun parseSchemaJsonObject(raw: String): JsonObject {
    val parsed = parseJsonObjectOrEmpty(raw)
    if (parsed.isNotEmpty()) return parsed
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }
}
