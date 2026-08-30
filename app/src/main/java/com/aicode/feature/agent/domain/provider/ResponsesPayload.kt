package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ResponsesItem
import com.aicode.feature.agent.data.remote.openai.ResponsesPart
import com.aicode.feature.agent.data.remote.openai.ResponsesToolDefinition
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 把模型给出的工具入参 JSON 字符串解析为 JsonObject；为空或非法时回退为空对象。 */
internal fun parseToolArguments(raw: String): JsonObject {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
}

/** 工具定义 → Responses 扁平 function 定义；无工具时返回 null（不发 `tools` 字段）。 */
internal fun buildResponsesTools(tools: List<AgentTool>): List<ResponsesToolDefinition>? =
    tools.takeIf { it.isNotEmpty() }?.map { tool ->
        ResponsesToolDefinition(
            name = tool.name,
            description = tool.description,
            parameters = tool.toJsonSchema()
        )
    }

/**
 * 会话历史 → Responses 的 `input` item 序列。
 *
 * 与 Chat Completions 的三种 role 消息不同，Responses 把工具往返拆成独立 item：
 * 模型的调用是 `function_call`（按 `call_id` 标识），执行结果是 `function_call_output`，
 * 二者不再依附于 assistant 消息与 `role:"tool"` 消息。
 *
 * 配对约束与 Chat Completions 一致且同样必须在客户端保证：`function_call_output` 必须能
 * 找到同 `call_id` 的 `function_call`，声明过的 `function_call` 也必须有结果。这里按调用逐个
 * 吸附其结果并成对写出，顺带处理两类历史脏数据：
 * - 结果乱序落位（如 askUserQuestion 阻塞期间其他工具结果插队）→ 吸附回调用之后；
 * - 孤立结果（前驱调用已被上下文压缩裁掉）→ 丢弃；
 * - 调用无结果（如用户拒绝执行）→ 连调用一起裁掉。
 */
internal fun buildResponsesInput(
    systemPrompt: String,
    systemRole: String,
    messages: List<AgentMessage>
): List<Map<String, Any?>> {
    val items = mutableListOf<Map<String, Any?>>()
    if (systemPrompt.isNotBlank()) {
        items.add(mapOf("role" to systemRole, "content" to systemPrompt))
    }

    val consumed = BooleanArray(messages.size)
    for (i in messages.indices) {
        if (consumed[i]) continue
        when (val message = messages[i]) {
            is AgentMessage.UserMessage -> items.add(
                mapOf("role" to "user", "content" to message.toResponsesContent())
            )

            is AgentMessage.AssistantMessage -> {
                if (message.content.isNotBlank()) {
                    items.add(
                        mapOf(
                            "role" to "assistant",
                            "content" to listOf(
                                mapOf("type" to ResponsesPart.OUTPUT_TEXT, "text" to message.content)
                            )
                        )
                    )
                }
                for (call in message.toolCalls) {
                    val resultIndex = (i + 1 until messages.size).firstOrNull { j ->
                        !consumed[j] && (messages[j] as? AgentMessage.ToolResultMessage)?.id == call.id
                    } ?: continue
                    val result = messages[resultIndex] as AgentMessage.ToolResultMessage
                    consumed[resultIndex] = true
                    items.add(
                        mapOf(
                            "type" to ResponsesItem.FUNCTION_CALL,
                            "call_id" to call.id,
                            "name" to call.name,
                            "arguments" to JsonObject(call.arguments).toString()
                        )
                    )
                    items.add(
                        mapOf(
                            "type" to ResponsesItem.FUNCTION_CALL_OUTPUT,
                            "call_id" to call.id,
                            "output" to result.toResponsesOutput()
                        )
                    )
                }
            }

            is AgentMessage.ToolResultMessage -> consumed[i] = true
        }
    }
    return items
}

/** 用户消息内容：纯文本直接给字符串，带图时拆成 input_text / input_image part 列表。 */
private fun AgentMessage.UserMessage.toResponsesContent(): Any {
    if (images.isEmpty()) return content
    val parts = mutableListOf<Map<String, Any>>()
    if (content.isNotBlank()) {
        parts.add(mapOf("type" to ResponsesPart.INPUT_TEXT, "text" to content))
    }
    images.forEach { parts.add(it.toResponsesImagePart()) }
    return parts
}

/** 工具结果：纯文本直接给字符串，带图时用 part 列表（Responses 允许 function_call_output 内含图片）。 */
private fun AgentMessage.ToolResultMessage.toResponsesOutput(): Any {
    if (images.isEmpty()) return result
    val parts = mutableListOf<Map<String, Any>>()
    if (result.isNotBlank()) {
        parts.add(mapOf("type" to ResponsesPart.INPUT_TEXT, "text" to result))
    }
    images.forEach { parts.add(it.toResponsesImagePart()) }
    return parts
}

private fun AgentImage.toResponsesImagePart(): Map<String, Any> = mapOf(
    "type" to ResponsesPart.INPUT_IMAGE,
    "image_url" to "data:$mimeType;base64,$base64Data",
    "detail" to "auto"
)
