package com.aicode.feature.agent.domain.plugin

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

/** 分发 chat.headers：插件可注入/改写请求头，返回最终 headers。 */
suspend fun PluginHookGateway.applyChatHeaders(
    sessionId: String?,
    model: String,
    provider: String,
    headers: Map<String, String> = emptyMap()
): Map<String, String> {
    val result = dispatchHook(
        "chat.headers",
        buildJsonObject {
            put("sessionID", sessionId ?: "")
            put("model", model)
            put("provider", provider)
        },
        buildJsonObject { putJsonObject("headers") { headers.forEach { (k, v) -> put(k, v) } } }
    )
    val out = (result.output["headers"] as? JsonObject)
        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
        ?.toMap()
    // auth.loader（返回型）：各插件动态提供认证头（临时凭证/签名），合并进请求头。
    // 返回的键值对优先级低于用户手动配置的 API Key（authorization 为单独参数，不受影响）。
    val merged = out ?: headers
    return merged + applyAuthLoader(provider)
}

/** 分发 auth.loader：收集各插件返回的认证键值对，合并为请求头。 */
suspend fun PluginHookGateway.applyAuthLoader(provider: String): Map<String, String> {
    val results = dispatchReturnHook(
        "auth.loader",
        buildJsonObject { put("provider", provider) }
    )
    val merged = mutableMapOf<String, String>()
    results.forEach { el ->
        val obj = el as? JsonObject ?: return@forEach
        obj.forEach { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { merged[k] = it }
        }
    }
    return merged
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

/** 分发 chat.params：插件可改写推理参数，返回解析后的参数。 */
suspend fun PluginHookGateway.applyChatParams(sessionId: String?, model: String): PluginRequestParams {
    val result = dispatchHook(
        "chat.params",
        buildJsonObject { put("sessionID", sessionId ?: ""); put("model", model) },
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