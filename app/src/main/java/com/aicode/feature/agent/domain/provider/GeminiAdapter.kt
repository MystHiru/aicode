package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.AILogger
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException

class GeminiAdapter @Inject constructor(
    private val api: GeminiApi
) : AIProvider {

    override var apiKey = ""
    override var baseUrl = "https://generativelanguage.googleapis.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "gemini-1.5-flash"
    override var providerId = ""
    override var logSessionId: String? = null

    /** 自定义请求头 User-Agent；留空使用默认。 */
    override var userAgent: String = ""

    // Gemini 发 generationConfig.maxOutputTokens（模型元数据的输出上限）；为 null 时不发该参数，用服务端默认。
    override var maxOutputTokens: Int? = null

    private fun extraHeaders(): Map<String, String> =
        if (userAgent.isNotBlank()) mapOf("User-Agent" to userAgent) else emptyMap()

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val request = buildRequestBody(systemPrompt, messages, tools, reasoningEffort)

        val url = if (useFullUrl) {
            baseUrl
        } else {
            val path = if (baseUrl.trimEnd('/').endsWith(model)) {
                baseUrl.trimEnd('/') + ":generateContent"
            } else {
                joinUrl(baseUrl, "v1beta/models/$model:generateContent")
            }
            path
        }
        AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)

        val response = try {
            retryStaircase {
                api.generateContent(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Gemini", response)

        var contentText = ""
        var thinkingText = ""
        val toolCalls = mutableListOf<ToolCall>()
        var finishReason: String? = null
        var partsSnapshot: String? = null

        val candidates = response.getAsJsonArray("candidates")
        candidates?.firstOrNull()?.asJsonObject?.let { candidate ->
            finishReason = candidate.get("finishReason")?.asString
            val content = candidate.getAsJsonObject("content")
            val parts = content?.getAsJsonArray("parts")
            parts?.forEach { partEl ->
                val part = partEl.asJsonObject
                val isThought = part.get("thought")?.asBoolean == true
                if (part.has("text")) {
                    val text = part.get("text").asString
                    if (isThought) thinkingText += text else contentText += text
                }
                if (part.has("functionCall")) {
                    val fnCall = part.getAsJsonObject("functionCall")
                    val name = fnCall.get("name")?.asString ?: ""
                    // 并行调用时服务端会给 id；缺失才退回用函数名（同名工具调两次会撞 id）。
                    val callId = fnCall.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: name
                    val argsStr = fnCall.getAsJsonObject("args")?.toString() ?: "{}"
                    val argsJson = parseArgs(argsStr)
                    toolCalls.add(ToolCall(id = callId, name = name, arguments = argsJson))
                }
            }
            partsSnapshot = snapshotOf(parts)
        }

        val usageMetadata = response.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject
        val inputTokens = usageMetadata?.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        // candidatesTokenCount 不含思考 token，而思考按输出价计费，故两者相加才是真实输出量。
        val outputTokens = (usageMetadata?.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0) +
            (usageMetadata?.get("thoughtsTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0)
        val cachedInputTokens = usageMetadata?.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0

        return AIResponse(content = contentText, toolCalls = toolCalls, stopReason = finishReason, reasoning = thinkingText.ifEmpty { null }, thinkingBlocksJson = partsSnapshot, inputTokens = inputTokens, outputTokens = outputTokens, cachedInputTokens = cachedInputTokens)
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        val request = buildRequestBody(systemPrompt, messages, tools, reasoningEffort)

        val url = if (useFullUrl) {
            baseUrl
        } else {
            val path = if (baseUrl.trimEnd('/').endsWith(model)) {
                baseUrl.trimEnd('/') + ":streamGenerateContent?alt=sse"
            } else {
                joinUrl(baseUrl, "v1beta/models/$model:streamGenerateContent?alt=sse")
            }
            path
        }
        
        AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)
        val rawSse = StringBuilder()

        try {
            streamWithStaircaseRetry(
                attemptOnce = { onContent ->
                val textBuilder = StringBuilder()
                val toolCalls = mutableListOf<ToolCall>()
                // model 轮的 parts 原样快照：文本分片按段合并，functionCall 与 thoughtSignature 原样保留。
                val snapshotParts = mutableListOf<JsonObject>()
                var currentFinishReason: String? = null
                var streamInputTokens = 0
                var streamOutputTokens = 0
                var streamCachedInputTokens = 0

                val body = api.streamGenerateContent(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)

                body.use { rb ->
                    // 首字节超时 watchdog：60s 内未收到首个内容块则关闭流，触发可重试的 IOException。
                    val firstByteReceived = java.util.concurrent.atomic.AtomicBoolean(false)
                    val watchdog = launchFirstByteWatchdog({ rb.close() }) { firstByteReceived.get() }
                    val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                        runCatching { rb.close() }
                    }
                    try {
                        val reader = rb.charStream().buffered()
                        while (true) {
                            coroutineContext.ensureActive()
                            val line = reader.readLine()
                                ?: throw IOException("SSE 流被中断（疑似网络断开）")
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            rawSse.append(line).append('\n')
                            val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                            
                            try {
                                obj.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject?.let { um ->
                                    streamInputTokens = um.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: streamInputTokens
                                    // 思考 token 不在 candidatesTokenCount 里，但按输出价计费，两者相加才是真实输出量。
                                    val candidateTokens = um.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt
                                    val thoughtTokens = um.get("thoughtsTokenCount")?.takeIf { !it.isJsonNull }?.asInt
                                    if (candidateTokens != null || thoughtTokens != null) {
                                        streamOutputTokens = (candidateTokens ?: 0) + (thoughtTokens ?: 0)
                                    }
                                    streamCachedInputTokens = um.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: streamCachedInputTokens
                                }
                                val chunkCandidates = obj.getAsJsonArray("candidates")
                                chunkCandidates?.firstOrNull()?.asJsonObject?.let { candidate ->
                                    val reason = candidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
                                    if (reason != null && reason != "null") currentFinishReason = reason

                                    val content = candidate.getAsJsonObject("content")
                                    content?.getAsJsonArray("parts")?.forEach { partEl ->
                                        val part = partEl.asJsonObject
                                        val isThought = part.get("thought")?.asBoolean == true
                                        accumulateSnapshotPart(snapshotParts, part, isThought)
                                        if (part.has("text")) {
                                            val text = part.get("text")?.asString ?: ""
                                            if (text.isNotEmpty()) {
                                                if (isThought) {
                                                    // 思考增量：仅 UI 实时展示，不计入正文、不计入正文（不落库，重试时可安全重新流出）
                                                    if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                                    onContent()
                                                    emit(AIStreamChunk.ReasoningDelta(text))
                                                } else {
                                                    textBuilder.append(text)
                                                    if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                                    onContent()
                                                    emit(AIStreamChunk.TextDelta(text))
                                                }
                                            }
                                        }
                                        if (part.has("functionCall")) {
                                            val fnCall = part.getAsJsonObject("functionCall")
                                            val name = fnCall.get("name")?.asString ?: ""
                                            val callId = fnCall.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: name
                                            val argsStr = fnCall.getAsJsonObject("args")?.toString() ?: "{}"
                                            val argsJson = parseArgs(argsStr)
                                            toolCalls.add(ToolCall(id = callId, name = name, arguments = argsJson))
                                        }
                                    }
                                }
                                if (currentFinishReason != null) break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                coroutineContext.ensureActive()
                                // ignore
                            }
                        }
                    } finally {
                        watchdog.cancel()
                        closeHandle?.dispose()
                    }
                }

                emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = currentFinishReason, thinkingBlocksJson = snapshotOf(snapshotParts), inputTokens = streamInputTokens, outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens)))
                },
                onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched)
            throw enriched
        } finally {
            AILogger.logResponseStream(logSessionId, "Gemini", rawSse.toString())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * generateContent / streamGenerateContent 共用的请求体。
     * `generationConfig` 里 thinkingConfig 与 maxOutputTokens 必须合并写（分两次赋值会互相覆盖）。
     */
    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): MutableMap<String, Any> {
        val request = mutableMapOf<String, Any>(
            "contents" to convertToGeminiContents(messages)
        )
        if (systemPrompt.isNotBlank()) {
            request["systemInstruction"] = mapOf(
                "role" to "system",
                "parts" to listOf(mapOf("text" to systemPrompt))
            )
        }
        tools.takeIf { it.isNotEmpty() }?.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.toJsonSchema()
            )
        }?.let { request["tools"] = listOf(mapOf("functionDeclarations" to it)) }

        val generationConfig = mutableMapOf<String, Any>()
        buildThinkingConfig(reasoningEffort)?.let { generationConfig["thinkingConfig"] = it }
        maxOutputTokens?.takeIf { it > 0 }?.let { generationConfig["maxOutputTokens"] = it }
        if (generationConfig.isNotEmpty()) request["generationConfig"] = generationConfig
        return request
    }

    /**
     * model 轮 parts 的原样快照；仅当存在需要原样回传的内容（thoughtSignature 或 functionCall）时才生成，
     * 纯文本轮返回 null，让历史走常规重建（避开正文双写）。
     */
    private fun snapshotOf(parts: com.google.gson.JsonArray?): String? {
        if (parts == null || parts.size() == 0) return null
        val needsSnapshot = parts.any { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@any false
            o.has("functionCall") || o.has("thoughtSignature")
        }
        return if (needsSnapshot) parts.toString() else null
    }

    private fun snapshotOf(parts: List<JsonObject>): String? {
        if (parts.isEmpty()) return null
        val array = com.google.gson.JsonArray().apply { parts.forEach { add(it) } }
        return snapshotOf(array)
    }

    /**
     * 把一个流式 part 并入快照：functionCall 单独成段原样保留；文本分片按“是否 thought”分段合并，
     * 分片上的 thoughtSignature 写回所属段（签名是 part 级元数据，只会随某一片到达）。
     */
    private fun accumulateSnapshotPart(snapshot: MutableList<JsonObject>, part: JsonObject, isThought: Boolean) {
        if (part.has("functionCall")) {
            snapshot.add(part.deepCopy())
            return
        }
        if (!part.has("text")) {
            if (part.has("thoughtSignature")) snapshot.add(part.deepCopy())
            return
        }
        val last = snapshot.lastOrNull()?.takeIf { prev ->
            !prev.has("functionCall") &&
                prev.has("text") &&
                (prev.get("thought")?.asBoolean == true) == isThought
        }
        if (last != null) {
            last.addProperty("text", last.get("text").asString + (part.get("text")?.asString ?: ""))
            part.get("thoughtSignature")?.takeIf { !it.isJsonNull }?.let { last.add("thoughtSignature", it) }
        } else {
            snapshot.add(part.deepCopy())
        }
    }

    /** 把快照还原成 parts 数组；为空或损坏时返回 null，由调用方回退重建逻辑。 */
    private fun decodeSnapshotParts(json: String): com.google.gson.JsonArray? {
        if (json.isBlank()) return null
        return runCatching {
            JsonParser.parseString(json).asJsonArray.takeIf { it.size() > 0 }
        }.getOrNull()
    }

    /** 思考强度 → Gemini thinkingConfig。模型名含 gemini-3 用 thinkingLevel，否则用 thinkingBudget（2.5 系）。 */
    private fun buildThinkingConfig(reasoningEffort: String?): Map<String, Any>? {
        if (reasoningEffort == null) return null
        return if (model.contains("gemini-3")) {
            // thinkingLevel 仅支持 minimal/low/medium/high；xhigh/max 归一到 high（UI 按元数据裁剪，正常不会选到）
            val level = if (reasoningEffort == "xhigh" || reasoningEffort == "max") "high" else reasoningEffort
            mapOf("thinkingLevel" to level)
        } else {
            val budget = when (reasoningEffort) {
                "low" -> 1024
                "medium" -> 4096
                "high", "xhigh", "max" -> 8192
                else -> return null
            }
            mapOf("thinkingBudget" to budget)
        }
    }

    private fun parseArgs(raw: String): kotlinx.serialization.json.JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return kotlinx.serialization.json.JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { kotlinx.serialization.json.JsonObject(emptyMap()) }
    }

    private fun convertToGeminiContents(messages: List<AgentMessage>): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        // 防御性跟踪：上一个 assistant(即 model) 消息是否包含 functionCall
        var lastModelHadFunctionCall = false

        for (message in messages) {
            when (message) {
                is AgentMessage.UserMessage -> {
                    val parts = mutableListOf<Map<String, Any>>()
                    if (message.content.isNotBlank()) {
                        parts.add(mapOf("text" to message.content))
                    }
                    message.images.forEach { image ->
                        parts.add(image.toGeminiInlineDataPart())
                    }
                    result.add(
                        mapOf(
                            "role" to "user",
                            "parts" to parts
                        )
                    )
                    lastModelHadFunctionCall = false
                }
                is AgentMessage.AssistantMessage -> {
                    lastModelHadFunctionCall = message.toolCalls.isNotEmpty()
                    // 上轮 model 输出存有原样快照时直接原样回传：thoughtSignature 是 part 级元数据，
                    // 重建 parts 会丢失签名，Gemini 3 系就接不上上一轮的推理上下文。
                    val snapshot = decodeSnapshotParts(message.thinkingBlocksJson)
                    if (snapshot != null) {
                        result.add(mapOf("role" to "model", "parts" to snapshot))
                        continue
                    }
                    val parts = mutableListOf<Map<String, Any>>()
                    if (message.content.isNotEmpty()) {
                        parts.add(mapOf("text" to message.content))
                    }
                    for (toolCall in message.toolCalls) {
                        parts.add(
                            mapOf(
                                "functionCall" to mapOf(
                                    "name" to toolCall.name,
                                    "args" to toolCall.arguments
                                )
                            )
                        )
                    }
                    if (parts.isNotEmpty()) {
                        result.add(
                            mapOf(
                                "role" to "model",
                                "parts" to parts
                            )
                        )
                    }
                }
                is AgentMessage.ToolResultMessage -> {
                    // 防御性清理：跳过没有配对 functionCall 的孤立 functionResponse
                    if (!lastModelHadFunctionCall) continue
                    val parts = mutableListOf<Map<String, Any>>()
                    // name 发函数名，并行调用时额外带 id 回去配对（旧数据的 id 就是函数名，此时不发 id）。
                    val functionName = message.toolName.ifBlank { message.id }
                    val functionResponse = mutableMapOf<String, Any>(
                        "name" to functionName,
                        "response" to mapOf("result" to message.result)
                    )
                    if (message.id.isNotBlank() && message.id != functionName) {
                        functionResponse["id"] = message.id
                    }
                    parts.add(mapOf("functionResponse" to functionResponse))
                    message.images.forEach { image ->
                        parts.add(image.toGeminiInlineDataPart())
                    }
                    result.add(
                        mapOf(
                            "role" to "user",
                            "parts" to parts
                        )
                    )
                }
            }
        }
        return result
    }

    private fun AgentImage.toGeminiInlineDataPart(): Map<String, Any> {
        return mapOf(
            "inline_data" to mapOf(
                "mime_type" to mimeType,
                "data" to base64Data
            )
        )
    }
}
