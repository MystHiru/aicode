package com.aicode.feature.plugin.domain

import java.util.Locale

/**
 * 面向使用者展示的插件文本：清单里写成单一字符串（对所有语言生效），
 * 或语言标签到文本的映射（可含 `default` 键）。
 *
 * 取值顺序：完整标签（`zh-CN`）→ 语言部分（`zh`）→ 其他中文标签 → `default` → `en` → 第一项。
 */
data class LocalizedText(val values: Map<String, String>) {

    init {
        require(values.isNotEmpty()) { "LocalizedText 至少需要一项文本" }
    }

    fun resolve(languageTag: String): String {
        val tag = normalize(languageTag)
        val language = tag.substringBefore('-')
        return values[tag]
            ?: values[language]
            ?: (if (language == "zh") chineseFallback(tag) else null)
            ?: values[DEFAULT_KEY]
            ?: values["en"]
            ?: values.values.first()
    }

    private fun chineseFallback(tag: String): String? {
        val locale = Locale.forLanguageTag(tag)
        val region = locale.country.lowercase()
        val traditionalRegion = region == "tw" || region == "hk" || region == "mo"
        val traditional = when (locale.script) {
            "Hant" -> true
            "Hans" -> false
            else -> traditionalRegion
        }
        val regional = values["zh-$region"].takeIf { traditional == traditionalRegion }
        val preferred = if (traditional) traditionalChineseTags else simplifiedChineseTags
        val alternate = if (traditional) simplifiedChineseTags else traditionalChineseTags
        return regional ?: preferred.firstNotNullOfOrNull(values::get)
            ?: alternate.firstNotNullOfOrNull(values::get)
    }

    companion object {
        const val DEFAULT_KEY = "default"
        private val simplifiedChineseTags = listOf("zh-hans", "zh-cn", "zh-sg")
        private val traditionalChineseTags = listOf("zh-hant", "zh-tw", "zh-hk", "zh-mo")

        fun single(text: String) = LocalizedText(mapOf(DEFAULT_KEY to text))

        /** 标签统一为小写、`-` 分隔，`zh_CN` 与 `zh-cn` 视为同一个。 */
        fun normalize(tag: String): String = tag.trim().lowercase().replace('_', '-')
    }
}
