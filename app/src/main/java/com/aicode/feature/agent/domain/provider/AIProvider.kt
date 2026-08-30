package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.flow.Flow

data class AIResponse(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * 模型停止的原因。Anthropic: "end_turn" / "tool_use" / "max_tokens" / "refusal" /
     * "model_context_window_exceeded" / "pause_turn"；OpenAI: "stop" / "tool_calls" / "length"；
     * Gemini（generateContent 的 finishReason，全大写）: "STOP" / "MAX_TOKENS" / "SAFETY" 等。
     * 当值为 "max_tokens" / "length" / "MAX_TOKENS" 时表示输出因 token 上限被截断，Agent 循环应自动续写。
     */
    val stopReason: String? = null,
    /**
     * 服务端给出的停止原因说明（Anthropic `stop_details.explanation`）。
     * 拒答时正文可能为空，只有这里有可展示的理由。取不到时为 null。
     */
    val stopDetail: String? = null,
    /** 本轮模型的完整思考过程（对应 OpenAI/DeepSeek 的 reasoning_content）。非空时需回传给 API，否则 DeepSeek 思考模式会报 400。 */
    val reasoning: String? = null,
    /** Anthropic extended thinking 的加密签名（thinking block 的 signature）。多轮/工具循环须随 thinking 原样回传，否则 400。其他 provider 为 null。 */
    val signature: String? = null,
    /**
     * 本轮 provider 原生“思考 / 签名”块的原样快照（JSON 文本），下一轮需原样、原序回传：
     * - Anthropic：thinking / redacted_thinking 内容块数组（redacted 的 `data` 不可重建，合并多块会被 400）。
     * - Gemini（generateContent）：model 轮的 `parts` 数组，因为 `thoughtSignature` 是挂在任意 part
     *   （常在 functionCall part 或最后一个 part）上的元数据，只有整块原样回传才能保住推理连续性。
     * 其他 provider 为 null。
     */
    val thinkingBlocksJson: String? = null,
    /** 本轮输入 token 数（来自 API 返回的 usage）。取不到时为 0。 */
    val inputTokens: Int = 0,
    /** 本轮输出 token 数（来自 API 返回的 usage）。取不到时为 0。 */
    val outputTokens: Int = 0,
    /** 本轮输入中命中服务端缓存的部分（OpenAI cached_tokens / Anthropic cache_read_input_tokens / Gemini cachedContentTokenCount）。取不到时为 0。 */
    val cachedInputTokens: Int = 0,
    /** 本轮写入服务端缓存的 token 数（Anthropic cache_creation_input_tokens）。按高于普通输入的单价计费，取不到时为 0。 */
    val cacheCreationTokens: Int = 0
) {
    val isTruncated: Boolean
        get() = stopReason in TRUNCATION_STOP_REASONS

    /** 本轮因服务端策略/上下文超限而中止：正文可能残缺或为空，Agent 循环不应把它当正常完成继续。 */
    val isAborted: Boolean
        get() = stopReason in ABORT_STOP_REASONS

    companion object {
        /** 输出被 token 上限截断（Anthropic / OpenAI / Gemini 三家的写法）。 */
        val TRUNCATION_STOP_REASONS = setOf("max_tokens", "length", "MAX_TOKENS")

        /**
         * 需要向用户解释而非静默完成的 stop_reason。
         * 前两个是 Anthropic；大写那组是 Gemini 的 finishReason（安全拦截 / 禁止内容 / 黑名单 / 敏感信息）。
         */
        val ABORT_STOP_REASONS = setOf(
            "refusal",
            "model_context_window_exceeded",
            "SAFETY",
            "PROHIBITED_CONTENT",
            "BLOCKLIST",
            "SPII"
        )
    }
}

/**
 * 流式补全过程中向上游推送的分块。
 * [TextDelta] 为模型新吐出的一小段文字（增量，非累积）；
 * [Final] 在本轮结束时给出完整结果（聚合后的文字 + 工具调用），供 Agent 循环驱动后续工具执行。
 * [Retrying] 在网络重试时推送，供 UI 展示"正在重试"提示。
 */
sealed class AIStreamChunk {
    data class TextDelta(val text: String) : AIStreamChunk()
    /** 模型新吐出的一小段思考过程（增量，非累积）。仅用于 UI 实时展示，不进入上下文回放。 */
    data class ReasoningDelta(val text: String) : AIStreamChunk()
    data class Final(val response: AIResponse) : AIStreamChunk()
    /** 网络请求正在重试。仅用于 UI 实时展示，不进入上下文回放。[error] 为触发重试的错误摘要，供 UI 展示具体原因。 */
    data class Retrying(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo) : AIStreamChunk()
}

interface AIProvider {
    var apiKey: String
    var baseUrl: String
    var useFullUrl: Boolean
    var useResponseApi: Boolean
    var model: String

    /**
     * 当前 provider 配置 id（数据库主键），用于关联自定义模型元数据。
     * 调用前由工作流设置；为空时元数据解析回退纯自动（拉取/内置/默认）。
     */
    var providerId: String

    /**
     * 当前会话 id，仅用于日志归档：调用前由工作流设置，[com.aicode.core.util.AILogger]
     * 据此把每次请求/响应写到对应会话的文件。为 null 时落到 `session-unknown.log`。
     */
    var logSessionId: String?

    /** 自定义请求头 User-Agent；留空使用默认。 */
    var userAgent: String

    /**
     * 本次请求允许的最大输出 token 数，来自模型元数据的输出上限（models.dev `limit.output`）。
     * 调用前由工作流设置；为 null 时各 adapter 用自身默认值或不发该参数。
     */
    var maxOutputTokens: Int?

    /**
     * 单轮补全。[tools] 会以提供商的 function-calling 格式真正发给模型，
     * 模型若决定调用工具，结果会出现在返回的 [AIResponse.toolCalls] 中。
     * [reasoningEffort] 为思考强度（"low"/"medium"/"high"），仅 OpenAI 系生效；
     * Anthropic/Gemini 与不支持该参数的模型忽略。
     */
    suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool> = emptyList(),
        reasoningEffort: String? = null
    ): AIResponse

    /**
     * 流式单轮补全：以 SSE 逐字接收模型回复。文字以 [AIStreamChunk.TextDelta] 增量推送，
     * 本轮结束时以 [AIStreamChunk.Final] 给出聚合后的完整 [AIResponse]（含工具调用）。
     * 工具调用的 function-calling 语义与 [complete] 一致。
     * [reasoningEffort] 同 [complete]。
     */
    fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool> = emptyList(),
        reasoningEffort: String? = null
    ): Flow<AIStreamChunk>
}

private val VERSION_SEGMENT_REGEX = Regex("""^v\d+.*$""", RegexOption.IGNORE_CASE)

/**
 * Builds an absolute request URL from a user-configured base URL and an API path
 * such as "v1/chat/completions". Tolerates trailing slashes and a base URL that
 * already ends with a version segment (e.g. "https://host/v1", "https://host/api/v3", "https://host/v1beta")
 * so it isn't duplicated or conflicted with "v1/".
 */
fun joinUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val cleanPath = path.trimStart('/')
    
    val lastSegment = base.substringAfterLast('/', "")
    
    // 1. 如果 base 末尾与 path 开头是完全相同的 segment（如 /v1 与 v1/chat），去重
    if (lastSegment.isNotEmpty() && cleanPath.startsWith("$lastSegment/", ignoreCase = true)) {
        return "$base/${cleanPath.substring(lastSegment.length + 1)}"
    }
    
    // 2. 如果 base 末尾已经是版本号（如 /v3, /v2, /v1beta 等），且待拼路径也以版本段开头（如 v1/chat, v1/models）
    if (lastSegment.matches(VERSION_SEGMENT_REGEX)) {
        val pathFirstSegment = cleanPath.substringBefore('/', "")
        if (pathFirstSegment.matches(VERSION_SEGMENT_REGEX)) {
            val remainingPath = cleanPath.substringAfter('/', "")
            return if (remainingPath.isNotEmpty()) "$base/$remainingPath" else base
        }
    }
    
    return "$base/$cleanPath"
}
