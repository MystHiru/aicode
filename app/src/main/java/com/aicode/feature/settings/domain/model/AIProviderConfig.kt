package com.aicode.feature.settings.domain.model

data class AIProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val apiKey: String,
    /** 多 Key 模式开关：开启后从 [apiKeys] 轮换取用，关闭时只用 [apiKey]。 */
    val multiKeyEnabled: Boolean = false,
    /** 多 Key 模式下的候选 Key（按列表顺序优先）。 */
    val apiKeys: List<String> = emptyList(),
    /** 多 Key 取用策略：顺序（失败才切）或轮询（新会话轮流起步）。 */
    val keyRotationStrategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
    /** 同一个 Key 连续失败多少次后切到下一个。 */
    val keyFailoverThreshold: Int = 2,
    /** 被切走的 Key 冷却多少分钟后重新纳入候选；0 表示不冷却。 */
    val keyCooldownMinutes: Int = 5,
    val baseUrl: String,
    val defaultModel: String,
    /** 该提供商已添加的可用模型列表（拉取或手动添加）。 */
    val models: List<String> = emptyList(),
    /** 当前选中使用的模型；为空时回退到 defaultModel。 */
    val selectedModel: String = defaultModel,
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Anthropic 显式缓存断点（cache_control）。仅 ANTHROPIC 类型生效，默认开启。 */
    val anthropicCacheBreakpoints: Boolean = true,
    /** Chat Completion 路径发送 prompt_cache_key（shard 路由）。仅 OPENAI 类型生效，默认关闭。 */
    val openaiChatCacheKey: Boolean = false,
    /** 套餐余量查询脚本路径（位于 ~/.aicode/scripts/，或绝对路径/自定义命令）。 */
    val balanceScriptPath: String = "",
    /** 套餐余量自动刷新间隔（分钟），0 表示仅进入时/手动刷新，支持 1, 3, 5, 10 等。默认 5 分钟。 */
    val balanceRefreshInterval: Int = 5,
    /** 自定义请求头 User-Agent；留空使用默认。 */
    val userAgent: String = "",
    /** 提供商列表排序序号，越小越靠前；-1 表示未分配（保存时取 max+1 排到末尾）。 */
    val sortOrder: Int = -1,
    /** 单独为该提供商配置代理（关闭时跟随全局代理设置）。 */
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = ""
) {
    /** 实际生效的模型：优先 selectedModel，其次 defaultModel。 */
    val effectiveModel: String
        get() = selectedModel.ifBlank { defaultModel }

    /**
     * 实际可用的 Key 列表。多 Key 模式开启且列表非空时用 [apiKeys]，否则退回单 [apiKey]，
     * 使「开关关闭」与「开着但没填」都能落到既有单 Key 行为上。
     */
    val effectiveApiKeys: List<String>
        get() = if (multiKeyEnabled && apiKeys.any { it.isNotBlank() }) {
            apiKeys.filter { it.isNotBlank() }
        } else {
            listOf(apiKey).filter { it.isNotBlank() }
        }

    /** 是否已配置至少一个可用 Key。 */
    val hasUsableApiKey: Boolean get() = effectiveApiKeys.isNotEmpty()

    /** 拉取模型列表 / 连通性测试等无会话上下文的请求用第一个可用 Key。 */
    val firstUsableApiKey: String get() = effectiveApiKeys.firstOrNull() ?: ""
}

/** 绝不包含空白的字段（API Key / URL / 代理主机）：连中间空白一并去掉。 */
private fun String.stripAllWhitespace(): String = filterNot { it.isWhitespace() }

/** 允许内部空格的字段（名称 / 模型名 / UA / 路径 / 代理账号）：去换行与制表符，再 trim 首尾。 */
private fun String.stripLineBreaks(): String =
    filterNot { it == '\n' || it == '\r' || it == '\t' }.trim()

/**
 * 清洗手填/粘贴的提供商配置。粘贴 API Key 常带入换行或首尾空格，直接拼进 Authorization
 * 头会被 OkHttp 拒绝（Unexpected char 0x0a in Authorization value）；baseUrl / 代理主机带空白则
 * 拼出非法 URL。写入与读取两侧各清洗一次，已存的脏数据不靠迁移也能恢复可用。
 */
fun AIProviderConfig.sanitized(): AIProviderConfig = copy(
    name = name.stripLineBreaks(),
    apiKey = apiKey.stripAllWhitespace(),
    apiKeys = apiKeys.map { it.stripAllWhitespace() }.filter { it.isNotEmpty() }.distinct(),
    baseUrl = baseUrl.stripAllWhitespace(),
    defaultModel = defaultModel.stripLineBreaks(),
    models = models.map { it.stripLineBreaks() }.filter { it.isNotEmpty() }.distinct(),
    selectedModel = selectedModel.stripLineBreaks(),
    balanceScriptPath = balanceScriptPath.stripLineBreaks(),
    userAgent = userAgent.stripLineBreaks(),
    proxyHost = proxyHost.stripAllWhitespace(),
    proxyUsername = proxyUsername.stripLineBreaks(),
    proxyPassword = proxyPassword.stripLineBreaks()
)

enum class ProviderType {
    OPENAI, ANTHROPIC, GEMINI
}

enum class KeyRotationStrategy {
    /** 顺序：始终用第一个未冷却的 Key，只有连续失败达阈值才前移。 */
    SEQUENTIAL,

    /** 轮询：新会话轮流分配起始 Key；同一会话内仍粘住同一个 Key 以保住服务端 prompt 缓存。 */
    ROUND_ROBIN
}

fun defaultProviderApiPath(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "v1/messages"
    ProviderType.GEMINI -> "v1beta"
    else -> "v1/chat/completions"
}
