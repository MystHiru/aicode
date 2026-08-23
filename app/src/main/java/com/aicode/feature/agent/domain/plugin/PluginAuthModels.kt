package com.aicode.feature.agent.domain.plugin

import kotlinx.serialization.Serializable

/** 插件认证凭据（对齐 opencode auth.json 的 Auth 类型）。
 *  type ∈ "oauth"（refresh/access/expires）/ "api"（key + metadata）。
 *  与 AiCode 用户 API Key（Room ai_providers 表）独立：这是插件自有的 OAuth/API 凭据。 */
@Serializable
data class PluginAuth(
    val type: String,
    val refresh: String = "",
    val access: String = "",
    val expires: Long = 0,
    val key: String = "",
    val metadata: Map<String, String> = emptyMap()
) {
    /** 是否存在有效凭据（oauth 型只要 type 匹配即视为已配置；api 型要求 key 非空）。 */
    val hasCredentials: Boolean
        get() = type == "oauth" || (type == "api" && key.isNotBlank()) || type == "wellknown"

    /** oauth 访问令牌是否已过期（带 60s 提前量，对齐插件侧 accessTokenExpired）。 */
    fun accessTokenExpired(): Boolean {
        if (type != "oauth") return true
        if (access.isBlank()) return true
        return expires <= System.currentTimeMillis() + 60_000
    }
}

/** 插件声明的一个登录方法（来自插件 `auth.methods`，runner 的 auth.methods.list 返回）。 */
data class PluginAuthMethod(
    val provider: String,
    val label: String,
    /** "oauth" | "api"。 */
    val type: String,
    val plugin: String
)

/** `auth.authorize` 的执行结果（runner 序列化后）。requiresKey=true 表示 api 型方法无 authorize，由宿主收集 key。 */
data class PluginAuthorizeResult(
    val url: String = "",
    val instructions: String = "",
    /** "auto" | "code"。 */
    val method: String = "auto",
    val type: String = "oauth",
    val requiresKey: Boolean = false,
    /** api 型 authorize 直接返回 success：凭据已落盘，无需后续 callback。 */
    val completed: Boolean = false,
    val error: String? = null
) {
    val isError: Boolean get() = !error.isNullOrBlank()
}

/** `auth.callback` 的执行结果。 */
data class PluginAuthCallbackResult(
    val type: String,
    val error: String? = null
) {
    val isSuccess: Boolean get() = type == "success"
}
