package com.aicode.feature.agent.domain.plugin.hostapi

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.JsonRpcError
import com.aicode.feature.agent.domain.mcp.JsonRpcResponse
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件 client.app.* / client.config.* API：结构化日志、列出可用 agent、读宿主配置。
 *
 * 所有方法只读：日志是 fire-and-forget 写入宿主日志；agent 列表是内置常量；config 只暴露最小子集。
 */
@Singleton
class ConfigHostApi @Inject constructor(
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository
) {

    private companion object {
        const val TAG = "ConfigHostApi"
        fun pluginTag(plugin: String?): String = plugin?.let { " plugin=$it" } ?: ""
    }

    /** client.app.log：结构化日志写入宿主日志（按 level 映射）。 */
    fun handleAppLog(params: JsonObject, plugin: String?): JsonRpcResponse {
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

    /** client.app.agents.list：返回可用 agent 列表（对齐 opencode GET /agent 的 Agent[]）。
     *  AiCode 无 agent 定义系统，返回内置子代理类型（name 记录到子会话 subagentType）。 */
    fun handleAgentsList(plugin: String?): JsonRpcResponse {
        FileLogger.d(TAG, "插件查询 agent 列表" + pluginTag(plugin))
        return ok(kotlinx.serialization.json.buildJsonArray {
            BUILTIN_AGENTS.forEach { add(agentToJson(it)) }
        })
    }

    /** client.config.get：返回宿主核心配置（对齐 opencode Config 字段命名的最小子集）。 */
    suspend fun handleConfigGet(plugin: String?): JsonRpcResponse {
        FileLogger.d(TAG, "插件读取配置" + pluginTag(plugin))
        val providerId = defaultModelSettingsRepository.getDefaultProviderId()
        val model = defaultModelSettingsRepository.getDefaultModel()
        return ok(buildJsonObject {
            // 对齐 opencode Config 字段命名（最小子集）：model/small_model 为 "provider/model" 字符串
            if (providerId.isNotBlank() && model.isNotBlank()) put("model", "$providerId/$model")
            put("small_model", "")
        })
    }

    private fun ok(result: JsonElement = buildJsonObject { }): JsonRpcResponse = JsonRpcResponse(result = result)
    private fun error(code: Int, message: String): JsonRpcResponse =
        JsonRpcResponse(error = JsonRpcError(code, message))

    /** 内置 agent 定义（与 SessionHostApi.BUILTIN_AGENTS 保持同步）。 */
    private val BUILTIN_AGENTS: List<BuiltinAgent> = listOf(
        BuiltinAgent("general", "通用", "通用子代理，适合大多数任务"),
        BuiltinAgent("build", "构建", "偏重编码与构建任务的子代理"),
        BuiltinAgent("plan", "规划", "偏重分析与规划的子代理")
    )

    /** 内置 agent → opencode Agent 形状 JSON（对齐 SDK Agent 类型）。 */
    private fun agentToJson(a: BuiltinAgent): JsonObject = kotlinx.serialization.json.buildJsonObject {
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
}
