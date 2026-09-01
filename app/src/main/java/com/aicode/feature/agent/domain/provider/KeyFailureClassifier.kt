package com.aicode.feature.agent.domain.provider

import retrofit2.HttpException

/**
 * 判断一次「已耗尽内部重试」的 LLM 调用失败是否可归因于当前使用的 API Key。
 *
 * 只认鉴权/权限（401/403）、限流（429）与余额/配额类（402、insufficient_quota 等）：
 * 这些换一个 Key 才有可能好转。5xx、超时、DNS/连接故障属于服务端或链路问题，
 * 换 Key 无益，不应消耗多 Key 的失败计数、更不应把好 Key 打进冷却。
 *
 * [enrichWithHttpErrorBody] 会把 [HttpException] 包成 IllegalStateException 并把原异常挂在
 * cause 上，所以这里要沿 cause 链找状态码，找不到才退回消息文本匹配。
 */
fun Throwable.isApiKeyFailure(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is HttpException -> if (current.code() in KEY_FAILURE_HTTP_CODES) return true
            is StreamApiException -> {
                if (current.code?.lowercase() in KEY_FAILURE_STREAM_CODES) return true
            }
        }
        current = current.cause
        depth++
    }
    val text = message?.lowercase() ?: return false
    return KEY_FAILURE_MESSAGES.any { text.contains(it) }
}

private const val MAX_CAUSE_DEPTH = 5

private val KEY_FAILURE_HTTP_CODES = setOf(401, 402, 403, 429)

private val KEY_FAILURE_STREAM_CODES = setOf(
    "insufficient_quota",
    "quota_exceeded",
    "usage_limit_reached",
    "usage_not_included",
    "rate_limit_exceeded",
    "rate_limit_error",
    "invalid_api_key",
    "authentication_error",
    "permission_error",
    "permission_denied"
)

private val KEY_FAILURE_MESSAGES = listOf(
    "http 401",
    "http 402",
    "http 403",
    "http 429",
    "invalid api key",
    "invalid_api_key",
    "incorrect api key",
    "insufficient_quota",
    "insufficient balance",
    "quota exceeded",
    "authentication_error",
    "permission_denied"
)
