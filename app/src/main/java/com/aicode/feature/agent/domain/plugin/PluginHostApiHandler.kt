package com.aicode.feature.agent.domain.plugin

import android.content.Context
import android.widget.Toast
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.mcp.JsonRpcError
import com.aicode.feature.agent.domain.mcp.JsonRpcResponse
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宿主 API 处理器：响应 runner 端插件 `client.*` 请求（session 查询、配置读取、结构化日志等）。
 *
 * 由 [PluginClient] 经 UdsTransport 反向通道调用（runner 是请求方、本类是服务方），
 * 数据源为宿主 Room 数据库与 DataStore 配置仓库。
 *
 * 安全边界：只提供**只读**的会话/配置查询与日志/通知，不暴露凭据、不做写操作
 * （plugin 的 files.* 由 runner 本地实现，session/config 均无修改入口）。
 */
@Singleton
class PluginHostApiHandler @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val workspaceRepository: WorkspaceRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    @ApplicationContext private val context: Context
) : HostApiHandler {

    private companion object {
        const val TAG = "PluginHostApi"
    }

    override suspend fun handleRequest(method: String, params: JsonObject): JsonRpcResponse {
        return try {
            when (method) {
                "client.app.log" -> handleAppLog(params)
                "client.session.get" -> handleSessionGet(params)
                "client.session.list" -> handleSessionList()
                "client.session.messages" -> handleSessionMessages(params)
                "client.config.get" -> handleConfigGet()
                "client.tui.toast" -> handleToast(params)
                else -> JsonRpcResponse(error = JsonRpcError(-32601, "Unknown method: $method"))
            }
        } catch (e: Exception) {
            // 兜底：任何异常都不应中断 UDS 读循环，转成 JSON-RPC 错误响应
            FileLogger.w(TAG, "handleRequest($method) 失败: ${e.message}")
            JsonRpcResponse(error = JsonRpcError(-32603, e.message ?: "$method 处理失败"))
        }
    }

    /** client.app.log：结构化日志写入宿主日志（按 level 映射）。 */
    private fun handleAppLog(params: JsonObject): JsonRpcResponse {
        val body = (params["body"] as? JsonObject)
        val service = (body?.get("service") as? JsonPrimitive)?.contentOrNull ?: "plugin"
        val level = (body?.get("level") as? JsonPrimitive)?.contentOrNull ?: "info"
        val message = (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: ""
        val extra = body?.get("extra")?.toString().orEmpty()
        val logLine = "[$service] $message" + (if (extra.isNotBlank() && extra != "null") " $extra" else "")
        when (level.lowercase()) {
            "debug" -> FileLogger.d(TAG, logLine)
            "warn", "warning" -> FileLogger.w(TAG, logLine)
            "error" -> FileLogger.e(TAG, logLine)
            else -> FileLogger.i(TAG, logLine)
        }
        return ok()
    }

    /** client.session.get：返回单个会话信息（对齐 opencode Session 字段命名）。 */
    private suspend fun handleSessionGet(params: JsonObject): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.get 缺少 id")
        val session = chatSessionDao.getById(id) ?: return error(-32004, "session not found: $id")
        return ok(buildJsonObject {
            put("id", session.id)
            put("title", session.title)
            put("directory", session.workspacePath)
            put("workspacePath", session.workspacePath)
            put("modelID", session.model ?: "")
            put("providerID", session.providerId ?: "")
            put("mode", session.mode)
            put("reasoningEffort", session.reasoningEffort)
            put("isPinned", session.isPinned)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
            put("totalInputTokens", session.totalInputTokens)
            put("totalOutputTokens", session.totalOutputTokens)
        })
    }

    /** client.session.list：返回当前工作区会话列表。 */
    private suspend fun handleSessionList(): JsonRpcResponse {
        val path = workspaceRepository.currentPath()
        val sessions = chatSessionDao.getAllSessionsByWorkspaceOnce(path)
        return ok(buildJsonObject {
            putJsonArray("sessions") {
                sessions.forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id)
                        put("title", s.title)
                        put("directory", s.workspacePath)
                        put("modelID", s.model ?: "")
                        put("providerID", s.providerId ?: "")
                        put("createdAt", s.createdAt)
                        put("updatedAt", s.updatedAt)
                    })
                }
            }
        })
    }

    /** client.session.messages：返回指定会话的历史消息（降序取最近 100 条）。 */
    private suspend fun handleSessionMessages(params: JsonObject): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "session.messages 缺少 id")
        val messages = agentMessageDao.getMessagesBySessionOnce(id)
            .takeLast(100)
        return ok(buildJsonObject {
            putJsonArray("messages") {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("id", m.id)
                        put("sessionID", m.sessionId)
                        put("role", m.role.lowercase())
                        put("content", m.content)
                        put("timestamp", m.timestamp)
                        m.toolName?.let { put("toolName", it) }
                        m.toolCallId?.let { put("toolCallID", it) }
                        put("isError", m.isError)
                        put("inputTokens", m.inputTokens)
                        put("outputTokens", m.outputTokens)
                    })
                }
            }
        })
    }

    /** client.config.get：返回宿主核心配置（当前工作区、默认模型）。 */
    private suspend fun handleConfigGet(): JsonRpcResponse {
        val path = workspaceRepository.currentPath()
        val providerId = defaultModelSettingsRepository.getDefaultProviderId()
        val model = defaultModelSettingsRepository.getDefaultModel()
        return ok(buildJsonObject {
            put("workspace", path)
            putJsonObject("defaultModel") {
                put("providerID", providerId)
                put("model", model)
            }
        })
    }

    /** client.tui.toast：映射为 Android Toast 提示（前台有 UI 时可见）。 */
    private fun handleToast(params: JsonObject): JsonRpcResponse {
        val body = (params["body"] as? JsonObject)
        val message = (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: ""
        if (message.isNotBlank()) {
            val prefix = runCatching { context.getString(R.string.plugin_toast_prefix) }.getOrDefault("[plugin] ")
            runCatching {
                Toast.makeText(context.applicationContext, "$prefix$message", Toast.LENGTH_SHORT).show()
            }.onFailure { FileLogger.w(TAG, "showToast 失败: ${it.message}") }
        }
        return ok()
    }

    private fun ok(result: JsonObject = buildJsonObject { }): JsonRpcResponse = JsonRpcResponse(result = result)

    private fun error(code: Int, message: String): JsonRpcResponse =
        JsonRpcResponse(error = JsonRpcError(code, message))
}