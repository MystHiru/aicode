package com.aicode.feature.agent.domain.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.mcp.JsonRpcError
import com.aicode.feature.agent.domain.mcp.JsonRpcResponse
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
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
 * 宿主 API 处理器：响应 runner 端插件 `client.*` 请求（session 查询/子代理管理、配置读取、结构化日志等）。
 *
 * 由 [PluginClient] 经 UdsTransport 反向通道调用（runner 是请求方、本类是服务方），
 * 数据源为宿主 Room 数据库与 DataStore 配置仓库。
 *
 * 安全边界：只提供**只读**的会话/配置查询与日志/通知，不暴露凭据；写操作仅限
 * session.create/prompt/delete/update（对齐 opencode，供插件驱动子代理）。
 * plugin 的 files.* 由 runner 本地实现。
 *
 * 依赖注意：本类不注入 SessionUseCase（会与 PluginManager 构成 Hilt 循环依赖），
 * 会话写操作直接用 DAO 内联实现；事件派发经 [Provider] 懒加载 PluginHookGateway，
 * 运行时（UDS 请求到达时）PluginManager 必然已构造完成。
 */
@Singleton
class PluginHostApiHandler @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val workspaceRepository: WorkspaceRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val subAgentEventBus: SubAgentEventBus,
    private val sessionActivityRegistry: SessionActivityRegistry,
    private val sessionCommandBus: PluginSessionCommandBus,
    private val pluginGatewayProvider: Provider<PluginHookGateway>,
    @ApplicationContext private val context: Context
) : HostApiHandler {

    private companion object {
        const val TAG = "PluginHostApi"
        /** 插件创建的普通会话默认标题（与 ViewModel createSession 一致）。 */
        const val DEFAULT_SESSION_TITLE = "新会话"
        /** 插件创建的子代理会话默认标题。 */
        const val DEFAULT_SUBAGENT_TITLE = "子代理任务"

        /** 日志用插件名标签（旧版 runner 无 plugin 字段时为空串）。 */
        fun pluginTag(plugin: String?): String = plugin?.let { " plugin=$it" } ?: ""

        /** 内置 agent 定义（对齐 opencode 内置 agent 命名；AiCode 无 agent 定义系统，id 仅记录为子会话 subagentType）。 */
        val BUILTIN_AGENTS = listOf(
            BuiltinAgent("general", "通用", "通用子代理，适合大多数任务"),
            BuiltinAgent("build", "构建", "偏重编码与构建任务的子代理"),
            BuiltinAgent("plan", "规划", "偏重分析与规划的子代理")
        )
    }

    override suspend fun handleRequest(method: String, params: JsonObject, plugin: String?): JsonRpcResponse {
        return try {
            when (method) {
                "client.app.log" -> handleAppLog(params, plugin)
                "client.app.agents.list" -> handleAgentsList(plugin)
                "client.session.get" -> handleSessionGet(params, plugin)
                "client.session.list" -> handleSessionList(plugin)
                "client.session.messages" -> handleSessionMessages(params, plugin)
                "client.session.children" -> handleSessionChildren(params, plugin)
                "client.session.create" -> handleSessionCreate(params, plugin)
                "client.session.prompt" -> handleSessionPrompt(params, plugin)
                "client.session.status" -> handleSessionStatus(plugin)
                "client.session.delete" -> handleSessionDelete(params, plugin)
                "client.session.update" -> handleSessionUpdate(params, plugin)
                "client.config.get" -> handleConfigGet(plugin)
                "client.tui.toast" -> handleToast(params, plugin)
                else -> JsonRpcResponse(error = JsonRpcError(-32601, "Unknown method: $method"))
            }
        } catch (e: Exception) {
            // 兜底：任何异常都不应中断 UDS 读循环，转成 JSON-RPC 错误响应
            FileLogger.w(TAG, "handleRequest($method) 失败: ${e.message}" + if (plugin != null) " plugin=$plugin" else "")
            JsonRpcResponse(error = JsonRpcError(-32603, e.message ?: "$method 处理失败"))
        }
    }

    /** client.app.agents.list：返回可用 agent 列表（对齐 opencode GET /agent 的 Agent[]）。
     *  AiCode 无 agent 定义系统，返回内置子代理类型（name 记录到子会话 subagentType）。 */
    private fun handleAgentsList(plugin: String?): JsonRpcResponse {
        FileLogger.d(TAG, "插件查询 agent 列表" + pluginTag(plugin))
        return ok(buildJsonArray {
            BUILTIN_AGENTS.forEach { add(agentToJson(it)) }
        })
    }

    /** 内置 agent → opencode Agent 形状 JSON（对齐 SDK Agent 类型）。 */
    private fun agentToJson(a: BuiltinAgent): JsonObject = buildJsonObject {
        put("name", a.id)
        put("description", a.description)
        put("mode", "subagent")
        put("builtIn", true)
        putJsonObject("permission") {
            put("edit", "ask")
            putJsonObject("bash") { }
        }
        putJsonObject("tools") { }
        putJsonObject("options") { }
    }

    /** client.app.log：结构化日志写入宿主日志（按 level 映射）。 */
    private fun handleAppLog(params: JsonObject, plugin: String?): JsonRpcResponse {
        val body = (params["body"] as? JsonObject)
        val service = (body?.get("service") as? JsonPrimitive)?.contentOrNull ?: "plugin"
        val level = (body?.get("level") as? JsonPrimitive)?.contentOrNull ?: "info"
        val message = (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: ""
        val extra = body?.get("extra")?.toString().orEmpty()
        val pluginPrefix = plugin?.let { "[$it] " } ?: ""
        val logLine = "$pluginPrefix[$service] $message" + (if (extra.isNotBlank() && extra != "null") " $extra" else "")
        when (level.lowercase()) {
            "debug" -> FileLogger.d(TAG, logLine)
            "warn", "warning" -> FileLogger.w(TAG, logLine)
            "error" -> FileLogger.e(TAG, logLine)
            else -> FileLogger.i(TAG, logLine)
        }
        return ok()
    }

    /** client.session.get：返回单个会话信息（对齐 opencode Session 字段命名）。 */
    private suspend fun handleSessionGet(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.get 缺少 id")
        FileLogger.d(TAG, "插件查询会话: id=$id" + pluginTag(plugin))
        val session = chatSessionDao.getById(id) ?: return error(-32004, "session not found: $id")
        return ok(sessionToJson(session))
    }

    /** client.session.list：返回当前工作区会话列表。 */
    private suspend fun handleSessionList(plugin: String?): JsonRpcResponse {
        FileLogger.d(TAG, "插件查询会话列表" + pluginTag(plugin))
        val path = workspaceRepository.currentPath()
        val sessions = chatSessionDao.getAllSessionsByWorkspaceOnce(path)
        return ok(buildJsonArray {
            sessions.forEach { s -> add(sessionToJson(s)) }
        })
    }

    /** client.session.children：返回指定会话的全部子会话（子代理）。 */
    private suspend fun handleSessionChildren(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.children 缺少 id")
        FileLogger.d(TAG, "插件查询子会话: id=$id" + pluginTag(plugin))
        if (chatSessionDao.getById(id) == null) return error(-32004, "session not found: $id")
        val children = chatSessionDao.getSubSessionsByParentOnce(id)
        return ok(buildJsonArray {
            children.forEach { s -> add(sessionToJson(s)) }
        })
    }

    /**
     * client.session.create：创建会话。body.parentID 存在时创建子代理会话
     * （继承父会话 provider/model/reasoningEffort/workspacePath，禁止嵌套）；
     * 否则创建普通根会话（绑定默认 provider/model，与 ViewModel createSession 一致）。
     */
    private suspend fun handleSessionCreate(params: JsonObject, plugin: String?): JsonRpcResponse {
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

        // 派发 session.created（与普通会话创建一致），子代理额外携带 parentID/subagentType
        pluginGatewayProvider.get().notifyEvent("session.created", buildJsonObject {
            put("sessionID", entity.id)
            put("title", entity.title)
            entity.parentId?.let { put("parentID", it) }
            entity.subagentType?.let { put("subagentType", it) }
        })
        FileLogger.i(TAG, "插件创建会话: id=${entity.id} parent=${entity.parentId ?: "-"}$pluginLog")
        return ok(sessionToJson(entity))
    }

    /**
     * client.session.prompt：向会话发送消息触发 AI 回复（对齐 opencode prompt_async 语义）。
     * parts 中仅 text 部分生效；noReply=true 仅注入上下文不触发 AI；body.model 可选覆盖会话模型。
     * 目标会话运行中时拒绝（避免并发 job），校验通过后经 [PluginSessionCommandBus] 交 ViewModel 执行。
     */
    private suspend fun handleSessionPrompt(params: JsonObject, plugin: String?): JsonRpcResponse {
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
        // subtask part（opencode 子代理派发协议）：每个 part 创建独立子代理会话并启动执行。
        // 子代理独立运行，不检查目标会话 busy 状态。先全量校验再创建，避免部分创建残留。
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
                    add(sessionToJson(sub))
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
    private fun handleSessionStatus(plugin: String?): JsonRpcResponse {
        FileLogger.d(TAG, "插件查询运行中会话" + pluginTag(plugin))
        return ok(buildJsonObject {
            // 对齐 opencode：Record<sessionID, SessionStatus>，运行中即 busy
            sessionActivityRegistry.running.value.forEach { put(it, buildJsonObject { put("type", "busy") }) }
        })
    }

    /**
     * client.session.delete：删除会话（含全部子代理会话与消息）。
     * 运行中的会话先发 STOPPED 请求取消 job，再经命令总线交 ViewModel 清理 UI 状态后落库删除。
     */
    private suspend fun handleSessionDelete(params: JsonObject, plugin: String?): JsonRpcResponse {
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

    /** client.session.update：更新会话元数据（目前仅 title，对齐 opencode）。 */
    private suspend fun handleSessionUpdate(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.update 缺少 id")
        val session = chatSessionDao.getById(id) ?: return error(-32004, "session not found: $id")
        val body = (params["body"] as? JsonObject) ?: buildJsonObject { }
        val title = (body["title"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (!title.isNullOrBlank()) {
            FileLogger.d(TAG, "插件更新会话: id=$id title=$title" + pluginTag(plugin))
            chatSessionDao.updateTitle(id, title)
            pluginGatewayProvider.get().notifyEvent("session.updated", buildJsonObject { put("sessionID", id) })
        }
        return ok(sessionToJson(chatSessionDao.getById(id) ?: session))
    }

    /**
     * client.session.messages：返回指定会话的历史消息（最近 100 条），
     * 形状对齐 opencode `{ info: Message, parts: Part[] }[]`。
     * AiCode 消息为扁平行（USER/ASSISTANT/TOOL），映射规则：
     * - USER 行 → UserMessage + text part
     * - ASSISTANT 行 → AssistantMessage + text part（reasoning 存在时追加 reasoning part）
     * - TOOL 行 → 合并为最近一条 assistant 消息的 ToolPart（toolCallId 匹配优先）
     */
    private suspend fun handleSessionMessages(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.messages 缺少 id")
        FileLogger.d(TAG, "插件查询会话消息: id=$id" + pluginTag(plugin))
        val session = chatSessionDao.getById(id)
        val rows = agentMessageDao.getMessagesBySessionOnce(id).takeLast(100)
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
        return ok(buildJsonArray {
            built.forEach { (info, parts) ->
                add(buildJsonObject {
                    put("info", info)
                    putJsonArray("parts") { parts.forEach { add(it) } }
                })
            }
        })
    }

    /** client.config.get：返回宿主核心配置（对齐 opencode Config 字段命名的最小子集）。 */
    private suspend fun handleConfigGet(plugin: String?): JsonRpcResponse {
        FileLogger.d(TAG, "插件读取配置" + pluginTag(plugin))
        val providerId = defaultModelSettingsRepository.getDefaultProviderId()
        val model = defaultModelSettingsRepository.getDefaultModel()
        return ok(buildJsonObject {
            // 对齐 opencode Config 字段命名（最小子集）：model/small_model 为 "provider/model" 字符串
            if (providerId.isNotBlank() && model.isNotBlank()) put("model", "$providerId/$model")
            put("small_model", "")
        })
    }

    /** client.tui.toast：映射为 Android Toast 提示（前台有 UI 时可见）。 */
    private fun handleToast(params: JsonObject, plugin: String?): JsonRpcResponse {
        val body = (params["body"] as? JsonObject)
        val message = (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: ""
        if (message.isNotBlank()) {
            FileLogger.d(TAG, "插件 toast: ${message.take(80)}" + pluginTag(plugin))
            val prefix = runCatching { context.getString(R.string.plugin_toast_prefix) }.getOrDefault("[plugin] ")
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(context.applicationContext, "$prefix$message", Toast.LENGTH_SHORT).show()
                }.onFailure { FileLogger.w(TAG, "showToast 失败: ${it.message}") }
            }
        }
        return ok()
    }

    /** 会话实体 → opencode Session 形状 JSON（对齐 SDK Session 类型）。
     *  AiCode 特有字段（mode/reasoningEffort/isPinned）收进 metadata，不污染顶级字段。 */
    private fun sessionToJson(s: ChatSessionEntity): JsonObject = buildJsonObject {
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

    /** 提取 parts 中全部 text 部分（换行拼接）；非 text part 忽略（AiCode 无 Part 概念）。 */
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

    /** 提取 parts 中全部 subtask part（opencode 子代理派发协议）；非 subtask 忽略。 */
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
private data class BuiltinAgent(val id: String, val name: String, val description: String)

/** 单个 subtask part（opencode 子代理派发协议）。 */
private data class SubtaskPart(val prompt: String, val description: String, val agent: String)