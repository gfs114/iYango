package com.gfs.iyango.data.ai

import com.gfs.iyango.core.domain.AiApiProtocol
import com.gfs.iyango.core.domain.AiChatTurnResult
import com.gfs.iyango.core.domain.AiToolCall
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_SSE_STREAM_CHARS = 4 * 1024 * 1024

/**
 * 统一三协议（OpenAI Chat / OpenAI Responses / Anthropic Messages）SSE 流式增量解析器。
 */
internal class AiSseAccumulator(
    private val protocol: AiApiProtocol,
    private val onTextDelta: (String) -> Unit,
) {
    private val textBuffer = StringBuilder()

    // OpenAI Chat: tool_calls 按 index 增量拼接
    private class MutableOpenAiTool(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )
    private val openAiTools = linkedMapOf<Int, MutableOpenAiTool>()

    // OpenAI Responses: 按 output_index 或 item_id 拼装函数调用
    private class MutableResponsesTool(
        var id: String = "",
        var callId: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )
    private val responsesToolsByItemId = linkedMapOf<String, MutableResponsesTool>()
    private val responsesToolsByIndex = linkedMapOf<Int, MutableResponsesTool>()
    private var responsesCompletedReply: AiChatTurnResult.Reply? = null

    // Anthropic Messages: 按 content_block index 拼装 tool_use
    private class MutableAnthropicTool(
        var id: String = "",
        var name: String = "",
        val input: StringBuilder = StringBuilder(),
    )
    private val anthropicTools = linkedMapOf<Int, MutableAnthropicTool>()

    fun onEvent(eventType: String, rawData: String) {
        val data = rawData.trim()
        if (data.isEmpty() || data == "[DONE]") return
        val root = runCatching { SSE_JSON.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return
        when (protocol) {
            AiApiProtocol.OPENAI_CHAT -> handleOpenAiChatEvent(root)
            AiApiProtocol.OPENAI_RESPONSES -> handleOpenAiResponsesEvent(eventType, root)
            AiApiProtocol.ANTHROPIC_MESSAGES -> handleAnthropicEvent(eventType, root)
        }
    }

    fun finish(): AiChatTurnResult.Reply? {
        val text = textBuffer.toString()
        val toolCalls = when (protocol) {
            AiApiProtocol.OPENAI_CHAT -> openAiTools.entries
                .sortedBy { it.key }
                .mapNotNull { (index, item) ->
                    val name = item.name.trim()
                    if (name.isEmpty()) return@mapNotNull null
                    AiToolCall(
                        id = item.id.trim().ifEmpty { "call_${index + 1}" },
                        name = name,
                        argumentsJson = item.args.toString().ifBlank { "{}" },
                    )
                }
            AiApiProtocol.OPENAI_RESPONSES -> {
                val ordered = if (responsesToolsByIndex.isNotEmpty()) {
                    responsesToolsByIndex.entries.sortedBy { it.key }.map { it.value }
                } else {
                    responsesToolsByItemId.values.toList()
                }
                val streamedTools = ordered.mapIndexedNotNull { idx, item ->
                    val name = item.name.trim()
                    if (name.isEmpty()) return@mapIndexedNotNull null
                    AiToolCall(
                        id = item.callId.trim().ifEmpty { item.id.trim() }.ifEmpty { "call_${idx + 1}" },
                        name = name,
                        argumentsJson = item.args.toString().ifBlank { "{}" },
                    )
                }
                if (streamedTools.isNotEmpty()) streamedTools
                else responsesCompletedReply?.toolCalls.orEmpty()
            }
            AiApiProtocol.ANTHROPIC_MESSAGES -> anthropicTools.entries
                .sortedBy { it.key }
                .mapNotNull { (index, item) ->
                    val name = item.name.trim()
                    if (name.isEmpty()) return@mapNotNull null
                    AiToolCall(
                        id = item.id.trim().ifEmpty { "toolu_${index + 1}" },
                        name = name,
                        argumentsJson = item.input.toString().ifBlank { "{}" },
                    )
                }
        }
        val finalText = if (text.isNotBlank()) text else responsesCompletedReply?.text.orEmpty()
        if (finalText.isBlank() && toolCalls.isEmpty()) return null
        return AiChatTurnResult.Reply(text = finalText, toolCalls = toolCalls)
    }

    private fun handleOpenAiChatEvent(root: JsonObject) {
        val choices = root["choices"] as? JsonArray ?: return
        val first = choices.firstOrNull() as? JsonObject ?: return
        val delta = first["delta"] as? JsonObject ?: return

        val contentDelta = (delta["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!contentDelta.isNullOrEmpty()) {
            textBuffer.append(contentDelta)
            onTextDelta(contentDelta)
        }

        val toolCalls = delta["tool_calls"] as? JsonArray ?: return
        toolCalls.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val idx = (obj["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val holder = openAiTools.getOrPut(idx) { MutableOpenAiTool() }
            (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let {
                holder.id = it
            }
            val fn = obj["function"] as? JsonObject ?: return@forEach
            (fn["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let {
                holder.name += it
            }
            (fn["arguments"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
                holder.args.append(it)
            }
        }
    }

    private fun handleOpenAiResponsesEvent(eventType: String, root: JsonObject) {
        val resolvedType = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: eventType
        when (resolvedType) {
            "response.output_text.delta" -> {
                val delta = (root["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!delta.isNullOrEmpty()) {
                    textBuffer.append(delta)
                    onTextDelta(delta)
                }
            }
            "response.output_item.added" -> {
                val item = root["item"] as? JsonObject ?: return
                if ((item["type"] as? JsonPrimitive)?.content != "function_call") return
                val outputIndex = (root["output_index"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: responsesToolsByIndex.size
                val itemId = (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val callId = (item["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val name = (item["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val args = (item["arguments"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val holder = responsesToolsByIndex.getOrPut(outputIndex) { MutableResponsesTool() }
                if (itemId.isNotEmpty()) holder.id = itemId
                if (callId.isNotEmpty()) holder.callId = callId
                if (name.isNotEmpty()) holder.name = name
                if (args.isNotEmpty() && holder.args.isEmpty()) holder.args.append(args)
                if (itemId.isNotEmpty()) responsesToolsByItemId[itemId] = holder
            }
            "response.function_call_arguments.delta" -> {
                val itemId = (root["item_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val outputIndex = (root["output_index"] as? JsonPrimitive)?.content?.toIntOrNull()
                val holder = responsesToolsByItemId[itemId]
                    ?: outputIndex?.let { responsesToolsByIndex.getOrPut(it) { MutableResponsesTool(id = itemId) } }
                    ?: return
                val delta = (root["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                holder.args.append(delta)
            }
            "response.function_call_arguments.done" -> {
                val itemId = (root["item_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val outputIndex = (root["output_index"] as? JsonPrimitive)?.content?.toIntOrNull()
                val holder = responsesToolsByItemId[itemId]
                    ?: outputIndex?.let { responsesToolsByIndex[it] }
                    ?: return
                val fullArgs = (root["arguments"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!fullArgs.isNullOrBlank()) {
                    holder.args.setLength(0)
                    holder.args.append(fullArgs)
                }
            }
            "response.completed" -> {
                val responseObj = root["response"] as? JsonObject ?: return
                responsesCompletedReply = parseChatTurnResponse(
                    AiApiProtocol.OPENAI_RESPONSES,
                    responseObj.toString(),
                )
                if (textBuffer.isEmpty()) {
                    val fallbackText = responsesCompletedReply?.text.orEmpty()
                    if (fallbackText.isNotEmpty()) {
                        textBuffer.append(fallbackText)
                        onTextDelta(fallbackText)
                    }
                }
            }
        }
    }

    private fun handleAnthropicEvent(eventType: String, root: JsonObject) {
        val resolvedType = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: eventType
        when (resolvedType) {
            "content_block_start" -> {
                val index = (root["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return
                val block = root["content_block"] as? JsonObject ?: return
                if ((block["type"] as? JsonPrimitive)?.content == "tool_use") {
                    val holder = anthropicTools.getOrPut(index) { MutableAnthropicTool() }
                    (block["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { holder.id = it }
                    (block["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { holder.name = it }
                }
            }
            "content_block_delta" -> {
                val index = (root["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val delta = root["delta"] as? JsonObject ?: return
                when ((delta["type"] as? JsonPrimitive)?.content) {
                    "text_delta" -> {
                        val text = (delta["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (!text.isNullOrEmpty()) {
                            textBuffer.append(text)
                            onTextDelta(text)
                        }
                    }
                    "input_json_delta" -> {
                        val partial = (delta["partial_json"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (!partial.isNullOrEmpty()) {
                            anthropicTools.getOrPut(index) { MutableAnthropicTool() }.input.append(partial)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val SSE_JSON = Json { ignoreUnknownKeys = true }

        /**
         * 从 [reader] 逐行读取 SSE 帧（`event:` / `data:` + 空行提交），按 [isActive] 响应取消。
         */
        fun consumeSseReader(
            reader: Reader,
            protocol: AiApiProtocol,
            isActive: () -> Boolean = { true },
            onTextDelta: (String) -> Unit,
        ): AiChatTurnResult.Reply? {
            val accumulator = AiSseAccumulator(protocol, onTextDelta)
            val buffered = reader as? BufferedReader ?: BufferedReader(reader)
            var currentEvent = ""
            val dataLines = StringBuilder()
            var totalChars = 0

            fun flushEvent() {
                if (dataLines.isNotEmpty()) {
                    accumulator.onEvent(currentEvent, dataLines.toString())
                    dataLines.setLength(0)
                }
                currentEvent = ""
            }

            while (isActive()) {
                val line = buffered.readLine() ?: break
                totalChars += line.length + 1
                if (totalChars > MAX_SSE_STREAM_CHARS) {
                    throw IOException("流式响应体积超出限制")
                }
                if (line.isEmpty()) {
                    flushEvent()
                    continue
                }
                if (line.startsWith(':')) continue
                when {
                    line.startsWith("event:") -> {
                        currentEvent = line.substringAfter("event:").trim()
                    }
                    line.startsWith("data:") -> {
                        val payload = line.substringAfter("data:").trimStart()
                        if (dataLines.isNotEmpty()) dataLines.append('\n')
                        dataLines.append(payload)
                    }
                }
            }
            if (isActive()) {
                flushEvent()
            }
            return accumulator.finish()
        }
    }
}
