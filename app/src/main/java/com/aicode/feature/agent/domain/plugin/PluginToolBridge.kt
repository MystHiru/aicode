package com.aicode.feature.agent.domain.plugin

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 把一个插件工具适配成应用内的 [AgentTool]，注册进 ToolRegistry 后即可被 Agent 循环复用。
 *
 * 与 [com.aicode.feature.agent.domain.mcp.McpTool] 同构：
 * - 工具名使用插件声明的原名（对齐 OpenCode「插件工具优先于内置工具」语义，注册时直接覆盖同名内置工具）；
 * - [toJsonSchema] 透传插件的 JSON Schema（插件工具参数不受受限的 ParameterType 枚举限制）；
 * - 真正调用时经 PluginClient 走 UDS 到 Node 运行时执行。
 */
class PluginToolBridge(
    private val client: PluginClient,
    private val descriptor: PluginToolDescriptor
) : AgentTool() {

    private companion object {
        const val TAG = "PluginToolBridge"
    }

    /** 日志用插件名标签（旧版 runner 无 plugin 字段时为空串）。 */
    private val pluginTag: String get() = descriptor.plugin?.let { " [plugin=$it]" } ?: ""

    override val name: String = descriptor.name

    override val description: String =
        descriptor.description.ifBlank { "插件工具 ${descriptor.name}（来自插件运行时）" }

    override val capabilities = setOf(ToolCapability.EXTERNAL_TOOL)

    // 插件工具默认走工具权限：需审核，可「始终允许」记忆（与 MCP 工具一致）。
    override val permissionPolicy = ToolPermissionPolicy.ASK

    // 插件工具直接用原始 schema，不走 parameters 这条路；保留空 map 满足基类契约。
    override val parameters: Map<String, ToolParameter> = emptyMap()

    /** 透传插件的 JSON Schema；缺失时回退为空对象 schema。 */
    override fun toJsonSchema(): Map<String, Any> {
        val schema = descriptor.parameters
        if (schema == null || schema.isEmpty()) {
            return mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        }
        @Suppress("UNCHECKED_CAST")
        return (jsonElementToAny(schema) as? Map<String, Any>)
            ?: mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        return try {
            FileLogger.d(TAG, "调用插件工具 $name$pluginTag args=${args.keys}")
            val call = client.callTool(name, JsonObject(args))
            if (call.isError) {
                FileLogger.w(TAG, "插件工具 $name$pluginTag 返回错误 耗时=${System.currentTimeMillis() - start}ms: ${call.text.take(200)}")
                ToolResult.Error(call.text)
            } else {
                FileLogger.d(TAG, "插件工具 $name$pluginTag 执行成功 耗时=${System.currentTimeMillis() - start}ms")
                ToolResult.Success(JsonPrimitive(call.text))
            }
        } catch (e: PluginException) {
            FileLogger.e(TAG, "插件工具调用失败: $name$pluginTag 耗时=${System.currentTimeMillis() - start}ms", e)
            ToolResult.Error("插件工具执行失败: ${e.message}")
        } catch (e: Exception) {
            FileLogger.e(TAG, "插件工具调用异常: $name$pluginTag 耗时=${System.currentTimeMillis() - start}ms", e)
            ToolResult.Error("插件工具执行异常: ${e.message}")
        }
    }

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ): ToolResult {
        val start = System.currentTimeMillis()
        return try {
            FileLogger.d(TAG, "调用插件工具 $name$pluginTag args=${args.keys} sessionId=${context.sessionId}")
            val call = client.callTool(name, JsonObject(args), context.sessionId)
            if (call.isError) {
                FileLogger.w(TAG, "插件工具 $name$pluginTag 返回错误 耗时=${System.currentTimeMillis() - start}ms: ${call.text.take(200)}")
                ToolResult.Error(call.text)
            } else {
                FileLogger.d(TAG, "插件工具 $name$pluginTag 执行成功 耗时=${System.currentTimeMillis() - start}ms")
                ToolResult.Success(JsonPrimitive(call.text))
            }
        } catch (e: PluginException) {
            FileLogger.e(TAG, "插件工具调用失败: $name$pluginTag 耗时=${System.currentTimeMillis() - start}ms", e)
            ToolResult.Error("插件工具执行失败: ${e.message}")
        } catch (e: Exception) {
            FileLogger.e(TAG, "插件工具调用异常: $name$pluginTag 耗时=${System.currentTimeMillis() - start}ms", e)
            ToolResult.Error("插件工具执行异常: ${e.message}")
        }
    }

    /** 工具被调用时展示的权限请求，提供清晰的插件工具上下文。 */
    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认调用插件工具",
            summary = "AI 请求调用插件工具「$name」",
            details = "插件工具：$name\n参数：$argsPreview",
            argsPreview = argsPreview
        )
    }

    /** kotlinx JsonElement → 普通 Kotlin 类型（Map/List/String/Number/Boolean/null），供 Gson 正确序列化。 */
    private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
        is JsonArray -> element.map { jsonElementToAny(it) }
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
    }
}