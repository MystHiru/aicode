package com.aicode.feature.agent.domain.plugin.hostapi

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.mcp.JsonRpcError
import com.aicode.feature.agent.domain.mcp.JsonRpcResponse
import com.aicode.feature.agent.domain.plugin.PluginAuth
import com.aicode.feature.agent.domain.plugin.PluginAuthStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件 client.auth.* API：读写宿主 auth.json（与 AiCode 用户 API Key 独立的插件凭据）。
 *
 * 安全边界：仅插件自己的 provider 凭据可写（由宿主侧校验 id 归属），不暴露 AiCode 用户 API Key。
 */
@Singleton
class AuthHostApi @Inject constructor(
    private val authStore: PluginAuthStore
) {

    private companion object {
        const val TAG = "AuthHostApi"
        fun pluginTag(plugin: String?): String = plugin?.let { " plugin=$it" } ?: ""
    }

    /** client.auth.set：写入/更新/删除插件认证凭据（对齐 opencode auth.json）。body 为 null 表示删除。 */
    suspend fun handleAuthSet(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "auth.set 缺少 id")
        val body = params["body"] as? JsonObject
        if (body == null) {
            authStore.remove(id)
        } else {
            authStore.set(id, PluginAuth(
                type = (body["type"] as? JsonPrimitive)?.contentOrNull ?: "",
                refresh = (body["refresh"] as? JsonPrimitive)?.contentOrNull ?: "",
                access = (body["access"] as? JsonPrimitive)?.contentOrNull ?: "",
                expires = (body["expires"] as? JsonPrimitive)?.let { it.contentOrNull?.toLongOrNull() ?: 0L } ?: 0L,
                key = (body["key"] as? JsonPrimitive)?.contentOrNull ?: "",
                metadata = (body["metadata"] as? JsonObject)?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                }?.toMap() ?: emptyMap()
            ))
        }
        FileLogger.i(TAG, "插件写入认证凭据: id=$id" + pluginTag(plugin))
        return ok()
    }

    /** client.auth.list：返回全部插件认证凭据的 provider id 列表（不暴露凭据内容）。 */
    suspend fun handleAuthList(plugin: String?): JsonRpcResponse {
        val ids = authStore.all().keys
        return ok(buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    }

    /** client.auth.get：返回指定 provider 的凭据（供插件 auth.loader 的 getAuth() 使用）。 */
    suspend fun handleAuthGet(params: JsonObject, plugin: String?): JsonRpcResponse {
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return error(-32602, "auth.get 缺少 id")
        val auth = authStore.get(id) ?: return ok(buildJsonObject { })
        return ok(buildJsonObject {
            put("type", auth.type)
            if (auth.refresh.isNotBlank()) put("refresh", auth.refresh)
            if (auth.access.isNotBlank()) put("access", auth.access)
            if (auth.expires != 0L) put("expires", auth.expires)
            if (auth.key.isNotBlank()) put("key", auth.key)
            if (auth.metadata.isNotEmpty()) {
                putJsonObject("metadata") { auth.metadata.forEach { (k, v) -> put(k, v) } }
            }
        })
    }

    private fun ok(result: JsonElement = buildJsonObject { }): JsonRpcResponse = JsonRpcResponse(result = result)
    private fun error(code: Int, message: String): JsonRpcResponse =
        JsonRpcResponse(error = JsonRpcError(code, message))
}
