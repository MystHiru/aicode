package com.aicode.feature.agent.domain.plugin.hostapi

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.mcp.JsonRpcError
import com.aicode.feature.agent.domain.mcp.JsonRpcResponse
import com.aicode.feature.agent.domain.plugin.PluginHookGateway
import com.aicode.feature.agent.domain.plugin.PluginSessionCommand
import com.aicode.feature.agent.domain.plugin.PluginSessionCommandBus
import com.aicode.feature.agent.domain.plugin.SessionActivityRegistry
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 插件 client.session.* API：会话 CRUD + 消息查询 + 子代理派发。
 *
 * 安全边界：只读操作直接走 DAO；写操作（create/prompt/delete/update）经命令总线交 ViewModel 执行。
 * 事件派发经 [Provider] 懒加载 PluginHookGateway，运行时（UDS 请求到达时）PluginManager 必然已构造完成。
 */
@Singleton
class SessionHostApi @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val workspaceRepository: WorkspaceRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val subAgentEventBus: SubAgentEventBus,
    private val sessionActivityRegistry: SessionActivityRegistry,
    private val sessionCommandBus: PluginSessionCommandBus,
    private val pluginGatewayProvider: Provider<PluginHookGateway>,
    private val messageMapper: MessageMapper
) {

    private companion object {
        const val TAG = "SessionHostApi"
        const val DEFAULT_SESSION_TITLE = "新会话"
        const val DEFAULT_SUBAGENT_TITLE = "子代理任务"

        fun pluginTag(plugin: String?): String = plugin?.let { " plugin=$it" } ?: ""

        /** 内置 agent 定义（对齐 opencode 内置 agent 命名；AiCode 无 agent 定义系统，id 仅记录为子会话 subagentType）。 */
        val BUILTIN_AGENTS = listOf(
            BuiltinAgent("general", "通用", "通用子代理，适合大多数任务"),
            BuiltinAgent("build", "构建", "偏重编码与构建任务的子代理"),
            BuiltinAgent("plan", "规划", "偏重分析与规划的子代理")
        )
    }

    /** client.session.get：返回单个会话信息（对齐 opencode Session 字段命名）。 */
    suspend fun handleSessionGet(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.get 缺少 id")
        FileLogger.d(TAG, "插件查询会话: id=$id" + pluginTag(plugin))
        val session = chatSessionDao.getById(id) ?: return error(-32004, "session not found: $id")
        return ok(messageMapper.sessionToJson(session))
    }

    /** client.session.list：返回当前工作区会话列表。 */
    suspend fun handleSessionList(plugin: String?): JsonRpcResponse {
        FileLogger.d(TAG, "插件查询会话列表" + pluginTag(plugin))
        val path = workspaceRepository.currentPath()
        val sessions = chatSessionDao.getAllSessionsByWorkspaceOnce(path)
        return ok(buildJsonArray {
            sessions.forEach { s -> add(messageMapper.sessionToJson(s)) }
        })
    }

    /** client.session.children：返回指定会话的全部子会话（子代理）。 */
    suspend fun handleSessionChildren(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.children 缺少 id")
        FileLogger.d(TAG, "插件查询子会话: id=$id" + pluginTag(plugin))
        if (chatSessionDao.getById(id) == null) return error(-32004, "session not found: $id")
        val children = chatSessionDao.getSubSessionsByParentOnce(id)
        return ok(buildJsonArray {
            children.forEach { s -> add(messageMapper.sessionToJson(s)) }
        })
    }

    /** client.session.create：创建会话。body.parentID 存在时创建子代理会话。 */
    suspend fun handleSessionCreate(params: JsonObject, plugin: String?): JsonRpcResponse {
        val body = (params["body"] as? JsonObject) ?: buildJsonObject { }
        val title = (body["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val parentId = (body["parentID"] as? JsonPrimitive)?.contentOrNull?.trim()
        val pluginLog = pluginTag(plugin)

        val entity = if (parentId.isNullOrEmpty()) {
            val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
            val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
            newSessionEntity(
                workspacePath = workspaceRepository.currentPath(),
                title = title.ifBlank { DEFAULT_SESSION_TITLE },
                providerId = providerId,
                model = model
            )
        } else {
            val parent = chatSessionDao.getById(parentId)
                ?: return error(-32004, "parent session not found: $parentId")
            if (parent.parentId != null) {
                return error(-32602, "子代理会话不能作为父会话（禁止嵌套）")
            }
            newSubSessionEntity(
                title = title.ifBlank { DEFAULT_SUBAGENT_TITLE },
                parent = parent
            )
        }
        chatSessionDao.upsert(entity)

        pluginGatewayProvider.get().notifyEvent("session.created", buildJsonObject {
            put("sessionID", entity.id)
            put("title", entity.title)
            entity.parentId?.let { put("parentID", it) }
            entity.subagentType?.let { put("subagentType", it) }
        })
        FileLogger.i(TAG, "插件创建会话: id=${entity.id} parent=${entity.parentId ?: "-"}$pluginLog")
        return ok(messageMapper.sessionToJson(entity))
    }

    /** client.session.prompt：向会话发送消息触发 AI 回复。 */
    suspend fun handleSessionPrompt(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.prompt 缺少 id")
        if (chatSessionDao.getById(id) == null) return error(-32004, "session not found: $id")
        val body = (params["body"] as? JsonObject) ?: buildJsonObject { }
        val pluginLog = pluginTag(plugin)
        val parts = body["parts"] as? JsonArray
        val text = extractTextParts(parts)
        val subtasks = extractSubtaskParts(parts)
        if (text.isBlank() && subtasks.isEmpty()) {
            return error(-32602, "session.prompt 需要至少一个 text 或 subtask part")
        }
        if (subtasks.isNotEmpty()) {
            val parent = chatSessionDao.getById(id) ?: return error(-32004, "session not found: $id")
            if (subAgentEventBus.isFull) {
                return error(-32602, "子代理已达上限（最多 5 个同时运行），请等待完成后再派发")
            }
            for (st in subtasks) {
                if (st.prompt.isBlank()) return error(-32602, "subtask part 缺少 prompt")
            }
            val created = buildJsonArray {
                for (st in subtasks) {
                    val sub = newSubSessionEntity(
                        title = st.description.ifBlank { st.agent.ifBlank { DEFAULT_SUBAGENT_TITLE } }.take(30),
                        parent = parent,
                        subagentType = st.agent.ifBlank { "subagent" }
                    )
                    chatSessionDao.upsert(sub)
                    pluginGatewayProvider.get().notifyEvent("session.created", buildJsonObject {
                        put("sessionID", sub.id)
                        put("parentID", id)
                        put("subagentType", sub.subagentType ?: "subagent")
                        put("title", sub.title)
                    })
                    subAgentEventBus.emit(
                        SubAgentEvent(sub.id, id, SubAgentEventType.SPAWNED, detail = st.prompt)
                    )
                    add(messageMapper.sessionToJson(sub))
                }
            }
            FileLogger.i(TAG, "插件 subtask 派发: parent=$id count=${subtasks.size}$pluginLog")
            return ok(buildJsonObject { putJsonArray("subagents") { created.forEach { add(it) } } })
        }
        if (sessionActivityRegistry.isRunning(id)) {
            return error(-32602, "会话正在运行中，请等待完成后再 prompt")
        }
        val noReply = (body["noReply"] as? JsonPrimitive)?.booleanOrNull ?: false
        val model = (body["model"] as? JsonObject)?.let { m ->
            val providerId = (m["providerID"] as? JsonPrimitive)?.contentOrNull
            val modelId = (m["modelID"] as? JsonPrimitive)?.contentOrNull
            if (!providerId.isNullOrBlank() && !modelId.isNullOrBlank()) providerId to modelId else null
        }
        sessionCommandBus.emit(
            PluginSessionCommand.Prompt(sessionId = id, text = text, noReply = noReply, model = model)
        )
        FileLogger.i(TAG, "插件 prompt: session=$id noReply=$noReply text=${text.take(80)}$pluginLog")
        return ok()
    }

    /** client.session.status：返回运行中会话（对齐 opencode Record<sessionID, {type}>）。 */
    fun handleSessionStatus(plugin: String?): JsonRpcResponse {
        FileLogger.d(TAG, "插件查询运行中会话" + pluginTag(plugin))
        return ok(buildJsonObject {
            sessionActivityRegistry.running.value.forEach { put(it, buildJsonObject { put("type", "busy") }) }
        })
    }

    /** client.session.delete：删除会话（含全部子代理会话与消息）。 */
    suspend fun handleSessionDelete(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.delete 缺少 id")
        FileLogger.d(TAG, "插件删除会话: id=$id" + pluginTag(plugin))
        if (chatSessionDao.getById(id) == null) return error(-32004, "session not found: $id")
        if (sessionActivityRegistry.isRunning(id)) {
            subAgentEventBus.emit(
                SubAgentEvent(
                    subSessionId = id,
                    parentSessionId = id,
                    type = SubAgentEventType.STOPPED
                )
            )
        }
        sessionCommandBus.emit(PluginSessionCommand.Delete(id))
        return ok(JsonPrimitive(true))
    }

    /** client.session.update：更新会话元数据（目前仅 title）。 */
    suspend fun handleSessionUpdate(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.update 缺少 id")
        val session = chatSessionDao.getById(id) ?: return error(-32004, "session not found: $id")
        val body = (params["body"] as? JsonObject) ?: buildJsonObject { }
        val title = (body["title"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (!title.isNullOrBlank()) {
            FileLogger.d(TAG, "插件更新会话: id=$id title=$title" + pluginTag(plugin))
            chatSessionDao.updateTitle(id, title)
            pluginGatewayProvider.get().notifyEvent("session.updated", buildJsonObject { put("sessionID", id) })
        }
        return ok(messageMapper.sessionToJson(chatSessionDao.getById(id) ?: session))
    }

    /** client.session.messages：返回指定会话的历史消息（最近 100 条），形状对齐 opencode `{info, parts}[]`。 */
    suspend fun handleSessionMessages(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.messages 缺少 id")
        FileLogger.d(TAG, "插件查询会话消息: id=$id" + pluginTag(plugin))
        val session = chatSessionDao.getById(id)
        val rows = agentMessageDao.getMessagesBySessionOnce(id).takeLast(100)
        return ok(messageMapper.toOpenCodeMessages(rows, session))
    }

    /** 普通根会话实体（对齐 SessionUseCase.newSessionEntity）。 */
    private fun newSessionEntity(
        workspacePath: String,
        title: String,
        providerId: String?,
        model: String?
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            workspacePath = workspacePath,
            createdAt = now,
            updatedAt = now,
            providerId = providerId,
            model = model,
            reasoningEffort = "MEDIUM"
        )
    }

    /** 子代理会话实体（对齐 SessionUseCase.newSubSessionEntity，继承父会话配置）。 */
    private fun newSubSessionEntity(
        title: String,
        parent: ChatSessionEntity,
        subagentType: String = "subagent"
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            workspacePath = parent.workspacePath,
            createdAt = now,
            updatedAt = now,
            providerId = parent.providerId,
            model = parent.model,
            reasoningEffort = parent.reasoningEffort,
            parentId = parent.id,
            subagentType = subagentType
        )
    }

    /** 提取 parts 中全部 text 部分（换行拼接）。 */
    private fun extractTextParts(parts: JsonArray?): String {
        if (parts == null) return ""
        return parts.mapNotNull { el ->
            val obj = (el as? JsonObject) ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.content == "text") {
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            } else {
                null
            }
        }.filter { it.isNotBlank() }.joinToString("\n")
    }

    /** 提取 parts 中全部 subtask part（opencode 子代理派发协议）。 */
    private fun extractSubtaskParts(parts: JsonArray?): List<SubtaskPart> {
        if (parts == null) return emptyList()
        return parts.mapNotNull { el ->
            val obj = (el as? JsonObject) ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.content == "subtask") {
                SubtaskPart(
                    prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull ?: "",
                    description = (obj["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                    agent = (obj["agent"] as? JsonPrimitive)?.contentOrNull ?: ""
                )
            } else {
                null
            }
        }
    }

    private fun ok(result: JsonElement = buildJsonObject { }): JsonRpcResponse = JsonRpcResponse(result = result)
    private fun error(code: Int, message: String): JsonRpcResponse =
        JsonRpcResponse(error = JsonRpcError(code, message))
}

/** 内置 agent 定义（client.app.agents.list 返回）。 */
internal data class BuiltinAgent(val id: String, val name: String, val description: String)

/** 单个 subtask part（opencode 子代理派发协议）。 */
private data class SubtaskPart(val prompt: String, val description: String, val agent: String)
