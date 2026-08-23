package com.aicode.feature.agent.domain.plugin

import com.aicode.feature.settings.domain.model.ProviderType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** chat.params hook 解析出的请求参数（插件未写入的字段为 null）。 */
data class PluginRequestParams(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val options: Map<String, JsonElement> = emptyMap()
)

/** auth.loader 的解析结果。headers 为插件返回的认证头（含 apiKey 转认证头）；baseURL/hasFetch 供请求代理用。 */
data class AuthLoaderResult(
    val headers: Map<String, String> = emptyMap(),
    val baseURL: String? = null,
    val hasFetch: Boolean = false
)

/** chat.headers 分发结果：headers 为合并后的请求头；pluginBaseUrl 为插件 auth.loader 声明的 baseURL（opencode 语义：覆盖 provider 端点）。 */
data class ChatHeadersResult(
    val headers: Map<String, String> = emptyMap(),
    val pluginBaseUrl: String? = null
)

/** 分发 chat.headers：插件可注入/改写请求头，返回最终 headers。
 *  input 对齐 opencode：sessionID/agent/model/provider/message；output 为 { headers }。
 *  provider 为 ProviderType 名（chat.headers 用）；providerId 为 AIProvider 配置 id，
 *  非空时额外分发 auth.loader 并合并认证头（loader 的 apiKey 按 providerType 转认证头）。
 *  同时带回插件 auth.loader 声明的 baseURL，供调用方构造请求 URL。 */
suspend fun PluginHookGateway.applyChatHeaders(
    sessionId: String?,
    model: String,
    provider: String,
    headers: Map<String, String> = emptyMap(),
    providerId: String? = null,
    baseUrl: String? = null,
    providerType: ProviderType? = null,
    agent: String? = null,
    message: JsonObject? = null
): ChatHeadersResult {
    val result = dispatchHook(
        "chat.headers",
        buildJsonObject {
            put("sessionID", sessionId ?: "")
            put("agent", agent ?: "")
            putJsonObject("model") {
                put("providerID", providerId ?: "")
                put("modelID", model)
            }
            putJsonObject("provider") {
                put("source", "config")
                putJsonObject("info") {
                    put("id", providerId ?: "")
                    providerType?.let { put("type", it.name) }
                }
                putJsonObject("options") { }
            }
            if (message != null) put("message", message)
        },
        buildJsonObject { putJsonObject("headers") { headers.forEach { (k, v) -> put(k, v) } } }
    )
    val out = (result.output["headers"] as? JsonObject)
        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
        ?.toMap()
    val merged = out ?: headers
    if (providerId.isNullOrBlank()) return ChatHeadersResult(merged)
    // auth.loader（返回型）：各插件动态提供认证信息，合并进请求头。
    // 返回的键值对优先级低于用户手动配置的 API Key（authorization 为单独参数，不受影响）。
    val loader = applyAuthLoader(providerId, baseUrl, providerType)
    return ChatHeadersResult(merged + loader.headers, loader.baseURL)
}

/** 用插件 auth.loader 返回的 baseURL 替换原始 URL 的 origin（保留路径与查询串）；解析失败时原样返回。 */
fun replaceOrigin(url: String, newBase: String): String {
    val old = runCatching { java.net.URI(url) }.getOrNull() ?: return newBase.trimEnd('/')
    val base = newBase.trimEnd('/')
    val path = old.rawPath?.takeIf { it.isNotBlank() && it != "/" } ?: ""
    val query = old.rawQuery?.let { "?$it" } ?: ""
    return base + path + query
}

/** 分发 auth.loader：收集各插件返回的认证信息（headers/apiKey/baseURL/hasFetch）。
 *  provider 为 AIProvider 配置 id（与插件 auth.provider 匹配）；providerConfig 传给插件 loader 第二参。 */
suspend fun PluginHookGateway.applyAuthLoader(
    provider: String,
    baseUrl: String? = null,
    providerType: ProviderType? = null
): AuthLoaderResult {
    val results = dispatchReturnHook(
        "auth.loader",
        buildJsonObject {
            put("provider", provider)
            // provider 配置对象（对齐 opencode Provider 形状的最小子集：id/baseURL/type）
            putJsonObject("providerConfig") {
                put("id", provider)
                baseUrl?.let { put("baseURL", it) }
                providerType?.let { put("type", it.name) }
            }
        }
    )
    val merged = mutableMapOf<String, String>()
    var hasFetch = false
    var baseURL: String? = null
    results.forEach { el ->
        val obj = el as? JsonObject ?: return@forEach
        if ((obj["hasFetch"] as? JsonPrimitive)?.booleanOrNull == true) hasFetch = true
        (obj["baseURL"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { baseURL = it }
        val apiKey = (obj["apiKey"] as? JsonPrimitive)?.contentOrNull
        (obj["headers"] as? JsonObject)?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { merged[k] = it }
        }
        // apiKey → 认证头（对齐 ModelApiService.applyAuth：OpenAI Bearer / Anthropic x-api-key / Gemini x-goog-api-key）
        if (!apiKey.isNullOrBlank()) {
            authHeaderFor(providerType, apiKey)?.let { merged.putAll(it) }
        }
    }
    return AuthLoaderResult(merged, baseURL, hasFetch)
}

/** 查询 auth.loader 返回自定义 fetch 的 provider 代理地址（127.0.0.1:<port>）；未命中返回 null。 */
suspend fun PluginHookGateway.resolveProviderProxy(providerId: String): String? =
    authProxy()[providerId]

private fun authHeaderFor(providerType: ProviderType?, apiKey: String): Map<String, String>? = when (providerType) {
    ProviderType.ANTHROPIC -> mapOf("x-api-key" to apiKey)
    ProviderType.GEMINI -> mapOf("x-goog-api-key" to apiKey)
    else -> mapOf("Authorization" to "Bearer $apiKey")
}

/**
 * 分发 chat.message：插件可改写用户输入（落库前），返回改写后的文本。
 * 形状对齐 opencode：input 含 sessionID/messageID/agent/model，output 为 { message: UserMessage, parts: Part[] }。
 * 插件改写 parts 中的 text part，取回时用第一个 text part 的文本。
 */
suspend fun PluginHookGateway.applyChatMessage(
    sessionId: String,
    messageId: String,
    content: String,
    agent: String? = null,
    providerId: String? = null,
    model: String? = null
): String {
    val now = System.currentTimeMillis() / 1000
    val result = dispatchHook(
        "chat.message",
        buildJsonObject {
            put("sessionID", sessionId)
            put("messageID", messageId)
            agent?.let { put("agent", it) }
            if (!providerId.isNullOrBlank() && !model.isNullOrBlank()) {
                putJsonObject("model") {
                    put("providerID", providerId)
                    put("modelID", model)
                }
            }
        },
        buildJsonObject {
            // message 为 UserMessage 形状（info），parts 为 TextPart 数组（opencode 语义：改写 parts）
            putJsonObject("message") {
                put("id", messageId)
                put("sessionID", sessionId)
                put("role", "user")
                putJsonObject("time") { put("created", now) }
                put("agent", agent ?: "")
                putJsonObject("model") {
                    put("providerID", providerId ?: "")
                    put("modelID", model ?: "")
                }
            }
            putJsonArray("parts") {
                add(buildJsonObject {
                    put("id", "prt-$messageId")
                    put("sessionID", sessionId)
                    put("messageID", messageId)
                    put("type", "text")
                    put("text", content)
                })
            }
        }
    )
    val outParts = (result.output["parts"] as? JsonArray).orEmpty()
    return outParts.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        if ((obj["type"] as? JsonPrimitive)?.content == "text") {
            (obj["text"] as? JsonPrimitive)?.contentOrNull
        } else null
    }.filter { it.isNotBlank() }.joinToString("\n").ifBlank { content }
}

/** 分发 chat.params：插件可改写推理参数，返回解析后的参数。
 *  input 对齐 opencode：sessionID/agent/model/provider/message；output 为 { temperature/topP/topK/maxOutputTokens/options }。 */
suspend fun PluginHookGateway.applyChatParams(
    sessionId: String?,
    model: String,
    providerId: String? = null,
    providerType: ProviderType? = null,
    agent: String? = null,
    message: JsonObject? = null
): PluginRequestParams {
    val result = dispatchHook(
        "chat.params",
        buildJsonObject {
            put("sessionID", sessionId ?: "")
            put("agent", agent ?: "")
            putJsonObject("model") {
                put("providerID", providerId ?: "")
                put("modelID", model)
            }
            putJsonObject("provider") {
                put("source", "config")
                putJsonObject("info") {
                    put("id", providerId ?: "")
                    providerType?.let { put("type", it.name) }
                }
                putJsonObject("options") { }
            }
            if (message != null) put("message", message)
        },
        buildJsonObject { }
    )
    val out = result.output
    return PluginRequestParams(
        temperature = (out["temperature"] as? JsonPrimitive)?.floatOrNull,
        topP = (out["topP"] as? JsonPrimitive)?.floatOrNull,
        topK = (out["topK"] as? JsonPrimitive)?.intOrNull,
        maxOutputTokens = (out["maxOutputTokens"] as? JsonPrimitive)?.intOrNull,
        options = (out["options"] as? JsonObject)?.toMap() ?: emptyMap()
    )
}

/** kotlinx JsonElement → 普通 Kotlin 类型（Map/List/String/Number/Boolean/null），供 Gson 序列化。 */
fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonObject -> mapValues { (_, v) -> v.toPlainValue() }
    is JsonArray -> map { it.toPlainValue() }
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
}