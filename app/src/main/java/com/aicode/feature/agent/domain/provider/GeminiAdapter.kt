package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.AILogger
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.plugin.PluginHookGateway
import com.aicode.feature.agent.domain.plugin.PluginRequestParams
import com.aicode.feature.agent.domain.plugin.ChatHeadersResult
import com.aicode.feature.agent.domain.plugin.applyChatHeaders
import com.aicode.feature.agent.domain.plugin.replaceOrigin
import com.aicode.feature.agent.domain.plugin.applyChatParams
import com.aicode.feature.agent.domain.plugin.resolveProviderProxy
import com.aicode.feature.agent.domain.plugin.toPlainValue
import com.aicode.feature.settings.domain.model.ProviderType
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

    private fun extraHeaders(): Map<String, String> =
        if (userAgent.isNotBlank()) mapOf("User-Agent" to userAgent) else emptyMap()

    /** 插件运行时（chat.headers / chat.params hook 分发用）；由工作流 createStandaloneProvider 注入。 */
    var pluginManager: PluginHookGateway? = null

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val geminiContents = convertToGeminiContents(messages)
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.toJsonSchema()
            )
        }?.let { listOf(mapOf("functionDeclarations" to it)) }

        val request = mutableMapOf<String, Any>(
            "contents" to geminiContents
        )
        if (systemPrompt.isNotBlank()) {
            request["systemInstruction"] = mapOf(
                "role" to "system",
                "parts" to listOf(mapOf("text" to systemPrompt))
            )
        }
        if (toolDefs != null) {
            request["tools"] = toolDefs
        }
        // 插件请求头/参数改写：chat.headers / chat.params（temperature/topP/topK/maxOutputTokens 并入 generationConfig）。
        // 插件 auth.loader 声明 baseURL 时覆盖请求端点（opencode 语义）。
        val chatHeaders = pluginManager?.applyChatHeaders(logSessionId, model, "GEMINI", providerId = providerId, baseUrl = baseUrl, providerType = ProviderType.GEMINI) ?: ChatHeadersResult()
        val pluginHeaders = chatHeaders.headers
        val pluginParams = pluginManager?.applyChatParams(logSessionId, model, providerId = providerId, providerType = ProviderType.GEMINI) ?: PluginRequestParams()
        val generationConfig = mutableMapOf<String, Any>()
        buildThinkingConfig(reasoningEffort)?.let { generationConfig["thinkingConfig"] = it }
        pluginParams.temperature?.let { generationConfig["temperature"] = it }
        pluginParams.topP?.let { generationConfig["topP"] = it }
        pluginParams.topK?.let { generationConfig["topK"] = it }
        pluginParams.maxOutputTokens?.let { generationConfig["maxOutputTokens"] = it }
        if (generationConfig.isNotEmpty()) request["generationConfig"] = generationConfig
        pluginParams.options.forEach { (k, v) -> request[k] = v.toPlainValue() ?: "" }

        val effectiveBaseUrl = chatHeaders.pluginBaseUrl?.let { replaceOrigin(baseUrl, it) } ?: baseUrl
        val realUrl = if (useFullUrl) {
            effectiveBaseUrl
        } else {
            val path = if (effectiveBaseUrl.trimEnd('/').endsWith(model)) {
                effectiveBaseUrl.trimEnd('/') + ":generateContent"
            } else {
                joinUrl(effectiveBaseUrl, "v1beta/models/$model:generateContent")
            }
            path
        }
        // 插件 auth.loader 返回自定义 fetch 时走本地代理（127.0.0.1:<port>），真实 URL 放私有头。
        val pluginProxy = pluginManager?.resolveProviderProxy(providerId)
        val url = if (pluginProxy != null) pluginProxy.trimEnd('/') + "/" else realUrl
        val proxyHeaders = if (pluginProxy != null) mapOf("X-Aicode-Real-Url" to realUrl, "X-Aicode-Provider" to providerId) else emptyMap()
        AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)

        val response = try {
            retryStaircase {
                api.generateContent(url = url, apiKey = apiKey, extraHeaders = extraHeaders() + pluginHeaders + proxyHeaders, request = request)
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

        val candidates = response.getAsJsonArray("candidates")
        candidates?.firstOrNull()?.asJsonObject?.let { candidate ->
            finishReason = candidate.get("finishReason")?.asString
            val content = candidate.getAsJsonObject("content")
            content?.getAsJsonArray("parts")?.forEach { partEl ->
                val part = partEl.asJsonObject
                val isThought = part.get("thought")?.asBoolean == true
                if (part.has("text")) {
                    val text = part.get("text").asString
                    if (isThought) thinkingText += text else contentText += text
                }
                if (part.has("functionCall")) {
                    val fnCall = part.getAsJsonObject("functionCall")
                    val name = fnCall.get("name")?.asString ?: ""
                    val argsStr = fnCall.getAsJsonObject("args")?.toString() ?: "{}"
                    val argsJson = parseArgs(argsStr)
                    toolCalls.add(ToolCall(id = name, name = name, arguments = argsJson))
                }
            }
            // 非流式时思考也可能以 candidate.thoughts 数组返回（thought 文本 + token 数）
            candidate.getAsJsonArray("thoughts")?.forEach { t ->
                t.asJsonObject?.get("text")?.takeIf { !it.isJsonNull }?.asString?.let { thinkingText += it }
            }
        }

        val usageMetadata = response.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject
        val inputTokens = usageMetadata?.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        val outputTokens = usageMetadata?.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        val cachedInputTokens = usageMetadata?.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0

        return AIResponse(content = contentText, toolCalls = toolCalls, stopReason = finishReason, reasoning = thinkingText.ifEmpty { null }, inputTokens = inputTokens, outputTokens = outputTokens, cachedInputTokens = cachedInputTokens)
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        val geminiContents = convertToGeminiContents(messages)
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.toJsonSchema()
            )
        }?.let { listOf(mapOf("functionDeclarations" to it)) }

        val request = mutableMapOf<String, Any>(
            "contents" to geminiContents
        )
        if (systemPrompt.isNotBlank()) {
            request["systemInstruction"] = mapOf(
                "role" to "system",
                "parts" to listOf(mapOf("text" to systemPrompt))
            )
        }
        if (toolDefs != null) {
            request["tools"] = toolDefs
        }
        // 插件请求头/参数改写：chat.headers / chat.params（temperature/topP/topK/maxOutputTokens 并入 generationConfig）。
        // 插件 auth.loader 声明 baseURL 时覆盖请求端点（opencode 语义）。
        val chatHeaders = pluginManager?.applyChatHeaders(logSessionId, model, "GEMINI", providerId = providerId, baseUrl = baseUrl, providerType = ProviderType.GEMINI) ?: ChatHeadersResult()
        val pluginHeaders = chatHeaders.headers
        val pluginParams = pluginManager?.applyChatParams(logSessionId, model, providerId = providerId, providerType = ProviderType.GEMINI) ?: PluginRequestParams()
        val generationConfig = mutableMapOf<String, Any>()
        buildThinkingConfig(reasoningEffort)?.let { generationConfig["thinkingConfig"] = it }
        pluginParams.temperature?.let { generationConfig["temperature"] = it }
        pluginParams.topP?.let { generationConfig["topP"] = it }
        pluginParams.topK?.let { generationConfig["topK"] = it }
        pluginParams.maxOutputTokens?.let { generationConfig["maxOutputTokens"] = it }
        if (generationConfig.isNotEmpty()) request["generationConfig"] = generationConfig
        pluginParams.options.forEach { (k, v) -> request[k] = v.toPlainValue() ?: "" }

        val effectiveBaseUrl = chatHeaders.pluginBaseUrl?.let { replaceOrigin(baseUrl, it) } ?: baseUrl
        val realUrl = if (useFullUrl) {
            effectiveBaseUrl
        } else {
            val path = if (effectiveBaseUrl.trimEnd('/').endsWith(model)) {
                effectiveBaseUrl.trimEnd('/') + ":streamGenerateContent?alt=sse"
            } else {
                joinUrl(effectiveBaseUrl, "v1beta/models/$model:streamGenerateContent?alt=sse")
            }
            path
        }
        // 插件 auth.loader 返回自定义 fetch 时走本地代理（127.0.0.1:<port>），真实 URL 放私有头。
        val pluginProxy = pluginManager?.resolveProviderProxy(providerId)
        val url = if (pluginProxy != null) pluginProxy.trimEnd('/') + "/" else realUrl
        val proxyHeaders = if (pluginProxy != null) mapOf("X-Aicode-Real-Url" to realUrl, "X-Aicode-Provider" to providerId) else emptyMap()
        
        AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)
        val rawSse = StringBuilder()

        try {
            streamWithStaircaseRetry(
                attemptOnce = {
                val textBuilder = StringBuilder()
                val toolCalls = mutableListOf<ToolCall>()
                var currentFinishReason: String? = null
                var streamInputTokens = 0
                var streamOutputTokens = 0
                var streamCachedInputTokens = 0

                val body = api.streamGenerateContent(url = url, apiKey = apiKey, extraHeaders = extraHeaders() + pluginHeaders + proxyHeaders, request = request)

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
                                    streamOutputTokens = um.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: streamOutputTokens
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
                                        if (part.has("text")) {
                                            val text = part.get("text")?.asString ?: ""
                                            if (text.isNotEmpty()) {
                                                if (isThought) {
                                                    // 思考增量：仅 UI 实时展示，不计入正文、不计入正文（不落库，重试时可安全重新流出）
                                                    if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                                    emit(AIStreamChunk.ReasoningDelta(text))
                                                } else {
                                                    textBuilder.append(text)
                                                    if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                                    emit(AIStreamChunk.TextDelta(text))
                                                }
                                            }
                                        }
                                        if (part.has("functionCall")) {
                                            val fnCall = part.getAsJsonObject("functionCall")
                                            val name = fnCall.get("name")?.asString ?: ""
                                            val argsStr = fnCall.getAsJsonObject("args")?.toString() ?: "{}"
                                            val argsJson = parseArgs(argsStr)
                                            toolCalls.add(ToolCall(id = name, name = name, arguments = argsJson))
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

                emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = currentFinishReason, inputTokens = streamInputTokens, outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens)))
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
                    lastModelHadFunctionCall = message.toolCalls.isNotEmpty()
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
                    parts.add(
                        mapOf(
                            "functionResponse" to mapOf(
                                "name" to message.id, // For Gemini, we typically use the name as ID
                                "response" to mapOf(
                                    "result" to message.result
                                )
                            )
                        )
                    )
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
