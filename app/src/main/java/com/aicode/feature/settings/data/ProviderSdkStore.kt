package com.aicode.feature.settings.data

import com.aicode.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider SDK 类型存储：从 models.dev 的 `npm` 字段解析 provider 的 SDK 类型。
 *
 * models.dev 的 `npm` 字段指示该 provider 使用的 SDK：
 * - `@ai-sdk/openai` / `@ai-sdk/openai-compatible` → OpenAI 兼容
 * - `@ai-sdk/anthropic` → Anthropic
 * - `@ai-sdk/google` → Gemini
 * - 其他 → 兜底为 OpenAI 兼容
 *
 * 供插件虚拟 provider 判断协议类型（替代字符串启发式）。
 */
@Singleton
class ProviderSdkStore @Inject constructor() {

    /** providerId → SDK 包名（如 "@ai-sdk/openai"）。 */
    private val sdkPackages = mutableMapOf<String, String>()

    /** 从 models.dev 解析时调用：记录 provider 的 npm 字段。 */
    fun update(providerId: String, npm: String?) {
        if (!npm.isNullOrBlank()) {
            sdkPackages[providerId] = npm
        }
    }

    /** 解析 provider 的 SDK 类型。未匹配到时兜底为 OpenAI 兼容。 */
    fun resolveType(providerId: String): ProviderType {
        val npm = sdkPackages[providerId] ?: return ProviderType.OPENAI
        return when {
            npm.contains("anthropic", ignoreCase = true) -> ProviderType.ANTHROPIC
            npm.contains("google", ignoreCase = true) -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }
    }

    /** 获取 provider 的 npm 包名（供调试/日志）。 */
    fun resolveNpm(providerId: String): String? = sdkPackages[providerId]
}
