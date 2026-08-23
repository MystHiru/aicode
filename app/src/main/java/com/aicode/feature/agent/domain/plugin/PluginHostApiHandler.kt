package com.aicode.feature.agent.domain.plugin

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.JsonRpcError
import com.aicode.feature.agent.domain.mcp.JsonRpcResponse
import com.aicode.feature.agent.domain.plugin.hostapi.AuthHostApi
import com.aicode.feature.agent.domain.plugin.hostapi.ConfigHostApi
import com.aicode.feature.agent.domain.plugin.hostapi.SessionHostApi
import com.aicode.feature.agent.domain.plugin.hostapi.ToastHostApi
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宿主 API 路由层：按 method 前缀分发给各子 handler。
 *
 * 实际业务逻辑在 [SessionHostApi] / [AuthHostApi] / [ConfigHostApi] / [ToastHostApi] 中实现，
 * 本类只做路由 + 异常兜底（任何异常都不应中断 UDS 读循环，转成 JSON-RPC 错误响应）。
 */
@Singleton
class PluginHostApiHandler @Inject constructor(
    private val sessionHostApi: SessionHostApi,
    private val authHostApi: AuthHostApi,
    private val configHostApi: ConfigHostApi,
    private val toastHostApi: ToastHostApi
) : HostApiHandler {

    private companion object {
        const val TAG = "PluginHostApi"
    }

    override suspend fun handleRequest(method: String, params: JsonObject, plugin: String?): JsonRpcResponse {
        return try {
            when {
                method == "client.app.log" -> configHostApi.handleAppLog(params, plugin)
                method == "client.app.agents.list" -> configHostApi.handleAgentsList(plugin)
                method == "client.config.get" -> configHostApi.handleConfigGet(plugin)
                method == "client.session.get" -> sessionHostApi.handleSessionGet(params, plugin)
                method == "client.session.list" -> sessionHostApi.handleSessionList(plugin)
                method == "client.session.messages" -> sessionHostApi.handleSessionMessages(params, plugin)
                method == "client.session.children" -> sessionHostApi.handleSessionChildren(params, plugin)
                method == "client.session.create" -> sessionHostApi.handleSessionCreate(params, plugin)
                method == "client.session.prompt" -> sessionHostApi.handleSessionPrompt(params, plugin)
                method == "client.session.promptAsync" -> sessionHostApi.handleSessionPromptAsync(params, plugin)
                method == "client.session.status" -> sessionHostApi.handleSessionStatus(plugin)
                method == "client.session.delete" -> sessionHostApi.handleSessionDelete(params, plugin)
                method == "client.session.update" -> sessionHostApi.handleSessionUpdate(params, plugin)
                method == "client.tui.toast" -> toastHostApi.handleToast(params, plugin)
                method == "client.auth.set" -> authHostApi.handleAuthSet(params, plugin)
                method == "client.auth.list" -> authHostApi.handleAuthList(plugin)
                method == "client.auth.get" -> authHostApi.handleAuthGet(params, plugin)
                else -> JsonRpcResponse(error = JsonRpcError(-32601, "Unknown method: $method"))
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "handleRequest($method) 失败: ${e.message}" + if (plugin != null) " plugin=$plugin" else "")
            JsonRpcResponse(error = JsonRpcError(-32603, e.message ?: "$method 处理失败"))
        }
    }
}
