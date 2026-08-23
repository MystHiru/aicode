package com.aicode.feature.agent.domain.plugin.hostapi

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AiCode 消息模型 ↔ opencode `{info, parts}[]` 转换器。
 *
 * AiCode 消息为扁平行（USER/ASSISTANT/TOOL），opencode 为嵌套结构（Message + Part[]）。
 * 转换规则：
 * - USER 行 → UserMessage + text part
 * - ASSISTANT 行 → AssistantMessage + text part（reasoning 存在时追加 reasoning part）
 * - TOOL 行 → 合并为最近一条 assistant 消息的 ToolPart（toolCallId 匹配优先）
 *
 * 供 SessionHostApi（client.session.messages）与未来的 experimental.chat.messages.transform hook 共用。
 */
@Singleton
class MessageMapper @Inject constructor() {

    /**
     * 把指定会话的消息行转换为 opencode `{info: Message, parts: Part[]}[]` 格式。
     * @param rows 会话消息行（按 timestamp 升序）
     * @param session 关联的会话实体（用于填充 model/agent/path 等字段）
     */
    fun toOpenCodeMessages(
        rows: List<AgentMessageEntity>,
        session: ChatSessionEntity?
    ): JsonArray {
        val built = mutableListOf<Pair<JsonObject, MutableList<JsonObject>>>()
        var lastMessageId: String? = null
        for (m in rows) {
            when (m.role) {
                MessageRole.USER.name -> {
                    built.add(userInfoToJson(m, session) to mutableListOf(textPartToJson(m)))
                    lastMessageId = m.id
                }
                MessageRole.ASSISTANT.name -> {
                    val parts = mutableListOf(textPartToJson(m))
                    m.reasoning?.takeIf { it.isNotBlank() }?.let { parts.add(reasoningPartToJson(m, it)) }
                    built.add(assistantInfoToJson(m, session, lastMessageId) to parts)
                    lastMessageId = m.id
                }
                MessageRole.TOOL.name -> {
                    // 无归属 assistant 的 TOOL 行直接丢弃（opencode 语义：tool part 挂在 assistant 消息下）
                    built.lastOrNull()?.second?.let { parts ->
                        toolPartToJson(m)?.let { parts.add(it) }
                    }
                }
            }
        }
        return buildJsonArray {
            built.forEach { (info, parts) ->
                add(buildJsonObject {
                    put("info", info)
                    putJsonArray("parts") { parts.forEach { add(it) } }
                })
            }
        }
    }

    /** 会话实体 → opencode Session 形状 JSON（对齐 SDK Session 类型）。
     *  AiCode 特有字段（mode/reasoningEffort/isPinned）收进 metadata，不污染顶级字段。 */
    fun sessionToJson(s: ChatSessionEntity): JsonObject = buildJsonObject {
        put("id", s.id)
        put("slug", s.id)
        put("projectID", projectHash(s.workspacePath))
        put("directory", s.workspacePath)
        put("title", s.title)
        put("version", "1")
        putJsonObject("time") {
            put("created", s.createdAt / 1000)
            put("updated", s.updatedAt / 1000)
        }
        if (!s.providerId.isNullOrBlank() && !s.model.isNullOrBlank()) {
            putJsonObject("model") {
                put("id", s.model)
                put("providerID", s.providerId)
            }
        }
        s.parentId?.let { put("parentID", it) }
        s.subagentType?.let { put("agent", it) }
        if (s.totalInputTokens > 0 || s.totalOutputTokens > 0) {
            putJsonObject("tokens") {
                put("input", s.totalInputTokens)
                put("output", s.totalOutputTokens)
                put("reasoning", 0)
                putJsonObject("cache") {
                    put("read", 0)
                    put("write", 0)
                }
            }
        }
        putJsonObject("metadata") {
            put("mode", s.mode)
            put("reasoningEffort", s.reasoningEffort)
            put("isPinned", s.isPinned)
        }
    }

    /** USER 消息行 → opencode UserMessage 形状（info 部分）。 */
    private fun userInfoToJson(m: AgentMessageEntity, session: ChatSessionEntity?): JsonObject = buildJsonObject {
        put("id", m.id)
        put("sessionID", m.sessionId)
        put("role", "user")
        putJsonObject("time") { put("created", m.timestamp / 1000) }
        put("agent", session?.subagentType ?: "")
        putJsonObject("model") {
            put("providerID", session?.providerId ?: "")
            put("modelID", session?.model ?: "")
        }
    }

    /** ASSISTANT 消息行 → opencode AssistantMessage 形状（info 部分）。parentID 为前一条消息 id（v1 必填）。 */
    private fun assistantInfoToJson(
        m: AgentMessageEntity,
        session: ChatSessionEntity?,
        parentId: String?
    ): JsonObject = buildJsonObject {
        put("id", m.id)
        put("sessionID", m.sessionId)
        put("role", "assistant")
        putJsonObject("time") {
            put("created", m.timestamp / 1000)
            put("completed", m.timestamp / 1000)
        }
        put("parentID", parentId ?: m.id)
        put("modelID", session?.model ?: "")
        put("providerID", session?.providerId ?: "")
        put("mode", session?.mode ?: "")
        put("agent", session?.subagentType ?: "")
        putJsonObject("path") {
            put("cwd", session?.workspacePath ?: "")
            put("root", session?.workspacePath ?: "")
        }
        put("cost", 0)
        putJsonObject("tokens") {
            put("input", m.inputTokens)
            put("output", m.outputTokens)
            put("reasoning", 0)
            putJsonObject("cache") {
                put("read", 0)
                put("write", 0)
            }
        }
    }

    /** 消息行 → opencode TextPart。AiCode 无 Part 概念，part id 由消息 id 派生。 */
    private fun textPartToJson(m: AgentMessageEntity): JsonObject = buildJsonObject {
        put("id", "prt-" + m.id)
        put("sessionID", m.sessionId)
        put("messageID", m.id)
        put("type", "text")
        put("text", m.content)
    }

    /** ASSISTANT 消息行的思考过程 → opencode ReasoningPart（time 为 v1 必填）。 */
    private fun reasoningPartToJson(m: AgentMessageEntity, text: String): JsonObject = buildJsonObject {
        put("id", "prt-" + m.id + "-r")
        put("sessionID", m.sessionId)
        put("messageID", m.id)
        put("type", "reasoning")
        put("text", text)
        putJsonObject("time") {
            put("start", m.timestamp / 1000)
            put("end", m.timestamp / 1000)
        }
    }

    /** TOOL 消息行 → opencode ToolPart（state=completed/error）。toolArgs 解析失败时 input 为空对象。 */
    private fun toolPartToJson(m: AgentMessageEntity): JsonObject? {
        val toolName = m.toolName ?: return null
        return buildJsonObject {
            put("id", "prt-" + m.id)
            put("sessionID", m.sessionId)
            put("messageID", m.id)
            put("type", "tool")
            put("callID", m.toolCallId ?: m.id)
            put("tool", toolName)
            putJsonObject("state") {
                val input = m.toolArgs?.let {
                    runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
                } ?: buildJsonObject { }
                put("input", input)
                if (m.isError) {
                    put("status", "error")
                    put("error", m.content)
                } else {
                    put("status", "completed")
                    put("output", m.content)
                    put("title", toolName)
                    putJsonObject("metadata") { }
                }
                putJsonObject("time") {
                    put("start", m.timestamp / 1000)
                    put("end", m.timestamp / 1000)
                }
            }
        }
    }

    /** 工作区路径 → 稳定短 hash（与 runner.mjs projectHash 同算法，作为 projectID）。 */
    private fun projectHash(dir: String): String {
        var h = 0
        for (c in dir) h = (h shl 5) - h + c.code
        return Math.abs(h.toLong()).toString(36)
    }

    companion object {
        /**
         * 把 opencode `{info, parts}[]` 格式反向转换为 AiCode AgentMessage 列表。
         * 用于 experimental.chat.messages.transform hook（插件改写后回写到 AiCode 模型）。
         *
         * 与 [toOpenCodeMessages] 互逆（对 TOOL 行合并进 assistant tool part 的格式同样兼容）：
         * - text part → content（多段拼接）
         * - reasoning part → AssistantMessage.reasoning
         * - assistant 下 pending/running 的 tool part → AssistantMessage.toolCalls
         * - assistant 下已完成/出错的 tool part → 拆出 ToolCall + 紧跟一条 ToolResultMessage
         * - 独立 role=tool 消息（tool part）→ ToolResultMessage
         *
         * 保证工具调用链（assistant tool_calls ↔ tool 结果）在插件改写后仍能完整回写到模型上下文，
         * 避免工具循环轮次上下文缺失导致模型重复调用同一工具。
         */
        fun fromOpenCodeMessages(json: JsonArray): List<AgentMessage> {
            val result = mutableListOf<AgentMessage>()
            for (el in json) {
                val obj = el as? JsonObject ?: continue
                val info = obj["info"] as? JsonObject ?: continue
                val parts = obj["parts"] as? JsonArray ?: continue
                val role = (info["role"] as? JsonPrimitive)?.contentOrNull ?: continue
                val id = (info["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val signature = (info["signature"] as? JsonPrimitive)?.contentOrNull

                val textParts = mutableListOf<String>()
                val reasoningParts = mutableListOf<String>()
                val toolCalls = mutableListOf<ToolCall>()
                // 已完成/出错的 tool part：callID → (toolName, output)，逐个拆为 ToolResultMessage。
                val completedTools = mutableListOf<Triple<String, String, String>>()

                for (p in parts) {
                    val pObj = p as? JsonObject ?: continue
                    when ((pObj["type"] as? JsonPrimitive)?.contentOrNull) {
                        "text" -> (pObj["text"] as? JsonPrimitive)?.contentOrNull?.let { textParts.add(it) }
                        "reasoning" -> (pObj["text"] as? JsonPrimitive)?.contentOrNull?.let { reasoningParts.add(it) }
                        "tool" -> {
                            val callID = (pObj["callID"] as? JsonPrimitive)?.contentOrNull ?: continue
                            val toolName = (pObj["tool"] as? JsonPrimitive)?.contentOrNull ?: continue
                            val state = pObj["state"] as? JsonObject
                            val status = (state?.get("status") as? JsonPrimitive)?.contentOrNull
                            val input = (state?.get("input") as? JsonObject)?.toMap().orEmpty()
                            toolCalls.add(ToolCall(id = callID, name = toolName, arguments = input))
                            if (status == "completed" || status == "error") {
                                val output = (state?.get("output") as? JsonPrimitive)?.contentOrNull
                                    ?: (state?.get("error") as? JsonPrimitive)?.contentOrNull
                                    ?: ""
                                completedTools.add(Triple(callID, toolName, output))
                            }
                        }
                    }
                }

                val content = textParts.joinToString("\n")
                when (role) {
                    "user" -> {
                        result.add(AgentMessage.UserMessage(id = id, content = content))
                    }
                    "assistant" -> {
                        if (content.isNotBlank() || toolCalls.isNotEmpty() || reasoningParts.isNotEmpty()) {
                            result.add(
                                AgentMessage.AssistantMessage(
                                    id = id,
                                    content = content,
                                    toolCalls = toolCalls,
                                    reasoning = reasoningParts.joinToString("\n"),
                                    signature = signature ?: ""
                                )
                            )
                            completedTools.forEach { (callID, toolName, output) ->
                                result.add(
                                    AgentMessage.ToolResultMessage(
                                        id = callID,
                                        toolName = toolName,
                                        result = output
                                    )
                                )
                            }
                        }
                    }
                    "tool" -> {
                        completedTools.forEach { (callID, toolName, output) ->
                            result.add(
                                AgentMessage.ToolResultMessage(
                                    id = callID,
                                    toolName = toolName,
                                    result = output
                                )
                            )
                        }
                    }
                }
            }
            return result
        }
    }
}
